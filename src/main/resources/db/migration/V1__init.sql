-- V1__init.sql
-- F-4:数据层初始化 — merchants / orders / likes 三张表。
-- 兼容 MySQL 8 与 H2(MODE=MySQL):避免 MySQL 8 才有的语法,索引命名保持 ASCII。

CREATE TABLE merchants (
    id VARCHAR(64) PRIMARY KEY,
    zone_id VARCHAR(64) NOT NULL,
    cuisine_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    tags VARCHAR(1024),
    heat_score DOUBLE DEFAULT 0
);

CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE likes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_orders_merchant ON orders(merchant_id);
CREATE INDEX idx_likes_merchant ON likes(merchant_id);