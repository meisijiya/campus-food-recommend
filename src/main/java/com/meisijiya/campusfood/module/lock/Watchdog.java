package com.meisijiya.campusfood.module.lock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 分布式锁看门狗(F-8 W1)— 后台调度线程,定期对已注册锁调
 * {@link RedisLock#extend} 实现"持锁业务比锁 TTL 长"的安全续期。
 *
 * <h2>注册表</h2>
 * <p>{@link ConcurrentHashMap}{@code <String, LockEntry>};key 是业务方传入的锁 key
 * (由调用方负责唯一性),value 是 {@link LockEntry}:包含 ownerToken、初始 ttlMs、
 * 连续失败计数 {@code failCount}。
 *
 * <h2>续期失败策略</h2>
 * <p>每轮对注册表所有 entry 调用 {@link RedisLock#extend};extend 返 {@code false}
 * (锁已过期 / 不是 owner)累加 {@code failCount};达 {@link LockProperties#getMaxExtendFailures()}
 * 后从注册表移除并日志 WARN — 极端情况下 Redis OOM / 网络分区会让锁提前过期,
 * 此时继续续期是浪费;Watchdog 主动放弃,让上层业务感知到锁丢失。
 *
 * <h2>生命周期</h2>
 * <p>{@code @PostConstruct} 启动单线程 {@link ScheduledExecutorService},
 * {@code @PreDestroy} 平滑关闭(等当前批次跑完,最多 30s)。线程名固定
 * {@code cfr-lock-watchdog-1} 便于 jstack 排查。
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>调度线程唯一(单线程池):避免并发 extend 同一把锁造成 failCount 计数乱。</li>
 *   <li>注册表操作全部走 {@link ConcurrentHashMap} 原子 API,不持外部锁。</li>
 *   <li>每次 extend 的目标 TTL 来自 {@link LockProperties#getExtendTtl()}(默认 30s)
 *       —— 调用方可在 register 时覆盖。</li>
 *   <li>不带 Lombok,字段全包私有 + 显式构造器注入(F-1 起的项目惯例)。</li>
 * </ul>
 *
 * @author meisijiya
 */
@Component
public class Watchdog {

    private static final Logger log = LoggerFactory.getLogger(Watchdog.class);

    private final RedisLock redisLock;
    private final LockProperties properties;

    /** 注册表:key → (ownerToken, ttlMs, failCount)。 */
    private final Map<String, LockEntry> registry = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public Watchdog(RedisLock redisLock, LockProperties properties) {
        this.redisLock = redisLock;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        long intervalSec = properties.getExtendInterval().getSeconds();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cfr-lock-watchdog-1");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::tick, intervalSec, intervalSec, TimeUnit.SECONDS);
        log.info("Watchdog started, extend interval={}s, maxExtendFailures={}",
                intervalSec, properties.getMaxExtendFailures());
    }

    @PreDestroy
    void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }
        log.info("Watchdog stopped");
    }

    /**
     * 注册一把锁,启动自动续期。
     *
     * <p>典型用法:
     * <pre>{@code
     * String token = UUID.randomUUID().toString();
     * if (redisLock.tryLock(key, token, 30)) {
     *     watchdog.register(key, token);
     *     try {
     *         doWork();
     *     } finally {
     *         watchdog.unregister(key);
     *         redisLock.release(key, token);
     *     }
     * }
     * }</pre>
     *
     * @param key        锁 key(唯一)
     * @param ownerToken 与 {@link RedisLock#tryLock} 传入的 token 一致
     * @param ttlMs      续期目标 TTL(毫秒),通常等于初始 ttlSec * 1000
     * @return {@code true} = 新注册;{@code false} = key 已存在(注册表幂等)
     */
    public boolean register(String key, String ownerToken, long ttlMs) {
        LockEntry entry = new LockEntry(ownerToken, ttlMs);
        LockEntry prev = registry.putIfAbsent(key, entry);
        if (prev != null) {
            log.warn("Watchdog register: key={} already registered (existing owner={}, new owner={})",
                    key, prev.ownerToken, ownerToken);
            return false;
        }
        log.debug("Watchdog registered key={} ttlMs={}", key, ttlMs);
        return true;
    }

    /**
     * 取消注册。业务结束后(无论是正常还是异常路径)必须调用,否则 Watchdog 会持续给
     * 已 release 的锁续期,直到 Redis 自然过期。
     *
     * @param key 锁 key
     */
    public void unregister(String key) {
        LockEntry removed = registry.remove(key);
        if (removed != null) {
            log.debug("Watchdog unregistered key={}", key);
        }
    }

    /** 当前注册表大小,用于测试与监控。 */
    public int registeredCount() {
        return registry.size();
    }

    /**
     * 单轮续期 — 由 {@link ScheduledExecutorService} 周期调用。遍历注册表,对每条 entry
     * 调 {@link RedisLock#extend};失败累加 failCount,达到上限移除并 WARN。
     *
     * <p>显式 {@code public} 是为了单测可以直接驱动,跳过等待调度。
     */
    public void tick() {
        if (registry.isEmpty()) {
            return;
        }
        int maxFailures = properties.getMaxExtendFailures();
        for (Map.Entry<String, LockEntry> e : registry.entrySet()) {
            String key = e.getKey();
            LockEntry entry = e.getValue();
            try {
                boolean ok = redisLock.extend(key, entry.ownerToken, entry.ttlMs);
                if (ok) {
                    if (entry.failCount > 0) {
                        log.info("Watchdog extend recovered key={} after {} failures", key, entry.failCount);
                    }
                    entry.failCount = 0;
                } else {
                    entry.failCount++;
                    log.warn("Watchdog extend failed key={} failCount={}/{}", key, entry.failCount, maxFailures);
                    if (entry.failCount >= maxFailures) {
                        registry.remove(key);
                        log.error("Watchdog giving up key={} after {} consecutive extend failures",
                                key, entry.failCount);
                    }
                }
            } catch (Exception ex) {
                entry.failCount++;
                log.warn("Watchdog extend threw key={} failCount={}/{} err={}",
                        key, entry.failCount, maxFailures, ex.toString());
                if (entry.failCount >= maxFailures) {
                    registry.remove(key);
                    log.error("Watchdog giving up key={} after {} consecutive extend failures (last error: {})",
                            key, entry.failCount, ex.toString());
                }
            }
        }
    }

    /** 注册表条目:{@code ownerToken} + 续期 TTL + 连续失败计数。 */
    static final class LockEntry {
        final String ownerToken;
        final long ttlMs;
        volatile int failCount;

        LockEntry(String ownerToken, long ttlMs) {
            this.ownerToken = ownerToken;
            this.ttlMs = ttlMs;
        }
    }
}