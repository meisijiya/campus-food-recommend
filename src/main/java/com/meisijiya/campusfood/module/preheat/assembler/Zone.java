package com.meisijiya.campusfood.module.preheat.assembler;

import java.util.ArrayList;
import java.util.List;

/**
 * 层级 JSON 第二层:校区 / 商圈(Zone)— F-4 凌晨预热 JVM 内拼装的目录骨架节点之一。
 *
 * <p>层级契约(深度 2,见 {@link MerchantCatalog} 注释):
 * <pre>
 *   MerchantCatalog            (depth 1)
 *     └─ List&lt;Zone&gt;          (depth 2)
 *          └─ List&lt;Cuisine&gt;   (depth 3)
 *               └─ List&lt;Merchant&gt;
 * </pre>
 *
 * <p>语义约定:
 * <ul>
 *   <li>{@code zoneId}:与 {@code SessionStage#ZONE} 槽位对齐的校区 / 商圈 ID。</li>
 *   <li>{@code cuisines}:该 zone 下的菜系列表,顺序保留首次出现的相对顺序。</li>
 * </ul>
 *
 * <p>Jackson 序列化约束:不带任何 Jackson 注解,完全依靠默认 property 名匹配;新增字段请同步更新
 * {@code CatalogHierarchyAssemblerTest} 的扁平对照断言。
 *
 * <p>不带 Lombok,字段全部 private + 显式 getter/setter + 无参构造,符合 F-1 起的项目惯例。
 *
 * @author meisijiya
 */
public class Zone {

    private String zoneId;

    private List<Cuisine> cuisines;

    /** Jackson 反序列化必需。 */
    public Zone() {
        this.cuisines = new ArrayList<>();
    }

    public Zone(String zoneId, List<Cuisine> cuisines) {
        this.zoneId = zoneId;
        this.cuisines = cuisines;
    }

    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public List<Cuisine> getCuisines() {
        return cuisines;
    }

    public void setCuisines(List<Cuisine> cuisines) {
        this.cuisines = cuisines;
    }
}