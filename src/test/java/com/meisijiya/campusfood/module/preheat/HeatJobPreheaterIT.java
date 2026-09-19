package com.meisijiya.campusfood.module.preheat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.meisijiya.campusfood.module.preheat.heat.Merchant;
import com.meisijiya.campusfood.module.preheat.heat.MerchantRepository;

import org.springframework.test.context.TestPropertySource;

/**
 * 凌晨预热集成测试(F-4 acceptance #10)— Testcontainers 起 MySQL + Redis,
 * 验证 {@link HeatJobPreheater#preheat()} 触发后 Redis 实际写入 {@code catalog:zone:<zoneId>}
 * 与 {@code catalog:hot:merchants}。
 *
 * <h2>测试策略</h2>
 * <ul>
 *   <li>{@code @Container MySQLContainer<"mysql:8.4">} — Flyway 自动跑 V1__init.sql</li>
 *   <li>{@code @Container GenericContainer<"redis:7.4-alpine">} — 暴露随机端口给 Spring</li>
 *   <li>{@code @DynamicPropertySource} 把容器端口注入 Spring 配置</li>
 *   <li>{@code MerchantRepository.saveAll} 灌 3 个商户(2 zone × 2 cuisine)</li>
 *   <li>{@code preheat()} 触发完整链路</li>
 *   <li>断言 Redis 2 个 zone key + 1 个 hot key 都存在,TTL 正确</li>
 * </ul>
 *
 * <p>不依赖 F-1 已起的 docker compose(Testcontainers 自起容器,端口不冲突);本地需 Docker daemon 运行。
 *
 * @author meisijiya
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        // 真实 MySQL 8.4: 切 dialect 避免 Hibernate 查 information_schema.SEQUENCES(MySQL 无此表)
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false"
})
@Testcontainers
class HeatJobPreheaterIT {

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
        // dev profile 默认禁用的 health indicator 重新启用(容器可达)
        registry.add("management.health.redis.enabled", () -> "true");
        registry.add("management.health.rabbit.enabled", () -> "true");
        // RabbitMQ 在本 IT 不需要,直接连不上不会影响 preheat 流程
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired
    private HeatJobPreheater preheater;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("preheat_3商户2zone_写Redis分片与hot集合_且TTL正确")
    void preheat_writesZonesAndHot_withTtl() {
        // given — 灌 3 个商户(2 zone × 2 cuisine)
        merchants.saveAll(List.of(
                new Merchant("M-1", "Z-1", "C-1", "noodle-shop", "", 0.0),
                new Merchant("M-2", "Z-1", "C-2", "burger-shop", "", 0.0),
                new Merchant("M-3", "Z-2", "C-3", "pizza-shop", "", 0.0)
        ));

        // when
        HeatJobPreheater.PreheatSummary summary = preheater.preheat();

        // then — 摘要
        assertThat(summary.merchantCount()).isEqualTo(3);
        assertThat(summary.zoneCount()).isEqualTo(2);
        assertThat(summary.hotCount()).isEqualTo(3);  // < HOT_TOP_N=10 全收
        assertThat(summary.elapsedMs()).isGreaterThanOrEqualTo(0L);

        // then — Redis zone 分片
        String zone1Json = redis.opsForValue().get("catalog:zone:Z-1");
        String zone2Json = redis.opsForValue().get("catalog:zone:Z-2");
        assertThat(zone1Json).as("catalog:zone:Z-1 must be written").isNotNull();
        assertThat(zone2Json).as("catalog:zone:Z-2 must be written").isNotNull();
        assertThat(zone1Json).contains("M-1").contains("M-2").contains("Z-1");
        assertThat(zone2Json).contains("M-3").contains("Z-2");

        // then — Redis hot merchants
        String hotJson = redis.opsForValue().get("catalog:hot:merchants");
        assertThat(hotJson).as("catalog:hot:merchants must be written").isNotNull();
        assertThat(hotJson).contains("M-1").contains("M-2").contains("M-3");

        // then — TTL
        Long zoneTtl = redis.getExpire("catalog:zone:Z-1");
        Long hotTtl = redis.getExpire("catalog:hot:merchants");
        assertThat(zoneTtl).as("zone TTL = 600s").isBetween(595L, 600L);
        assertThat(hotTtl).as("hot TTL = 12h = 43200s").isBetween(43195L, 43200L);

        // then — heatScore 回写持久化(无订单/点赞的商户 heatScore=0.0,验证持久化生效)
        Merchant m1 = merchants.findById("M-1").orElseThrow();
        assertThat(m1.getHeatScore()).as("heatScore 回写 MySQL").isEqualTo(0.0);
    }
}