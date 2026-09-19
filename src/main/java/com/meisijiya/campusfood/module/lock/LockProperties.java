package com.meisijiya.campusfood.module.lock;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分布式锁配置属性(F-8 W1)— {@code campusfood.lock.*} 前缀。
 *
 * <h2>字段语义</h2>
 * <ul>
 *   <li>{@link #ttl}:锁初始 TTL,默认 30 秒。{@code tryLock} 写入 Redis 时使用此值;
 *       若调用方未显式传入 ttlSec,Redis 侧的 EX 也是它。</li>
 *   <li>{@link #extendInterval}:看门狗续期间隔,默认 10 秒。{@link Watchdog} 后台调度
 *       每隔该时长遍历已注册锁调用 {@code extend};需显著小于 ttl,保证 TTL 不会断档
 *       (默认 10s ≤ TTL 30s,极端 GC / 调度抖动也不会让锁提前过期)。</li>
 *   <li>{@link #extendTtl}:每次续期的目标 TTL,默认 30 秒(等于初始 TTL)。续期后 key
 *       的 PEXPIRE 被重置到该值,语义上等价于"每 10s 把锁续到还有 30s 寿命"。</li>
 *   <li>{@link #maxExtendFailures}:同一把锁连续续期失败的容忍上限,默认 3 次。失败计数
 *       累计到该上限,Watchdog 从注册表移除该锁并记日志,避免在锁已过期的情况下继续
 *       浪费 Redis QPS。</li>
 *   <li>{@link #scriptLocation}:Lua 脚本目录(classpath 路径),默认 {@code scripts/};
 *       {@link RedisLock} 从该目录加载 {@code lock-try.lua} / {@code lock-release.lua} /
 *       {@code lock-extend.lua}。</li>
 * </ul>
 *
 * <h2>纪律</h2>
 * <p>不带 Lombok,纯 setter/getter 即可(Spring Boot
 * {@code @ConfigurationProperties} 兼容 record,但项目惯例用 setter 注入)。
 *
 * @author meisijiya
 */
@ConfigurationProperties(prefix = "campusfood.lock")
public class LockProperties {

    /** 默认锁 TTL 30 秒 — 与工单 / ADR-0007 §F-8 一致。 */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    /** 默认续期间隔 10 秒 — TTL/3,留足 GC / 调度抖动 buffer。 */
    public static final Duration DEFAULT_EXTEND_INTERVAL = Duration.ofSeconds(10);

    /** 默认续期目标 TTL 30 秒 — 与初始 TTL 一致,语义"每 10s 续到 30s"。 */
    public static final Duration DEFAULT_EXTEND_TTL = Duration.ofSeconds(30);

    /** 默认续期失败容忍次数 3 — 约 30s 窗口,与 ttl=30s 对齐。 */
    public static final int DEFAULT_MAX_EXTEND_FAILURES = 3;

    private Duration ttl = DEFAULT_TTL;
    private Duration extendInterval = DEFAULT_EXTEND_INTERVAL;
    private Duration extendTtl = DEFAULT_EXTEND_TTL;
    private int maxExtendFailures = DEFAULT_MAX_EXTEND_FAILURES;
    private String scriptLocation = "scripts/";

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getExtendInterval() {
        return extendInterval;
    }

    public void setExtendInterval(Duration extendInterval) {
        this.extendInterval = extendInterval;
    }

    public Duration getExtendTtl() {
        return extendTtl;
    }

    public void setExtendTtl(Duration extendTtl) {
        this.extendTtl = extendTtl;
    }

    public int getMaxExtendFailures() {
        return maxExtendFailures;
    }

    public void setMaxExtendFailures(int maxExtendFailures) {
        this.maxExtendFailures = maxExtendFailures;
    }

    public String getScriptLocation() {
        return scriptLocation;
    }

    public void setScriptLocation(String scriptLocation) {
        this.scriptLocation = scriptLocation;
    }
}