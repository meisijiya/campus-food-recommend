package com.meisijiya.campusfood.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.meisijiya.campusfood.module.catalog.MerchantQueryService;
import com.meisijiya.campusfood.module.catalog.session.SessionService;
import com.meisijiya.campusfood.module.like.LikeService;
import com.meisijiya.campusfood.module.recommend.RecommendService;

/**
 * F-9 W3 Observability 集成测试 — 验证 Spring Boot Actuator + Micrometer + Prometheus 链路
 * 在 Testcontainers Redis 容器下能产出 4 个自定义业务指标的 scrape 文本。
 *
 * <h2>测试目标</h2>
 * <ul>
 *   <li>{@code GET /actuator/prometheus} 200 + Prometheus 文本格式</li>
 *   <li>scrape 文本含 4 个自定义指标名 — 对应 CONTEXT §13 + ADR-0007 §F-9:
 *       <ol>
 *         <li>{@code like_count_total} — LikeService.like() Counter(Micrometer 名经
 *             Prometheus exporter 加 {@code _total} 后缀;scrape 文本以 {@code like_count_total} 子串形式呈现,
 *             本 IT 用 contains 断言兼容)</li>
 *         <li>{@code recommend_latency_seconds} — RecommendService.recommend() Timer(scrape
 *             文本出现 {@code recommend_latency_seconds_bucket / _sum / _count})</li>
 *         <li>{@code cache_hit_ratio} — MerchantQueryService 三级命中路径 Counter(W2 注入;scrape
 *             文本以 {@code cache_hit_ratio_total} 形式呈现,contains 子串兼容)</li>
 *         <li>{@code session_stage_distribution} — SessionService.readContext() Counter(W2 注入;scrape
 *             文本以 {@code session_stage_distribution_total} 形式呈现)</li>
 *       </ol>
 *   </li>
 *   <li>evidence 落 {@code evidence/f9-prometheus-scrape.log} — 完整 scrape 文本 + 时间戳</li>
 * </ul>
 *
 * <h2>关键纪律</h2>
 * <ul>
 *   <li><strong>Testcontainers 必须用</strong>(acceptance criteria 8)— Redis 容器让 LikeService / SessionService
 *       走真实 SETNX + GET 路径,而不是 mock 跳过</li>
 *   <li><strong>RabbitTemplate 用 {@code @MockBean} 替换</strong> — 本 IT 验证 prometheus scrape,不验证
 *       LikeService 的 MQ 投递链路;避免引入 RabbitMQ 容器,减少 IT 启动开销</li>
 *   <li><strong>触发业务路径必须真实调用</strong>(不是直接 register Counter)— 让 Micrometer 实际自增,
 *       scrape 文本里 metric value &gt; 0,而不只是占位 0</li>
 *   <li><strong>不依赖 bench profile</strong>(CONTEXT §10)— dev profile + MockChatModel 即可让
 *       RecommendService 走通,不会触发 DashScope API 计费</li>
 *   <li><strong>用 {@code @WithMockUser} 绕过 SecurityConfig</strong>(不需要改 W2 的 /actuator/prometheus 放行)
 *       — 让 MockMvc 信任当前 user,所有 actuator 端点都能访问</li>
 * </ul>
 *
 * <h2>运行方式</h2>
 * <pre>
 * mvn -B verify -Dit.test='ObservabilityIT'
 * </pre>
 * (无需 Spring profile 切换;无需 DASHSCOPE_API_KEY)
 *
 * @author meisijiya
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        // dev profile 用 H2 in-memory (MODE=MySQL),关 Hibernate JDBC metadata access
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        // 暴露 prometheus 端点 — dev profile 默认禁,这里显式开
        "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
        "management.endpoint.prometheus.enabled=true",
        "management.prometheus.metrics.export.enabled=true",
        // 关掉 RabbitMQ listener + 不连 RabbitMQ(本 IT 不验证 MQ 链路)
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@Testcontainers
class ObservabilityIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LikeService likeService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private RecommendService recommendService;

    @Autowired
    private MerchantQueryService merchantQueryService;

    /**
     * 替换 RabbitTemplate — 本 IT 不验证 MQ 投递链路,只验证 prometheus scrape。
     * LikeService.like() 会调 {@code rabbit.convertAndSend(...)};用 mock 避免 AmqpConnectException。
     */
    @MockBean
    private RabbitTemplate rabbitTemplate;

    @Test
    @WithMockUser(username = "obs-tester", roles = "STUDENT")
    @DisplayName("actuator_prometheus_contains_all_4_business_metrics")
    void prometheusEndpoint_containsAll4BusinessMetrics() throws Exception {
        // given — 触发 4 个业务路径,让 Micrometer 注册对应指标 + 至少一次自增
        // 1) LikeService.like() — 触发 like_count_total Counter
        //    studentId="3" 命中 like-cache-bypass 白名单 [1,2,3] (F-11 FeatureFlagAspect 否则短路返 false)
        boolean liked = likeService.like("3", "M-OBS-001");
        assertThat(liked).as("first like() must succeed (Redis SETNX true)").isTrue();

        // 2) SessionService.init() + readContext() — 触发 session_stage_distribution Counter (INIT stage +1)
        sessionService.init("demo-stu-obs");
        sessionService.readContext("demo-stu-obs");

        // 3) RecommendService.recommend() — 触发 recommend_latency_seconds Timer.Sample.stop()
        //    dev profile + MockChatModel,ChatModel 返固定合规 JSON,不会触发 DASHSCOPE API 计费
        RecommendService.RecommendationResult recommendation = recommendService.recommend("demo-stu-obs");
        assertThat(recommendation).as("recommend() must return a non-null result under MockChatModel").isNotNull();

        // 4) MerchantQueryService.findById() — 触发 cache_hit_ratio Counter (L0 miss → L1 miss → L2 miss 路径,W2 都打 counter)
        //    H2 in-memory 无该 merchant,走完整 L0/L1/L2 miss 链,counter 打 merchantHotCache/{L0,L1,miss}
        merchantQueryService.findById("M-OBS-NONEXISTENT");

        // when — GET /actuator/prometheus(@WithMockUser 让 Security 放行所有 actuator 端点)
        MvcResult mvcResult = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn();

        String scrapeText = mvcResult.getResponse().getContentAsString();
        assertThat(scrapeText)
                .as("/actuator/prometheus body must not be empty")
                .isNotNull().isNotBlank();

        // then — 4 个自定义业务指标名都在 scrape 文本里(contains 子串,兼容 Prometheus exporter 的 _total 后缀)
        assertThat(scrapeText)
                .as("scrape text must contain like_count_total (Counter metric)")
                .contains("like_count_total");
        assertThat(scrapeText)
                .as("scrape text must contain recommend_latency_seconds (Timer metric base name)")
                .contains("recommend_latency_seconds");
        assertThat(scrapeText)
                .as("scrape text must contain cache_hit_ratio (Counter metric)")
                .contains("cache_hit_ratio");
        assertThat(scrapeText)
                .as("scrape text must contain session_stage_distribution (Counter metric)")
                .contains("session_stage_distribution");

        // evidence 落盘
        writeScrapeEvidence(scrapeText);
    }

    /**
     * 把完整 Prometheus scrape 文本 + 时间戳落到 {@code evidence/f9-prometheus-scrape.log}。
     * 该文件被 {@code .gitignore} 排除(evidence/*.log),不进版本控制。
     */
    private void writeScrapeEvidence(String scrapeText) {
        try {
            Path target = Paths.get("evidence", "f9-prometheus-scrape.log");
            Files.createDirectories(target.getParent());
            String header = "# F-9 W3 · /actuator/prometheus scrape snapshot\n"
                    + "# generated_at: " + java.time.Instant.now() + "\n"
                    + "# prometheus_endpoint: /actuator/prometheus\n"
                    + "# business_metrics_expected: like_count_total / recommend_latency_seconds / cache_hit_ratio / session_stage_distribution\n"
                    + "# ---- BEGIN PROM SCRAPE ----\n";
            String footer = "\n# ---- END PROM SCRAPE ----\n";
            Files.writeString(target, header + scrapeText + footer);
        } catch (IOException e) {
            // evidence 落盘失败不阻塞测试主断言;记录到 stderr 供后续人工补
            System.err.println("[ObservabilityIT] evidence write failed (non-fatal): " + e.getMessage());
        }
    }
}
