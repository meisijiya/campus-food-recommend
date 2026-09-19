package com.meisijiya.campusfood.module.catalog.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link SessionService} 的指标埋点单元测试(F-9 W2)— 验证 {@code session_stage_distribution}
 * 在 {@code readContext()} 中按当前 stage 自增对应 tag 的 Counter。
 *
 * <h2>覆盖</h2>
 * <ul>
 *   <li>stage 不存在(默认 INIT)→ tag stage=INIT</li>
 *   <li>stage=ZONE/CUISINE/MERCHANT → 对应 tag 自增</li>
 *   <li>连续多次 readContext 不同 stage → 各自累加</li>
 * </ul>
 *
 * @author meisijiya
 */
class SessionServiceMetricsTest {

    private SessionSlotStateMachine stateMachine;
    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> ops;
    private Map<String, String> store;
    private MeterRegistry meterRegistry;
    private SessionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stateMachine = new SessionSlotStateMachine();
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        store = new HashMap<>();

        // in-memory backstore 通过 Answer 维护
        doAnswer(inv -> {
            store.put((String) inv.getArgument(0), (String) inv.getArgument(1));
            return null;
        }).when(ops).set(any(String.class), any(String.class));
        doAnswer(inv -> {
            store.put((String) inv.getArgument(0), (String) inv.getArgument(1));
            return null;
        }).when(ops).set(any(String.class), any(String.class), anyLong(), any());

        when(ops.get(any(String.class)))
                .thenAnswer(inv -> store.get((String) inv.getArgument(0)));
        when(redis.opsForValue()).thenReturn(ops);

        meterRegistry = new SimpleMeterRegistry();
        service = new SessionService(stateMachine, redis, meterRegistry);
    }

    private double stageCount(String stage) {
        Counter c = meterRegistry.find("session_stage_distribution")
                .tag("stage", stage)
                .counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    @DisplayName("readContext_无stage_key_默认INIT_tag_INIT_自增")
    void noStageKey_defaultsToInit_incrementsInitTag() {
        SessionContext ctx = service.readContext("sid-1");

        assertThat(ctx.stage()).isEqualTo(SessionStage.INIT);
        assertThat(stageCount("INIT")).isEqualTo(1.0);
        assertThat(stageCount("ZONE")).isEqualTo(0.0);
        assertThat(stageCount("CUISINE")).isEqualTo(0.0);
        assertThat(stageCount("MERCHANT")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("readContext_stage_ZONE_tag_ZONE_自增")
    void zoneStage_incrementsZoneTag() {
        store.put("session:sid-2:stage", "ZONE");

        SessionContext ctx = service.readContext("sid-2");

        assertThat(ctx.stage()).isEqualTo(SessionStage.ZONE);
        assertThat(stageCount("ZONE")).isEqualTo(1.0);
        assertThat(stageCount("INIT")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("readContext_stage_CUISINE_tag_CUISINE_自增")
    void cuisineStage_incrementsCuisineTag() {
        store.put("session:sid-3:stage", "CUISINE");

        SessionContext ctx = service.readContext("sid-3");

        assertThat(ctx.stage()).isEqualTo(SessionStage.CUISINE);
        assertThat(stageCount("CUISINE")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("readContext_stage_MERCHANT_tag_MERCHANT_自增")
    void merchantStage_incrementsMerchantTag() {
        store.put("session:sid-4:stage", "MERCHANT");

        SessionContext ctx = service.readContext("sid-4");

        assertThat(ctx.stage()).isEqualTo(SessionStage.MERCHANT);
        assertThat(stageCount("MERCHANT")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("readContext_连续多次不同stage_各 tag 累加")
    void multipleReads_accumulateAcrossStages() {
        store.put("session:sid-a:stage", "INIT");
        store.put("session:sid-b:stage", "ZONE");
        store.put("session:sid-c:stage", "CUISINE");
        store.put("session:sid-d:stage", "MERCHANT");

        service.readContext("sid-a");
        service.readContext("sid-a");
        service.readContext("sid-b");
        service.readContext("sid-c");
        service.readContext("sid-c");
        service.readContext("sid-c");
        service.readContext("sid-d");

        assertThat(stageCount("INIT")).isEqualTo(2.0);
        assertThat(stageCount("ZONE")).isEqualTo(1.0);
        assertThat(stageCount("CUISINE")).isEqualTo(3.0);
        assertThat(stageCount("MERCHANT")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("readContext_非法sid_抛IllegalArgumentException_不触发metric")
    void blankSid_throwsAndDoesNotIncrement() {
        try {
            service.readContext("");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            service.readContext(null);
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertThat(stageCount("INIT")).isEqualTo(0.0);
        assertThat(stageCount("ZONE")).isEqualTo(0.0);
        assertThat(stageCount("CUISINE")).isEqualTo(0.0);
        assertThat(stageCount("MERCHANT")).isEqualTo(0.0);
    }
}
