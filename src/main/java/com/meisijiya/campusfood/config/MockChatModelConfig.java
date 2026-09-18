package com.meisijiya.campusfood.config;

import com.meisijiya.campusfood.module.recommend.MockChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Mock ChatModel 配置 — dev / test / it profile 默认启用,覆盖 Spring AI Alibaba 自动注入。
 *
 * <p>F-3 真实接入百炼时切 {@code SPRING_PROFILES_ACTIVE=bench};其他 profile 必须走 Mock,
 * 避免开发 / 测试阶段产生 token 计费。
 *
 * @author meisijiya
 */
@Configuration
@Profile("!bench & !smoke")
public class MockChatModelConfig {

    /**
     * 覆盖 Alibaba Starter 自动配置的 ChatModel Bean。
     * 仅当没有其他 ChatModel Bean 时生效(F-3 bench profile 不会启用本配置类)。
     */
    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel mockChatModel() {
        return new MockChatModel();
    }
}