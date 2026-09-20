package com.meisijiya.campusfood.module.featureflag;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link FeatureFlagService} 默认实现(F-11 W1)— 内存 Caffeine 缓存 +
 * Redis hash 配置中心。
 *
 * <h2>架构</h2>
 * <pre>
 *   ┌─────────────────────┐
 *   │ isEnabled(flag,sid) │   ←── 业务热路径
 *   └──────────┬──────────┘
 *              │
 *              ▼
 *   ┌─────────────────────┐  miss          ┌─────────────────────┐
 *   │ Caffeine cache      │ ─────────────► │ Redis hash          │
 *   │ (60s TTL per flag)  │ ◄───────────── │ feature_flags       │
 *   └─────────────────────┘  load          └─────────────────────┘
 *              │
 *              ▼
 *   ┌─────────────────────┐
 *   │ 4 种 FlagMode 分发  │
 *   │  ALL_ON / ALL_OFF / │
 *   │  WHITELIST_ONLY /   │
 *   │  PERCENTAGE         │
 *   └─────────────────────┘
 * </pre>
 *
 * <h2>关键设计决策</h2>
 * <ul>
 *   <li><b>总开关 first</b>:{@code properties.enabled == false} 直接返回 false,
 *       不进 Caffeine 查询 — 这是 F-11 的 kill switch。</li>
 *   <li><b>flagKey 不存在 → false</b>:安全默认;W2 admin POST 会拒绝未知 key。</li>
 *   <li><b>Caffeine 60s TTL</b>:避免每请求打 Redis;W2 setConfig 时同步失效
 *       该 flagKey 的 Caffeine 项,保证 admin POST 后 < 1ms 生效。</li>
 *   <li><b>PERCENTAGE 哈希取模</b>:用 {@code (flagKey + ":" + studentId).hashCode()}
 *       保证同 user 在同 flag 下结果稳定;不同 flag 之间独立分布。</li>
 *   <li><b>每次 isEnabled 都打 Counter</b>:业务热路径上有 metric,Prometheus 能
 *       反向验证灰度比例是否准确(避免"改了 PERCENTAGE 但实际没生效")。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>Caffeine cache 本身线程安全;{@link #configs} {@code volatile} 保证可见性;
 * Redis 写操作用 {@link StringRedisTemplate} 自带连接池线程安全。
 *
 * @author meisijiya
 */
public class DefaultFeatureFlagService implements FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(DefaultFeatureFlagService.class);

    /** Redis hash key — 所有 flag 共享一个 hash。 */
    public static final String REDIS_HASH_KEY = "feature_flags";

    /** Caffeine cache TTL — 60s,平衡 Redis 实时性与本地缓存命中率。 */
    public static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final FeatureFlagProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FeatureFlagMetrics metrics;
    /** 当前生效的 flag 配置(Redis hash 全量 + default-flags 合并);启动期一次性加载。 */
    private volatile Map<String, FlagConfig> configs;

    /**
     * Caffeine cache — key = flagKey, value = {@link FlagConfig};TTL 60s;
     * W2 setConfig 时 {@link Cache#invalidate(Object)} 立即失效。
     */
    private final Cache<String, FlagConfig> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(10_000)
            .build();

    public DefaultFeatureFlagService(FeatureFlagProperties properties,
                                     StringRedisTemplate redis,
                                     ObjectMapper objectMapper,
                                     FeatureFlagMetrics metrics,
                                     Map<String, FlagConfig> initialFlagConfigs) {
        if (properties == null) {
            throw new IllegalArgumentException("properties must be non-null");
        }
        if (redis == null) {
            throw new IllegalArgumentException("redis must be non-null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must be non-null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must be non-null");
        }
        this.properties = properties;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.configs = initialFlagConfigs == null
                ? java.util.Collections.emptyMap()
                : Map.copyOf(initialFlagConfigs);
        log.info("DefaultFeatureFlagService initialized: total flags={}, enabled={}",
                this.configs.size(), properties.isEnabled());
    }

    // ---------- 接口实现 ----------

    @Override
    public boolean isEnabled(String flagKey, Long studentId) {
        // 1. 总开关 first — F-11 kill switch
        if (!properties.isEnabled()) {
            metrics.incrementCheck(flagKey, false);
            return false;
        }
        if (flagKey == null || flagKey.isBlank()) {
            metrics.incrementCheck(flagKey, false);
            return false;
        }

        FlagConfig config = loadConfig(flagKey);
        if (config == null) {
            // flagKey 不存在 → 安全默认 false
            metrics.incrementCheck(flagKey, false);
            return false;
        }

        boolean result = evaluate(flagKey, config, studentId);
        metrics.incrementCheck(flagKey, result);
        return result;
    }

    @Override
    public FlagConfig getConfig(String flagKey) {
        if (flagKey == null || flagKey.isBlank()) {
            return null;
        }
        return loadConfig(flagKey);
    }

    @Override
    public void setConfig(String flagKey, FlagConfig config) {
        if (flagKey == null || flagKey.isBlank()) {
            throw new IllegalArgumentException("flagKey must be non-blank");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must be non-null");
        }

        // 1. 同步写 Redis hash
        try {
            String json = objectMapper.writeValueAsString(config);
            HashOperations<String, Object, Object> ops = redis.opsForHash();
            ops.put(REDIS_HASH_KEY, flagKey, json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize FlagConfig for flagKey=" + flagKey, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Failed to write feature_flag hash for flagKey=" + flagKey, e);
        }

        // 2. 同步失效 Caffeine + 更新内存 Map
        cache.invalidate(flagKey);
        Map<String, FlagConfig> newMap = new java.util.HashMap<>(configs);
        newMap.put(flagKey, config);
        this.configs = Map.copyOf(newMap);

        log.info("Feature flag updated: flag={}, config={}", flagKey, config);
    }

    @Override
    public Set<String> listFlags() {
        Set<String> keys = new HashSet<>(configs.keySet());
        // 也合并 default-flags(可能在 Redis 加载前就有 yml 里的 flag)
        keys.addAll(properties.getDefaultFlags().keySet());
        return new TreeSet<>(keys);
    }

    // ---------- 内部 ----------

    /**
     * 加载 flag 配置 — 先 Caffeine,后 {@link #configs} 内存 Map;Cache miss 时回源到内存 Map,
     * <strong>不在 isEnabled 热路径上读 Redis</strong>(Redis 加载已在启动期一次性完成)。
     */
    private FlagConfig loadConfig(String flagKey) {
        FlagConfig cached = cache.getIfPresent(flagKey);
        if (cached != null) {
            return cached;
        }
        FlagConfig fromMemory = configs.get(flagKey);
        if (fromMemory != null) {
            cache.put(flagKey, fromMemory);
        }
        return fromMemory;
    }

    /**
     * 4 种 FlagMode 分发决策。
     *
     * @param flagKey   flag 名(PERCENTAGE 模式需要它做 hash key,保证同 user 不同 flag 独立分布)
     * @param config    当前 flag 配置
     * @param studentId 学生 ID
     */
    private static boolean evaluate(String flagKey, FlagConfig config, Long studentId) {
        return switch (config.getMode()) {
            case ALL_ON -> true;
            case ALL_OFF -> false;
            case WHITELIST_ONLY -> {
                if (studentId == null || config.getWhitelist() == null
                        || config.getWhitelist().isEmpty()) {
                    yield false;
                }
                yield config.getWhitelist().contains(studentId);
            }
            case PERCENTAGE -> {
                if (studentId == null) {
                    yield false;
                }
                int percentage = config.getPercentage();
                if (percentage <= 0) {
                    yield false;
                }
                if (percentage >= 100) {
                    yield true;
                }
                // 用 Math.abs 防御 Integer.MIN_VALUE 边界
                int bucket = Math.abs(Objects.hash(flagKey + ":" + studentId)) % 100;
                yield bucket < percentage;
            }
        };
    }
}