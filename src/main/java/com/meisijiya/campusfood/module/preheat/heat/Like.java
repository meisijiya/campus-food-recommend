package com.meisijiya.campusfood.module.preheat.heat;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 点赞实体(F-4 数据层)— 商户热度计算的输入源之一(点赞权重 0.4)。
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@code id}:自增主键</li>
 *   <li>{@code studentId}:点赞学生 ID</li>
 *   <li>{@code merchantId}:被点赞的商户 ID</li>
 *   <li>{@code createdAt}:点赞时间</li>
 * </ul>
 *
 * <p>F-5 起 {@code likes} 表由 RabbitMQ 异步落库批量写入;F-4 阶段主要供凌晨任务读取。
 *
 * @author meisijiya
 */
@Entity
@Table(name = "likes")
public class Like {

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
    Like() {
    }

    /** 全字段构造(F-5 W1 LikeMessageConsumer 异步落库时构造实体使用;测试 / 种子数据也用此)。 */
    public Like(String studentId, String merchantId, Instant createdAt) {
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