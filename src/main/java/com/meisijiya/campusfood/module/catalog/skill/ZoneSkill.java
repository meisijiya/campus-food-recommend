package com.meisijiya.campusfood.module.catalog.skill;

import org.springframework.stereotype.Component;

import com.meisijiya.campusfood.module.catalog.session.SessionContext;
import com.meisijiya.campusfood.module.catalog.session.SessionStage;

/**
 * ZoneSkill — 渲染当前会话所属商圈的目录片段。
 *
 * <p>F-2 阶段 Redis 中只持久化 zoneId(由 {@code SessionService} 写入);
 * render 直接从 {@link SessionContext#zoneId()} 回显。F-4 起替换为完整目录 JSON。
 *
 * @author meisijiya
 */
@Component
public class ZoneSkill implements Skill {

    @Override
    public String name() {
        return "zone";
    }

    @Override
    public String render(SessionContext ctx) {
        if (ctx == null || ctx.stage() == null || ctx.stage() == SessionStage.INIT) {
            return null;
        }
        String zoneId = ctx.zoneId();
        if (zoneId == null || zoneId.isBlank()) {
            return null;
        }
        return "zone=" + zoneId;
    }
}