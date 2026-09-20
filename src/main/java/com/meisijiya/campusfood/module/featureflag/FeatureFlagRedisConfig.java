package com.meisijiya.campusfood.module.featureflag;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * F-11 W1:Feature Flag Redis 配置中心 — Spring bean 装配。
 *
 * <h2>bean 清单(W2/W3 必须按此名注入)</h2>
 * <ul>
 *   <li>{@code featureFlagMetrics} — Micrometer Counter helper,业务侧
 *       {@code DefaultFeatureFlagService.isEnabled} 内部调。</li>
 *   <li>{@code initialFlagConfigs} — 启动期从 Redis hash 一次性加载的
 *       {@code Map<String, FlagConfig>};缺失字段降级到
 *       {@link FeatureFlagProperties#getDefaultFlags()}。</li>
 *   <li>{@code featureFlagService} — {@link FeatureFlagService} 默认实现
 *       {@link DefaultFeatureFlagService}。</li>
 *   <li>{@code featureFlagRedisTemplate} — 把 {@link StringRedisTemplate}
 *       暴露为命名 bean(W2 admin controller 可直接 {@code @Autowired} 注入,
 *       不强制)。</li>
 * </ul>
 *
 * <h2>启动期 Redis 加载策略</h2>
 * <ol>
 *   <li>读 {@code feature_flags} hash 全量 entries(空 Redis 时 entries() 返空 Map,
 *       不抛异常)。</li>
 *   <li>逐字段 JSON 反序列化为 {@link FlagConfig}。</li>
 *   <li>与 {@link FeatureFlagProperties#getDefaultFlags()} 合并:Redis 有值用 Redis,
 *       Redis 没有用 yml default-flags 兜底。</li>
 *   <li>启动期 Redis 不可达时 → 日志 warn,继续用 default-flags 启动(不阻塞应用启动,
 *       与 F-7 限流桶的"启动失败立即崩"策略不同,因为 flag 是非关键路径)。</li>
 * </ol>
 *
 * @author meisijiya
 */
@Configuration
@EnableConfigurationProperties(FeatureFlagProperties.class)
public class FeatureFlagRedisConfig {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagRedisConfig.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FeatureFlagProperties properties;

    public FeatureFlagRedisConfig(StringRedisTemplate redis,
                                  ObjectMapper objectMapper,
                                  FeatureFlagProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Micrometer Counter helper — 业务侧 {@link DefaultFeatureFlagService}
     * 用它打 {@code feature_flag_check_total{flag,decision}}。
     */
    @Bean(name = "featureFlagMetrics")
    public FeatureFlagMetrics featureFlagMetrics(MeterRegistry meterRegistry) {
        return new FeatureFlagMetrics(meterRegistry);
    }

    /**
     * 启动期一次性从 Redis hash 加载全量 flag 配置,与 yml default-flags 合并。
     *
     * <p>Redis 不可达时降级到 yml default-flags(应用仍能启动,只是无 admin 改 flag 能力)。
     */
    @Bean(name = "initialFlagConfigs")
    public Map<String, FlagConfig> initialFlagConfigs() {
        Map<String, FlagConfig> fromRedis = loadFromRedis();
        Map<String, FlagConfig> merged = new LinkedHashMap<>(fromRedis);

        // 用 yml default-flags 兜底(只填补 Redis 中缺失的 key,不覆盖 Redis 已有值)
        Map<String, FlagConfig> defaults = properties.getDefaultFlags();
        for (Map.Entry<String, FlagConfig> e : defaults.entrySet()) {
            merged.putIfAbsent(e.getKey(), e.getValue());
        }

        log.info("FeatureFlag initial configs loaded: total={} (redis={}, default={})",
                merged.size(), fromRedis.size(), defaults.size());
        return merged;
    }

    /**
     * 默认 {@link FeatureFlagService} 实现 — W2 admin / W3 业务注解都通过
     * {@code @Autowired FeatureFlagService} 拿这唯一实例。
     */
    @Bean(name = "featureFlagService")
    public FeatureFlagService featureFlagService(
            FeatureFlagMetrics metrics,
            Map<String, FlagConfig> initialFlagConfigs) {
        return new DefaultFeatureFlagService(
                properties,
                redis,
                objectMapper,
                metrics,
                initialFlagConfigs);
    }

    // ---------- 内部 ----------

    /**
     * 从 Redis hash {@code feature_flags} 加载全量 entries。
     * 不可达 / 异常时降级到空 Map,日志 warn,不阻塞启动。
     */
    private Map<String, FlagConfig> loadFromRedis() {
        try {
            HashOperations<String, Object, Object> ops = redis.opsForHash();
            Map<Object, Object> raw = ops.entries(DefaultFeatureFlagService.REDIS_HASH_KEY);
            if (raw == null || raw.isEmpty()) {
                log.info("FeatureFlag Redis hash '{}' is empty (cold start, will use yml defaults)",
                        DefaultFeatureFlagService.REDIS_HASH_KEY);
                return new HashMap<>();
            }

            Map<String, FlagConfig> result = new HashMap<>(raw.size());
            for (Map.Entry<Object, Object> e : raw.entrySet()) {
                String flagKey = String.valueOf(e.getKey());
                String json = String.valueOf(e.getValue());
                try {
                    FlagConfig cfg = objectMapper.readValue(json, FlagConfig.class);
                    result.put(flagKey, cfg);
                } catch (Exception parseEx) {
                    // 单条解析失败不阻塞整个 hash 加载 — log warn,跳过脏数据
                    log.warn("Skip malformed FlagConfig in Redis hash: flag={}, value={}, err={}",
                            flagKey, json, parseEx.toString());
                }
            }
            return result;
        } catch (RuntimeException e) {
            // Redis 不可达:降级到 yml default-flags,不阻塞启动
            log.warn("Failed to load feature flags from Redis ({}); falling back to yml defaults",
                    e.toString());
            return new HashMap<>();
        }
    }

    // 静态工具:让 W2 admin controller 能复用 REDIS_HASH_KEY 常量引用
    /** @deprecated 用 {@link DefaultFeatureFlagService#REDIS_HASH_KEY} */
    @Deprecated
    public static String redisHashKey() {
        return DefaultFeatureFlagService.REDIS_HASH_KEY;
    }

    /** 仅供单元测试用 — Redis 不可达时返回的默认 Map 引用。 */
    Set<String> defaultFlagKeys() {
        return properties.getDefaultFlags().keySet();
    }
}