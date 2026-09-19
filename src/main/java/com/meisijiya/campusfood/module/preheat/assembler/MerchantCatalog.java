package com.meisijiya.campusfood.module.preheat.assembler;

import java.util.ArrayList;
import java.util.List;

/**
 * 层级 JSON 顶层目录(MerchantCatalog)— F-4 凌晨预热的最终装配产物,经
 * {@link com.meisijiya.campusfood.module.preheat.RedisShardedWriter} 写入
 * {@code catalog:zone:<zoneId>} 等 Redis key。
 *
 * <p>层级契约(深度 ≤ 3,见 {@code CONTEXT.md §3}):
 * <pre>
 *   MerchantCatalog              (depth 1)
 *     └─ zones: List&lt;Zone&gt;     (depth 2)
 *          └─ cuisines: List&lt;Cuisine&gt;     (depth 3,叶子层 Merchant 不再嵌套)
 *               └─ merchants: List&lt;Merchant&gt;
 * </pre>
 *
 * <p>序列化样例:
 * <pre>
 * {
 *   "zones": [
 *     { "zoneId": "Z-1", "cuisines": [
 *         { "cuisineId": "C-1", "merchants": [
 *             { "id": "M-1", "zoneId": "Z-1", "cuisineId": "C-1",
 *               "name": "...", "tags": "...", "heatScore": 8.0 }
 *         ]}
 *     ]}
 *   ]
 * }
 * </pre>
 *
 * <p>约束:
 * <ul>
 *   <li>不使用 Jackson 注解,依赖默认 property 名匹配。</li>
 *   <li>深度永远 ≤ 3,由 {@link CatalogHierarchyAssembler#maxDepth(MerchantCatalog)} 在装配后断言。</li>
 *   <li>本类对外公开(类层级 {@code public}),因为 {@code RedisShardedWriter} 跨包消费;
 *       {@link Zone} / {@link Cuisine} 保持包内可见(包私有),仅作 MerchantCatalog 的嵌套元素。</li>
 * </ul>
 *
 * <p>不带 Lombok,字段全部 private + 显式 getter/setter + 无参构造,符合 F-1 起的项目惯例。
 *
 * @author meisijiya
 */
public class MerchantCatalog {

    private List<Zone> zones;

    /** Jackson 反序列化必需;默认空集合,避免 NPE。 */
    public MerchantCatalog() {
        this.zones = new ArrayList<>();
    }

    public MerchantCatalog(List<Zone> zones) {
        this.zones = zones;
    }

    /** 当前 zones 列表(永不为 null,但可能为空集合)。 */
    public List<Zone> zones() {
        return zones;
    }

    public void setZones(List<Zone> zones) {
        this.zones = zones;
    }

    /** Jackson 反序列化入口。 */
    public List<Zone> getZones() {
        return zones;
    }
}