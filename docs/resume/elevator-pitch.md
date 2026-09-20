# Elevator Pitch · 1-2 分钟项目介绍

> **用法**：面试开场前 90 秒用。3 hook 设计：技术深度 → 业务价值 → 可扩展性, 适配不同导向面试官。
> **节奏**：慢速（每句停 0.5s）完整版 90 秒, 极简版 30 秒（递简历时）。

---

## 完整版（90 秒, 推荐使用）

> 我做了一个**校园美食推荐平台后端 demo**, 从 0 到 1 完整复现了 7 条工程实践 bullet, 每条都有可量化的压测 evidence, 不是凭感觉写的简历。
>
> **技术深度层面**（工程导向）:
> 主导了无状态架构搭建 —— Spring Boot 3.5 + JDK 21 + Nginx `least_conn`, 单节点 JMeter **稳态 5243 RPS**, 3 次 60s 复测均值, 0 错误, 平均 85ms; 缓存层做了 **Caffeine L0 + Redis L1 + MySQL L2 三级降级**, locust 50/50 混合流量 P99 **28ms**; 限流做了 **Redis Lua 双层令牌桶**, Redis 挂时切 Caffeine lock-free CAS 桶进程内降级; 分布式锁做了 **Redis SETNX + 看门狗续期**, ownerToken Lua 校验防误删。
>
> **业务价值层面**（产品导向）:
> 用户登录后走 4 阶段状态机 `INIT → ZONE → CUISINE → MERCHANT`, 每阶段 AI 按需加载数据, 不冗余发送全量目录; 同推荐任务 prompt 从 **19098 token 降到 34 token**, 降幅 99.8%; 推荐接口用 JSON Schema 严格校验 + 反思重试 2 次, 失败有降级 advisor 兜底, 不会因为 AI 输出不合规就让用户报错。
>
> **可扩展性层面**（架构导向）:
> 状态全在 Redis, 应用层完全无状态, 水平扩容只加 Nginx upstream 实例; Tomcat 800 线程调参 + 架构预留了读写分离演进位; Cache 失效策略、限流桶参数、分布式锁 TTL 都在 `application.yml` 可配, 不写死; 7 个 ticket 都有 ADR 文档记录设计取舍。
>
> 工程化: 25 个 Java 主类, 250+ 单测, 15 集成测试用 Testcontainers, GitHub Actions 3 个 workflow 自动跑 build + bench + release。简历每条 bullet 都有对应的 commit hash + 压测 evidence + 文档锚点, 不空喊。

---

## 极简版（30 秒, 递简历时）

> 做了个 **Spring Boot 3 + JDK 21** 的校园美食推荐平台后端 demo, 7 条工程实践 bullet, 每条都有量化压测 evidence。
>
> 最硬的一条: 单节点稳态 **5243 RPS**, 3 次 JMeter 复测均值, 0 错误, 85ms 平均延迟。

---

## 3 Hook 设计（选用一种）

### Hook A · 技术深度（给工程导向面试官）

**重点**：架构选型 + 量化数字 + 工程化指标

> "25 个 Java 类, 250+ 单测, 15 集成测试, JMeter 5243 RPS, locust P99 28ms, Tomcat 800 线程, JJWT 无状态鉴权, Nginx least_conn, Docker Compose 5 服务一键起"

**适配**：技术面、字节 / 阿里 / 美团后端岗

---

### Hook B · 业务价值（给产品导向面试官）

**重点**：用户场景 + 数据降本 + 鲁棒性

> "用户聊天式检索附近美食, 4 阶段状态机, AI 按需加载, prompt 从 19098 token 降到 34 token, 降幅 99.8%; 推荐接口有降级 advisor 兜底, AI 抽风不会让用户报错"

**适配**：产品 / 全栈岗、面试官偏 PM 风格

---

### Hook C · 可扩展性（给架构导向面试官）

**重点**：架构原则 + 演进位 + 配置化

> "无状态 + Redis 全状态 + Tomcat 800 线程, 水平扩容只加实例; 架构预留读写分离演进位, Cache 失效策略 + 限流桶参数 + 分布式锁 TTL 都在 yaml 可配, 不写死; 7 个 ticket 都有 ADR 文档"

**适配**：架构岗、面试官偏 SRE / 基础架构

---

## 应变话术（被反问时切换）

| 面试官反应 | 切换策略 |
|---|---|
| "做这个花了多久" | "从 0 到 1 大约 12 个工作日, 7 张 ticket 分批落地, 每张都有 commit hash + 压测 evidence, 不浮夸" |
| "能讲讲某条 bullet 的细节吗" | 切到 cheat-sheet.md 对应行的「回答思路」+「关联代码」, 不要硬撑 |
| "跟生产级外卖系统差距在哪" | "demo 是为了复现工程实践 bullet, 不是生产级; 生产需要加监控告警链路 / 灰度 / 容灾, 这是 demo 范围内的取舍" |
| "你最得意的设计是哪个" | **F-5 点赞幂等 + 多级加速**（同时练到了 Redis 原子 + MQ 批量 + DLX + Caffeine 多级, 一个 ticket 练 4 个技术点） 或 **F-4 离线预热**（凌晨 cron + 加权公式 + 层级 JSON + Redis 分片） |
| "你最想改进的是什么" | "F-1 JMX 计划加 30s pre-warm phase, 避免下次 cold start 异常误判 bullet 不成立; F-11 default flag 改成 ALL_ON 让 4 业务指标 demo 现场全可见" |

---

## 练习建议

1. **录音**：自己说一遍 90 秒完整版 + 30 秒极简版, 计时 + 听回放调整节奏
2. **朋友扮演面试官**：朋友问"这条 bullet 怎么测的", 用 cheat-sheet.md 的对应行答, 不卡壳
3. **考前 5 分钟过一遍**：cheat-sheet.md 单页 A4, 重点看「通用救场话术」段
4. **demo 演练**：`bash scripts/interview-demo.sh` 跑通 5 endpoint, 5 秒内可演示