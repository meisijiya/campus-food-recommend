package com.meisijiya.campusfood.module.featureflag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * F-11 W3 orchestrator 补写 {@link FeatureFlagService} 集成测试 — 3 demo flag 端到端验证。
 *
 * <h2>降级路径</h2>
 * <p>Testcontainers Redis 在本环境(Docker daemon 不可用)无法用,
 * {@link StringRedisTemplate} 用 {@link MockBean} 替换。
 * Mock Redis 不可达抛异常 → {@link FeatureFlagRedisConfig#loadFromRedis()} catch 路径降级到
 * {@code application.yml} default-flags 启动,验证 Service 端到端 flag 决策。
 *
 * <h2>为什么这里</h2>
 * <p>F-11 W3 worker 报告 9 个 IT 在其 workspace 跑通,但 .java 未落盘到
 * {@code src/test/java/.../module/featureflag/}。orchestrator 在串行收尾阶段
 * 补写本测试,以保证工程可重跑。
 *
 * @author meisijiya
 */
@SpringBootTest
@ActiveProfiles("dev")
class FeatureFlagIT {

    @Autowired
    private FeatureFlagService featureFlagService;

    @Autowired
    private FeatureFlagProperties properties;

    @MockBean
    private StringRedisTemplate redis;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void resetFlagsToDefaults() throws Exception {
        // 每次测试前 reset Redis mock 让 loadFromRedis 抛异常 → 走 yml default-flags 路径
        // 这样 Spring 启动时 default-flags 已注入到 service.configs,reset 干净
        HashOperations<String, Object, Object> ops =
                (HashOperations<String, Object, Object>) org.mockito.Mockito.mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(ops);
        when(ops.entries(DefaultFeatureFlagService.REDIS_HASH_KEY))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("mock redis down"));
    }

    // ---------- Demo 1: recommend-v2 (PERCENTAGE 20%) ----------

    @Test
    @DisplayName("Demo 1: recommend-v2 default PERCENTAGE 20% → 100 次采样命中 ≈ 20 (允许 ±8)")
    void demo1_recommendV2_percentage20_hitRatioApproximately20() {
        // application.yml 已配 recommend-v2 → PERCENTAGE 20
        // 验证 100 次不同 studentId 中约 20 次 enabled
        int enabledCount = 0;
        for (long sid = 1; sid <= 100; sid++) {
            if (featureFlagService.isEnabled("recommend-v2", sid)) {
                enabledCount++;
            }
        }
        assertThat(enabledCount)
                .as("PERCENTAGE 20% over 100 students")
                .isBetween(12, 28);
    }

    @Test
    @DisplayName("Demo 1: recommend-v2 setConfig → ALL_ON → 立即生效(setConfig 同步写 Redis + 失效 Caffeine)")
    void demo1_recommendV2_setAllOn_immediatelyEffective() {
        featureFlagService.setConfig("recommend-v2", new FlagConfig(FlagMode.ALL_ON, null, 0));

        for (long sid = 1; sid <= 10; sid++) {
            assertThat(featureFlagService.isEnabled("recommend-v2", sid)).isTrue();
        }

        // 回滚到 PERCENTAGE 20(避免污染其他 test)
        featureFlagService.setConfig("recommend-v2",
                new FlagConfig(FlagMode.PERCENTAGE, null, 20));
    }

    // ---------- Demo 2: like-cache-bypass (WHITELIST_ONLY [1,2,3]) ----------

    @Test
    @DisplayName("Demo 2: like-cache-bypass default WHITELIST_ONLY [1,2,3] → sid=1 命中,sid=999 不命中")
    void demo2_likeCacheBypass_whitelistHitAndMiss() {
        // application.yml 已配 like-cache-bypass → WHITELIST_ONLY [1,2,3]
        assertThat(featureFlagService.isEnabled("like-cache-bypass", 1L)).isTrue();
        assertThat(featureFlagService.isEnabled("like-cache-bypass", 2L)).isTrue();
        assertThat(featureFlagService.isEnabled("like-cache-bypass", 3L)).isTrue();
        assertThat(featureFlagService.isEnabled("like-cache-bypass", 999L)).isFalse();
        assertThat(featureFlagService.isEnabled("like-cache-bypass", null)).isFalse();
    }

    @Test
    @DisplayName("Demo 2: like-cache-bypass setConfig → WHITELIST_ONLY [100,200] → sid=100 命中")
    void demo2_likeCacheBypass_setNewWhitelist_immediatelyEffective() {
        FlagConfig newCfg = FlagConfig.whitelistOnly(java.util.Set.of(100L, 200L));
        featureFlagService.setConfig("like-cache-bypass", newCfg);

        assertThat(featureFlagService.isEnabled("like-cache-bypass", 100L)).isTrue();
        assertThat(featureFlagService.isEnabled("like-cache-bypass", 200L)).isTrue();
        assertThat(featureFlagService.isEnabled("like-cache-bypass", 1L)).isFalse();   // 旧白名单失效

        // 回滚
        featureFlagService.setConfig("like-cache-bypass",
                FlagConfig.whitelistOnly(java.util.Set.of(1L, 2L, 3L)));
    }

    // ---------- Demo 3: merchant-detail-new (ALL_OFF) ----------

    @Test
    @DisplayName("Demo 3: merchant-detail-new default ALL_OFF → 任何 sid 都返 false")
    void demo3_merchantDetailNew_defaultAllOff_alwaysFalse() {
        assertThat(featureFlagService.isEnabled("merchant-detail-new", 1L)).isFalse();
        assertThat(featureFlagService.isEnabled("merchant-detail-new", 999L)).isFalse();
        assertThat(featureFlagService.isEnabled("merchant-detail-new", null)).isFalse();
    }

    @Test
    @DisplayName("Demo 3: merchant-detail-new setConfig → ALL_ON → 立即生效")
    void demo3_merchantDetailNew_setAllOn_immediatelyEffective() {
        featureFlagService.setConfig("merchant-detail-new", FlagConfig.allOn());

        for (long sid = 1; sid <= 5; sid++) {
            assertThat(featureFlagService.isEnabled("merchant-detail-new", sid)).isTrue();
        }

        // 回滚到 ALL_OFF
        featureFlagService.setConfig("merchant-detail-new", FlagConfig.allOff());
    }

    // ---------- getConfig / listFlags ----------

    @Test
    @DisplayName("getConfig: flagKey 存在 → 返对应配置")
    void getConfig_existingFlag_returnsConfig() {
        FlagConfig cfg = featureFlagService.getConfig("recommend-v2");
        assertThat(cfg).isNotNull();
        assertThat(cfg.getMode()).isEqualTo(FlagMode.PERCENTAGE);
        assertThat(cfg.getPercentage()).isEqualTo(20);
    }

    @Test
    @DisplayName("getConfig: flagKey 不存在 → 返 null(安全默认)")
    void getConfig_nonExistentFlag_returnsNull() {
        FlagConfig cfg = featureFlagService.getConfig("non-existent-flag");
        assertThat(cfg).isNull();
    }

    @Test
    @DisplayName("listFlags: 包含 3 个 demo flag + sorted")
    void listFlags_containsThreeDemoFlags() {
        Set<String> flags = featureFlagService.listFlags();
        assertThat(flags).contains("recommend-v2", "like-cache-bypass", "merchant-detail-new");
        // TreeSet sorted 验证
        assertThat(flags).isInstanceOf(java.util.TreeSet.class);
    }
}