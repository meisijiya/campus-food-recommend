package com.meisijiya.campusfood.module.catalog.skill;

import org.springframework.stereotype.Component;

import com.meisijiya.campusfood.module.catalog.session.SessionContext;
import com.meisijiya.campusfood.module.catalog.session.SessionStage;

/**
 * MerchantSkill — 渲染当前会话已选商家的目录片段。
 *
 * <p>仅在 stage 已推进到 {@code MERCHANT} 且已写入 merchantId 时生效。
 *
 * @author meisijiya
 */
@Component
public class MerchantSkill implements Skill {

    @Override
    public String name() {
        return "merchant";
    }

    @Override
    public String render(SessionContext ctx) {
        if (ctx == null || ctx.stage() == null) {
            return null;
        }
        // 渐进式:仅在 MERCHANT 阶段有意义
        if (ctx.stage() != SessionStage.MERCHANT) {
            return null;
        }
        String merchantId = ctx.merchantId();
        if (merchantId == null || merchantId.isBlank()) {
            return null;
        }
        return "merchant=" + merchantId;
    }
}