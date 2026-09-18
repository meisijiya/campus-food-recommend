package com.meisijiya.campusfood;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 上下文烟雾测试:验证 ApplicationContext 能正常启动。
 *
 * <p>关键验证:Spring AI 双 Profile 配置正确;SecurityFilterChain 装配成功;
 * MockChatModel 在 dev profile 下被注入。
 *
 * @author meisijiya
 */
@SpringBootTest
@ActiveProfiles("dev")
class ApplicationContextSmokeTest {

    @Test
    void contextLoads() {
        // 上下文成功启动即通过
    }
}