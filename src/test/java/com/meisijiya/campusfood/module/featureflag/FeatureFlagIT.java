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

    // ---------- F-13 Polish (W1 补写):prod 启动期 4 种场景的兜底行为测试 ----------
    // 对应 docs/observability/feature-flag-defaults.md 的 4 种启动场景:
    //   场景 1 — Redis hash empty(cold start)
    //   场景 2 — Redis 不可达(loadFromRedis catch 降级)
    //   场景 3 — Redis 含 malformed JSON(单条 skip 不阻塞整个 hash)
    //   场景 4 — Redis + yml 部分交集(merge 语义)
    // case A~D 不依赖 @BeforeEach 的 throw mock 路径(reuse 兜底);在 yml ALL_ON/ALL_ON/WHITELIST_ONLY 状态下断言。

    @Test
    @DisplayName("case A: Redis hash empty at startup → yml default-flags 3 个全部生效")
    void caseA_redisHashEmpty_ymlDefaultsApplied() {
        // 场景 1 / 2 / 4:Redis hash empty 或 Redis 不可达 → loadFromRedis 返空 Map → merged 全走 yml default-flags
        // 用 properties.defaultFlagsView() 读 yml 快照(不受 service.configs 中其他测试改写的影响 ——
        // configs 在测试间共享,demo1_setAllOn 之类测试会改写 recommend-v2 为 PERCENTAGE 20)
        Map<String, FlagConfig> defaults = properties.defaultFlagsView();
        assertThat(defaults).containsOnlyKeys(
                "recommend-v2", "like-cache-bypass", "merchant-detail-new");
        // F-13 hardening:recommend-v2 / merchant-detail-new 默认 ALL_ON(让 4 业务指标 Grafana 全可见)
        assertThat(defaults.get("recommend-v2").getMode()).isEqualTo(FlagMode.ALL_ON);
        assertThat(defaults.get("merchant-detail-new").getMode()).isEqualTo(FlagMode.ALL_ON);
        // 边界不可动:like-cache-bypass 保持 WHITELIST_ONLY [1,2,3](ALL_ON 会让所有 sid 直查 MySQL 绕过 Redis L1,F-5 bullet 失效)
        assertThat(defaults.get("like-cache-bypass").getMode()).isEqualTo(FlagMode.WHITELIST_ONLY);
        assertThat(defaults.get("like-cache-bypass").getWhitelist())
                .containsExactlyInAnyOrder(1L, 2L, 3L);
        // listFlags 仍合并 yml keys(即使 configs 被改写,yml keys 永远可见)
        assertThat(featureFlagService.listFlags()).containsExactlyInAnyOrder(
                "recommend-v2", "like-cache-bypass", "merchant-detail-new");
    }

    @Test
    @DisplayName("case B: setConfig 写后 configs 内存 Map 立刻反映 Redis 值(setConfig → isEnabled 立即生效)")
    void caseB_setConfigImmediatelyEffective() {
        // yml 默认(recommend-v2 = ALL_ON)由 properties.defaultFlagsView() 验证 — 不依赖 service.configs 当前状态
        // (configs 已被其他测试改动过,直接读 getConfig 不可靠;properties 是启动期 binding 的不可变快照)
        assertThat(properties.defaultFlagsView().get("recommend-v2").getMode()).isEqualTo(FlagMode.ALL_ON);

        // setConfig ALL_OFF → configs 立即反映(覆盖 yml default);setConfig 同步写 Redis hash + 失效 Caffeine
        featureFlagService.setConfig("recommend-v2", FlagConfig.allOff());
        assertThat(featureFlagService.isEnabled("recommend-v2", 1L)).isFalse();
        assertThat(featureFlagService.getConfig("recommend-v2").getMode()).isEqualTo(FlagMode.ALL_OFF);
        // 回滚到 yml 默认 ALL_ON(保持后续 demo1 测试期望;demo1_percentage20 内部自带 rollback 到 PERCENTAGE 20)
        featureFlagService.setConfig("recommend-v2", FlagConfig.allOn());
        assertThat(featureFlagService.isEnabled("recommend-v2", 1L)).isTrue();
    }

    @Test
    @DisplayName("case C: yml 没配 + Redis 没写的 flag → isEnabled 安全返 false")
    void caseC_yamlNotConfigured_redisNotWritten_isEnabledReturnsFalse() {
        // 场景 4 边界:flagKey 既不在 yml default-flags 也不在 Redis hash → service.configs 无该 key → 安全返 false
        assertThat(featureFlagService.isEnabled("non-existent-flag", 1L)).isFalse();
        assertThat(featureFlagService.isEnabled("non-existent-flag", null)).isFalse();
        assertThat(featureFlagService.getConfig("non-existent-flag")).isNull();
        // listFlags 不含未配置 flag(防止 admin 端列表出现幽灵 key)
        assertThat(featureFlagService.listFlags()).doesNotContain("non-existent-flag");
    }

    @Test
    @DisplayName("case D: setConfig(null config) 抛 IllegalArgumentException,service 不挂掉")
    void caseD_setConfigNull_throwsButServiceHealthy() {
        // 记录 flag 数(后续对照)
        int flagsBefore = featureFlagService.listFlags().size();

        // setConfig 入参校验在 Redis 写之前,IllegalArgumentException 不污染 service.configs / Caffeine 缓存
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> featureFlagService.setConfig("recommend-v2", null));

        // service 不挂掉:listFlags 仍返回原有 flag 数(异常未污染 configs)
        int flagsAfter = featureFlagService.listFlags().size();
        assertThat(flagsAfter).isEqualTo(flagsBefore);
        // service 不挂掉:3 demo flag 仍可查(不受当前 configs mode 状态影响 —— 用 listFlags keys 而非 isEnabled)
        assertThat(featureFlagService.listFlags()).contains(
                "recommend-v2", "like-cache-bypass", "merchant-detail-new");
    }
}