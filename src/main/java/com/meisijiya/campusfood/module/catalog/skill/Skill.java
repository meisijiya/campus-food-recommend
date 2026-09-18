package com.meisijiya.campusfood.module.catalog.skill;

import com.meisijiya.campusfood.module.catalog.session.SessionContext;

/**
 * Skill 模块接口(F-2;对应 CONTEXT.md §3 / 简历 bullet "可插拔 Skill 模块封装目录")。
 *
 * <p>Spring AI 的 prompt 模板只引用 Skill 名称(由 {@code SkillRegistry} 按当前 stage 选择);
 * 运行时由具体 Skill 从 Redis 读取目录片段并 {@link #render(SessionContext)} 成待注入字符串。
 *
 * <p>F-2 阶段目录数据尚未持久化到 Redis(留给 F-4 预热),render 仅回显当前 stage 已写入的
 * 槽位 ID(zoneId / cuisineId / merchantId)。F-4 起会替换为真实目录片段(JSON 子串)。
 *
 * @author meisijiya
 */
public interface Skill {

    /**
     * Skill 名称 — 与 prompt 模板中的占位符 {@code {skill:<name>}} 一一对应。
     * 约定纯小写无空格,如 {@code "zone"} / {@code "cuisine"} / {@code "merchant"}。
     */
    String name();

    /**
     * 把当前 stage 的目录片段渲染成 prompt 文本。
     *
     * @param ctx 当前会话快照(必须含 stage;槽位 ID 可能为 null)
     * @return 渲染产物;若 Skill 不适用当前 stage(例如 ZoneSkill 还未填入 zoneId)返 {@code null},
     *         调用方应跳过占位符替换
     */
    String render(SessionContext ctx);
}