package com.meisijiya.campusfood.module.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 分布式锁集成测试(F-8 W3)— Testcontainers 起真实 Redis,验证 {@link RedisLock} 与
 * {@link Watchdog} 在真实 Lua 脚本执行 + Redis TTL 计时下的行为。
 *
 * <h2>为什么需要 IT 层(已有 RedisLockTest / WatchdogTest 单测)</h2>
 * 单测用 Mockito 替身,无法验证:
 * <ul>
 *   <li>Lua 脚本在 Redis 单线程内的真实语义(SETNX / EXPIRE / GET / DEL / PEXPIRE 真执行);</li>
 *   <li>PEXPIRE 的毫秒级精度,以及 TTL 在 Redis 端的真实衰减;</li>
 *   <li>Lock + Release 之间锁 key 在 Redis 的可见性。</li>
 * </ul>
 * 这三类只能连真实 Redis 才验得出,所以本 IT 是验收必跑。
 *
 * <h2>测试策略</h2>
 * <ul>
 *   <li>{@code @Container GenericContainer<"redis:7.4-alpine">} — Spring Data Redis 直连容器</li>
 *   <li>{@code @DynamicPropertySource} 把容器端口注入 Spring 配置</li>
 *   <li>每个用例 {@code @AfterEach} 清掉测试 key(避免跨用例串扰);key 加 {@code UUID}
 *       后缀保证并发下也不冲突</li>
 *   <li>5 个用例覆盖工单 acceptance:tryLock 成功 / 重复拒绝 / ownerToken 防误删 /
 *       Watchdog 自动续期 / 续期失败放弃</li>
 * </ul>
 *
 * <h2>为什么不开 RabbitMQ 容器</h2>
 * <p>Lock 链路只依赖 Redis;Spring Boot 启动仍会要求 RabbitTemplate 可连,因此用与
 * {@code LikeServiceIT} 同款 {@code GenericContainer<"rabbitmq:3.13-management-alpine">}
 * 兜底 + {@code spring.rabbitmq.listener.simple.auto-startup=false} 关掉消费者,
 * 避免对 Lock 测试造成副作用。
 *
 * @author meisijiya
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        "management.health.redis.enabled=true",
        "management.health.rabbit.enabled=true",
        // 关掉消费者,避免 IT 类加载时 RabbitListener 起线程拖慢测试
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // Watchdog 调度间隔在测试里压短:默认 10s 太长,改 1s 续期更明显;不影响单测(W1 已用 mock)
        "campusfood.lock.extend-interval=1s",
        // 默认 30s 即可,IT 不验 Watchdog 跨多轮续期
        "campusfood.lock.ttl=30s"
})
@Testcontainers
class DistributedLockIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> RABBIT = new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withExposedPorts(5672)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());

        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672).toString());
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
    }

    @Autowired
    private RedisLock redisLock;

    @Autowired
    private Watchdog watchdog;

    @Autowired
    private StringRedisTemplate redis;

    /** 每个用例的 key,测试结束统一清理。 */
    private String testKey;

    @AfterEach
    void cleanup() {
        if (testKey != null) {
            // 同时清掉注册表和 Redis 端,即便 Watchdog 还没 unregister
            watchdog.unregister(testKey);
            redis.delete(testKey);
        }
    }

    // ---------- acceptance #11 / #12 case 1: tryLock 成功 + release ----------

    @Test
    @DisplayName("tryLock_首次获取_返回true_且Redis写入key带TTL_release后key消失")
    void tryLock_firstAcquire_returnsTrue_andKeyDisappearsAfterRelease() {
        testKey = "lock:it:basic:" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();

        // when — 首次获取
        boolean acquired = redisLock.tryLock(testKey, token, 30);

        // then — 返回 true;Redis 端 key 存在,TTL ≈ 30s
        assertThat(acquired).as("first tryLock must succeed").isTrue();
        assertThat(redis.hasKey(testKey))
                .as("lock key must be present in Redis after tryLock").isTrue();
        Long ttlAfterAcquire = redis.getExpire(testKey);
        assertThat(ttlAfterAcquire)
                .as("lock TTL should be ~30s after tryLock (range: 28-30)")
                .isBetween(28L, 30L);

        // when — release
        boolean released = redisLock.release(testKey, token);

        // then — release 返回 true;Redis 端 key 消失
        assertThat(released).as("release with correct ownerToken must succeed").isTrue();
        assertThat(redis.hasKey(testKey))
                .as("lock key must be removed after release").isFalse();
    }

    // ---------- acceptance #11 / #12 case 2: tryLock 已存在 key 拒绝 ----------

    @Test
    @DisplayName("tryLock_key已存在_返回false_且原ownerToken不被覆盖")
    void tryLock_secondAttemptOnExistingKey_returnsFalse_andOwnerTokenUnchanged() {
        testKey = "lock:it:mutex:" + UUID.randomUUID();
        String firstToken = "owner-A";
        String secondToken = "owner-B";

        // given — A 先获取
        assertThat(redisLock.tryLock(testKey, firstToken, 30))
                .as("first tryLock should succeed").isTrue();

        // when — B 再获取同一把锁
        boolean secondAcquired = redisLock.tryLock(testKey, secondToken, 30);

        // then — B 失败,Redis 端 key 仍属于 A
        assertThat(secondAcquired)
                .as("second tryLock on existing key must return false (mutual exclusion)").isFalse();

        // ownerToken 校验 — A 还能 release,B 不能 release
        assertThat(redisLock.release(testKey, firstToken))
                .as("original ownerToken must still be able to release").isTrue();
    }

    // ---------- acceptance #11 / #12 case 3: ownerToken 校验防误删 ----------

    @Test
    @DisplayName("release_错误ownerToken_返回false_且Redis端key未被删除")
    void release_wrongOwnerToken_returnsFalse_andKeyRemains() {
        testKey = "lock:it:safety:" + UUID.randomUUID();
        String realOwner = "real-owner";
        String impostor = "impostor";

        // given — 真实 owner 获取锁
        assertThat(redisLock.tryLock(testKey, realOwner, 30)).isTrue();
        // Redis 端真实 owner 记录在 key 的 value 上(SETNX 写入 token)
        assertThat(redis.opsForValue().get(testKey))
                .as("lock key value should be the ownerToken (used by Lua GET-校验)")
                .isEqualTo(realOwner);

        // when — 冒充者尝试 release
        boolean releaseByImpostor = redisLock.release(testKey, impostor);

        // then — release 返 false;Redis 端 key 仍然存在 + value 仍是 realOwner
        assertThat(releaseByImpostor)
                .as("release with wrong ownerToken must return false (no false delete)").isFalse();
        assertThat(redis.hasKey(testKey))
                .as("lock key must remain intact after wrong-owner release attempt").isTrue();
        assertThat(redis.opsForValue().get(testKey))
                .as("lock key value must remain the real ownerToken").isEqualTo(realOwner);

        // 收尾:真实 owner 自己 release,免得污染 Redis
        assertThat(redisLock.release(testKey, realOwner)).isTrue();
    }

    // ---------- acceptance #11 / #12 case 4: Watchdog 自动续期 ----------

    @Test
    @DisplayName("watchdog_register后tick_锁TTL被续到目标值_且key持续存在")
    void watchdog_registerAndTick_extendsTtl_andKeyRemains() {
        testKey = "lock:it:extend:" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();

        // 给一个非常短的初始 TTL=2s,如果 Watchdog 不续期,2s 后 key 自动消失
        assertThat(redisLock.tryLock(testKey, token, 2))
                .as("initial tryLock with short TTL=2s should succeed").isTrue();
        // 注册到 Watchdog,目标续期 TTL=2s(与初始一致)
        assertThat(watchdog.register(testKey, token, Duration.ofSeconds(2).toMillis()))
                .as("watchdog register must succeed for new key").isTrue();
        assertThat(watchdog.registeredCount()).isEqualTo(1);

        // 等待超过初始 TTL(2s)+ 调度间隔(1s)+ Redis 计时抖动 → 若 Watchdog 工作,
        // key 仍在;若 Watchdog 没工作,key 已被 Redis 自然过期
        sleepQuietly(Duration.ofMillis(3_500L));

        // then — Redis 端 key 仍然存在(说明 Watchdog 已续期;否则 PEXPIRE 已过期)
        assertThat(redis.hasKey(testKey))
                .as("after 3.5s (TTL=2s + extend interval=1s), key must still exist (Watchdog extended it)")
                .isTrue();
        Long ttlAfterWait = redis.getExpire(testKey);
        assertThat(ttlAfterWait)
                .as("after Watchdog extend, TTL should be ~2s (>=1s)")
                .isGreaterThanOrEqualTo(1L);

        // 手动驱动一次 tick,验证 Watchdog 显式调过 extend(注册表保持)
        watchdog.tick();
        assertThat(watchdog.registeredCount())
                .as("Watchdog registry must keep the lock after successful extend").isEqualTo(1);
    }

    // ---------- acceptance #11 / #12 case 5: 续期失败 → failCount 累加 → 上限放弃 ----------

    @Test
    @DisplayName("watchdog_锁已过期tickN次_failCount累加达上限_从注册表移除")
    void watchdog_lockExpiredConsecutiveFails_unregistersAfterMaxFailures() {
        testKey = "lock:it:giveup:" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();

        // 直接 register(不调 tryLock — 模拟"业务已持锁但 Redis 端 key 因某种原因已消失"边界)
        assertThat(watchdog.register(testKey, token, 30_000L))
                .as("watchdog register should succeed even when Redis key is absent").isTrue();
        // 显式断言 maxExtendFailures=3(默认值,Lua 续期必然失败因为 key 不存在)
        int maxFailures = 3;

        // when — tick 3 次,每次 RedisLock.extend 都因 key 不存在返 false
        for (int i = 1; i <= maxFailures; i++) {
            watchdog.tick();
            // 累加但未到上限前,注册表仍保留该 key
            assertThat(watchdog.registeredCount())
                    .as("after %d/%d tick(s), Watchdog must keep the lock in registry", i, maxFailures)
                    .isEqualTo(1);
        }

        // then — 第 3 次 tick 触发"达到 maxExtendFailures"分支,从注册表移除
        // (loop 在 maxFailures=3 时第 3 次 tick 末尾已移除;为保险再 tick 一次断言稳定空)
        watchdog.tick();
        assertThat(watchdog.registeredCount())
                .as("after exceeding maxExtendFailures, Watchdog must remove the lock from registry")
                .isEqualTo(0);

        // 再 tick 一次,确认没有残留(也不会再尝试 extend)
        watchdog.tick();
        assertThat(watchdog.registeredCount())
                .as("post-giveup tick must not re-register the lock").isEqualTo(0);
    }

    // ---------- helpers ----------

    /**
     * 静默 sleep — 不抛 checked exception,测试主流程不需 try/catch 噪音。
     */
    private static void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for Watchdog", e);
        }
    }
}