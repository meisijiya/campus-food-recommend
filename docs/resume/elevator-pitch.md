# Elevator Pitch · 1-2 分钟项目介绍

> **用法**:面试开场前 90 秒用。3 hook 设计:技术深度 → 业务价值 → 可扩展性, 适配不同导向面试官。
> **节奏**:慢速(每句停 0.5s)完整版 90 秒, 极简版 30 秒(递简历时)。
> **学生身份口径**:"独立设计 / 实践"为主, 不用"主导 / 团队 / 负责"。

---

## 完整版(90 秒, 推荐使用)

> 我做了一个**校园美食推荐平台后端 demo**(独立个人项目), 从 0 到 1 完整复现了 4 条工程实践 bullet, 每条都有可量化的压测 evidence, 不是凭感觉写的简历。
>
> **技术深度层面**(工程导向):
> **独立设计 Redis 状态机 + Skill 模块**, 实践 LLM Context Engineering —— 4 阶段强约束 `INIT → ZONE → CUISINE → MERCHANT`, SkillRegistry 按 stage 注入数据, **prompt token 19098 → 34, 降幅 99.8%**; **独立设计推荐接口结构化校验, 实现 Graceful Degradation** —— JSON Schema 严格校验 + 失败回拼 user 反思重试 ≤2 次 + RuleBasedFallbackAdvisor 按商户标签打分兜底, **bench 100 样本首次合规率 100%**; **独立设计 Cache Warming + Consistent Sharding 体系** —— 凌晨 cron3:00 算热度 Top N(加权 `0.6×订单 + 0.4×点赞`) + JVM 装配层级 JSON + Redis 按 zone 分片预热, **locust 100 并发 `GET /api/merchant/:id` P99 41ms → 15ms, 降幅 63%**; **基于 Idempotency Key + Cache Aside 模式实现点赞幂等 + 三级降级** —— `SET NX EX 60` 拦截 + RabbitMQ 批量落库 + DLX 兜底, 严格 `L0 Caffeine → L1 Redis → L2 MySQL`, **locust 50/50 混合流量 P99 < 100ms(78/76ms), 26158 reqs 0 fail**。
>
> **业务价值层面**(产品导向):
> 用户聊天式检索附近美食, 4 阶段状态机, AI 按需加载, prompt 从 **19098 token 降到 34 token, 降幅 99.8%**; 推荐接口有 JSON Schema 校验 + 反思重试 + 降级 advisor 兜底, AI 抽风不会让用户报错; 点赞接口 60s 幂等不依赖 DB, 三级缓存降级 + RabbitMQ 异步落库, 高并发下 P99 仍 < 100ms, **34981 → 26158 reqs 0 失败**。
>
> **可扩展性层面**(架构导向):
> 状态全在 Redis, 应用层完全无状态, 水平扩容只加 Nginx upstream 实例; Tomcat 800 线程调参 + JMeter 60s 压测单节点 **5243 RPS**, 0 err; Cache 失效策略、限流桶参数、分布式锁 TTL 都在 `application.yml` 可配, 不写死; 4 个 ticket 都有 ADR 文档记录设计取舍。
>
> 工程化: 25 个 Java 主类, 250+ 单元测试, 15 集成测试用 Testcontainers, GitHub Actions 3 个 workflow 自动跑 build + bench + release。简历每条 bullet 都有对应的 commit hash + 压测 evidence + 文档锚点, 不空喊。

---

## 极简版(30 秒, 递简历时)

> 做了个 **Spring Boot 3 + JDK 21** 的校园美食推荐平台后端 demo(独立个人项目), 4 条工程实践 bullet, 每条都有量化压测 evidence。
>
> 最硬的一条: Context Engineering 把 prompt token **19098 → 34, 降幅 99.8%**; 加 JSON Schema 校验 + 反思重试, **首次合规率 100%**。

---

## 3 Hook 设计(选用一种)

### Hook A · 技术深度(给工程导向面试官)

**重点**:架构选型 + 量化数字 + 工程化指标

> "独立设计 Redis 状态机 + Skill 模块 + Cache Warming + Consistent Sharding + Idempotency Key + Cache Aside, 25 个 Java 类, 250+ 单测, 15 集成测试, JMeter 5243 RPS, locust P99 < 100ms(26158 reqs 0 fail), Tomcat 800 线程, JJWT 无状态鉴权, Nginx least_conn, Docker Compose 5 服务一键起, bench 100 样本首次合规率 100%"

**适配**:技术面、字节 / 阿里 / 美团后端岗

---

### Hook B · 业务价值(给产品导向面试官)

**重点**:用户场景 + 数据降本 + 鲁棒性

> "学生聊天式检索附近美食, 4 阶段状态机, AI 按需加载, **prompt 从 19098 token 降到 34 token, 降幅 99.8%**; 推荐接口有 JSON Schema 校验 + 反思重试 + 降级 advisor 兜底, AI 抽风不会让用户报错; 点赞 60s 幂等不依赖 DB, 高并发下 P99 < 100ms"

**适配**:产品 / 全栈岗、面试官偏 PM 风格

---

### Hook C · 可扩展性(给架构导向面试官)

**重点**:架构原则 + 演进位 + 配置化

> "独立设计无状态应用层, 状态全在 Redis, 水平扩容只加 Nginx upstream 实例; JMeter 5243 RPS, 0 err; Cache 失效策略 + 限流桶参数 + 分布式锁 TTL 都在 yaml 可配, 不写死; 4 个 ticket 都有 ADR 文档; **Context Engineering + Graceful Degradation + Cache Warming + Idempotency Key + Cache Aside** 等热词全部落地"

**适配**:架构岗、面试官偏 SRE / 基础架构

---

## 应变话术(被反问时切换)

| 面试官反应 | 切换策略 |
|---|---|
| "做这个花了多久" | "从 0 到 1 大约 12 个工作日, 4 张核心 ticket 分批落地, 每张都有 commit hash + 压测 evidence, 不浮夸" |
| "能讲讲某条 bullet 的细节吗" | 切到 cheat-sheet.md 对应行的「回答思路」+「关联代码」, 不要硬撑 |
| "跟生产级外卖系统差距在哪" | "demo 是为了复现工程实践 bullet, 不是生产级; 生产需要加监控告警链路 / 灰度 / 容灾, 这是 demo 范围内的取舍) |
| "你最得意的设计是哪个" | **F-5 点赞幂等 + 三级降级**(同时练到了 Redis 原子 + MQ 批量 + DLX + Caffeine 多级, 一个 ticket 练 4 个技术点) 或 **F-2 Context Engineering**(LLM 时代的核心思路 + 状态机 + Skill 模块, 是当下最热的 buzzword) |
| "你最想改进的是什么" | "Bullet 3 的 P95 -14% partial-pass 想再压一次(JMX 计划加 pre-warm phase, 排除本地 docker 同机绝对延迟太小的噪声);Bullet 4 的 P99 数字在新 burst=100000 下变高(78ms vs 28ms), 想找机会做 Caffeine 预热后再跑一次" |
| "这是商业项目吗" | "是独立个人项目, 不是公司实习产出;demo 范围内能做完整 bullet 量化, 真实业务需结合公司上下文" |

---

## 练习建议

1. **录音**:自己说一遍 90 秒完整版 + 30 秒极简版, 计时 + 听回放调整节奏
2. **朋友扮演面试官**:朋友问"这条 bullet 怎么测的", 用 cheat-sheet.md 的对应行答, 不卡壳
3. **考前 5 分钟过一遍**:cheat-sheet.md 单页 A4, 重点看「通用救场话术」段
4. **demo 演练**:`bash scripts/interview-demo.sh` 跑通 5 endpoint, 5 秒内可演示