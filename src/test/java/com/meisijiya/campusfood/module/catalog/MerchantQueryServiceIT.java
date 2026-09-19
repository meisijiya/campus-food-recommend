package com.meisijiya.campusfood.module.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.github.benmanes.caffeine.cache.Cache;
import com.meisijiya.campusfood.module.preheat.RedisShardedWriter;
import com.meisijiya.campusfood.module.preheat.heat.Merchant;
import com.meisijiya.campusfood.module.preheat.heat.MerchantRepository;

/**
 * {@link MerchantQueryService} 集成测试(F-5 acceptance #12)— Testcontainers 起 MySQL + Redis,
 * 端到端验证 L0 Caffeine → L1 Redis → L2 MySQL 严格降级链路 + backfill 闭环 + 负缓存防穿透。
 *
 * <h2>测试策略</h2>
 * <ul>
 *   <li>{@code @Container MySQLContainer<"mysql:8.4">} — Flyway 自动跑 V1__init.sql 建 merchants 表</li>
 *   <li>{@code @Container GenericContainer<"redis:7.4-alpine">} — Spring Data Redis 直连容器</li>
 *   <li>{@code @DynamicPropertySource} 把容器端口注入 Spring 配置</li>
 *   <li>{@code MerchantRepository.save(...)} seed 1 个商户(单 case 独立,避免跨用例 L0 命中串扰)</li>
 *   <li>用 {@link Cache#invalidate(Object)} 在用例内主动清 L0,模拟"L0 过期 / 容量淘汰"</li>
 * </ul>
 *
 * <h2>为什么不开 RabbitMQ 容器</h2>
 * <p>{@link MerchantQueryService} 不依赖 RabbitMQ;只起 MySQL + Redis 即可,降低测试基础设施开销。
 * RabbitMQ 健康检查在 dev profile 默认关闭,容器缺失不会影响 context 启动。
 *
 * <p>不依赖 F-已起的 docker compose(Testcontainers 自起容器,端口不冲突);本地需 Docker daemon 运行。
 *
 * @author meisijiya
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        // 真实 MySQL 8.4: 切 dialect 避免 Hibernate 查 information_schema.SEQUENCES(MySQL 无此表)
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        // dev 默认禁 health indicator;容器可达,重新启用
        "management.health.redis.enabled=true",
        // RabbitMQ 在本 IT 不需要,直接连不上不会影响 query 路径
        "management.health.rabbit.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@Testcontainers
class MerchantQueryServiceIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("cfr")
            .withUsername("cfr")
            .withPassword("cfr123")
            .waitingFor(Wait.forListeningPort());

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
    }

    @Autowired
    private MerchantQueryService service;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private Cache<String, Optional<Merchant>> merchantHotCache;

    private static Merchant newMerchant(String id) {
        Merchant m = new Merchant();
        m.setId(id);
        m.setZoneId("Z-1");
        m.setCuisineId("C-1");
        m.setName("shop-" + id);
        m.setTags("");
        m.setHeatScore(0.0);
        return m;
    }

    // ---------- acceptance #12: L0 hit 链路 ----------

    @Test
    @DisplayName("findById_L0命中_直接返商户_不调Redis/MySQL")
    void findById_l0Hit_returnsMerchant() {
        // given — seed 1 个商户 + 预填 L0
        String id = "M-L0-" + System.nanoTime();
        merchants.save(newMerchant(id));
        Merchant cached = newMerchant(id);
        // 用 put 直接注入 Caffeine 引用 — 不走 Redis,确保 L0 命中
        merchantHotCache.put(id, Optional.of(cached));
        // L1 也清掉,确保下面断言不会被 L1 命中掩盖
        redis.delete(RedisShardedWriter.PREFIX_MERCHANT + id);

        // when
        Optional<Merchant> result = service.findById(id);

        // then — 返商户
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(id);
        assertThat(result.get().getName()).isEqualTo("shop-" + id);

        // then — L1 仍无 key(L0 命中不会写回 L1,只有 L2 命中才写 L1)
        assertThat(redis.hasKey(RedisShardedWriter.PREFIX_MERCHANT + id))
                .as("L0 hit must NOT trigger L1 backfill (only L2 hit writes L1)").isFalse();

        // then — L0 仍持正缓存引用
        assertThat(merchantHotCache.getIfPresent(id))
                .as("L0 should still hold positive cache entry").isPresent();
    }

    // ---------- acceptance #12: L1 hit 链路(L0 失效 / 容量淘汰 后)----------

    @Test
    @DisplayName("findById_L1命中_清L0后返商户_不调MySQL")
    void findById_l1Hit_returnsMerchant_afterL0Cleaned() {
        // given — seed MySQL
        String id = "M-L1-" + System.nanoTime();
        merchants.save(newMerchant(id));

        // 第 1 次调用走 L0 miss → L1 miss → L2 命中,触发 L1 + L0 backfill
        Optional<Merchant> first = service.findById(id);
        assertThat(first).isPresent();
        // L1 已写
        assertThat(redis.hasKey(RedisShardedWriter.PREFIX_MERCHANT + id)).isTrue();
        // L0 已回填
        assertThat(merchantHotCache.getIfPresent(id)).isPresent();

        // 清空 L0(等价于 6:00 凌晨 Caffeine 容量淘汰 / 过期,模拟 L0 miss)
        merchantHotCache.invalidate(id);
        assertThat(merchantHotCache.getIfPresent(id)).isNull();

        // when — 第 2 次调用:L0 miss,期望命中 L1
        Optional<Merchant> second = service.findById(id);

        // then — 返商户
        assertThat(second).isPresent();
        assertThat(second.get().getId()).isEqualTo(id);
        // L1 被回填到 L0
        assertThat(merchantHotCache.getIfPresent(id))
                .as("L1 hit must backfill L0 for next call").isPresent();
    }

    // ---------- acceptance #12: L2 hit → backfill L0 + L1 ----------

    @Test
    @DisplayName("findById_L0L1全miss_查MySQL_回填L0与L1")
    void findById_l2Hit_returnsMerchant_andBackfillsL0AndL1() {
        // given — seed MySQL,L0 / L1 都为空
        String id = "M-L2-" + System.nanoTime();
        merchants.save(newMerchant(id));
        // 防御性清,防止跨用例残留
        merchantHotCache.invalidate(id);
        redis.delete(RedisShardedWriter.PREFIX_MERCHANT + id);
        assertThat(merchantHotCache.getIfPresent(id)).isNull();
        assertThat(redis.hasKey(RedisShardedWriter.PREFIX_MERCHANT + id)).isFalse();

        // when
        Optional<Merchant> result = service.findById(id);

        // then — 返商户
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(id);

        // then — L0 被回填正缓存
        Optional<Merchant> l0After = merchantHotCache.getIfPresent(id);
        assertThat(l0After).as("L0 must be backfilled after L2 hit").isPresent();
        assertThat(l0After.get().getId()).isEqualTo(id);

        // then — L1 被回填正缓存(JSON 内容含 id 与 name)
        String l1Json = redis.opsForValue().get(RedisShardedWriter.PREFIX_MERCHANT + id);
        assertThat(l1Json).as("L1 (Redis catalog:merchant:<id>) must be backfilled").isNotNull();
        assertThat(l1Json).contains(id).contains("shop-" + id);
        // L1 负缓存 marker 应当不存在
        assertThat(l1Json).doesNotContain("__null__");
        // TTL ≈ 10min
        Long l1Ttl = redis.getExpire(RedisShardedWriter.PREFIX_MERCHANT + id);
        assertThat(l1Ttl).as("L1 merchant TTL should be ~10min").isBetween(595L, 600L);
    }

    // ---------- acceptance #12: 不存在的商户 → 负缓存防穿透 ----------

    @Test
    @DisplayName("findById_不存在商户_返空_写L0负缓存与L1负缓存marker")
    void findById_nonExistent_returnsEmpty_andWritesNegativeCache_l0AndL1() {
        // given — 不 seed MySQL(确保 findById 查不到),L0 / L1 都为空
        String id = "M-NOPE-" + System.nanoTime();
        merchantHotCache.invalidate(id);
        redis.delete(RedisShardedWriter.PREFIX_MERCHANT + id);
        assertThat(merchants.findById(id)).isEmpty();

        // when
        Optional<Merchant> result = service.findById(id);

        // then — 返空
        assertThat(result).isEmpty();

        // then — L0 负缓存被回填(Optional.empty)
        Optional<Merchant> l0Neg = merchantHotCache.getIfPresent(id);
        assertThat(l0Neg).as("L0 must hold negative cache entry (Optional.empty)").isNotNull();
        assertThat(l0Neg).isEmpty();

        // then — L1 负缓存 marker 被写入({"__null__":true})
        String l1Neg = redis.opsForValue().get(RedisShardedWriter.PREFIX_MERCHANT + id);
        assertThat(l1Neg).as("L1 negative cache marker must be written").isNotNull();
        assertThat(l1Neg).contains("\"__null__\"").contains("true");
    }
}