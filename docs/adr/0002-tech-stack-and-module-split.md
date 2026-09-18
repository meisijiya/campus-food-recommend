# ADR-0002:技术栈与模块拆分

- **状态**: 已采纳
- **日期**: 2026-09-18
- **决策者**: 用户(meisijiya)

## 决策

| 维度 | 选择 |
|---|---|
| 语言 | Java 17 |
| 框架 | Spring Boot 3.x |
| AI 框架 | Spring AI |
| 持久层 | MySQL 8(单实例,预留主从读写分离) |
| 缓存 | Redis 7 + Caffeine(JVM 本地缓存) |
| 消息队列 | RabbitMQ 3.13 |
| 鉴权 | JWT(无状态) |
| 构建工具 | Maven(`com.meisijiya:campus-food-recommend`) |
| 部署 | Docker Compose + Nginx |
| JDK | OpenJDK 17 LTS |

## 模块拆分(Maven multi-module 暂用单 module,预留拆分)

```
com.meisijiya.campusfood
├── CampusFoodApplication.java     // 主类
├── config/                        // 配置(Redis/RabbitMQ/Security 等)
├── module/
│   ├── auth/                      // F-1: JWT 登录、刷新、网关验证
│   ├── catalog/                   // F-2/F-4: 目录数据 + Skill 模块
│   ├── recommend/                 // F-3: Spring AI 集成、结构化查询
│   ├── like/                      // F-5: 幂等点赞、异步落库
│   └── preheat/                   // F-4: Spring Task 定时预热
└── common/                        // 通用响应、异常、工具
```

## 理由

- 与简历技术栈 1:1 对齐,无偏差。
- 模块边界与 ticket 边界同构,代码可读性 ↔ 工单可追溯性 一致。
- 单 module 起步降低脚手架复杂度,后续 Maven 多 module 拆分预留接口隔离即可。

## 后果

- 单 module 阶段所有代码在 `com.meisijiya.campusfood.*` 下,无跨 module 依赖问题。
- AI 集成(Spring AI)需要 API Key,通过 `.env` 注入,`application.yml` 用 `${}` 占位。

## 备选

- 多 module Maven: 工期多 2-3 个 ticket,不必要。
- Gradle: 面试解释成本高,与"主流 Java 后端"画像不符。