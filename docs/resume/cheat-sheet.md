# 面试 Cheat Sheet · 1 页 A4 救命件

> **用法**:面试现场被追问时翻这一页。每行 = 追问 + 答 + 关联代码路径。
> **优先级**:先答口径 / 数字 / 边界条件,再指代码路径,最后给 commit hash 锚点。
> **学生身份口径**:4 条 bullet 全部用"独立设计 / 实践"开篇,不写"主导 / 团队 / 负责"。

---

## Bullet 1 · Context Engineering / token ↓99.8%

| 追问 | 回答思路 | 关联代码 |
|---|---|---|
| 99.8% 怎么算的 | `TokenEstimator.estimateTokens` chars/4 / baseline 19098 = 12 zone × 8 cuisine × 6 merchant = 576 商户全量 JSON 内联 / skill 注入后 34 = `{skill:zone}` 占位符 + 3 stage id / MockChatModel 内置一致 | `tools/token-counter.py` + `evidence/f2-token-reduction.json` + `module/catalog/skill/{Skill,ZoneSkill,CuisineSkill,MerchantSkill}.java` |
| Context Engineering 是什么 | 思路:"不要预先把全量知识塞给 LLM,运行时按上下文查";**和 RAG 同源**(向量检索代替全量文本内联),但这里用的是状态机 + Skill 模块,更适合结构化有限的领域知识,延迟更低、token 更省、可调试 | `CONTEXT.md §2/§3` + `module/recommend/SkillRegistry.java` |
| 状态机非法跳转怎么报 | 抛 `IllegalSlotTransitionException` → `GlobalExceptionHandler` → HTTP 400 + JSON `code=40000` | `module/catalog/session/SessionSlotStateMachine.java` + `common/GlobalExceptionHandler.java` |
| Skill 怎么注入 | `SkillRegistry` 按 stage 选 / prompt 模板只写 `{skill:zone}` 占位符 / 运行时 `Skill.render(ctx)` 拿真实数据 | `module/recommend/SkillRegistry.java` + `module/recommend/RecommendService.java` |
| 30 min 怎么实现 | 每次写入 `EXPIRE 1800` 重置 / Redis key TTL 机制 / 触发读路径时 lazy check 过期 | `module/catalog/session/SessionService.java` |
| 槽位不是冗余吗 | INIT→ZONE→CUISINE→MERCHANT 强约束避免无效请求 + token 省;非法跳转兜底返回错误而非默认值 | `module/catalog/session/SessionController.java` |

---

## Bullet 2 · 结构化校验 / 首次合规率 100%

| 追问 | 回答思路 | 关联代码 |
|---|---|---|
| 100% 怎么测 | bench profile(`SPRING_PROFILES_ACTIVE=bench` + `DASHSCOPE_API_KEY`)触发 `RecommendBenchIT`,**100 样本** 跑出来 `first_attempt_compliance_rate=1.0` + `final_compliance_rate=1.0`;仓内 README 给步骤 | `evidence/f3-compliance-rate.json` + `src/test/java/.../RecommendBenchIT.java`(FIRST_ATTEMPT_TARGET=0.88 阈值,**实测 1.0 超 +12pp**)+ `application-bench.yml` |
| 2 次为啥不更多 | trade-off:更多次 → 延迟 + token 成本翻倍;2 次 + 降级 advisor 是生产经验值(Google SRE 多数 RAG / Agent 系统也用 2-3 次) | `application.yml:advisor.maxRetries`(默认 2)|
| 降级 advisor 怎么做 | `RuleBasedFallbackAdvisor` 按商户标签 + 用户历史打分排序,返回 schema 合规 JSON;fallback 路径**不**走 AI 调用,0 token 成本 | `module/recommend/advisor/RuleBasedFallbackAdvisor.java` + `RuleBasedFallbackAdvisorTest.java`(6 用例)|
| 双 profile 怎么隔离 | `@Profile("!bench & !smoke")` 注入 `MockChatModel` / `@Profile({"bench","smoke"})` 注入 Spring AI Alibaba DashScope / `dev/test/it` 默认走 Mock 零成本 | `config/MockChatModelConfig.java` + `config/DashScopeConfig.java` + AGENTS.md §16.2 |
| Schema 校验细节 | `networknt:json-schema-validator:1.5.2` / `JsonSchemaFactory.getInstance(VersionFlag.V7)` / 失败抛 `SchemaViolationException` 携带错误摘要 | `module/recommend/schema/JsonSchemaValidator.java` + `src/main/resources/schema/recommendation.json` |
| 反思回拼怎么实现 | `BaseAdvisor` 拦截 ChatResponse → schema 校验失败 → 把错误摘要 + 原 prompt 拼回下一轮 `user` 消息;最多 2 次;advisor 顺序:ReflectiveRetry → RuleBasedFallback | `module/recommend/advisor/ReflectiveRetryAdvisor.java` |
| F-14.1 升级了什么 | bullet 数字从 88% → 100%(commit `ca8ac94` amend)的关键改动:qwen-plus model + response_format=json_object + temperature 0.1 + "m-" 前缀提示;**F-14 修了 RBFA chain 越界**(commit `3381694`) | `.scratch/campus-food-recommend/issues/18-F14.1-first-attempt-88pct.md` |

---

## Bullet 3 · Cache Warming + Sharding / P99 -63%

| 追问 | 回答思路 | 关联代码 |
|---|---|---|
| 60% 怎么测的(P99 是 41→15ms -63%,bullet 是 P95 60%) | locust 30s 100 并发 `GET /api/merchant/<random EVM-id>` / before = MySQL 直查 / after = Redis L1 命中 / before 跑前 `redis-cli FLUSHDB` 清空;**实际是 P99 -63% 达标,P95 -14% 部分**(为什么?lab 条件绝对延迟太小) | `evidence/f4-p95-before_stats.csv` + `evidence/f4-p95-after_stats.csv` |
| 为啥 P95 不是 -60% | lab 条件绝对延迟太小(MySQL 直查 2-3ms vs Redis 1ms,绝对差 < 2ms)/ 30s 压测期内 L1 已 backfill, BEFORE 后段接近稳态 L1 hit / P95 7ms→6ms 1ms 之差被 GC/JIT 噪声吸收 / **P99 -63% 才是 cache 价值的真信号**(tail 收敛) | F-4 ticket §5 partial-pass 完整解释 + `evidence/f4-p95-{before,after}_stats.csv` P99 行 |
| 加权公式怎么定的 | `0.6*订单+0.4*点赞` 来自产品经验(订单权重更高,因为消费转化是终极指标)/ Top N 默认 100 / 公式参数化可配 | `module/preheat/MerchantHeatCalculator.java:scoreOf()` + `application.yml:preheat.heat.weight` |
| 凌晨 3 点 cron 怎么配 | `@Scheduled(cron="0 0 3 * * ?")` + `spring.task.scheduling.pool.size=2`(防 cron 阻塞) | `module/preheat/HeatJobPreheater.java:run()` + `application.yml:spring.task.scheduling` |
| 分片 key 怎么设计的 | `catalog:zone:<zoneId>` 按地理分片(防大 key)+ `catalog:hot:merchants` SET 存 Top N(O(log N) 查)+ `catalog:merchant:<id>` 单商户 SETEX 10min(详情读) | `module/preheat/RedisShardedWriter.java` + CONTEXT.md §5 |
| 层级 JSON 深度为啥 ≤3 | zone(1) → cuisine(2) → merchant(3);不超 3 因为 UI 一次性渲染 + 序列化体积可控 | `module/preheat/assembler/CatalogHierarchyAssembler.java:assemble()` |
| Cache Warming vs 实时加载 | 凌晨预热把"明天大概率要查"的热点装进 Redis / 冷启动 / 流量高峰过来时不需要重建缓存 / 配合 Consistent Sharding(按 zone 分片)避免大 key 撑爆 | ADR-0007(取舍记录)+ `module/preheat/HeatJobPreheater.java` |

---

## Bullet 4 · Idempotency Key + Cache Aside / P99 < 100ms

| 追问 | 回答思路 | 关联代码 |
|---|---|---|
| P99 怎么测 | locust 30s 50 并发 50/50 混合流量(点赞 + 详情)/ `uv run locust -f locustfile_mix.py --tags mix-like-detail -u 50 -r 25 -t 30s` / **26158 总请求 0 失败**(临时 burst=100000 跑,绕开 F-7 限流看真实 P99)/ P99 78ms(like)/76ms(merchant) | `evidence/f5-p99_stats.csv`(26158 reqs,0 fail)+ `locustfile_mix.py` |
| 60s 幂等怎么做 | `SET like:idem:<sid>:<mid> 1 NX EX 60` / 第二次返回 `liked:false "already liked"` / 不依赖 DB, Redis 单命令原子 | `module/like/LikeService.java:like()` |
| 批量合并怎么写 | `LikeMessageConsumer` 每 100 条或 1s 触发 batch flush / `LikeRepository.saveAll` 一次落库 / 失败转 DLX | `module/like/LikeMessageConsumer.java` |
| DLX 怎么走 | 业务 catch 异常 → reject → MQ 走 `x-dead-letter-exchange` → 进 `like.db.write.dlq` / 不无限重试(避免放大事故) | `config/RabbitMQConfig.java` |
| Idempotency Key 是什么 | 分布式系统标配术语:用唯一 key(`<sid>:<mid>`)确保操作幂等;Spring 生态 / Stripe / AWS 都用;**60s 窗口期不依赖 DB**, Redis 单命令原子 | `module/like/LikeService.java` + `module/like/LikeServiceIT.java`(3 用例覆盖 60s 窗口)|
| Cache Aside 模式 | 读:cache miss → 读 DB → 写 cache;写:更新 DB → 失效 cache(下次读触发回填);本 demo 严格 L0 → L1 → L2 三级降级 | `module/catalog/MerchantQueryService.java` + `config/CacheConfig.java` |
| 三级降级为啥这样排 | L0 Caffeine JVM-local POJO 引用(最快,无序列化)→ L1 Redis JSON(次快,跨实例共享)→ L2 MySQL(兜底)/ 严格命中日志,debug 可追 | `module/catalog/MerchantQueryService.java` + `config/CacheConfig.java` |
| 为啥 Caffeine 容量这么设 | `merchantHotCache` 10k = 校园场景单日访问商户 1k × 10 buffer / `zoneCatalogCache` 200 = 12 zone × 10 cuisine × N merchant,实测足够 | `config/CacheConfig.java:merchantHotCache()` |
| 命中日志怎么看 | L0/L1/L2 INFO 日志带 key,debug 时 `grep "hit layer" app.log` | `MerchantQueryService.java:findById()` |

---

## 工程亮点延伸(被问"还有别的吗"时)

| 领域 | ticket | 一句话答 | 代码锚点 |
|---|---|---|---|
| 可观测性 | F-9 | Micrometer + Prometheus scrape `/actuator/prometheus`;4 业务 + 11 系统指标 | `module/observability/MicrometerConfig.java` |
| 限流(没进 bullet 但有实现) | F-7 | 自研 Redis Lua 双层令牌桶(用户级 + API 全局),Redis 不可达切 Caffeine CAS 桶 | `module/ratelimit/{RedisTokenBucket, RateLimitFilter}.java` |
| 分布式锁(没进 bullet 但有实现) | F-8 | SETNX + ownerToken Lua 防误删 + Watchdog 看门狗续期,2 个生产场景 | `module/lock/{RedisLock, Watchdog}.java` |
| Feature Flag | F-11 | Redis hash + Caffeine L1 + 4 FlagMode;Admin POST 双层鉴权 | `module/featureflag/FeatureFlagAspect.java` |
| CI/CD | F-10 | GitHub Actions 3 workflow:ci(编译+测试)/ bench(压测 artifact)/ release(多 JDK matrix) | `.github/workflows/{ci,bench,release}.yml` |
| Demo Readiness | F-6 | `bash init.sh` 6 阶段门禁 / `uv run locust` + JMeter 双工具 | `init.sh` + `init.ps1` |

---

## 通用救场话术

| 场景 | 话术 |
|---|---|
| 被追问"这数字怎么测的" | "JMeter / locust 在 `evidence/` 目录有原始 jtl / csv, 跑 `bash init.sh` 阶段 5/6 一键复现" |
| 被追问"为什么 partial-pass" | "诚实答:lab 条件 X 限制了 Y 指标, 但 Z 指标达标, 真实有数字见 `evidence/`" |
| 被追问"为什么不用 XX 框架" | "取舍记录在 ADR-000X, 项目规模下自研更可控 + 学习价值更高" |
| 被追问"线上出过故障吗" | "demo 环境跑通 4 bullet 全套, prod 部署路径在 `docker-compose.yml` 配齐, 上线后用 `bash init.sh` 阶段 4 health check 验证" |
| 被追问"如何排查线上问题" | "F-9 Prometheus 指标 + F-11 Feature Flag 切流 + F-7 限流 429 日志, 三件套定位" |
| 答不上来时 | "这个问题我的设计还没覆盖到, 但思路是 X, 后续我会按 Y 改进"(永远给思路而非硬撑)|
| **学生身份被追问"这是商业项目吗"** | "是独立个人项目, 不是公司实习产出;demo 范围内能做完整 bullet 量化, 真实业务需结合公司上下文" |