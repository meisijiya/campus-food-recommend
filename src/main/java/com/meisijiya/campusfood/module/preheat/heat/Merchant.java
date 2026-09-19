package com.meisijiya.campusfood.module.preheat.heat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 商户实体(F-4 数据层)— F-4 凌晨热度计算 + F-5 商户查询的根实体。
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@code id}:商户主键,业务方提供(非自增),便于跨系统对齐</li>
 *   <li>{@code zoneId}:所属校区 / 区域 ID(对应 F-2 {@code SessionStage#ZONE})</li>
 *   <li>{@code cuisineId}:所属菜系 ID(对应 F-2 {@code SessionStage#CUISINE})</li>
 *   <li>{@code name}:商户展示名</li>
 *   <li>{@code tags}:逗号分隔的标签字符串(降级推荐按标签打分时使用,F-3 引入)</li>
 *   <li>{@code heatScore}:热度分缓存(F-4 凌晨算完回写;运行时白读不写)</li>
 * </ul>
 *
 * <p>不带 Lombok,字段全部 private + 显式 getter/setter + 无参构造,符合 F-1 起的项目惯例。
 *
 * @author meisijiya
 */
@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "zone_id", length = 64, nullable = false)
    private String zoneId;

    @Column(name = "cuisine_id", length = 64, nullable = false)
    private String cuisineId;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "tags", length = 1024)
    private String tags;

    @Column(name = "heat_score")
    private Double heatScore;

    /** JPA 必需的无参构造。 */
    public Merchant() {
    }

    /** 全字段构造(测试 / 种子数据使用)。 */
    public Merchant(String id, String zoneId, String cuisineId, String name, String tags, Double heatScore) {
        this.id = id;
        this.zoneId = zoneId;
        this.cuisineId = cuisineId;
        this.name = name;
        this.tags = tags;
        this.heatScore = heatScore;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public String getCuisineId() {
        return cuisineId;
    }

    public void setCuisineId(String cuisineId) {
        this.cuisineId = cuisineId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public Double getHeatScore() {
        return heatScore;
    }

    public void setHeatScore(Double heatScore) {
        this.heatScore = heatScore;
    }
}