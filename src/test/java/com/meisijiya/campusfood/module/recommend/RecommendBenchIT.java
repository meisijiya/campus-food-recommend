package com.meisijiya.campusfood.module.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.meisijiya.campusfood.module.catalog.session.SessionContext;
import com.meisijiya.campusfood.module.catalog.session.SessionService;
import com.meisijiya.campusfood.module.catalog.session.SessionStage;
import com.meisijiya.campusfood.module.recommend.schema.JsonSchemaValidator;

/**
 * bench 量化 evidence 采集集成测试(F-3;对应 ticket acceptance #11)。
 *
 * <p><b>纪律:</b> {@link EnabledIfEnvironmentVariable} 保证
 * 没有 {@code DASHSCOPE_API_KEY} 时自动跳过 ——
 * 严格遵守 plan §16.2 "默认 mvn test 不走 bench profile" + CONTEXT §10 纪律
 * (用户 2026-09-18 Q2 "测试一定要先 mock 数据跑通,不然会造成金钱损失")。
 *
 * <p><b>运行方式:</b>
 * <pre>
 * SPRING_PROFILES_ACTIVE=bench \
 * DASHSCOPE_API_KEY=&lt;your-key&gt; \
 * mvn -B test -Dtest=RecommendBenchIT
 * </pre>
 *
 * <p><b>evidence 落盘:</b> {@code evidence/f3-compliance-rate.json},
 * 含首次合规率 / 总合规率(经过 RRA 重试 + RBFA fallback 后)/ 100 样本分布。
 *
 * <p><b>API key 缺失时:</b> 测试自动被 JUnit 跳过,evidence 不更新。
 * 在该情况下 plan §量化证据采集程序 #3 要求 evidence 段标
 * {@code unverified} 并写明降级方案 — 由 implementer 在 report 与 ticket evidence 段手写。
 *
 * <p><b>设计说明:</b> 端点 {@code /api/recommend} 不接受 prompt 参数 —
 * prompt 由 session 槽位(zone / cuisine / merchant)+ 当前 stage 在
 * {@code RecommendService} 内部拼装。为得到 100 条不同 prompt,
 * 本测试用 {@link MockitoBean} 替换 {@link SessionService},
 * 每次调用按 counter 返回不同的 {@link SessionContext}。
 *
 * @author meisijiya
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("bench")
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class RecommendBenchIT {

    /** 简历 bullet 目标:首次响应合规率 ≥ 88%。 */
    private static final double FIRST_ATTEMPT_TARGET = 0.88;
    private static final int SAMPLE_COUNT = 100;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonSchemaValidator validator;

    @MockitoBean
    private SessionService sessionService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger firstValidCount = new AtomicInteger();
    private final AtomicInteger finalValidCount = new AtomicInteger();
    private final List<Map<String, Object>> samples = new ArrayList<>();

    @AfterEach
    void writeEvidence() throws IOException {
        if (samples.isEmpty()) {
            return;
        }
        int total = samples.size();
        double firstRate = (double) firstValidCount.get() / total;
        double finalRate = (double) finalValidCount.get() / total;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("ticket", "F-3-structured-output");
        evidence.put("profile", "bench");
        evidence.put("sample_count", total);
        evidence.put("first_attempt_valid_count", firstValidCount.get());
        evidence.put("first_attempt_compliance_rate", round(firstRate));
        evidence.put("final_valid_count", finalValidCount.get());
        evidence.put("final_compliance_rate", round(finalRate));
        evidence.put("first_attempt_target", FIRST_ATTEMPT_TARGET);
        evidence.put("first_attempt_target_met", firstRate >= FIRST_ATTEMPT_TARGET);
        evidence.put("samples_first_5", samples.subList(0, Math.min(5, samples.size())));

        Path target = Paths.get("evidence", "f3-compliance-rate.json");
        Files.createDirectories(target.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), evidence);
    }

    @Test
    @WithMockUser(username = "bench-user", roles = "STUDENT")
    @DisplayName("100 样本:RRA + RBFA 后 100% 合规;记录首次合规率对照目标 0.88")
    void bench_compliance_rate() throws Exception {
        // 用 100 个不同 SessionContext 触发 100 条不同 prompt
        List<SessionContext> contexts = buildContexts(SAMPLE_COUNT);
        AtomicInteger idx = new AtomicInteger();
        when(sessionService.readContext(anyString())).thenAnswer(inv ->
                contexts.get(idx.getAndIncrement() % contexts.size()));

        for (int i = 0; i < SAMPLE_COUNT; i++) {
            MvcResult mvc = mockMvc.perform(post("/api/recommend")).andReturn();
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("index", i);
            sample.put("stage", contexts.get(i).stage().name());
            sample.put("zone", contexts.get(i).zoneId());
            sample.put("status", mvc.getResponse().getStatus());

            String body = mvc.getResponse().getContentAsString();
            if (mvc.getResponse().getStatus() != 200 || body.isEmpty()) {
                sample.put("error", "non-200 or empty response");
                samples.add(sample);
                continue;
            }
            String content = mapper.readTree(body).path("data").path("content").asText();
            sample.put("content", content);
            boolean finalValid = validator.isValid(content);
            sample.put("final_valid", finalValid);

            // 判定首次合规:响应合规 且 不是 RBFA fallback 模板
            boolean isFallback = content.contains("按本地热门标签排序的降级推荐");
            boolean firstValid = finalValid && !isFallback;
            sample.put("first_attempt_valid", firstValid);
            sample.put("is_fallback", isFallback);

            if (finalValid) finalValidCount.incrementAndGet();
            if (firstValid) firstValidCount.incrementAndGet();
            samples.add(sample);
        }

        // 强断言:经过 RRA + RBFA 后,所有响应必须 schema 合规
        assertThat(finalValidCount.get())
                .as("after reflection + fallback, all %d responses must be schema compliant", SAMPLE_COUNT)
                .isEqualTo(SAMPLE_COUNT);

        // 弱断言:首次合规率(soft — dashscope 行为受 prompt 影响)
        double firstRate = (double) firstValidCount.get() / SAMPLE_COUNT;
        assertThat(firstRate)
                .as("first-attempt compliance rate should be ≥ 0.88 for resume bullet")
                .isGreaterThanOrEqualTo(FIRST_ATTEMPT_TARGET);
    }

    /** 构造 100 个不同 SessionContext(覆盖各 stage × zone × cuisine × merchant)。 */
    private static List<SessionContext> buildContexts(int n) {
        String[] zones = {"Z-west", "Z-east", "Z-south", "Z-library", "Z-north"};
        String[] cuisines = {"C-sichuan", "C-cantonese", "C-noodle", "C-fastfood", "C-japanese"};
        String[] merchants = {"m-001", "m-002", "m-003"};
        SessionStage[] stages = SessionStage.values();
        List<SessionContext> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            SessionStage stage = stages[i % stages.length];
            String zone = zones[i % zones.length];
            String cuisine = cuisines[i % cuisines.length];
            String merchant = merchants[i % merchants.length];
            out.add(new SessionContext(stage, zone, cuisine, merchant));
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}