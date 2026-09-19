package com.meisijiya.campusfood.module.preheat.heat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MerchantHeatCalculator 单元测试(F-4)— 覆盖排序 / 公式 / 空数据 / topN limit / 平手排序稳定 5 路径。
 *
 * <p>测试纪律:纯 Mockito + AssertJ,不依赖 Spring 上下文,保证单测运行时间 < 100ms。
 *
 * @author meisijiya
 */
@ExtendWith(MockitoExtension.class)
class MerchantHeatCalculatorTest {

    @Mock
    private MerchantRepository merchants;

    @Mock
    private OrderRepository orders;

    @Mock
    private LikeRepository likes;

    private MerchantHeatCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new MerchantHeatCalculator(orders, likes, merchants);
    }

    @Test
    @DisplayName("scores_加权公式0_6订单加0_4点赞_计算正确")
    void scores_加权公式_计算正确() {
        Merchant m1 = merchant("M-1");
        Merchant m2 = merchant("M-2");
        Merchant m3 = merchant("M-3");
        when(merchants.findAll()).thenReturn(List.of(m1, m2, m3));

        // M-1: 10 订单 + 5 点赞 = 0.6*10 + 0.4*5 = 6 + 2 = 8.0
        when(orders.countByMerchantId("M-1")).thenReturn(10L);
        when(likes.countByMerchantId("M-1")).thenReturn(5L);
        // M-2: 0 订单 + 10 点赞 = 0 + 4 = 4.0
        when(orders.countByMerchantId("M-2")).thenReturn(0L);
        when(likes.countByMerchantId("M-2")).thenReturn(10L);
        // M-3: 5 订单 + 0 点赞 = 3 + 0 = 3.0
        when(orders.countByMerchantId("M-3")).thenReturn(5L);
        when(likes.countByMerchantId("M-3")).thenReturn(0L);

        Map<String, Double> result = calculator.scores();

        assertThat(result)
                .hasSize(3)
                .containsEntry("M-1", 8.0)
                .containsEntry("M-2", 4.0)
                .containsEntry("M-3", 3.0);
    }

    @Test
    @DisplayName("scores_无订单无点赞_所有商户默认0_0")
    void scores_无订单无点赞_默认零() {
        Merchant m1 = merchant("M-1");
        Merchant m2 = merchant("M-2");
        when(merchants.findAll()).thenReturn(List.of(m1, m2));
        when(orders.countByMerchantId("M-1")).thenReturn(0L);
        when(orders.countByMerchantId("M-2")).thenReturn(0L);
        when(likes.countByMerchantId("M-1")).thenReturn(0L);
        when(likes.countByMerchantId("M-2")).thenReturn(0L);

        Map<String, Double> result = calculator.scores();

        assertThat(result)
                .hasSize(2)
                .containsEntry("M-1", 0.0)
                .containsEntry("M-2", 0.0);
    }

    @Test
    @DisplayName("topN_按热度降序排列_顺序正确")
    void topN_按热度降序_顺序正确() {
        Merchant hot = merchant("M-HOT");      // score 8.0
        Merchant mid = merchant("M-MID");      // score 5.0
        Merchant cold = merchant("M-COLD");    // score 1.0

        when(merchants.findAll()).thenReturn(List.of(hot, mid, cold));
        when(orders.countByMerchantId("M-HOT")).thenReturn(10L);
        when(likes.countByMerchantId("M-HOT")).thenReturn(5L);
        when(orders.countByMerchantId("M-MID")).thenReturn(5L);
        when(likes.countByMerchantId("M-MID")).thenReturn(5L);
        when(orders.countByMerchantId("M-COLD")).thenReturn(1L);
        when(likes.countByMerchantId("M-COLD")).thenReturn(1L);

        List<Merchant> top = calculator.topN(3);

        assertThat(top).extracting(Merchant::getId).containsExactly("M-HOT", "M-MID", "M-COLD");
    }

    @Test
    @DisplayName("topN_n大于商户总数_返回全量")
    void topN_大于总数_返回全量() {
        Merchant m1 = merchant("M-1");
        Merchant m2 = merchant("M-2");
        when(merchants.findAll()).thenReturn(List.of(m1, m2));
        when(orders.countByMerchantId(anyString())).thenReturn(0L);
        when(likes.countByMerchantId(anyString())).thenReturn(0L);

        List<Merchant> top = calculator.topN(100);

        assertThat(top).hasSize(2);
    }

    @Test
    @DisplayName("topN_n为0_返回空列表")
    void topN_零值_返回空列表() {
        // 不 stub merchants.findAll() — topN(0) 短路在 n<=0,不会触发 findAll,
        // 这正是要覆盖的边界;若短路失效,这里会因为 NPE 自然暴露。

        List<Merchant> top = calculator.topN(0);

        assertThat(top).isEmpty();
    }

    @Test
    @DisplayName("topN_平手时按merchantId升序_排序稳定")
    void topN_平手_按id升序() {
        Merchant a = merchant("M-A");
        Merchant b = merchant("M-B");
        Merchant c = merchant("M-C");
        // 三个商户热度完全相同(都是 0),按 ID 升序兜底
        when(merchants.findAll()).thenReturn(List.of(a, b, c));
        when(orders.countByMerchantId(anyString())).thenReturn(0L);
        when(likes.countByMerchantId(anyString())).thenReturn(0L);

        List<Merchant> top = calculator.topN(3);

        assertThat(top).extracting(Merchant::getId).containsExactly("M-A", "M-B", "M-C");
    }

    @Test
    @DisplayName("topN_输入顺序不同_平手结果稳定")
    void topN_输入顺序无关_结果稳定() {
        // 验证:即使 findAll 返回顺序乱,平手项按 ID 升序 → 结果稳定
        Merchant a = merchant("M-A");
        Merchant b = merchant("M-B");
        Merchant c = merchant("M-C");
        // 平手 score = 3.0
        when(merchants.findAll()).thenReturn(List.of(c, a, b));   // 故意乱序
        when(orders.countByMerchantId(anyString())).thenReturn(5L);
        when(likes.countByMerchantId(anyString())).thenReturn(0L);

        List<Merchant> top = calculator.topN(3);

        assertThat(top).extracting(Merchant::getId).containsExactly("M-A", "M-B", "M-C");
    }

    @Test
    @DisplayName("scoreOf_已知商户_返回正确分")
    void scoreOf_已知商户_正确分() {
        when(merchants.findById("M-1")).thenReturn(Optional.of(merchant("M-1")));
        when(orders.countByMerchantId("M-1")).thenReturn(10L);
        when(likes.countByMerchantId("M-1")).thenReturn(5L);

        Double score = calculator.scoreOf("M-1");

        assertThat(score).isEqualTo(8.0);
    }

    @Test
    @DisplayName("scoreOf_未知商户_返回null")
    void scoreOf_未知商户_返回null() {
        when(merchants.findById("M-X")).thenReturn(Optional.empty());

        Double score = calculator.scoreOf("M-X");

        assertThat(score).isNull();
    }

    @Test
    @DisplayName("scoreOf_空字符串或null_返回null")
    void scoreOf_空字符串或null_返回null() {
        assertThat(calculator.scoreOf("")).isNull();
        assertThat(calculator.scoreOf(null)).isNull();
    }

    // ---------- helpers ----------

    /** 构造一个固定字段的 Merchant(测试用)。 */
    private static Merchant merchant(String id) {
        return new Merchant(id, "Z-1", "C-1", "demo-" + id, "tag", null);
    }

    /**
     * 抑制测试用 {@link Merchant} / {@link Order} / {@link Like} 构造器对编译器的
     * "unused private method" 警告 — 测试只通过 6 参 / 3 参 / 3 参 构造器造种子数据,
     * 保留未来扩展入口。
     */
    @SuppressWarnings("unused")
    private static Order seedOrder(String studentId, String merchantId) {
        return new Order(studentId, merchantId, Instant.parse("2026-01-01T00:00:00Z"));
    }

    @SuppressWarnings("unused")
    private static Like seedLike(String studentId, String merchantId) {
        return new Like(studentId, merchantId, Instant.parse("2026-01-01T00:00:00Z"));
    }
}