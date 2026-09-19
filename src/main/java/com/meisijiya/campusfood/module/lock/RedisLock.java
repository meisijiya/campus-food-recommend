package com.meisijiya.campusfood.module.lock;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;

/**
 * Redis 分布式锁原语(F-8 W1)— 提供 {@code tryLock} / {@code release} / {@code extend}
 * 三个原子操作,全部走 Lua 脚本在 Redis 单线程内串行执行。
 *
 * <h2>API 契约</h2>
 * <ul>
 *   <li>{@link #tryLock(String, String, long)}:SETNX + EXPIRE,返 {@code true} = 成功获取;
 *       已存在返 {@code false};Redis 异常抛 {@link IllegalStateException}。</li>
 *   <li>{@link #release(String, String)}:校验 ownerToken 后 DEL,防 A 释放 B 的锁;
 *       不是 owner 返 {@code false};Redis 异常抛 {@link IllegalStateException}。</li>
 *   <li>{@link #extend(String, String, long)}:校验 ownerToken 后 PEXPIRE,语义同 release;
 *       续期失败返 {@code false}(锁已过期 / 不是 owner)。</li>
 * </ul>
 *
 * <h2>ownerToken 纪律</h2>
 * <p>调用方必须每次生成新 UUID 作为 ownerToken(同一进程内同一把锁共用一个 token),
 * 释放 / 续期都带此 token。F-8 默认推荐
 * {@code UUID.randomUUID().toString()};进程内复用同一 token 是显式行为 — 锁的
 * 生命周期由 tryLock → release 或 tryLock → extend... → release 闭合。
 *
 * <h2>为什么用 Lua 而不是 SET ... NX EX 单命令</h2>
 * <p>Redis 2.6.12+ 支持 {@code SET key value NX EX seconds} 单命令,语义等价;
 * 这里坚持用 {@code SETNX + EXPIRE} 两步走(Lua 内原子),原因:
 * <ol>
 *   <li>与 release / extend 的 {@code GET key, then DEL/PEXPIRE} 模式保持一致 — 都是
 *       "GET-校验-写"两步;后续若换 SET ... NX EX 也只是改一个 Lua 文件,不影响 API。</li>
 *   <li>{@code SETNX + EXPIRE} 是 Redis 远古 API,所有版本都支持,跨集群 / 跨 Sentinel
 *       部署时更可移植。</li>
 *   <li>Lua 脚本统一管理在 {@code scripts/} 目录,运维 / 审计 / 热更新都集中。</li>
 * </ol>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>所有 Redis 调用都包在 {@link IllegalStateException} 里,异常时不静默吞。</li>
 *   <li>{@code null} key / ownerToken / ttlSec ≤ 0 由调用方负责;本类不做防御校验,
 *       让"业务方传错参数"尽早 NPE / IllegalArgumentException 暴露。</li>
 *   <li>不带 Lombok,字段全包私有 + 显式构造器注入(F-1 起的项目惯例)。</li>
 * </ul>
 *
 * @author meisijiya
 */
@Component
public class RedisLock {

    private static final Logger log = LoggerFactory.getLogger(RedisLock.class);

    /** Lua 脚本文件名(classpath:scripts/ 目录下)。 */
    static final String SCRIPT_TRY = "lock-try.lua";
    static final String SCRIPT_RELEASE = "lock-release.lua";
    static final String SCRIPT_EXTEND = "lock-extend.lua";

    private final StringRedisTemplate redis;
    private final LockProperties properties;

    private RedisScript<Long> tryScript;
    private RedisScript<Long> releaseScript;
    private RedisScript<Long> extendScript;

    public RedisLock(StringRedisTemplate redis, LockProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 启动时从 classpath 加载 3 个 Lua 脚本到 {@link DefaultRedisScript}。失败立即抛
     * {@link IllegalStateException},让 Spring 上下文启动失败 — 宁可启动崩,也不要
     * "脚本路径写错但 Redis 调用全失败"这种悄悄上线的状态。
     */
    @PostConstruct
    void loadScripts() {
        String base = properties.getScriptLocation();
        this.tryScript = loadScript(base + SCRIPT_TRY);
        this.releaseScript = loadScript(base + SCRIPT_RELEASE);
        this.extendScript = loadScript(base + SCRIPT_EXTEND);
        log.info("RedisLock scripts loaded from {} (try={}, release={}, extend={})",
                base, SCRIPT_TRY, SCRIPT_RELEASE, SCRIPT_EXTEND);
    }

    private static RedisScript<Long> loadScript(String classpathPath) {
        try (var in = new ClassPathResource(classpathPath).getInputStream()) {
            String body = StreamUtils.copyToString(in, java.nio.charset.StandardCharsets.UTF_8);
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setScriptText(body);
            script.setResultType(Long.class);
            return script;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Lua script: " + classpathPath, e);
        }
    }

    /**
     * 尝试获取锁。原子执行 Lua {@code lock-try.lua}:
     * SETNX + EXPIRE 两步。
     *
     * @param key        业务方定义的锁 key(建议带 namespace 前缀,如 {@code lock:like:s-1:m-1})
     * @param ownerToken 本次持锁的 token(UUID),后续 release / extend 必须用同一 token
     * @param ttlSec     锁 TTL(秒);{@code ≤ 0} 抛 {@link IllegalArgumentException}
     * @return {@code true} = 获取成功;{@code false} = key 已存在
     * @throws IllegalArgumentException ttlSec ≤ 0
     * @throws IllegalStateException    Redis 调用异常
     */
    public boolean tryLock(String key, String ownerToken, long ttlSec) {
        if (ttlSec <= 0) {
            throw new IllegalArgumentException("ttlSec must be > 0, got " + ttlSec);
        }
        Long result = executeScript(tryScript, key, ownerToken, Long.toString(ttlSec));
        return result != null && result == 1L;
    }

    /**
     * 释放锁。原子执行 Lua {@code lock-release.lua}:GET 校验 ownerToken 后 DEL。
     *
     * @param key        锁 key
     * @param ownerToken 必须与 tryLock 时传入的 token 一致
     * @return {@code true} = 释放成功(确认是 owner);{@code false} = 不是 owner / key 已过期
     * @throws IllegalStateException Redis 调用异常
     */
    public boolean release(String key, String ownerToken) {
        Long result = executeScript(releaseScript, key, ownerToken);
        return result != null && result == 1L;
    }

    /**
     * 续期。原子执行 Lua {@code lock-extend.lua}:GET 校验 ownerToken 后 PEXPIRE。
     *
     * @param key        锁 key
     * @param ownerToken 必须与 tryLock 时传入的 token 一致
     * @param ttlMs      续期目标 TTL(毫秒);{@code ≤ 0} 抛 {@link IllegalArgumentException}
     * @return {@code true} = 续期成功(确认是 owner);{@code false} = 不是 owner / key 已过期
     * @throws IllegalArgumentException ttlMs ≤ 0
     * @throws IllegalStateException    Redis 调用异常
     */
    public boolean extend(String key, String ownerToken, long ttlMs) {
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be > 0, got " + ttlMs);
        }
        Long result = executeScript(extendScript, key, ownerToken, Long.toString(ttlMs));
        return result != null && result == 1L;
    }

    /**
     * 执行 Lua 脚本并把 Redis 异常统一包成 {@link IllegalStateException} — 不暴露底层
     * 异常类名,避免调用方依赖 Lettuce/Jedis 特定异常类型。
     */
    private Long executeScript(RedisScript<Long> script, String key, String ownerToken, String... extraArgs) {
        try {
            Object[] args;
            if (extraArgs == null || extraArgs.length == 0) {
                args = new Object[] { ownerToken };
            } else {
                args = new Object[1 + extraArgs.length];
                args[0] = ownerToken;
                System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
            }
            Long result = redis.execute(script, List.of(key), args);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Redis lock script execution failed for key=" + key, e);
        }
    }
}