package com.meisijiya.campusfood.module.catalog.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.meisijiya.campusfood.module.catalog.session.SessionContext;
import com.meisijiya.campusfood.module.catalog.session.SessionStage;
import com.meisijiya.campusfood.module.recommend.SkillRegistry;

/**
 * SkillRegistry 单元测试(F-2;对应 ticket acceptance #13 "覆盖不同 stage 下渲染产物正确")。
 *
 * <p>策略:用真实的 ZoneSkill / CuisineSkill / MerchantSkill 组装 SkillRegistry,
 * 覆盖 4 stage × 3 skill 的 render 输出,以及 substitute() 的占位符替换行为。
 *
 * @author meisijiya
 */
class SkillRegistryTest {

    private final ZoneSkill zoneSkill = new ZoneSkill();
    private final CuisineSkill cuisineSkill = new CuisineSkill();
    private final MerchantSkill merchantSkill = new MerchantSkill();

    private final SkillRegistry registry =
            new SkillRegistry(List.of(zoneSkill, cuisineSkill, merchantSkill));

    // ---------- 注册校验 ----------

    @Test
    @DisplayName("registeredNames:3 个 Skill 都注册上")
    void registeredNames_containsAllThree() {
        assertThat(registry.registeredNames())
                .containsExactlyInAnyOrder("zone", "cuisine", "merchant");
    }

    @Test
    @DisplayName("重复 name 抛 IllegalStateException")
    void duplicateName_throws() {
        Skill dup = new Skill() {
            @Override public String name() { return "zone"; }
            @Override public String render(SessionContext ctx) { return "dup"; }
        };
        assertThatThrownBy(() -> new SkillRegistry(List.of(zoneSkill, dup)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zone");
    }

    // ---------- Stage × Skill render 矩阵(12 路径) ----------

    @Test
    @DisplayName("INIT × ZoneSkill → null(stage 不匹配)")
    void init_zoneSkill_null() {
        SessionContext ctx = new SessionContext(SessionStage.INIT, null, null, null);
        assertThat(zoneSkill.render(ctx)).isNull();
    }

    @Test
    @DisplayName("INIT × CuisineSkill → null")
    void init_cuisineSkill_null() {
        SessionContext ctx = new SessionContext(SessionStage.INIT, null, null, null);
        assertThat(cuisineSkill.render(ctx)).isNull();
    }

    @Test
    @DisplayName("INIT × MerchantSkill → null")
    void init_merchantSkill_null() {
        SessionContext ctx = new SessionContext(SessionStage.INIT, null, null, null);
        assertThat(merchantSkill.render(ctx)).isNull();
    }

    @Test
    @DisplayName("ZONE × ZoneSkill → zone=Z-1(stage 已匹配且 zoneId 已填)")
    void zone_zoneSkill_rendered() {
        SessionContext ctx = new SessionContext(SessionStage.ZONE, "Z-1", null, null);
        assertThat(zoneSkill.render(ctx)).isEqualTo("zone=Z-1");
    }

    @Test
    @DisplayName("ZONE × CuisineSkill → null(尚未到 CUISINE)")
    void zone_cuisineSkill_null() {
        SessionContext ctx = new SessionContext(SessionStage.ZONE, "Z-1", null, null);
        assertThat(cuisineSkill.render(ctx)).isNull();
    }

    @Test
    @DisplayName("ZONE × MerchantSkill → null(尚未到 MERCHANT)")
    void zone_merchantSkill_null() {
        SessionContext ctx = new SessionContext(SessionStage.ZONE, "Z-1", null, null);
        assertThat(merchantSkill.render(ctx)).isNull();
    }

    @Test
    @DisplayName("CUISINE × ZoneSkill → zone=Z-1(渐进式注入已填槽位)")
    void cuisine_zoneSkill_rendered() {
        SessionContext ctx = new SessionContext(SessionStage.CUISINE, "Z-1", "C-1", null);
        assertThat(zoneSkill.render(ctx)).isEqualTo("zone=Z-1");
    }

    @Test
    @DisplayName("CUISINE × CuisineSkill → cuisine=C-1")
    void cuisine_cuisineSkill_rendered() {
        SessionContext ctx = new SessionContext(SessionStage.CUISINE, "Z-1", "C-1", null);
        assertThat(cuisineSkill.render(ctx)).isEqualTo("cuisine=C-1");
    }

    @Test
    @DisplayName("CUISINE × MerchantSkill → null(尚未到 MERCHANT)")
    void cuisine_merchantSkill_null() {
        SessionContext ctx = new SessionContext(SessionStage.CUISINE, "Z-1", "C-1", null);
        assertThat(merchantSkill.render(ctx)).isNull();
    }

    @Test
    @DisplayName("MERCHANT × ZoneSkill → zone=Z-1")
    void merchant_zoneSkill_rendered() {
        SessionContext ctx = new SessionContext(SessionStage.MERCHANT, "Z-1", "C-1", "M-1");
        assertThat(zoneSkill.render(ctx)).isEqualTo("zone=Z-1");
    }

    @Test
    @DisplayName("MERCHANT × CuisineSkill → cuisine=C-1")
    void merchant_cuisineSkill_rendered() {
        SessionContext ctx = new SessionContext(SessionStage.MERCHANT, "Z-1", "C-1", "M-1");
        assertThat(cuisineSkill.render(ctx)).isEqualTo("cuisine=C-1");
    }

    @Test
    @DisplayName("MERCHANT × MerchantSkill → merchant=M-1")
    void merchant_merchantSkill_rendered() {
        SessionContext ctx = new SessionContext(SessionStage.MERCHANT, "Z-1", "C-1", "M-1");
        assertThat(merchantSkill.render(ctx)).isEqualTo("merchant=M-1");
    }

    // ---------- renderAll 快照 ----------

    @Test
    @DisplayName("renderAll(MERCHANT stage):三个 Skill 全部产出非 null")
    void renderAll_merchant_allNonNull() {
        SessionContext ctx = new SessionContext(SessionStage.MERCHANT, "Z-1", "C-1", "M-1");
        Map<String, String> snapshot = registry.renderAll(ctx);
        assertThat(snapshot).containsEntry("zone", "zone=Z-1");
        assertThat(snapshot).containsEntry("cuisine", "cuisine=C-1");
        assertThat(snapshot).containsEntry("merchant", "merchant=M-1");
    }

    @Test
    @DisplayName("renderAll(INIT stage):全部 null")
    void renderAll_init_allNull() {
        SessionContext ctx = new SessionContext(SessionStage.INIT, null, null, null);
        Map<String, String> snapshot = registry.renderAll(ctx);
        assertThat(snapshot).containsEntry("zone", null);
        assertThat(snapshot).containsEntry("cuisine", null);
        assertThat(snapshot).containsEntry("merchant", null);
    }

    // ---------- substitute 行为 ----------

    @Test
    @DisplayName("substitute:MERCHANT stage 模板三个占位符全部替换")
    void substitute_merchant_allPlaceholdersReplaced() {
        SessionContext ctx = new SessionContext(SessionStage.MERCHANT, "Z-1", "C-1", "M-1");
        String template = "zone=[{skill:zone}] cuisine=[{skill:cuisine}] merchant=[{skill:merchant}]";
        String result = registry.substitute(template, ctx);
        assertThat(result).isEqualTo("zone=[zone=Z-1] cuisine=[cuisine=C-1] merchant=[merchant=M-1]");
    }

    @Test
    @DisplayName("substitute:INIT stage 三个占位符替换为空(因为 render 返 null)")
    void substitute_init_placeholdersEmpty() {
        SessionContext ctx = new SessionContext(SessionStage.INIT, null, null, null);
        String template = "zone=[{skill:zone}] cuisine=[{skill:cuisine}] merchant=[{skill:merchant}]";
        String result = registry.substitute(template, ctx);
        assertThat(result).isEqualTo("zone=[] cuisine=[] merchant=[]");
    }

    @Test
    @DisplayName("substitute:未注册占位符保持原样,不抛错")
    void substitute_unknownPlaceholder_keptVerbatim() {
        SessionContext ctx = new SessionContext(SessionStage.ZONE, "Z-1", null, null);
        String template = "known=[{skill:zone}] unknown=[{skill:price}]";
        String result = registry.substitute(template, ctx);
        assertThat(result).isEqualTo("known=[zone=Z-1] unknown=[{skill:price}]");
    }

    @Test
    @DisplayName("substitute:空模板返 null(短路)")
    void substitute_nullOrEmpty_returnsAsIs() {
        SessionContext ctx = new SessionContext(SessionStage.INIT, null, null, null);
        assertThat(registry.substitute(null, ctx)).isNull();
        assertThat(registry.substitute("", ctx)).isEmpty();
    }
}