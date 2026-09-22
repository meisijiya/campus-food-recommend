package com.meisijiya.campusfood.module.recommend.advisor;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import com.meisijiya.campusfood.module.recommend.advisor.ReflectiveRetryAdvisor;
import com.meisijiya.campusfood.module.recommend.schema.JsonSchemaValidator;

/**
 * 规则降级顾问(F-3;对应 ticket acceptance #6)。
 *
 * <p>仅当 {@link ReflectiveRetryAdvisor} 重试 1 次后仍不合规时接管 —
 * 由上下文键 {@link ReflectiveRetryAdvisor#ATTEMPT_KEY}{@code =2} 标识。
 * 接管动作:用一个静态"按本地热门标签打分"的候选商户集合,生成 schema 合规的 fallback JSON。
 *
 * <p>候选集合是 <b>mock 数据</b>(F-3 阶段);F-4 起会从 Redis 分片缓存的 catalog 中读取真实数据
 * (见 ticket acceptance 的降级说明)。
 *
 * <p>纪律(plan Global Constraint #6):fallback 输出必须 schema 合规 —
 * 因此这里走硬编码字符串拼接 + {@link JsonSchemaValidator#isValid} 自检,
 * 防止 fallback 自己也违反 schema 导致无限降级循环。
 *
 * @author meisijiya
 */
@Component
public class RuleBasedFallbackAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedFallbackAdvisor.class);

    /** F-3 mock 候选目录;F-4 起替换为 Redis 真实 catalog。 */
    static final List<CandidateMerchant> MOCK_CATALOG = List.of(
            new CandidateMerchant("m-001", "黄焖鸡米饭", List.of("中式", "快餐", "便宜")),
            new CandidateMerchant("m-002", "兰州拉面",   List.of("中式", "面食")),
            new CandidateMerchant("m-003", "麻辣香锅",   List.of("中式", "辣")),
            new CandidateMerchant("m-004", "肯德基",     List.of("西式", "快餐")),
            new CandidateMerchant("m-005", "麦当劳",     List.of("西式", "快餐"))
    );

    private final JsonSchemaValidator validator;

    public RuleBasedFallbackAdvisor(JsonSchemaValidator validator) {
        this.validator = validator;
    }

    @Override
    public String getName() {
        return "rule-based-fallback-advisor";
    }

    @Override
    public int getOrder() {
        // F-15 fix:位于 ReflectiveRetryAdvisor(L-100)与 ChatModelCallAdvisor(L=MAX)之间
        // ——stable sort 不会让同 order tie-break,RBFA.order = MAX-50 < CM.order = MAX,
        // RBFA 真正在 CM 之前,attempt=1 直通 CM(不接管),attempt=2 拦截 CM 响应接管(保留 metadata)。
        return Ordered.LOWEST_PRECEDENCE - 50;
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
        // F-15 fix (前序 F-14):RBFA.order 现在是 LOWEST_PRECEDENCE - 50(MAX-50),
        // ChatModelCallAdvisor.order 是 LOWEST_PRECEDENCE(MAX),stable sort 不再 tie-break
        // ——RBFA 真正排在 CM 之前。两条主路径:
        //   - attempt=1:RRA 调 chain.nextCall 第一次,pop 顺序 [RRA → RBFA → CM]。RBFA 调
        //     isRetryAttempt(request) → 读 request.context().get(RRA.ATTEMPT_KEY) → Integer 不为 2
        //     → 返回 false → RBFA 直通 CM 响应(在 chain.nextCall 拿到 CM 响应后立即判断并透传)。
        //   - attempt=2:RRA 触发重试,第二次 nextCall pop 顺序仍是 [RRA → RBFA → CM],
        //     CM 真实调用。RBFA 拿到 CM 响应后 → isRetryAttempt(request) 返回 true →
        //     校验不合规 → buildFallbackReplacedResponse 接管(保留 CM chatResponse metadata:
        //     usage / model / token 计数等)。
        // 残留 fallback try-catch(F-14 防御):即便因框架升级等出现 deque-empty,仍兜底产 fallback,
        // 但 F-15 order 修复后实际不会走这条分支。
        ChatClientResponse response;
        try {
            response = chain.nextCall(request);
        } catch (IllegalStateException e) {
            if (e.getMessage() == null || !e.getMessage().contains("No CallAdvisors")) {
                throw e;
            }
            log.warn("F-3 fallback advisor: inner chain empty (chain drained after RRA retry), "
                    + "returning mock-catalog fallback JSON");
            return buildFallbackChatClientResponse(request);
        }

        // 只在第 2 次(重试)调用 + 响应仍不合规时接管
        boolean isRetryAttempt = isRetryAttempt(request);
        if (!isRetryAttempt) {
            return response;
        }
        String content = extractText(response);
        if (content != null && safeIsValid(content)) {
            return response;
        }

        log.warn("F-3 反思重试仍不合规,触发规则降级 fallback");
        return buildFallbackReplacedResponse(response, request);
    }

    /**
     * 在 chain 已耗尽(no downstream advisor)的极端情况下,直接构造 fallback
     * ChatClientResponse ——
     * 不保留任何上游 ChatModel 响应 metadata(因为没有),但保留当前 request 的 context。
     */
    private static ChatClientResponse buildFallbackChatClientResponse(ChatClientRequest request) {
        ChatResponse replaced = replaceContent(null, buildFallbackJson());
        return ChatClientResponse.builder()
                .chatResponse(replaced)
                .context(request.context())
                .build();
    }

    /**
     * chain.nextCall 拿到上游响应后,把它替换成 schema 合规的 fallback JSON —
     * 保留原 ChatResponse 的 metadata(usage / model 等),只换 AssistantMessage 内容。
     */
    private static ChatClientResponse buildFallbackReplacedResponse(ChatClientResponse original,
                                                                    ChatClientRequest request) {
        ChatResponse replaced = replaceContent(original.chatResponse(), buildFallbackJson());
        return ChatClientResponse.builder()
                .chatResponse(replaced)
                .context(request.context())
                .build();
    }

    private static boolean isRetryAttempt(ChatClientRequest request) {
        Object attempt = request.context().get(ReflectiveRetryAdvisor.ATTEMPT_KEY);
        return attempt instanceof Integer i && i == 2;
    }

    private boolean safeIsValid(String content) {
        try {
            return validator.isValid(content);
        } catch (Exception e) {
            return false;
        }
    }

    /** 构造 schema 合规的 fallback JSON。F-4 起替换为真实 catalog 排序。 */
    static String buildFallbackJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"merchantId\":[");
        int limit = Math.min(3, MOCK_CATALOG.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(MOCK_CATALOG.get(i).id()).append("\"");
        }
        sb.append("],\"reason\":\"按本地热门标签排序的降级推荐(F-3 mock catalog)\","
                + "\"confidence\":0.5}");
        return sb.toString();
    }

    private static ChatResponse replaceContent(ChatResponse original, String newContent) {
        if (original == null) {
            // 极端情况:ChatModel 没返回任何内容 — 用裸 metadata 构造一个
            AssistantMessage msg = new AssistantMessage(newContent);
            return new ChatResponse(List.of(new Generation(msg)));
        }
        AssistantMessage msg = new AssistantMessage(newContent);
        Generation gen = new Generation(msg);
        return new ChatResponse(List.of(gen), original.getMetadata());
    }

    private static String extractText(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return null;
        }
        return response.chatResponse().getResult().getOutput().getText();
    }

    /** Mock 候选商户。F-4 起删除(由 Redis catalog 取代)。 */
    public record CandidateMerchant(String id, String name, List<String> tags) {}
}