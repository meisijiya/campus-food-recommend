package com.meisijiya.campusfood.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.meisijiya.campusfood.module.recommend.advisor.ReflectiveRetryAdvisor;
import com.meisijiya.campusfood.module.recommend.advisor.RuleBasedFallbackAdvisor;

/**
 * 推荐 ChatClient 配置(F-3;对应 ticket acceptance #7 "接入 ReflectiveRetryAdvisor + RuleBasedFallbackAdvisor")。
 *
 * <p>把现有 {@link ChatModel}(dev/test/it 走 MockChatModel;bench/smoke 走 DashScope ChatModel)
 * 包成 {@link ChatClient},在 defaultAdvisors 上挂 F-3 的两个顾问 ——
 * <ol>
 *   <li>{@link ReflectiveRetryAdvisor}({@code LOWEST_PRECEDENCE - 100})— 外层先跑,做反思重试</li>
 *   <li>{@link RuleBasedFallbackAdvisor}({@code LOWEST_PRECEDENCE})— 内层后跑,仅在 attempt=2 且不合规时接管</li>
 * </ol>
 *
 * <p>不需要在 Controller / Service 里关心 advisor 编排 ——
 * {@code ChatClient} bean 一旦注入,所有 {@code .call().chatResponse()} 自动经过这条链。
 *
 * @author meisijiya
 */
@Configuration
public class RecommendChatClientConfig {

    /**
     * 推荐专用 ChatClient。命名为 {@code recommendChatClient} 避免与未来其他
     * (可能引入的)ChatClient Bean 冲突。
     */
    @Bean
    public ChatClient recommendChatClient(
            ChatModel chatModel,
            ReflectiveRetryAdvisor reflectiveRetryAdvisor,
            RuleBasedFallbackAdvisor ruleBasedFallbackAdvisor) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(reflectiveRetryAdvisor, ruleBasedFallbackAdvisor)
                .build();
    }
}