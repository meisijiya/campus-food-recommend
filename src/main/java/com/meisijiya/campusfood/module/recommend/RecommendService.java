package com.meisijiya.campusfood.module.recommend;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import com.meisijiya.campusfood.common.TokenEstimator;
import com.meisijiya.campusfood.module.catalog.session.SessionContext;
import com.meisijiya.campusfood.module.catalog.session.SessionService;

/**
 * 推荐业务服务(F-2 占位 + Skill 注入;对应 ticket acceptance #11)。
 *
 * <p>流程:
 * <ol>
 *   <li>读当前会话上下文(从 Redis,见 {@link SessionService#readContext(String)})</li>
 *   <li>用 {@link SkillRegistry#substitute(String, SessionContext)} 把 Skill 数据注入模板</li>
 *   <li>组装 {@link Prompt} 调 {@link ChatModel#call(Prompt)}</li>
 *   <li>从 {@code ChatResponse.metadata.usage.promptTokens} 读 token 统计(由
 *       {@link MockChatModel} 按 {@link TokenEstimator} 填充)</li>
 * </ol>
 *
 * <p>F-3 起会在这里加 JSON Schema 校验与反思重试;当前只占位。
 *
 * @author meisijiya
 */
@Service
public class RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendService.class);

    /**
     * 推荐 prompt 模板 — 仅引用 Skill 名称(由 {@link SkillRegistry} 运行时注入真实数据)。
     * 该模板不可 inline 任何目录数据(违反 plan.md §Global Constraints #8)。
     */
    static final String RECOMMEND_TEMPLATE = """
            你是校园美食推荐助手。请根据用户当前会话阶段给出推荐。
            用户当前 stage: %s
            {skill:zone}
            {skill:cuisine}
            {skill:merchant}
            请输出符合 RecommendationSchema 的 JSON。
            """;

    private final ChatModel chatModel;
    private final SkillRegistry skillRegistry;
    private final SessionService sessionService;

    public RecommendService(ChatModel chatModel, SkillRegistry skillRegistry, SessionService sessionService) {
        this.chatModel = chatModel;
        this.skillRegistry = skillRegistry;
        this.sessionService = sessionService;
    }

    /**
     * 对当前 sid 的会话跑一次推荐。返回 MockChatModel 的固定 JSON + token 统计。
     */
    public RecommendationResult recommend(String sid) {
        SessionContext ctx = sessionService.readContext(sid);
        String template = RECOMMEND_TEMPLATE.formatted(ctx.stage().name());
        String fullPrompt = skillRegistry.substitute(template, ctx);

        log.debug("F-2 recommend sid={} stage={} promptLen={}", sid, ctx.stage(), fullPrompt.length());

        List<Message> messages = List.of(
                new SystemMessage("你是校园美食推荐助手。"),
                new UserMessage(fullPrompt)
        );
        Prompt prompt = new Prompt(messages);
        ChatResponse response = chatModel.call(prompt);

        String content = response.getResult().getOutput().getText();
        Integer promptTokens = response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? null : response.getMetadata().getUsage().getPromptTokens();
        Integer completionTokens = response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? null : response.getMetadata().getUsage().getCompletionTokens();

        return new RecommendationResult(content, ctx.stage(), promptTokens, completionTokens);
    }

    /** 推荐响应。 */
    public record RecommendationResult(String content,
                                       com.meisijiya.campusfood.module.catalog.session.SessionStage stage,
                                       Integer promptTokens,
                                       Integer completionTokens) {}
}