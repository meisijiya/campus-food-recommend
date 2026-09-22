package com.meisijiya.campusfood.module.recommend.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.meisijiya.campusfood.module.recommend.advisor.ReflectiveRetryAdvisor;
import com.meisijiya.campusfood.module.recommend.advisor.RuleBasedFallbackAdvisor;
import com.meisijiya.campusfood.module.recommend.schema.JsonSchemaValidator;
import com.meisijiya.campusfood.module.recommend.schema.RecommendationSchema;

/**
 * RuleBasedFallbackAdvisor 单元测试(F-3;对应 ticket acceptance #10 "返回 schema 合规")。
 *
 * <p>覆盖:
 * <ul>
 *   <li>attempt=1 + 不合规 → 不接管,原样返回(交给 RRA 重试)</li>
 *   <li>attempt=2 + 不合规 → 接管,fallback 产 schema 合规 JSON</li>
 *   <li>attempt=2 + 已合规 → 不接管,原样返回</li>
 *   <li>buildFallbackJson 静态方法直接断言合规</li>
 * </ul>
 *
 * @author meisijiya
 */
class RuleBasedFallbackAdvisorTest {

    private RuleBasedFallbackAdvisor advisor;
    private JsonSchemaValidator validator;

    @BeforeEach
    void setUp() {
        validator = new JsonSchemaValidator(new RecommendationSchema());
        advisor = new RuleBasedFallbackAdvisor(validator);
    }

    @Test
    @DisplayName("attempt=1 + 不合规响应 → 不接管,原样返回(留给 RRA 重试)")
    void firstAttemptInvalid_notReplaced() {
        ChatClientRequest req = newRequest("x"); // attempt 不在 context
        StubChain chain = new StubChain(aiResponse("{\"merchantId\":[\"bad\"],\"reason\":\"r\",\"confidence\":0.5}"));

        ChatClientResponse resp = advisor.adviseCall(req, chain);

        assertThat(chain.callCount()).isEqualTo(1);
        assertThat(extractText(resp)).contains("\"bad\"");
    }

    @Test
    @DisplayName("attempt=2 + 不合规响应 → 接管,fake catalog 输出 schema 合规 JSON")
    void retryAttemptInvalid_replacedWithFallback() {
        ChatClientRequest req = newRequestWithAttempt("x", 2);
        StubChain chain = new StubChain(aiResponse("{\"merchantId\":[\"bad\"],\"reason\":\"r\",\"confidence\":0.5}"));

        ChatClientResponse resp = advisor.adviseCall(req, chain);

        assertThat(chain.callCount()).isEqualTo(1); // inner chain 还是被调一次
        String text = extractText(resp);
        // fallback 包含真实 mock catalog 的前 3 个 ID
        assertThat(text).contains("\"m-001\"").contains("\"m-002\"").contains("\"m-003\"");
        // fallback 必须 schema 合规
        assertThat(validator.isValid(text))
                .as("fallback JSON must satisfy RecommendationSchema: %s", text)
                .isTrue();
    }

    @Test
    @DisplayName("attempt=2 + 已合规 → 不接管,原样返回")
    void retryAttemptValid_passesThrough() {
        String validJson = "{\"merchantId\":[\"m-1\"],\"reason\":\"r\",\"confidence\":0.7}";
        ChatClientRequest req = newRequestWithAttempt("x", 2);
        StubChain chain = new StubChain(aiResponse(validJson));

        ChatClientResponse resp = advisor.adviseCall(req, chain);

        assertThat(extractText(resp)).isEqualTo(validJson);
    }

    @Test
    @DisplayName("buildFallbackJson 静态输出 schema 合规")
    void buildFallbackJson_isSchemaCompliant() {
        String json = RuleBasedFallbackAdvisor.buildFallbackJson();
        assertThat(validator.isValid(json))
                .as("static fallback must be schema compliant: %s", json)
                .isTrue();
        // merchantId 数组最多 3 个(F-3 mock catalog limit)
        assertThat(json).containsPattern("\"merchantId\":\\[\"m-001\",\"m-002\",\"m-003\"\\]");
        // confidence 是 0..1 浮点
        assertThat(json).containsPattern("\"confidence\":0\\.5");
    }

    @Test
    @DisplayName("mock 候选目录包含 5 个商户")
    void mockCatalog_hasFiveEntries() {
        assertThat(RuleBasedFallbackAdvisor.MOCK_CATALOG).hasSize(5);
        assertThat(RuleBasedFallbackAdvisor.MOCK_CATALOG)
                .extracting(RuleBasedFallbackAdvisor.CandidateMerchant::id)
                .containsExactly("m-001", "m-002", "m-003", "m-004", "m-005");
    }

    @Test
    @DisplayName("getOrder:LOWEST_PRECEDENCE - 50 — 位于 RRA(L-100)与 ChatModel(L=MAX)之间")
    void getOrder_innerRelativeToRetry() {
        ReflectiveRetryAdvisor retry = new ReflectiveRetryAdvisor(validator);
        // F-15 fix:order 调到 LOWEST_PRECEDENCE - 50(MAX-50)。
        // 三层关系断言:RRA < RBFA < CM(RBFA 在 CM 之前,否则会被 tie-break stable sort 把 CM 排在 RBFA 前 → RBFA 沦为 retry-only 兜底)。
        int rra = retry.getOrder();
        int rbfa = advisor.getOrder();
        assertThat(rbfa)
                .as("RBFA.order 必须严格大于 RRA.order(即 RBFA 在 RRA 内层,retry 先做完)")
                .isGreaterThan(rra);
        assertThat(rbfa)
                .as("RBFA.order 必须严格小于 Integer.MAX_VALUE(即 RBFA 在 ChatModel 之前,first-attempt 拦截可达)")
                .isLessThan(Integer.MAX_VALUE);
        assertThat(rbfa)
                .as("RBFA.order 必须严格大于 LOWEST_PRECEDENCE - 100(即 RBFA 在 RRA 之后,role chain 顺序 RRA → RBFA → CM)")
                .isGreaterThan(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 100);
        // acceptance 显式值断言
        assertThat(rbfa).isEqualTo(Integer.MAX_VALUE - 50);
    }

    // ---------- helpers ----------

    private static ChatClientRequest newRequest(String userText) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.<Message>of(new UserMessage(userText))))
                .context(new HashMap<>())
                .build();
    }

    private static ChatClientRequest newRequestWithAttempt(String userText, int attempt) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(ReflectiveRetryAdvisor.ATTEMPT_KEY, attempt);
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.<Message>of(new UserMessage(userText))))
                .context(ctx)
                .build();
    }

    private static ChatResponse aiResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static String extractText(ChatClientResponse resp) {
        return resp.chatResponse().getResult().getOutput().getText();
    }

    private static final class StubChain implements CallAdvisorChain {

        private final List<ChatResponse> responses;
        private int count = 0;

        StubChain(ChatResponse response) {
            this.responses = List.of(response);
        }

        int callCount() { return count; }

        @Override
        public ChatClientResponse nextCall(ChatClientRequest request) {
            count++;
            return ChatClientResponse.builder()
                    .chatResponse(responses.get(0))
                    .context(request.context())
                    .build();
        }

        @Override
        public List<org.springframework.ai.chat.client.advisor.api.CallAdvisor> getCallAdvisors() {
            return List.of();
        }

        @Override
        public CallAdvisorChain copy(org.springframework.ai.chat.client.advisor.api.CallAdvisor advisor) {
            throw new UnsupportedOperationException();
        }
    }
}