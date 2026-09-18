package com.meisijiya.campusfood.module.catalog.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SessionController 集成测试(F-2;覆盖 4 endpoint 200 + 非法跳转 400)。
 *
 * <p>测试纪律:用 {@code @MockitoBean} 替换 {@link StringRedisTemplate},
 * 配套一个 in-memory backstore(由 {@link ValueOperations} 的 stub 通过 Answer 维护),
 * 让 SET 后 GET 能读到值 — 不依赖真 Redis(CI / 本机零依赖原则)。
 *
 * @author meisijiya
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private StringRedisTemplate redis;

    /** mock ValueOperations + 共享的内存 map(给 Answer 用)。 */
    private Map<String, String> store;

    @BeforeEach
    void setUp() {
        store = new HashMap<>();

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        // 拦截所有 set 重载 -> 写入 store
        Answer<Void> putAnswer = inv -> {
            store.put((String) inv.getArgument(0), (String) inv.getArgument(1));
            return null;
        };
        doAnswer(putAnswer).when(valueOps).set(any(String.class), any(String.class));
        doAnswer(putAnswer).when(valueOps).set(any(String.class), any(String.class), anyLong(), any());

        // get -> 从 store 读
        when(valueOps.get(any(String.class)))
                .thenAnswer(inv -> store.get((String) inv.getArgument(0)));

        when(redis.opsForValue()).thenReturn(valueOps);

        // delete(Collection<String>) -> 批量移除
        when(redis.delete((Collection<String>) any())).thenAnswer(inv -> {
            Collection<String> keys = inv.getArgument(0);
            long removed = 0;
            for (String k : new ArrayList<>(keys)) {
                if (store.remove(k) != null) removed++;
            }
            return removed;
        });
    }

    // ---------- 4 个 endpoint 200 路径 ----------

    @Test
    @DisplayName("POST /api/session/init:未登录 -> 401")
    void init_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/session/init").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/session/init:登录态下返 200 + stage=INIT")
    void init_withAuth_returns200Init() throws Exception {
        mockMvc.perform(post("/api/session/init")
                        .with(csrf())
                        .with(user("demo-user").roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.stage").value("INIT"));
    }

    @Test
    @DisplayName("POST /api/session/zone:INIT -> ZONE 合法,返 200 + stage=ZONE")
    void zone_fromInit_returns200() throws Exception {
        String body = objectMapper.writeValueAsString(new SessionController.SlotRequest("Z-1"));
        mockMvc.perform(post("/api/session/zone")
                        .with(csrf())
                        .with(user("demo-user").roles("STUDENT"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("ZONE"))
                .andExpect(jsonPath("$.data.zoneId").value("Z-1"));
    }

    @Test
    @DisplayName("POST /api/session/cuisine:INIT 直接 cuisine 非法 -> 400(越级)")
    void cuisine_fromInit_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(new SessionController.SlotRequest("C-1"));
        mockMvc.perform(post("/api/session/cuisine")
                        .with(csrf())
                        .with(user("demo-user").roles("STUDENT"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("POST /api/session/cuisine:合法路径 INIT -> ZONE -> CUISINE")
    void cuisine_fromZone_returns200() throws Exception {
        String zoneBody = objectMapper.writeValueAsString(new SessionController.SlotRequest("Z-1"));
        mockMvc.perform(post("/api/session/zone")
                        .with(csrf())
                        .with(user("demo-user").roles("STUDENT"))
                        .contentType("application/json")
                        .content(zoneBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("ZONE"));

        String cuisineBody = objectMapper.writeValueAsString(new SessionController.SlotRequest("C-1"));
        mockMvc.perform(post("/api/session/cuisine")
                        .with(csrf())
                        .with(user("demo-user").roles("STUDENT"))
                        .contentType("application/json")
                        .content(cuisineBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("CUISINE"))
                .andExpect(jsonPath("$.data.cuisineId").value("C-1"));
    }

    @Test
    @DisplayName("POST /api/session/merchant:合法路径 INIT -> ZONE -> CUISINE -> MERCHANT")
    void merchant_fullChain_returns200() throws Exception {
        runFullChain("M-1");
        assertThat(store.get("session:demo-user:stage")).isEqualTo("MERCHANT");
        assertThat(store.get("session:demo-user:zone")).isEqualTo("Z-1");
        assertThat(store.get("session:demo-user:cuisine")).isEqualTo("C-1");
        assertThat(store.get("session:demo-user:merchant")).isEqualTo("M-1");
    }

    @Test
    @DisplayName("POST /api/session/merchant:INIT 直接 merchant 非法 -> 400(跨多级)")
    void merchant_fromInit_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(new SessionController.SlotRequest("M-1"));
        mockMvc.perform(post("/api/session/merchant")
                        .with(csrf())
                        .with(user("demo-user").roles("STUDENT"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/session/zone:value 为空触发 400(@Valid)")
    void slotRequest_blankValue_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(new SessionController.SlotRequest(""));
        mockMvc.perform(post("/api/session/zone")
                        .with(csrf())
                        .with(user("demo-user").roles("STUDENT"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("SessionService.slotTtl() == 1800 秒")
    void slotTtl_is1800Seconds() {
        assertThat(SessionService.slotTtl().getSeconds()).isEqualTo(1800L);
    }

    // ---------- helpers ----------

    private void runFullChain(String merchantId) throws Exception {
        for (String endpoint : List.of("/api/session/zone", "/api/session/cuisine", "/api/session/merchant")) {
            String value = switch (endpoint) {
                case "/api/session/zone" -> "Z-1";
                case "/api/session/cuisine" -> "C-1";
                case "/api/session/merchant" -> merchantId;
                default -> throw new IllegalArgumentException(endpoint);
            };
            String body = objectMapper.writeValueAsString(new SessionController.SlotRequest(value));
            mockMvc.perform(post(endpoint)
                            .with(csrf())
                            .with(user("demo-user").roles("STUDENT"))
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isOk());
        }
    }
}