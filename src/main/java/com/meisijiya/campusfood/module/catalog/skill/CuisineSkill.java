package com.meisijiya.campusfood.module.catalog.skill;

import org.springframework.stereotype.Component;

import com.meisijiya.campusfood.module.catalog.session.SessionContext;
import com.meisijiya.campusfood.module.catalog.session.SessionStage;

/**
 * CuisineSkill — 渲染当前会话已选菜系的目录片段。
 *
 * <p>仅在 stage 已推进到 {@code CUISINE} 或 {@code MERCHANT} 且已写入 cuisineId 时生效。
 *
 * @author meisijiya
 */
@Component
public class CuisineSkill implements Skill {

    @Override
    public String name() {
        return "cuisine";
    }

    @Override
    public String render(SessionContext ctx) {
        if (ctx == null || ctx.stage() == null) {
            return null;
        }
        // 渐进式:仅在 CUISINE / MERCHANT 阶段有意义
        if (ctx.stage() != SessionStage.CUISINE && ctx.stage() != SessionStage.MERCHANT) {
            return null;
        }
        String cuisineId = ctx.cuisineId();
        if (cuisineId == null || cuisineId.isBlank()) {
            return null;
        }
        return "cuisine=" + cuisineId;
    }
}