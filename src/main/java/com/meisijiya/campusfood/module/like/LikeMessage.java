package com.meisijiya.campusfood.module.like;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 点赞消息(F-5)— {@code LikeService.like} 通过幂等检查后投到 {@code like.db.write} 队列,
 * 消费者批量落库 MySQL {@code likes} 表。
 *
 * <p>Java 17 record,字段固定三段;Jackson 通过 {@code spring-boot-starter-amqp} 配的
 * {@code Jackson2JsonMessageConverter} 自动序列化,无需手工 getter。
 *
 * <p>三字段语义:
 * <ul>
 *   <li>{@code studentId}:JWT sub(直接当字符串主键,不加密不脱敏 — demo 范围)</li>
 *   <li>{@code merchantId}:被点赞的商户主键</li>
 *   <li>{@code createdAt}:点赞时刻(UTC,Instant)</li>
 * </ul>
 *
 * @author meisijiya
 */
@JsonIgnoreProperties(ignoreUnknown = true)  // Security verifier finding #2:broker 被攻陷时未知字段全部 FAIL 默认进 DLQ,
                                            // fail-closed 安全但噪音大;允许未知字段被丢弃仅 3 核心字段注入。
public record LikeMessage(String studentId, String merchantId, Instant createdAt) {

    /** Compact constructor:防止 null 字段污染消息体 — 任何 null 都视为非法入参,拒绝构造。 */
    public LikeMessage {
        if (studentId == null || studentId.isBlank()) {
            throw new IllegalArgumentException("studentId must be non-blank, got: " + studentId);
        }
        if (merchantId == null || merchantId.isBlank()) {
            throw new IllegalArgumentException("merchantId must be non-blank, got: " + merchantId);
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must be non-null");
        }
    }
}