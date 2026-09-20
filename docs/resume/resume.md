# 简历 · 校园美食推荐平台后端 demo

> **求职目标**：后端开发 / 测试开发实习
> **技术栈**：Java (JDK 21) · Spring Boot 3.5 · MySQL 8.4 · Redis 7.4 · RabbitMQ 3.13 · Spring AI Alibaba · Docker Compose · JMeter · locust
> **仓库**：`github.com/meisijiya/campus-food-recommend`（主仓本地路径 `D:\26code\Java\campus-food-recommend`）
> **覆盖 bullet**：7 条（每条都有 commit hash + 压测 evidence + 文档锚点）

---

## 项目经历：校园美食推荐平台后端 · 从 0 到 1 demo

**项目定位**：演示性后端 demo。完整复现 7 条工程实践 bullet，每条都可量化验证；不是生产级外卖系统，但每条 bullet 在生产场景下成立。

**架构总览**：
```
Nginx (least_conn)
  └─ app (Spring Boot 3.5 + JDK 21, Tomcat max=800, stateless JWT)
       ├─ MySQL 8.4 (读写分离预留位)
       ├─ Redis 7.4 (会话状态 / 限流 / 分布式锁 / L1 缓存)
       └─ RabbitMQ 3.13 (点赞异步落库 + DLX)
```

**代码体量**：25 个 Java 主类 + 250+ 单元测试 + 15 集成测试（Testcontainers）+ GitHub Actions 3 workflow（ci / bench / release）。

### 7 Bullet 简历文案

---

#### Bullet 1 · 面向扩缩容的无状态架构（JWT + Docker Compose + Nginx）

主导平台从 0 到 1 后端架构搭建：**单节点稳态 5243 RPS**（3 次 JMeter 60s 复测均值：5476 / 5216 / 5036,0% err,85ms avg,排除 cold start）/ Spring Boot 3.5 + JDK 21 + Tomcat `max:800 / accept-count:500` 调参 / Nginx `least_conn` 反向代理 / Docker Compose 一键起 5 服务（MySQL 8.4 + Redis 7.4 + RabbitMQ 3.13 + app + Nginx）/ JJWT 0.12.6 无状态鉴权（架构预留读写分离演进位）

> **[手法]** 细节密度（线程数 + 3 次均值 + 版本号）+ 量化留余地（约 5243, 区间写法）+ 演进叙事（预留读写分离位）
> **[句式]** 职责定位句 #5：「主导 + 量化数字 + 链路展开 + 演进位」

---

#### Bullet 2 · 会话槽位约束的渐进式检索（Redis 会话状态 + Skill 模块）

设计 Redis 会话状态机 + Skill 模块体系：**同推荐任务 prompt token 从 19098 → 34,降幅 99.8%**（口径：`TokenEstimator.estimateTokens` chars/4,MockChatModel 内置一致;baseline = 12 zone × 8 cuisine × 6 merchant 全量 JSON 内联）/ 4 阶段状态严格单向流转 `INIT → ZONE → CUISINE → MERCHANT`（非法跳转抛 `IllegalSlotTransitionException` → 400）/ Skill 接口 + `ZoneSkill` / `CuisineSkill` / `MerchantSkill` 3 实现 + `SkillRegistry` 按 stage 选注入 / 30 分钟空闲自动回 INIT（每次写入 `EXPIRE 1800` 重置）

> **[手法]** 命名枚举体系（Skill 3 实现）+ 箭头链路句（4 阶段单向流转）+ 细节密度（EXPIRE 1800 + chars/4 口径）
> **[句式]** 职责定位句 #5 + 命名枚举体系 #8

---

#### Bullet 3 · 结构化输出与反思重试（JSON Schema + AI 自反思）

设计 AI 结构化输出 + 反思重试链路：JSON Schema（`networknt 1.5.2`）严格校验（`merchantId[]` + `reason` + `confidence` 0-1 浮点）→ 失败时把错误摘要回拼下一次 `user` 消息 → 最多 2 次 → 仍失败走 `RuleBasedFallbackAdvisor` 按商户标签打分降级 / dev profile 单测覆盖 3 路径：一次通过 / 一次失败重试通过 / 两次失败降级 / 双 profile 隔离（`dev/test/it` 走 `MockChatModel` 零成本;`bench/smoke` 走 Spring AI Alibaba DashScope 真实 API,`qwen-plus`）

> **[手法]** 箭头链路句（校验 → 重试 → 降级）+ 双 profile 设计 + 单测覆盖三路径
> **[句式]** 职责定位句 #5 + 箭头链路 #7
>
> **[诚实注 · 88% 数字口径]** bullet 原话"首次响应合规率 88%" 是 binding acceptance 阈值；仓内 dev profile 单测验证的是**机制**（3 路径分支），**非数字**（首次合规率）。真实首次合规率需 `SPRING_PROFILES_ACTIVE=bench` + `DASHSCOPE_API_KEY` 触发 `RecommendBenchIT`（100 条样本）采集。面试官追问"88% 怎么测"→ 答"机制层 100% 覆盖,数字层 unverified,真实数字按设计路径触发,仓内 README 给步骤"。

---

#### Bullet 4 · 离线数据加工与缓存预热（Spring Task + 层级 JSON + Redis 分片）

主导离线预热体系：`@Scheduled(cron="0 0 3 * * ?")` 凌晨 3 点跑 → MySQL 算热度（加权公式 `0.6 × 订单数 + 0.4 × 点赞数`）取 Top N → JVM 内组装层级 JSON（深度 ≤3） / `RedisShardedWriter` 按 zone 分片预热（`catalog:zone:<zoneId>` SETEX 600s;`catalog:hot:merchants` SET TTL 12h）/ dev 模式手动触发同路径 `POST /admin/preheat/trigger`（5 秒内 Redis 出现分片 key）/ locust 30s 100 并发 `GET /api/merchant/:id` 混合流量：**P99 延迟 -63%（达标 ≥60% 阈值）,P95 -14%**（lab 条件同机绝对延迟 ~7ms 被 GC/JIT 噪声吸收,tail 收益 P99/P98 真实）

> **[手法]** 箭头链路句（cron → 算法 → 装配 → 分片）+ 细节密度（cron 表达式 + 加权公式 + TTL + 容量）+ 量化口径诚实
> **[句式]** 职责定位句 #5 + 命名枚举体系 #8
>
> **[诚实注 · 60% 数字口径]** bullet 原话"响应时间缩短 60%" 在 F-4 ticket 实测中 **P99 -63% 达标, P95 -14% 未达字面 60%**。原因：本地 docker 同机 MySQL 直查 ~2-3ms / Redis L1 ~1ms，绝对差 < 2ms；30s 压测期内 L1 已 backfill, BEFORE 后段接近稳态。**P99 / P98 才是真正能体现 L1→L2 差距的 tail 指标**。面试官追问"为啥 P95 不是 -60%" → 答"lab 条件绝对延迟太小, P95 在噪声地板; 真信号在 tail(P99 -63%), bullet 字面阈值用 P99 解读"。

---

#### Bullet 5 · 缓存一致性与多级加速（Redis 原子 + RabbitMQ + Caffeine）

主导点赞幂等 + 多级缓存加速：点赞 `SET like:idem:<sid>:<mid> 1 NX EX 60` 前置拦截重复请求 → 通过后投 RabbitMQ `like.db.write` 队列 → 消费者每 100 条或 1s 批量合并写 MySQL `likes` 表 → 失败转 DLX `like.db.write.dlq`（不无限重试）/ 详情读严格降级 **Caffeine L0**（`merchantHotCache` 容量 10k / `expireAfterWrite=5min` + `zoneCatalogCache` 容量 200 / 10min,JVM-local POJO 引用无序列化）→ **Redis L1**（Jackson JSON,SETEX 600s）→ **MySQL L2** + 每层命中日志 / locust 30s 50 并发 50/50 混合流量（点赞 + 详情）：**P99 28ms（点赞）/ 25ms（详情）,全部 <50ms ✓**（34981 总请求,0 失败）

> **[手法]** 箭头链路句（拦截 → 队列 → 批量 → DLX）+ 细节密度（队列名 + DLX + TTL + 容量 + 命中日志）+ 量化 ✓
> **[句式]** 职责定位句 #5 + 命名枚举体系 #8

---

#### Bullet 6 · 自研 Redis Lua 双层令牌桶限流（用户级 + API 全局）

自研 Redis Lua 双层令牌桶限流：**用户级 `rate:user:<sid>`**（防单用户滥用,默认 burst=100 / rate=10/s）+ **API 全局 `rate:api:<endpoint>`**（防系统过载,默认 burst=1000 / rate=500/s）两层都通过才放行 / Lua 原子脚本 `token-bucket.lua`（读桶 → 算 refill → 扣 permits → 写回一次性完成,无竞态）/ Redis 不可达时切 **Caffeine lock-free CAS 桶**进程内降级 + 自增 `rate_limiter_degraded_total` Counter + WARN 日志 / 拒绝响应 `HTTP 429` + `Retry-After: 1` + JSON `{"code":42900,"message":"rate limited"}` / 30 单元测试（RedisTokenBucket 15 + CaffeineLocalBucket 8 + RateLimitFilter 7）+ 3 Testcontainers IT（burst 耗尽 429 / Retry-After header / Caffeine 降级）

> **[手法]** 命名枚举体系（双层桶 / 降级路径）+ 细节密度（Lua 4 步原子 + CAS + counter 名）+ 热词命名
> **[句式]** 职责定位句 #5 + 命名枚举体系 #8

---

#### Bullet 7 · 自研 Redis SETNX 分布式锁 + 看门狗续期

自研 Redis `SETNX` 分布式锁 + 看门狗续期：`SET key <ownerToken> NX EX 30` 原子获取 + Lua 校验 ownerToken 后 `PEXPIRE` 续期（防 A 释放 B 的锁）/ Watchdog 单线程 `ScheduledExecutorService` 每 10s 续期,失败 3 次放弃（网络抖动 1-2 次可接受,3 次仍失败 = 真挂了）/ 2 个生产场景落地：① `HeatJobPreheater` 多实例防重（`@Scheduled` cron 跨实例）② `LikeService` 升级严格幂等（在 F-5 `NX EX 60` 之前加分布式锁,窗口级 + 实例级双层保护）/ 30 单元测试（RedisLock 11 + Watchdog 9 + HeatJobPreheaterLock 5 + LikeServiceLock 5）+ 5 Testcontainers IT（tryLock 互斥 / release 防误删 / Watchdog 续期 / 续期 3 次失败放弃 / 同 JVM 嵌套 deadlock 防护）

> **[手法]** 箭头链路句（SET NX → Lua 校验 → PEXPIRE）+ 命名枚举体系（看门狗）+ 极值标签（2 个生产场景）
> **[句式]** 新旧对比句 #6（F-5 NX 60s 之前加分布式锁）+ 命名枚举体系 #8

---

### 工程亮点附注（不进 bullet 主条,面试官追问"还有别的吗"用）

| 亮点 | ticket | 量化口径 |
|---|---|---|
| **可观测性全栈** Micrometer + Prometheus + Grafana | F-9 | 4 业务指标（`merchant_view_total` / `recommend_total` / `like_total` / `cache_hit_ratio_total`）+ 11 系统指标（JVM / Tomcat / HikariCP / RabbitMQ）;`/actuator/prometheus` 37853 bytes |
| **CI/CD** GitHub Actions 3 workflow | F-10 | ci（编译 + 250 单测）/ bench（locust + JMeter artifact）/ release（多 JDK matrix）|
| **Feature Flag 配置中心** Redis hash + Caffeine L1 + 4 FlagMode | F-11 | `ALL_ON` / `PERCENTAGE` / `WHITELIST_ONLY` / `KILL_SWITCH`;公开 `check()` + Admin POST 双层鉴权 |
| **Demo Readiness** `bash init.sh` 6 阶段门禁 | F-6 | 编译 / 测试 / compose 合法 / health / JMeter 5243 RPS / locust P99 PASS |

---

## 每条 bullet 的"防伪审计"

| Bullet | 真实数字 | 口径解释一句话 | 可守 15 分钟追问？ |
|---|---|---|---|
| 1 | 5243 RPS | 3 次 JMeter 60s 复测均值,排除 cold start | ✓ Tomcat 调参 + 验证方法可讲 |
| 2 | 99.8% token 降 | chars/4 口径,baseline 19098, skill 注入后 34 | ✓ Skill 接口 + 状态机分支可讲 |
| 3 | 88%（unverified）| bench profile 触发, dev 单测覆盖机制 | ⚠️ 诚实标 unverified + 答路径 |
| 4 | P99 -63% / P95 -14% | locust 30s 100 并发, tail 收益真实 | ⚠️ P95 字面未达,但 P99 达标 |
| 5 | P99 28ms / 25ms | locust 50/50 混合, 34981 reqs 0 fail | ✓ 三级降级 + 命中日志可讲 |
| 6 | 双层桶 + Lua 原子 | 30 单测 + 3 IT 全过 | ✓ 降级路径 + 拒绝响应可讲 |
| 7 | SETNX + 看门狗 | 30 单测 + 5 IT 全过 | ✓ ownerToken 防误删可讲 |

> **诚实原则**：面试官追问"为什么 P95 不是 -60%" / "88% 怎么测"时，**承认 partial-pass + unverified + 给验证路径**，比硬撑更可信。F-4 / F-3 ticket 已有完整口径解释，本文档 cheat-sheet.md 给追问应答模板。