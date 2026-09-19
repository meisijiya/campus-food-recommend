package com.meisijiya.campusfood.module.preheat.assembler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.meisijiya.campusfood.module.preheat.heat.Merchant;

/**
 * {@link CatalogHierarchyAssembler} 单元测试 — 覆盖空输入 / 单节点 / 多 cuisine / 多 zone /
 * LinkedHashMap 顺序 / 深度断言 / 同商户跨 zone 边界 6 路径 + maxDepth(null) 边界 1 路径,
 * 总计 ≥ 10 个测试。
 *
 * <p>测试纪律:纯 POJO,无 Spring / Mockito,运行时间 &lt; 50ms。
 *
 * @author meisijiya
 */
class CatalogHierarchyAssemblerTest {

    private CatalogHierarchyAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new CatalogHierarchyAssembler();
    }

    @Test
    @DisplayName("assemble_空列表_返回空zones")
    void assemble_emptyList_emptyZones() {
        MerchantCatalog catalog = assembler.assemble(Collections.emptyList());

        assertThat(catalog).isNotNull();
        assertThat(catalog.zones()).isEmpty();
    }

    @Test
    @DisplayName("assemble_空null输入_返回空zones不抛NPE")
    void assemble_nullList_emptyZones() {
        MerchantCatalog catalog = assembler.assemble(null);

        assertThat(catalog).isNotNull();
        assertThat(catalog.zones()).isEmpty();
    }

    @Test
    @DisplayName("assemble_单zone单cuisine单商户_嵌套1x1x1")
    void assemble_singleZoneSingleCuisineSingleMerchant() {
        Merchant m = new Merchant("M-1", "Z-1", "C-1", "demo", "tag", 1.0);

        MerchantCatalog catalog = assembler.assemble(List.of(m));

        assertThat(catalog.zones()).hasSize(1);
        Zone zone = catalog.zones().get(0);
        assertThat(zone.getZoneId()).isEqualTo("Z-1");
        assertThat(zone.getCuisines()).hasSize(1);
        Cuisine cuisine = zone.getCuisines().get(0);
        assertThat(cuisine.getCuisineId()).isEqualTo("C-1");
        assertThat(cuisine.getMerchants()).hasSize(1);
        assertThat(cuisine.getMerchants().get(0).getId()).isEqualTo("M-1");
    }

    @Test
    @DisplayName("assemble_单zone多cuisine_按cuisineId分组")
    void assemble_singleZoneMultipleCuisines() {
        Merchant a1 = new Merchant("M-A1", "Z-1", "C-A", "A1", null, null);
        Merchant b1 = new Merchant("M-B1", "Z-1", "C-B", "B1", null, null);
        Merchant a2 = new Merchant("M-A2", "Z-1", "C-A", "A2", null, null);

        MerchantCatalog catalog = assembler.assemble(List.of(a1, b1, a2));

        assertThat(catalog.zones()).hasSize(1);
        Zone zone = catalog.zones().get(0);
        assertThat(zone.getCuisines()).hasSize(2);
        // LinkedHashMap 顺序:先出现的 cuisineId 在前
        assertThat(zone.getCuisines().get(0).getCuisineId()).isEqualTo("C-A");
        assertThat(zone.getCuisines().get(0).getMerchants())
                .extracting(Merchant::getId)
                .containsExactly("M-A1", "M-A2");
        assertThat(zone.getCuisines().get(1).getCuisineId()).isEqualTo("C-B");
        assertThat(zone.getCuisines().get(1).getMerchants())
                .extracting(Merchant::getId)
                .containsExactly("M-B1");
    }

    @Test
    @DisplayName("assemble_多zone_按首次出现顺序排列LinkedHashMap")
    void assemble_multipleZones_orderedByFirstSeen() {
        Merchant m1 = new Merchant("M-1", "Z-2", "C-1", "n1", null, null);
        Merchant m2 = new Merchant("M-2", "Z-1", "C-1", "n2", null, null);
        Merchant m3 = new Merchant("M-3", "Z-3", "C-1", "n3", null, null);
        Merchant m4 = new Merchant("M-4", "Z-1", "C-2", "n4", null, null);

        MerchantCatalog catalog = assembler.assemble(List.of(m1, m2, m3, m4));

        assertThat(catalog.zones())
                .extracting(Zone::getZoneId)
                .containsExactly("Z-2", "Z-1", "Z-3");
        // Z-1 下两个 cuisine:C-1 先出现 → 在前
        Zone z1 = catalog.zones().get(1);
        assertThat(z1.getCuisines())
                .extracting(Cuisine::getCuisineId)
                .containsExactly("C-1", "C-2");
    }

    @Test
    @DisplayName("assemble_深度约束_永远不超过3")
    void assemble_depthConstraint_neverExceedsThree() {
        Merchant m = new Merchant("M-1", "Z-1", "C-1", "demo", null, 1.0);
        MerchantCatalog catalog = assembler.assemble(List.of(m));

        int depth = CatalogHierarchyAssembler.maxDepth(catalog);

        assertThat(depth).isEqualTo(3);
    }

    @Test
    @DisplayName("assemble_空目录maxDepth_仍返回3_契约守护")
    void assemble_emptyCatalog_maxDepthIsThree() {
        MerchantCatalog catalog = new MerchantCatalog();

        int depth = CatalogHierarchyAssembler.maxDepth(catalog);

        assertThat(depth).isEqualTo(3);
    }

    @Test
    @DisplayName("assemble_多zone多cuisine_深度仍为3")
    void assemble_multipleZonesDepthStillThree() {
        List<Merchant> merchants = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            merchants.add(new Merchant("M-Z1-" + i, "Z-1", "C-1", "n", null, 1.0));
        }
        for (int i = 0; i < 3; i++) {
            merchants.add(new Merchant("M-Z2-" + i, "Z-2", "C-2", "n", null, 2.0));
        }
        MerchantCatalog catalog = assembler.assemble(merchants);

        assertThat(CatalogHierarchyAssembler.maxDepth(catalog)).isEqualTo(3);
        assertThat(catalog.zones()).hasSize(2);
    }

    @Test
    @DisplayName("assemble_同商户ID跨zone_拆分成两个zone不合并")
    void assemble_sameMerchantInTwoZones_splitIntoTwoZones() {
        Merchant mInZ1 = new Merchant("M-SHARED", "Z-1", "C-1", "shared-1", null, 1.0);
        Merchant mInZ2 = new Merchant("M-SHARED", "Z-2", "C-1", "shared-2", null, 2.0);

        MerchantCatalog catalog = assembler.assemble(List.of(mInZ1, mInZ2));

        assertThat(catalog.zones()).hasSize(2);
        assertThat(catalog.zones())
                .extracting(Zone::getZoneId)
                .containsExactly("Z-1", "Z-2");
        // 各自 zone 下只有一个商户,名字分别为 shared-1 / shared-2(同名不同 ID 不应合并)
        assertThat(catalog.zones().get(0).getCuisines().get(0).getMerchants())
                .extracting(Merchant::getName)
                .containsExactly("shared-1");
        assertThat(catalog.zones().get(1).getCuisines().get(0).getMerchants())
                .extracting(Merchant::getName)
                .containsExactly("shared-2");
    }

    @Test
    @DisplayName("assemble_同cuisine跨zone_各自独立不合并")
    void assemble_sameCuisineAcrossZones_independent() {
        Merchant a = new Merchant("M-A", "Z-1", "C-X", "a", null, 1.0);
        Merchant b = new Merchant("M-B", "Z-2", "C-X", "b", null, 2.0);

        MerchantCatalog catalog = assembler.assemble(List.of(a, b));

        assertThat(catalog.zones()).hasSize(2);
        assertThat(catalog.zones().get(0).getCuisines().get(0).getMerchants())
                .extracting(Merchant::getId)
                .containsExactly("M-A");
        assertThat(catalog.zones().get(1).getCuisines().get(0).getMerchants())
                .extracting(Merchant::getId)
                .containsExactly("M-B");
    }

    @Test
    @DisplayName("maxDepth_null目录_返回0")
    void maxDepth_nullCatalog_returnsZero() {
        int depth = CatalogHierarchyAssembler.maxDepth(null);

        assertThat(depth).isEqualTo(0);
    }
}