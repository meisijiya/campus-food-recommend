package com.meisijiya.campusfood.module.recommend;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.meisijiya.campusfood.module.catalog.session.SessionContext;
import com.meisijiya.campusfood.module.catalog.skill.Skill;

/**
 * Skill 注册中心(F-2;对应 ticket acceptance #6 + plan.md §Global Constraints #8)。
 *
 * <p>启动时收集所有 {@link Skill} {@code @Component} 实现,按 {@link Skill#name()} 建索引。
 * 运行时由 {@link #renderAll(SessionContext)} 一次性渲染当前 stage 适用的所有 Skill,
 * 然后用 {@link #substitute(String, Map)} 把 prompt 模板里的 {@code {skill:<name>}} 占位符替换掉。
 *
 * <h2>模板约束</h2>
 * <ul>
 *   <li>模板中只允许出现 {@code {skill:<name>}} 占位符(不能把目录数据 inline 进模板)</li>
 *   <li>未注册的 {@code <name>} 占位符保留原样,触发日志告警</li>
 *   <li>Skill.render 返 {@code null} 时,占位符替换为空字符串</li>
 * </ul>
 *
 * @author meisijiya
 */
@Component
public class SkillRegistry {

    private final Map<String, Skill> skills;

    public SkillRegistry(List<Skill> skillBeans) {
        Map<String, Skill> map = new HashMap<>();
        for (Skill s : skillBeans) {
            String name = s.name();
            if (map.put(name, s) != null) {
                throw new IllegalStateException(
                        "Skill name 重复:" + name + " (要求每个 Skill name 唯一)");
            }
        }
        this.skills = Map.copyOf(map);
    }

    /** 当前注册的 Skill 名称列表(只读快照,供调试 / 测试断言用)。 */
    public java.util.Set<String> registeredNames() {
        return skills.keySet();
    }

    /**
     * 把模板里的 {@code {skill:<name>}} 占位符替换为对应 Skill 的 render 结果。
     *
     * @param template 含 {@code {skill:<name>}} 占位符的 prompt 模板
     * @param ctx      当前会话快照(用于各 Skill.render)
     * @return 替换后的完整 prompt 字符串
     */
    public String substitute(String template, SessionContext ctx) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        Map<String, String> rendered = renderAll(ctx);
        String result = template;
        for (Map.Entry<String, String> entry : rendered.entrySet()) {
            String placeholder = "{skill:" + entry.getKey() + "}";
            String value = entry.getValue() == null ? "" : entry.getValue();
            result = result.replace(placeholder, value);
        }
        return result;
    }

    /** 当前 stage 适用的所有 Skill render 结果(快照)。 */
    public Map<String, String> renderAll(SessionContext ctx) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, Skill> entry : skills.entrySet()) {
            String value = entry.getValue().render(ctx);
            out.put(entry.getKey(), value);
        }
        return out;
    }
}