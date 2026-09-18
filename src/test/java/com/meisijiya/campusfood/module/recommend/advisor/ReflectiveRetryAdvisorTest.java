package com.meisijiya.campusfood.module.recommend.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.meisijiya.campusfood.module.recommend.advisor.ReflectiveRetryAdvisor;
import com.meisijiya.campusfood.module.recommend.schema.JsonSchemaValidator;
import com.meisijiya.campusfood.module.recommend.schema.RecommendationSchema;

/**
 * ReflectiveRetryAdvisor 单元测试(F-3;对应 ticket acceptance #9)。
 *
 * <p>三路径覆盖:
 * <ul>
 *   <li>首次通过 → 仅 1 次 model 调用,不重试</li>
 *   <li>首次失败 → 触发重试,user 消息追加 reflection 反馈,第 2 次合规即返回</li>
 *   <li>两次仍败 → 调用 chain 2 次,第 2 次请求上下文标记 attempt=2(交给 RuleBasedFallbackAdvisor)</li>
 * </ul>
 *
 * <p>策略:用 {@link StubChain} 替代 {@link CallAdvisorChain},内部按调用次数返回不同 mock 响应。
 * ChatResponse 用最简结构(只填 {@code result.output.text}),不依赖任何真实 ChatModel。
 *
 * @author meisijiya
 */
class ReflectiveRetryAdvisorTest {

    private ReflectiveRetryAdvisor advisor;
    private JsonSchemaValidator validator;

    @BeforeEach
    void setUp() {
        validator = new JsonSchemaValidator(new RecommendationSchema());
        advisor = new ReflectiveRetryAdvisor(validator);
    }

    @Test
    @DisplayName("一次通过:仅调 1 次 model,无重试,user 消息不追加 reflection")
    void firstAttemptValid_noRetry() {
        StubChain chain = new StubChain(
                List.of(aiResponse("{\"merchantId\":[\"m-001\"],\"reason\":\"ok\",\"confidence\":0.9}")));

        ChatClientRequest req = newRequest("请推荐一家");
        ChatClientResponse resp = advisor.adviseCall(req, chain);

        assertThat(chain.callCount()).isEqualTo(1);
        assertThat(extractText(resp)).contains("\"m-001\"");
    }

    @Test
    @DisplayName("一次失败重试通过:第 1 次不合规 → 第 2 次合规;调 2 次 model,第 2 次 user 追加 reflection")
    void firstFailRetrySucceeds() {
        StubChain chain = new StubChain(List.of(
                // 第 1 次:不合规(merchantId 缺前缀)
                aiResponse("{\"merchantId\":[\"bad\"],\"reason\":\"r\",\"confidence\":0.5}"),
                // 第 2 次:合规
                aiResponse("{\"merchantId\":[\"m-001\"],\"reason\":\"ok\",\"confidence\":0.8}")));

        ChatClientRequest req = newRequest("请推荐一家");
        ChatClientResponse resp = advisor.adviseCall(req, chain);

        assertThat(chain.callCount()).isEqualTo(2);
        assertThat(extractText(resp)).contains("\"m-001\"");

        // 第 2 次请求的 user 消息应该包含 [Reflection] 反馈
        Prompt secondPrompt = chain.lastPrompt();
        assertThat(secondPrompt.getUserMessage().getText()).contains("[Reflection]");
        assertThat(secondPrompt.getUserMessage().getText()).contains("merchantId");

        // 第 2 次请求上下文标记 attempt=2
        Map<String, Object> secondCtx = chain.lastRequestContext();
        assertThat(secondCtx).containsEntry(ReflectiveRetryAdvisor.ATTEMPT_KEY, 2);
    }

    @Test
    @DisplayName("两次仍失败:调 2 次 model,即使都不合规也返回最后一次响应(不抛异常)")
    void bothAttemptsFail_returnsLastResponse() {
        StubChain chain = new StubChain(List.of(
                aiResponse("{\"merchantId\":[\"bad\"],\"reason\":\"r\",\"confidence\":0.5}"),
                aiResponse("{\"merchantId\":[\"still-bad\"],\"reason\":\"r\",\"confidence\":0.5}")));

        ChatClientRequest req = newRequest("请推荐");
        ChatClientResponse resp = advisor.adviseCall(req, chain);

        // 2 次 chain 调用,第 2 次上下文标记 attempt=2
        assertThat(chain.callCount()).isEqualTo(2);
        assertThat(chain.lastRequestContext()).containsEntry(ReflectiveRetryAdvisor.ATTEMPT_KEY, 2);

        // 不抛异常 — 返回第 2 次响应,RuleBasedFallbackAdvisor 会接管
        assertThat(resp).isNotNull();
        assertThat(extractText(resp)).contains("still-bad");
    }

    @Test
    @DisplayName("getOrder:LOWEST_PRECEDENCE - 100 — 比 RuleBasedFallbackAdvisor 靠外")
    void getOrder_outerRelativeToFallback() {
        assertThat(advisor.getOrder()).isLessThan(new RuleBasedFallbackAdvisor(validator).getOrder());
        assertThat(advisor.getName()).isEqualTo("reflective-retry-advisor");
    }

    // ---------- helpers ----------

    /** 构造 ChatClientRequest 的便捷方法。 */
    private static ChatClientRequest newRequest(String userText) {
        List<Message> messages = List.of(new UserMessage(userText));
        return ChatClientRequest.builder()
                .prompt(new Prompt(messages))
                .context(new HashMap<>())
                .build();
    }

    private static ChatResponse aiResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static String extractText(ChatClientResponse resp) {
        return resp.chatResponse().getResult().getOutput().getText();
    }

    /**
     * 替代 {@link CallAdvisorChain}:每次 {@code nextCall} 顺序返回预排的响应,
     * 并记录最后一次请求(用于断言 user 消息追加 + context 标记)。
     */
    private static final class StubChain implements CallAdvisorChain {

        private final List<ChatResponse> responses;
        private final AtomicInteger count = new AtomicInteger(0);
        private Prompt lastPrompt;
        private Map<String, Object> lastRequestContext;

        StubChain(List<ChatResponse> responses) {
            this.responses = responses;
        }

        int callCount() { return count.get(); }
        Prompt lastPrompt() { return lastPrompt; }
        Map<String, Object> lastRequestContext() { return lastRequestContext; }

        @Override
        public ChatClientResponse nextCall(ChatClientRequest request) {
            int idx = count.getAndIncrement();
            this.lastPrompt = request.prompt();
            this.lastRequestContext = request.context();
            ChatResponse cr = responses.get(Math.min(idx, responses.size() - 1));
            return ChatClientResponse.builder()
                    .chatResponse(cr)
                    .context(request.context())
                    .build();
        }

        @Override
        public List<org.springframework.ai.chat.client.advisor.api.CallAdvisor> getCallAdvisors() {
            return List.of();
        }

        @Override
        public CallAdvisorChain copy(org.springframework.ai.chat.client.advisor.api.CallAdvisor advisor) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }
}