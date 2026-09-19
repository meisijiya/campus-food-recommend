package com.meisijiya.campusfood.module.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.meisijiya.campusfood.config.MicrometerConfig;
import com.meisijiya.campusfood.module.catalog.session.SessionContext;
import com.meisijiya.campusfood.module.catalog.session.SessionService;

/**
 * {@link RecommendService} 业务指标单元测试(F-9 W1)。
 *
 * <p>验证:
 * <ul>
 *   <li>recommend() 正常路径(mock profile)— {@code recommend_latency_seconds{endpoint="recommend",hit_tier="mock"}}
 *       Timer 记录 1 次</li>
 *   <li>recommend() 正常路径(bench profile)— Timer tag hit_tier="dashscope"</li>
 *   <li>fallback 路径(content 含 "降级推荐")— Timer tag hit_tier="fallback"</li>
 *   <li>Timer 的 count 与 total time 都增加</li>
 * </ul>
 *
 * <p>mock ChatClient 的 chain ({@code prompt(prompt).call().chatResponse()}) 用
 * {@code Mockito.RETURNS_DEEP_STUBS} — Spring AI 的 prompt()/call()/chatResponse() 链路很深,
 * deep stub 在单测里最干净。
 *
 * @author meisijiya
 */
class RecommendServiceMetricsTest {

    private ChatClient chatClient;
    private SkillRegistry skillRegistry;
    private SessionService sessionService;
    private MeterRegistry meterRegistry;
    private MicrometerConfig micrometerConfig;

    @BeforeEach
    void setUp() {
        // ChatClient 用 deep stubs 自动处理 prompt(...).call().chatResponse() 链
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        skillRegistry = mock(SkillRegistry.class);
        sessionService = mock(SessionService.class);
        meterRegistry = new SimpleMeterRegistry();
        micrometerConfig = new MicrometerConfig(meterRegistry);

        // SkillRegistry 用 noop(测试不需要真实 Skill 数据);但要求返回非空,因为
        // skillRegistry.substitute(template, ctx) 直接被 RecommendService 调用
        when(skillRegistry.substitute(any(String.class), any(SessionContext.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("recommend_mock_profile_Timer记录hit_tier=mock")
    void recommend_mockProfile_timerHitsMockTier() {
        when(sessionService.readContext("sid-1")).thenReturn(SessionContext.empty());
        ChatResponse response = chatResponse("{\"merchantId\":[\"m-001\"],\"reason\":\"r\",\"confidence\":0.9}");
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .call().chatResponse()).thenReturn(response);

        RecommendService service = newService(""); // 空 activeProfile → mock

        service.recommend("sid-1");

        Timer t = meterRegistry.find(MicrometerConfig.RECOMMEND_LATENCY)
                .tag("endpoint", "recommend")
                .tag("hit_tier", "mock")
                .timer();
        assertThat(t).isNotNull();
        assertThat(t.count()).isEqualTo(1L);
        assertThat(t.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("recommend_bench_profile_Timer记录hit_tier=dashscope")
    void recommend_benchProfile_timerHitsDashscopeTier() {
        when(sessionService.readContext("sid-2")).thenReturn(SessionContext.empty());
        ChatResponse response = chatResponse("{\"merchantId\":[\"m-001\"],\"reason\":\"r\",\"confidence\":0.9}");
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .call().chatResponse()).thenReturn(response);

        RecommendService service = newService("bench,smoke");

        service.recommend("sid-2");

        Timer t = meterRegistry.find(MicrometerConfig.RECOMMEND_LATENCY)
                .tag("endpoint", "recommend")
                .tag("hit_tier", "dashscope")
                .timer();
        assertThat(t).isNotNull();
        assertThat(t.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("recommend_fallback_content_Timer记录hit_tier=fallback_优先级高于profile")
    void recommend_fallbackContent_timerHitsFallbackTier_overridingProfile() {
        when(sessionService.readContext("sid-3")).thenReturn(SessionContext.empty());
        // content 含 "降级推荐" 是 RuleBasedFallbackAdvisor 的稳定产物
        ChatResponse response = chatResponse("{\"merchantId\":[\"m-001\"],\"reason\":\"按本地热门标签排序的降级推荐(F-3 mock catalog)\",\"confidence\":0.5}");
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .call().chatResponse()).thenReturn(response);

        // 即使 profile=bench,fallback 命中时 tier 仍记 fallback
        RecommendService service = newService("bench");

        service.recommend("sid-3");

        Timer fallbackTimer = meterRegistry.find(MicrometerConfig.RECOMMEND_LATENCY)
                .tag("endpoint", "recommend")
                .tag("hit_tier", "fallback")
                .timer();
        assertThat(fallbackTimer).isNotNull();
        assertThat(fallbackTimer.count()).isEqualTo(1L);

        // mock / dashscope timer 不应有数据
        assertThat(meterRegistry.find(MicrometerConfig.RECOMMEND_LATENCY)
                .tag("hit_tier", "mock").timer().count()).isEqualTo(0L);
        assertThat(meterRegistry.find(MicrometerConfig.RECOMMEND_LATENCY)
                .tag("hit_tier", "dashscope").timer().count()).isEqualTo(0L);
    }

    @Test
    @DisplayName("MicrometerConfig.recommendTimer_helper返回正确name_and_tags")
    void micrometerConfig_recommendTimer_publishesCorrectMeterNameAndTags() {
        Timer t = micrometerConfig.recommendTimer("recommend", "mock");
        t.record(() -> {
            try { Thread.sleep(2); } catch (InterruptedException e) { /* ignore */ }
        });

        assertThat(t.count()).isEqualTo(1L);
        assertThat(t.getId().getName()).isEqualTo(MicrometerConfig.RECOMMEND_LATENCY);
        assertThat(t.getId().getTag("endpoint")).isEqualTo("recommend");
        assertThat(t.getId().getTag("hit_tier")).isEqualTo("mock");
    }

    /** 构造一个 ChatClient 实例(用空 profile 或指定 profile)。 */
    private RecommendService newService(String activeProfile) {
        return new RecommendService(chatClient, skillRegistry, sessionService,
                micrometerConfig, activeProfile);
    }

    private static ChatResponse chatResponse(String content) {
        AssistantMessage msg = new AssistantMessage(content);
        Generation gen = new Generation(msg);
        // 不传 metadata 是允许的 — recommend() 内部会 null-check
        return new ChatResponse(List.of(gen));
    }
}
