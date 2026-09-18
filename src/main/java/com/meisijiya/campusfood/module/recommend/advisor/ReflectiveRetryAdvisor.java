package com.meisijiya.campusfood.module.recommend.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import com.meisijiya.campusfood.module.recommend.schema.JsonSchemaValidator;

/**
 * 反思重试顾问(F-3;对应 ticket acceptance #5)。
 *
 * <p>Spring AI 1.1.x 的 {@link CallAdvisor} 接口约定 {@code before() → chain.nextCall() → after()},
 * 但 {@code BaseAdvisor} 默认实现就是这条流水线 — 而我们的需求是"校验失败 → 把错误摘要回拼
 * 到下一次 user 消息 → 再调一次"。在 {@link #adviseCall} 里手动执行这个重试循环最自然,
 * 这样不需要在 {@code after()} 里抛异常控制流,也不需要调用方介入。
 *
 * <p>重试上限:1 次原始 + 1 次重试 = 2 次 chatModel 调用。第二次请求通过上下文
 * {@code f3.attempt=2} 通知 {@link RuleBasedFallbackAdvisor} 接管 —
 * 不会抛异常给 controller,符合 plan Global Constraint #5。
 *
 * @author meisijiya
 */
@Component
public class ReflectiveRetryAdvisor implements BaseAdvisor {

    /** 上下文键,标记"已经是第 2 次(重试)调用"。 */
    public static final String ATTEMPT_KEY = "f3.attempt";

    private static final Logger log = LoggerFactory.getLogger(ReflectiveRetryAdvisor.class);

    /** 反思反馈模板 — 把违规消息作为 user 追加消息回拼给 AI。 */
    private static final String REFLECTION_PROMPT = """
            \n\n[Reflection] 上一次响应未通过 JSON Schema 校验,违规如下:\n%s\n
            请重新输出严格符合 schema 的 JSON,不要再加额外字段或解释文字。""";

    private final JsonSchemaValidator validator;

    public ReflectiveRetryAdvisor(JsonSchemaValidator validator) {
        this.validator = validator;
    }

    @Override
    public String getName() {
        return "reflective-retry-advisor";
    }

    @Override
    public int getOrder() {
        // LOWEST_PRECEDENCE - 100:在链中靠外,先于 RuleBasedFallbackAdvisor 执行,
        // 保证我们能在 fallback 之前做完重试。
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // 第一次调用 — 信任 inner advisor 链
        ChatClientResponse first = chain.nextCall(request);
        String firstContent = extractText(first);
        if (firstContent != null && safeIsValid(firstContent)) {
            return first;
        }

        log.debug("F-3 first response invalid, building retry request with reflection feedback");

        // 第二次调用 — 把违规摘要回拼到 user 消息 + 上下文标记 attempt=2
        String violations = firstContent == null
                ? "响应为空或无法解析"
                : String.join("; ", collectViolations(firstContent));
        ChatClientRequest retryRequest = request.mutate()
                .prompt(request.prompt().augmentUserMessage(REFLECTION_PROMPT.formatted(violations)))
                .context(ATTEMPT_KEY, 2)
                .build();

        ChatClientResponse second = chain.nextCall(retryRequest);
        // 第二次响应 — RuleBasedFallbackAdvisor 在 inner 链中看到 attempt=2 后会接管,
        // 直接替换成 schema 合规的 fallback JSON。这里再做一次校验:
        //   - 如果 RBFA 已替换 → fallback 必然合规 → 返回
        //   - 如果 RBFA 没替换(防御性) → 第二次仍不合规,继续返回原响应让上层兜底
        String secondContent = extractText(second);
        if (secondContent != null && !safeIsValid(secondContent)) {
            log.warn("ReflectiveRetryAdvisor: retry response failed schema validation; returning as-is. content={}",
                    secondContent);
        }
        return second;
    }

    private boolean safeIsValid(String content) {
        try {
            return validator.isValid(content);
        } catch (Exception e) {
            return false;
        }
    }

    private java.util.List<String> collectViolations(String content) {
        try {
            validator.validateOrThrow(content);
            return java.util.List.of();
        } catch (com.meisijiya.campusfood.module.recommend.schema.SchemaViolationException ex) {
            return ex.violations();
        } catch (Exception ex) {
            return java.util.List.of(ex.getMessage());
        }
    }

    private static String extractText(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return null;
        }
        return response.chatResponse().getResult().getOutput().getText();
    }
}