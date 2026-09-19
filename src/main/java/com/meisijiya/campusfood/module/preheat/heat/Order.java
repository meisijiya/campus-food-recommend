package com.meisijiya.campusfood.module.preheat.heat;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 订单实体(F-4 数据层)— 商户热度计算的输入源之一(订单权重 0.6)。
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@code id}:自增主键,DB 内部使用</li>
 *   <li>{@code studentId}:下单学生 ID(F-1 鉴权用户)</li>
 *   <li>{@code merchantId}:被下单的商户 ID(对应 {@link Merchant#getId()})</li>
 *   <li>{@code createdAt}:下单时间(F-4 计算当前取全量;F-5 起按时间窗裁剪)</li>
 * </ul>
 *
 * <p>表名显式声明为 {@code orders},避开 JPA 默认 {@code order} 在不同数据库方言里的保留字风险。
 *
 * @author meisijiya
 */
@Entity
@Table(name = "orders")
class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "student_id", length = 64, nullable = false)
    private String studentId;

    @Column(name = "merchant_id", length = 64, nullable = false)
    private String merchantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 必需的无参构造。 */
    Order() {
    }

    /** 全字段构造(测试 / 种子数据使用)。 */
    Order(String studentId, String merchantId, Instant createdAt) {
        this.studentId = studentId;
        this.merchantId = merchantId;
        this.createdAt = createdAt;
    }

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    String getStudentId() {
        return studentId;
    }

    void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    String getMerchantId() {
        return merchantId;
    }

    void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}