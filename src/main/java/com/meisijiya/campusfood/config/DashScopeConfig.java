package com.meisijiya.campusfood.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatProperties;

/**
 * bench / smoke profile 启用的 DashScope 配置(F-3;对应 ticket acceptance #4)。
 *
 * <p>{@code spring-ai-alibaba-starter-dashscope:1.1.2.2} 已经通过
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 自动注册 {@code DashScopeChatAutoConfiguration},在 {@code spring.ai.dashscope.enabled=true}
 * 时创建 {@code DashScopeChatModel} Bean。本类作为 bench 入口的显式标记:
 * <ul>
 *   <li>{@link Profile} 限定只在 bench / smoke 触发(避免污染 dev/test/it)</li>
 *   <li>{@link ConditionalOnProperty} 限定必须在 API key 已注入时才激活 — 否则整链路
 *       (本类 + bench profile)无效,不会去拉真实 API</li>
 *   <li>激活时打 INFO 日志,让 evidence 段可直接 grep 到 "DashScope ChatModel enabled"</li>
 * </ul>
 *
 * <p>模型 / 温度等参数已在 {@code application-bench.yml} 配齐;key 通过
 * {@code spring.ai.dashscope.api-key=${DASHSCOPE_API_KEY:}} 注入(默认空 → 本类不激活)。
 *
 * @author meisijiya
 */
@Configuration
@Profile({"bench", "smoke"})
@ConditionalOnProperty(name = "spring.ai.dashscope.api-key")
public class DashScopeConfig {

    private static final Logger log = LoggerFactory.getLogger(DashScopeConfig.class);

    public DashScopeConfig(DashScopeChatProperties props) {
        String model = props.getOptions() == null ? "unknown"
                : (props.getOptions().getModel() == null ? "qwen-plus" : props.getOptions().getModel());
        log.info("F-3 DashScope ChatModel enabled (profile=bench/smoke, model={}, api-key=*****)", model);
    }
}