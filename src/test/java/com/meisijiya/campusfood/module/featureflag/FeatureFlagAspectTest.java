package com.meisijiya.campusfood.module.featureflag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * F-11 W3 orchestrator 补写 {@link FeatureFlagAspect} 单元测试 — 覆盖 4 种方法返回类型
 * (boolean / map / void / 异常透传)+ studentId 解析 + service 异常 fallback。
 *
 * <h2>为什么这里</h2>
 * <p>F-11 W3 worker 报告 14 个 AspectTest 在其 workspace 跑通,但 .java 未落盘到
 * {@code src/test/java/.../module/featureflag/}。orchestrator 在串行收尾阶段
 * 补写本测试,以保证工程可重跑。
 *
 * @author meisijiya
 */
class FeatureFlagAspectTest {

    private FeatureFlagService service;
    private FeatureFlagAspect aspect;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        service = mock(FeatureFlagService.class);
        meterRegistry = new SimpleMeterRegistry();
        aspect = new FeatureFlagAspect(service, meterRegistry);
    }

    /** 用 aspect 包装 target 形成代理。 */
    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        @SuppressWarnings("unchecked")
        T p = (T) factory.getProxy();
        return p;
    }

    // ---------- boolean 返回类型(LikeService.like 用) ----------

    @Test
    @DisplayName("boolean 方法 + flag 开启 → 调原方法,返原值")
    void booleanReturn_flagOn_callsTarget_returnsOriginal() {
        when(service.isEnabled("flag-bool", 100L)).thenReturn(true);

        BooleanReturnBean target = new BooleanReturnBean();
        BooleanReturnBean p = proxy(target);

        boolean result = p.likeBooleanWithLong(100L);

        assertThat(result).isTrue();
        assertThat(target.calls.get()).isEqualTo(1);
        verify(service, times(1)).isEnabled("flag-bool", 100L);
    }

    @Test
    @DisplayName("boolean 方法 + flag 关闭 → 跳原方法,返 defaultOn=true")
    void booleanReturn_flagOff_skipsTarget_returnsDefaultOn() {
        when(service.isEnabled(any(), any())).thenReturn(false);

        BooleanReturnBean target = new BooleanReturnBean();
        BooleanReturnBean p = proxy(target);

        // defaultOn=true → skip 时返 true
        boolean result = p.likeBooleanDefaultOnTrue();

        assertThat(result).isTrue();
        assertThat(target.calls.get()).isZero();
    }

    // ---------- Map 返回类型(MerchantQueryService.findDetailById 用) ----------

    @Test
    @DisplayName("map 方法 + merchant-detail-new flag 开启 → 走原方法,Aspect 注入 openHours")
    void mapReturn_merchantDetailNewOn_injectsOpenHours() {
        when(service.isEnabled("merchant-detail-new", 1L)).thenReturn(true);

        MapReturnBean target = new MapReturnBean();
        MapReturnBean p = proxy(target);

        Map<String, Object> result = p.findDetailById(1L);

        assertThat(result).containsEntry("id", 1L)
                .containsEntry("name", "Tasty")
                .containsEntry("openHours", "09:00-22:00")
                .containsEntry("featureFlag", "merchant-detail-new:ON");
    }

    @Test
    @DisplayName("map 方法 + merchant-detail-new flag 关闭 → 跳原方法,返 null")
    void mapReturn_merchantDetailNewOff_returnsNull_skipsTarget() {
        when(service.isEnabled(any(), any())).thenReturn(false);

        MapReturnBean target = new MapReturnBean();
        MapReturnBean p = proxy(target);

        Map<String, Object> result = p.findDetailById(1L);

        assertThat(result).isNull();
        assertThat(target.calls.get()).isZero();
    }

    // ---------- void 返回类型(RecommendService.recommend 用) ----------

    @Test
    @DisplayName("void 方法 + flag 开启 → 调原方法,记录 Timer metric")
    void voidReturn_flagOn_callsTarget_recordsTimer() {
        when(service.isEnabled("recommend-v2", 1L)).thenReturn(true);

        VoidReturnBean target = new VoidReturnBean();
        VoidReturnBean p = proxy(target);

        p.recommendLong(1L);

        assertThat(target.calls.get()).isEqualTo(1);
        // Timer 注册到 MeterRegistry
        assertThat(meterRegistry.find("flag_hit_timer_seconds").tag("flag", "recommend-v2").timer())
                .isNotNull();
    }

    @Test
    @DisplayName("void 方法 + flag 关闭 → 跳原方法,不注册 Timer")
    void voidReturn_flagOff_skipsTarget_noTimer() {
        when(service.isEnabled(any(), any())).thenReturn(false);

        VoidReturnBean target = new VoidReturnBean();
        VoidReturnBean p = proxy(target);

        p.recommendLong(1L);

        assertThat(target.calls.get()).isZero();
        // flag off 不注册 Timer(只 on 时走 proceed + stop)
        assertThat(meterRegistry.find("flag_hit_timer_seconds").tag("flag", "recommend-v2").timer())
                .isNull();
    }

    // ---------- studentId 解析 ----------

    @Test
    @DisplayName("studentId 解析:String 'sid' 参数名 → Long.parseLong")
    void studentId_resolution_stringSidParam() {
        when(service.isEnabled("flag-string-sid", 100L)).thenReturn(true);

        VoidReturnBean target = new VoidReturnBean();
        VoidReturnBean p = proxy(target);

        p.recommendStringSid("100");

        verify(service, times(1)).isEnabled("flag-string-sid", 100L);
    }

    @Test
    @DisplayName("studentId 解析:无 studentId 相关参数 → service 拿 null")
    void studentId_resolution_unmatchedParam() {
        when(service.isEnabled("flag-other", null)).thenReturn(true);

        VoidReturnBean target = new VoidReturnBean();
        VoidReturnBean p = proxy(target);

        p.doSomethingUnrelated("hello");

        verify(service, times(1)).isEnabled("flag-other", null);
    }

    // ---------- service 异常 fallback ----------

    @Test
    @DisplayName("service.isEnabled 抛 RuntimeException → fallback 到 defaultOn=true → 调原方法")
    void serviceException_fallsBackToDefaultOn() {
        when(service.isEnabled(any(), any())).thenThrow(new RuntimeException("redis down"));

        BooleanReturnBean target = new BooleanReturnBean();
        BooleanReturnBean p = proxy(target);

        // defaultOn=true → service 异常 → 仍调原方法
        boolean result = p.likeBooleanDefaultOnTrue();

        assertThat(result).isFalse();
        assertThat(target.calls.get()).isEqualTo(1);
    }

    // ---------- 业务异常透传 ----------

    @Test
    @DisplayName("原方法抛业务异常 → Aspect 不吞,透传给调用方")
    void targetException_propagatedNotWrapped() {
        when(service.isEnabled(any(), any())).thenReturn(true);

        BooleanThrowBean target = new BooleanThrowBean();
        BooleanThrowBean p = proxy(target);

        assertThatThrownBy(() -> p.likeBooleanWithLong(100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("biz error");
    }

    // ---------- isEnabled 调用次数 ----------

    @Test
    @DisplayName("多次调用 → service.isEnabled 每次都查(缓存由 Service 内部负责)")
    void flagOn_multipleCalls_isEnabledCalledEachTime() {
        when(service.isEnabled("flag-bool", 1L)).thenReturn(true);

        BooleanReturnBean target = new BooleanReturnBean();
        BooleanReturnBean p = proxy(target);

        p.likeBooleanWithLong(1L);
        p.likeBooleanWithLong(1L);
        p.likeBooleanWithLong(1L);

        verify(service, times(3)).isEnabled("flag-bool", 1L);
        assertThat(target.calls.get()).isEqualTo(3);
    }

    // ---------- 测试桩 Bean ----------

    /** boolean 返回类型测试桩 */
    static class BooleanReturnBean {
        final AtomicInteger calls = new AtomicInteger();

        @FeatureFlag("flag-bool")
        public boolean likeBooleanWithLong(Long studentId) {
            calls.incrementAndGet();
            return true;
        }

        @FeatureFlag(value = "flag-bool-default-on", defaultOn = true)
        public boolean likeBooleanDefaultOnTrue() {
            calls.incrementAndGet();
            return false;
        }
    }

    /** Map 返回类型测试桩 */
    static class MapReturnBean {
        final AtomicInteger calls = new AtomicInteger();

        @FeatureFlag("merchant-detail-new")
        public Map<String, Object> findDetailById(Long merchantId) {
            calls.incrementAndGet();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", merchantId);
            m.put("name", "Tasty");
            return m;
        }
    }

    /** void 返回类型测试桩 */
    static class VoidReturnBean {
        final AtomicInteger calls = new AtomicInteger();

        @FeatureFlag("recommend-v2")
        public void recommendLong(Long studentId) {
            calls.incrementAndGet();
        }

        @FeatureFlag("flag-string-sid")
        public void recommendStringSid(String sid) {
            calls.incrementAndGet();
        }

        @FeatureFlag("flag-other")
        public void doSomethingUnrelated(String text) {
            calls.incrementAndGet();
        }
    }

    /** 业务异常透传专用测试桩 */
    static class BooleanThrowBean {
        @FeatureFlag("flag-throw")
        public boolean likeBooleanWithLong(Long studentId) {
            throw new IllegalStateException("biz error");
        }
    }
}