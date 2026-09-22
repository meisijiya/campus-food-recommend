# 简历 · 校园美食推荐平台后端 demo

> **求职目标**:后端开发 / 测试开发实习(大四秋招)
> **技术栈**:Java (JDK 21) · Spring Boot 3.5 · MySQL 8.4 · Redis 7.4 · RabbitMQ 3.13 · Spring AI Alibaba · Docker Compose · JMeter · locust
> **仓库**:`github.com/meisijiya/campus-food-recommend`
> **覆盖 bullet**:4 条(每条都有 commit hash + 压测 evidence + 文档锚点;学生身份口径"独立设计 / 实践")

---

## 项目经历:校园美食推荐平台后端 · 从 0 到 1 demo

**项目定位**:演示性后端 demo(独立个人项目)。完整复现 4 条工程实践 bullet,每条都可压测验证;不是生产级外卖系统,但每条 bullet 在生产场景下成立。

**项目描述**:Spring Boot 3.5 + JDK 21 后端 demo;Docker Compose 一键部署 5 服务(MySQL 8.4 / Redis 7.4 / RabbitMQ 3.13 / app / Nginx);JMeter 60s 压测单节点 QPS **5243**,0 err,85ms avg;`bash init.sh` 6 阶段门禁自动化(GitHub Actions 3 workflows);250+ 单元测试 + 15 Testcontainers 集成测试。locust P99 < 100ms(50/50 混合 26158 reqs 0 fail)。

**架构总览**:
```
Nginx (least_conn)
  └─ app (Spring Boot 3.5 + JDK 21, Tomcat max=800, stateless JWT)
       ├─ MySQL 8.4 (读写分离预留位)
       ├─ Redis 7.4 (会话状态 / 限流 / 分布式锁 / L1 缓存)
       └─ RabbitMQ 3.13 (点赞异步落库 + DLX)
```

**代码体量**:25 个 Java 主类 + 250+ 单元测试 + 15 集成测试(Testcontainers)+ GitHub Actions 3 workflow(ci / bench / release)。

### 4 Bullet 简历文案

---

#### Bullet 1 · Context Engineering · token ↓99.8%
**独立设计** Redis 状态机 + Skill 模块,实践 LLM Context Engineering:4 阶段强约束 `INIT → ZONE → CUISINE → MERCHANT`,SkillRegistry 按 stage 注入数据;**同任务 prompt token 19098 → 34,降幅 99.8%**(baseline = 12 zone × 8 cuisine × 6 merchant = 576 商户全量 JSON 内联;口径:`TokenEstimator.estimateTokens` chars/4,与 MockChatModel 内置一致)。

> **[手法]** Context Engineering 热词 + 状态机约束 + Skill 模块抽象 + 4 阶段单向流转箭头
> **[句式]** 职责定位句「独立设计 + 解决什么问题 + 量化结果」
> **[evidence]** `evidence/f2-token-reduction.json` + `tools/token-counter.py` + `module/catalog/skill/{Skill,ZoneSkill,CuisineSkill,MerchantSkill}.java`

---

#### Bullet 2 · 结构化校验 · 首次合规率 100%
**独立设计**推荐接口结构化校验,**实现 Graceful Degradation**:JSON Schema(`networknt 1.5.2`)严格校验(`merchantId[]` + `reason` + `confidence` 0-1)→ 失败把错误摘要回拼 user 消息 → 反思重试 ≤2 次 → 仍失败走 `RuleBasedFallbackAdvisor` 按商户标签打分,100% 返回合规 JSON;**bench 100 样本首次合规率 100%**(F-14.1 amend 后 qwen-plus + JSON Object mode + prompt 强化)。

> **[手法]** Graceful Degradation 热词 + 箭头链路(校验→重试→降级)+ 双 profile 隔离
> **[句式]** 职责定位句「独立设计 + 实现 XXX + 量化结果」
> **[evidence]** `evidence/f3-compliance-rate.json` + `module/recommend/advisor/{ReflectiveRetryAdvisor, RuleBasedFallbackAdvisor}.java`

---

#### Bullet 3 · Cache Warming + Consistent Sharding · P99 -63%
**独立设计** Cache Warming + Consistent Sharding 体系,**解决热点读数据库瓶颈**:`@Scheduled(cron="0 0 3 * * ?")` 凌晨跑 → MySQL 算热度 Top N(加权公式 `0.6×订单数 + 0.4×点赞数`)→ JVM 内组装层级 JSON(深度 ≤3)→ `RedisShardedWriter` 按 zone 分片预热(`catalog:zone:<zoneId>` SETEX 600s;`catalog:hot:merchants` SET TTL 12h);**locust 100 并发压测 `GET /api/merchant/:id`,P99 延迟 41ms → 15ms,降幅 63%**(P95 -14% partial-pass,因 lab 条件同机绝对延迟 ~7ms 被 GC/JIT 噪声吸收,tail 收益 P99/P98 真实)。

> **[手法]** Cache Warming / Consistent Sharding 热词 + 箭头链路(cron → 加权公式 → 装配 → 分片)+ 诚实注(P95 partial-pass)
> **[句式]** 职责定位句「独立设计 + 解决什么问题 + 量化结果」
> **[evidence]** `evidence/f4-p95-{before,after}_stats.csv` + `module/preheat/{HeatJobPreheater, MerchantHeatCalculator, RedisShardedWriter, CatalogHierarchyAssembler}.java`

---

#### Bullet 4 · Idempotency Key + Cache Aside · P99 <100ms
**基于 Idempotency Key + Cache Aside 模式**,实现点赞幂等 + 三级降级,**解决高并发重复写与缓存击穿**:点赞 `SET like:idem:<sid>:<mid> 1 NX EX 60` 前置拦截 60s 重复请求(不依赖 DB)→ 通过后投 RabbitMQ `like.db.write` 队列 → 消费者每 100 条或 1s 批量合并写 MySQL → 失败转 DLX `like.db.write.dlq`(不无限重试);详情读严格三级降级 **Caffeine L0**(JVM-local POJO 引用,无序列化)→ **Redis L1**(Jackson JSON,SETEX 600s)→ **MySQL L2** + 每层命中日志;**locust 50/50 混合流量 P99 < 100ms(78ms like / 76ms merchant),26158 总请求 0 失败**(刷新证据 `evidence/f5-p99_stats.csv`,burst=100000 临时覆盖,绕开 F-7 限流看真实业务 P99)。

> **[手法]** Idempotency Key / Cache Aside 热词 + 箭头链路(拦截→队列→批量→DLX)+ 命名枚举(L0/L1/L2)
> **[句式]** 职责定位句「基于 XX 模式 + 实现 XXX + 解决什么问题 + 量化结果」
> **[evidence]** `evidence/f5-p99_stats.csv` + `module/like/{LikeService, LikeMessageConsumer, LikeController}.java` + `config/{CacheConfig, RabbitMQConfig}.java`

---

### 工程亮点附注(不进 bullet 主条,面试官追问"还有别的吗"用)

| 亮点 | ticket | 量化口径 |
|---|---|---|
| **可观测性全栈** Micrometer + Prometheus + Grafana | F-9 | 4 业务指标(`merchant_view_total` / `recommend_total` / `like_total` / `cache_hit_ratio_total`)+ 11 系统指标(JVM / Tomcat / HikariCP / RabbitMQ);`/actuator/prometheus` 37853 bytes |
| **CI/CD** GitHub Actions 3 workflow | F-10 | ci(编译 + 250 单测)/ bench(locust + JMeter artifact)/ release(多 JDK matrix)|
| **Feature Flag 配置中心** Redis hash + Caffeine L1 + 4 FlagMode | F-11 | `ALL_ON` / `PERCENTAGE` / `WHITELIST_ONLY` / `KILL_SWITCH`;公开 `check()` + Admin POST 双层鉴权 |
| **Demo Readiness** `bash init.sh` 6 阶段门禁 | F-6 | 编译 / 测试 / compose 合法 / health / JMeter 5243 RPS / locust P99 < 100ms |

---

## 每条 bullet 的"防伪审计"

| Bullet | 真实数字 | 口径解释一句话 | 可守 15 分钟追问? |
|---|---|---|---|
| 1 | 99.8% token 降 | chars/4 口径,baseline 19098, skill 注入后 34 | ✓ Skill 接口 + 状态机分支可讲 |
| 2 | 100% 首次合规 | 100 样本, F-14.1 amend 后 qwen-plus + JSON Object mode | ✓ 3 路径分支 + F-14.1 升级点可讲 |
| 3 | P99 -63% / P95 -14% | locust 100 并发, tail 收益真实 | ✓ P95 partial-pass 诚实口径可讲 |
| 4 | P99 < 100ms (78/76ms) | locust 50/50 混合 burst=100000, 26158 reqs 0 fail | ✓ Idempotency Key / Cache Aside 三级降级可讲 |

> **诚实原则**:面试官追问"为什么 P95 不是 -63%" 时,**承认 partial-pass + 给验证路径**,比硬撑更可信。F-4 ticket 已有完整口径解释,cheat-sheet.md 给追问应答模板。