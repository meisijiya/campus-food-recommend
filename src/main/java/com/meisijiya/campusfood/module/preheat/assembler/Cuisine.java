package com.meisijiya.campusfood.module.preheat.assembler;

import java.util.ArrayList;
import java.util.List;

import com.meisijiya.campusfood.module.preheat.heat.Merchant;

/**
 * 层级 JSON 第三层:菜系(Cuisine)— F-4 凌晨预热 JVM 内拼装的目录骨架节点之一。
 *
 * <p>层级契约(深度 3,见 {@link MerchantCatalog} 注释):
 * <pre>
 *   MerchantCatalog            (depth 1)
 *     └─ List&lt;Zone&gt;          (depth 2)
 *          └─ List&lt;Cuisine&gt;   (depth 3)
 *               └─ List&lt;Merchant&gt;   (depth 4,但语义上"叶子层")
 * </pre>
 *
 * <p>注意:虽然本类是 {@code Merchant} 的祖父节点,但 {Merchant} 已经是叶子层,不再嵌套,
 * 因此整个目录树的最大深度恒为 3(zones → cuisines → merchants),由
 * {@link CatalogHierarchyAssembler#maxDepth(MerchantCatalog)} 断言守护。
 *
 * <p>语义约定:
 * <ul>
 *   <li>{@code cuisineId}:与 {@code SessionStage#CUISINE} 槽位对齐的菜系 ID。</li>
 *   <li>{@code merchants}:该菜系下商户列表,顺序保留首次出现的相对顺序(用于稳定 JSON 输出)。</li>
 * </ul>
 *
 * <p>不带 Lombok,字段全部 private + 显式 getter/setter + 无参构造,符合 F-1 起的项目惯例。
 *
 * @author meisijiya
 */
public class Cuisine {

    private String cuisineId;

    private List<Merchant> merchants;

    /** Jackson 反序列化必需。 */
    public Cuisine() {
        this.merchants = new ArrayList<>();
    }

    public Cuisine(String cuisineId, List<Merchant> merchants) {
        this.cuisineId = cuisineId;
        this.merchants = merchants;
    }

    public String getCuisineId() {
        return cuisineId;
    }

    public void setCuisineId(String cuisineId) {
        this.cuisineId = cuisineId;
    }

    public List<Merchant> getMerchants() {
        return merchants;
    }

    public void setMerchants(List<Merchant> merchants) {
        this.merchants = merchants;
    }
}