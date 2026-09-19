package com.meisijiya.campusfood.module.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.meisijiya.campusfood.module.preheat.heat.Merchant;
import com.meisijiya.campusfood.module.preheat.heat.MerchantRepository;

/**
 * {@link MerchantQueryService} 单元测试(F-4 W3)— 覆盖 L2 中间态读路径 4 场景。
 *
 * <p>纯 Mockito + AssertJ,不依赖 Spring 上下文,保证单测运行时间 < 100ms。
 * 不打桩 Logger,因为打 INFO/WARN 不影响返回值断言(测试只关心返回值)。
 *
 * @author meisijiya
 */
class MerchantQueryServiceTest {

    private final MerchantRepository repo = mock(MerchantRepository.class);
    private final MerchantQueryService service = new MerchantQueryService(repo);

    @Test
    @DisplayName("findById_存在_返回Optional包装的商户")
    void findById_present_returnsMerchant() {
        Merchant m = new Merchant();
        m.setId("M-1");
        m.setZoneId("Z-1");
        m.setCuisineId("C-1");
        m.setName("测试商家");
        m.setTags("");
        m.setHeatScore(0.0);
        when(repo.findById("M-1")).thenReturn(Optional.of(m));

        Optional<Merchant> result = service.findById("M-1");

        assertThat(result).isPresent().containsSame(m);
    }

    @Test
    @DisplayName("findById_不存在_返回空Optional")
    void findById_absent_returnsEmpty() {
        when(repo.findById("M-X")).thenReturn(Optional.empty());

        Optional<Merchant> result = service.findById("M-X");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByZoneId_有商户_返回完整列表")
    void findByZoneId_returnsList() {
        Merchant m1 = new Merchant();
        m1.setId("M-1");
        m1.setZoneId("Z-1");
        m1.setCuisineId("C-1");
        m1.setName("商户一");
        m1.setTags("");
        m1.setHeatScore(0.0);

        Merchant m2 = new Merchant();
        m2.setId("M-2");
        m2.setZoneId("Z-1");
        m2.setCuisineId("C-1");
        m2.setName("商户二");
        m2.setTags("");
        m2.setHeatScore(0.0);

        when(repo.findByZoneId("Z-1")).thenReturn(List.of(m1, m2));

        List<Merchant> result = service.findByZoneId("Z-1");

        assertThat(result).hasSize(2)
                .extracting(Merchant::getId)
                .containsExactly("M-1", "M-2");
    }

    @Test
    @DisplayName("findByZoneId_无商户_返回空列表")
    void findByZoneId_empty_returnsEmptyList() {
        when(repo.findByZoneId("Z-X")).thenReturn(List.of());

        List<Merchant> result = service.findByZoneId("Z-X");

        assertThat(result).isEmpty();
    }
}