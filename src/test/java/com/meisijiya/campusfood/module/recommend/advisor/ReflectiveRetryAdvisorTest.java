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
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
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

        // F-14.2:RRA 第二次走 chain.copy(this).nextCall,所以总 nextCall 调用 = 原 chain 1 + copy 链 1 = 2
        assertThat(chain.callCount() + chain.lastCopy().callCount()).isEqualTo(2);
        assertThat(extractText(resp)).contains("\"m-001\"");

        // 第 2 次请求(retry)的 user 消息应该包含 [Reflection] 反馈
        // F-14.2:retry 走 copy 链,所以从 copy.lastPrompt() 拿
        Prompt secondPrompt = chain.lastCopy().lastPrompt();
        assertThat(secondPrompt.getUserMessage().getText()).contains("[Reflection]");
        assertThat(secondPrompt.getUserMessage().getText()).contains("merchantId");

        // 第 2 次请求上下文标记 attempt=2(retry 路径设置,在 copy 链上记录)
        Map<String, Object> secondCtx = chain.lastCopy().lastRequestContext();
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

        // F-14.2:2 次总 nextCall = 原 chain 1 + copy 链 1;attempt=2 由 retry 路径设置
        assertThat(chain.callCount() + chain.lastCopy().callCount()).isEqualTo(2);
        assertThat(chain.lastCopy().lastRequestContext()).containsEntry(ReflectiveRetryAdvisor.ATTEMPT_KEY, 2);

        // 不抛异常 — 返回第 2 次响应,RuleBasedFallbackAdvisor 会接管
        assertThat(resp).isNotNull();
        assertThat(extractText(resp)).contains("still-bad");
    }

    @Test
    @DisplayName("getOrder:LOWEST_PRECEDENCE - 100 — 严格最外层(RRA < RBFA,RBFA.order 现在是 MAX-50)")
    void getOrder_outerRelativeToFallback() {
        // F-15 同步:RRA.order 不变(MAX-100),RBFA.order 现在是 MAX-50;
        // 语义未变(RRA 仍在最外层,RBFA 仍在 RRA 内层),displayName 加注 RBFA 新值便于回归期一眼看出。
        int rra = advisor.getOrder();
        int rbfa = new RuleBasedFallbackAdvisor(validator).getOrder();
        assertThat(rra)
                .as("RRA.order 必须严格小于 RBFA.order(MAX-100 < MAX-50,retry 先跑)")
                .isLessThan(rbfa);
        assertThat(rra).isEqualTo(Integer.MAX_VALUE - 100);
        assertThat(advisor.getName()).isEqualTo("reflective-retry-advisor");
    }

    @Test
    @DisplayName("F-14.2:RRA retry 走 copy(this) 链 — 原 chain 仅被 first response 命中,retry 命中 copy 副本")
    void firstAttemptRetry_usesCopyNotOriginalChain() {
        // 原 chain:第 1 次响应 invalid(故意不合规)
        // copy 链:第 2 次响应合规(模拟 RBFA 接管后的 fallback JSON)
        StubChain chain = new StubChain(List.of(
                aiResponse("{\"merchantId\":[\"bad\"],\"reason\":\"r\",\"confidence\":0.5}"),
                aiResponse("{\"merchantId\":[\"m-001\"],\"reason\":\"ok\",\"confidence\":0.9}")));

        ChatClientRequest req = newRequest("请推荐一家");
        ChatClientResponse resp = advisor.adviseCall(req, chain);

        // 原 chain 只被首次 nextCall 命中 1 次
        assertThat(chain.callCount())
                .as("F-14.2:原 chain 仅 first response 命中,retry 必须走 copy")
                .isEqualTo(1);
        assertThat(chain.isCopy())
                .as("原 chain isCopy=false")
                .isFalse();

        // copy 副本被 retry 命中 1 次,标记 isCopy=true
        StubChain copy = chain.lastCopy();
        assertThat(copy)
                .as("F-14.2:retry 路径必须调用 chain.copy(this)")
                .isNotNull();
        assertThat(copy.isCopy())
                .as("copy 副本 isCopy=true")
                .isTrue();
        assertThat(copy.callCount())
                .as("F-14.2:copy 副本仅被 retry 命中 1 次")
                .isEqualTo(1);
        assertThat(chain.lastCopyAdvisor())
                .as("F-14.2:copy(this) 必须传入 RRA 实例自身(防框架升级时找不到 self)")
                .isSameAs(advisor);

        // 最终响应合规(retry 路径返回 copy 链上的合规 JSON)
        assertThat(extractText(resp)).contains("\"m-001\"");
    }

    @Test
    @DisplayName("F-14.2:copy(this) 返回的副本链不含 RRA 自身(防无限递归)")
    void firstAttemptRetry_chainCopyExcludesRRA() {
        StubChain chain = new StubChain(List.of(
                aiResponse("{\"merchantId\":[\"bad\"],\"reason\":\"r\",\"confidence\":0.5}"),
                aiResponse("{\"merchantId\":[\"m-001\"],\"reason\":\"ok\",\"confidence\":0.9}")));

        ChatClientRequest req = newRequest("请推荐一家");
        advisor.adviseCall(req, chain);

        // copy 链的 advisor 列表不含 RRA 自身(Spring AI copy() 内部"过滤掉 self"契约)
        StubChain copy = chain.lastCopy();
        assertThat(copy).isNotNull();
        assertThat(copy.getCallAdvisors())
                .as("F-14.2:copy 链 advisor 列表必须过滤掉 self(RRA)")
                .doesNotContain((CallAdvisor) advisor);
        assertThat(copy.getCallAdvisors())
                .as("F-14.2:copy 链 advisor 列表不包含 RRA 的 getName()")
                .noneMatch(a -> a.getName().equals(advisor.getName()));
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
     *
     * <p>F-14.2 改造:支持 {@link #copy(CallAdvisor)} 返回一个新的 {@code StubChain} 副本
     * (标记 {@link #isCopy}=true),响应索引从原 chain 已消费位置续接,便于断言 RRA retry 路径
     * 走 copy 链而非原 chain(并正确返回下一条预排响应)。
     */
    private static final class StubChain implements CallAdvisorChain {

        private final List<ChatResponse> responses;
        private final boolean isCopy;         // 是否由 copy() 返回的副本
        private final int responseStartIndex; // copy 链续接原 chain 已消费位置
        private final AtomicInteger count = new AtomicInteger(0);
        private Prompt lastPrompt;
        private Map<String, Object> lastRequestContext;
        private CallAdvisor lastCopyAdvisor;  // copy() 被调用时传入的 advisor
        private StubChain lastCopy;            // copy() 最近一次返回的副本

        StubChain(List<ChatResponse> responses) {
            this(responses, 0, false);
        }

        private StubChain(List<ChatResponse> responses, int responseStartIndex, boolean isCopy) {
            this.responses = responses;
            this.responseStartIndex = responseStartIndex;
            this.isCopy = isCopy;
        }

        int callCount() { return count.get(); }
        Prompt lastPrompt() { return lastPrompt; }
        Map<String, Object> lastRequestContext() { return lastRequestContext; }
        boolean isCopy() { return isCopy; }
        CallAdvisor lastCopyAdvisor() { return lastCopyAdvisor; }
        StubChain lastCopy() { return lastCopy; }

        @Override
        public ChatClientResponse nextCall(ChatClientRequest request) {
            int idx = responseStartIndex + count.getAndIncrement();
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
            // F-14.2 语义:copy 链的 advisor 列表不含 RRA 自身(防无限递归)
            // 这里 stub 始终返回空 list,代表"已过滤掉 self"
            return List.of();
        }

        @Override
        public CallAdvisorChain copy(org.springframework.ai.chat.client.advisor.api.CallAdvisor advisor) {
            // F-14.2 行为:返回一个新的 StubChain 副本,标记 isCopy=true,
            // responseStartIndex = 原 chain 已消费位置(续接,避免 retry 拿到同一条 invalid 响应)
            this.lastCopyAdvisor = advisor;
            this.lastCopy = new StubChain(this.responses, this.responseStartIndex + this.count.get(), true);
            return this.lastCopy;
        }
    }
}