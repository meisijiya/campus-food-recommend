# 面试 Cheat Sheet · 1 页 A4 救命件

> **用法**：面试现场被追问时翻这一页。每行 = 追问 + 答 + 关联代码路径。
> **优先级**：先答口径 / 数字 / 边界条件，再指代码路径，最后给 commit hash 锚点。

---

## Bullet 1 · 无状态架构 / 5243 RPS

| 追问 | 回答思路 | 关联代码 |
|---|---|---|
| 5243 怎么测的 | JMeter 5.6.3 non-GUI / 500 threads / 10s ramp / 60s sustained / `GET /actuator/health` / 排除 cold start 取 3 次稳态均值（5476 / 5216 / 5036） | `evidence/f1-jmeter.jmx` + `evidence/verify-f1-qps-{1,2,3}.jtl` |
| Tomcat 800 线程为什么 | Spring Boot 3.x 默认 200 上限会让 accept 队列堆积；压测撞瓶颈后拉到 `max:800 / min-spare:50 / accept-count:500` | `src/main/resources/application.yml:server.tomcat.threads` |
| 无状态怎么做 | JJWT 0.12.6 / `JwtAuthenticationFilter` 解析 `Authorization: Bearer ...` / **不**依赖 Spring Session；token 自含 authorities | `config/JwtAuthenticationFilter.java` + `module/auth/JwtService.java` |
| 怎么水平扩 | Nginx upstream 多实例（least_conn 调度）/ 状态全在 Redis / 数据库预留读写分离演进位 | `nginx/nginx.conf` + ADR-0003 |
| 怎么压到 5000+ | 关键路径就一个 `/actuator/health`（无业务逻辑），但要保证 JWT 路径不被探针撞到；500 threads 是 JMeter 单机天花板 | ADR-0005（JMeter 替代 locust） |

---

## Bullet 2 · 会话槽位 / Skill / token -99.8%

| 追问 | 回答思路 | 关联代码 |
|---|---|---|
| 99.8% 怎么算的 | `TokenEstimator.estimateTokens` chars/4 / baseline 19098 = 12 zone × 8 cuisine × 6 merchant 全量 JSON 内联 / skill 注入后 34 = `{skill:zone}` 占位符 1 行 + 3 stage id / MockChatModel 内置一致 | `tools/token-counter.py` + `evidence/f2-token-reduction.json` + `module/recommend/SkillRegistry.java` |
| 状态机非法跳转怎么报 | 抛 `IllegalSlotTransitionException` → `GlobalExceptionHandler` → HTTP 400 + JSON `code=40000` | `module/catalog/session/SessionSlotStateMachine.java` + `common/GlobalExceptionHandler.java` |
| Skill 怎么注入 | `SkillRegistry` 按 stage 选 / prompt 模板只写 `{skill:zone}` 占位符 / 运行时 `Skill.render(ctx)` 拿真实数据 | `module/catalog/skill/{Skill,ZoneSkill,CuisineSkill,MerchantSkill}.java` + `module/recommend/RecommendService.java` |
| 30 min 怎么实现 | 每次写入 `EXPIRE 1800` 重置 / Redis key TTL 机制 / 触发读路径时 lazy check 过期 | `module/catalog/session/SessionService.java` |
| 槽位不是冗余吗 | INIT→ZONE→CUISINE→MERCHANT 强约束避免无效请求 + token 省;非法跳转兜底返回错误而非默认值 | `module/catalog/session/SessionController.java` |

---

## Bullet 3 · JSON Schema + 反思重试

| 追问 | 回答思路 | 关联代码 |
|---|---|---|
| 88% 怎么测 | **诚实答**：dev 单测覆盖**机制**（3 路径分支）而非**数字**（首次合规率）；真实数字需 `SPRING_PROFILES_ACTIVE=bench` + `DASHSCOPE_API_KEY` 触发 `RecommendBenchIT`（100 条样本）;仓内已给 README 步骤 | `module/recommend/advisor/ReflectiveRetryAdvisorTest.java`（4 用例）+ `RecommendBenchIT.java`（`@EnabledIfEnvironmentVariable("DASHSCOPE_API_KEY", ".+")`） |
| 2 次为啥不更多 | trade-off：更多次 → 延迟 + token 成本翻倍；2 次 + 降级 advisor 是生产经验值（Google SRE 多数 RAG / Agent 系统也用 2-3 次） | `application.yml:advisor.maxRetries`（默认 2） |
| 降级 advisor 怎么做 | `RuleBasedFallbackAdvisor` 按商户标签 + 用户历史打分排序，返回 schema 合规 JSON；fallback 路径**不**走 AI 调用，0 token 成本 | `module/recommend/advisor/RuleBasedFallbackAdvisor.java` + `RuleBasedFallbackAdvisorTest.java`（6 用例） |
| 双 profile 怎么隔离 | `@Profile("!bench & !smoke")` 注入 `MockChatModel` / `@Profile({"bench","smoke"})` 注入 Spring AI Alibaba DashScope / `dev/test/it` 默认走 Mock 零成本 | `config/MockChatModelConfig.java` + `config/DashScopeConfig.java` + AGENTS.md §16.2 |
| Schema 校验细节 | `networknt:json-schema-validator:1.5.2` / `JsonSchemaFactory.getInstance(VersionFlag.V7)` / 失败抛 `SchemaViolationException` 携带错误摘要 | `module/recommend/schema/JsonSchemaValidator.java` + `src/main/resources/schema/recommendation.json` |
| 反思回拼怎么实现 | `BaseAdvisor` 拦截 ChatResponse → schema 校验失败 → 把错误摘要 + 原 prompt 拼回下一轮 `user` 消息；最多 2 次；advisor 顺序：ReflectiveRetry → RuleBasedFallback | `module/recommend/advisor/ReflectiveRetryAdvisor.java` |

---

## Bullet 4 · 离线预热 / P99 -63%

| 追问 | 回答思路 | 关联代码 |
|---|---|---|
| 60% 怎么测的 | locust 30s 100 并发 `GET /api/merchant/<random EVM-id>` / before = MySQL 直查 / after = Redis L1 命中 / before 跑前 `redis-cli FLUSHDB` 清空 | `evidence/f4-p95-before_stats.csv` + `evidence/f4-p95-after_stats.csv` |
| 为啥 P95 不是 -60% | lab 条件绝对延迟太小（MySQL 直查 2-3ms vs Redis 1ms，绝对差 < 2ms）/ 30s 压测期内 L1 已 backfill，BEFORE 后段接近稳态 L1 hit / P95 7ms→6ms 1ms 之差被 GC/JIT 噪声吸收 / **P99 -63% 才是 cache 价值的真信号**（tail 收敛） | F-4 ticket §5 partial-pass 完整解释 + `evidence/f4-p95-{before,after}_stats.csv` P99 行 |
| 加权公式怎么定的 | `0.6*订单+0.4*点赞` 来自产品经验（订单权重更高，因为消费转化是终极指标）/ Top N 默认 100 / 公式参数化可配 | `module/preheat/MerchantHeatCalculator.java:scoreOf()` + `application.yml:preheat.heat.weight` |
| 凌晨 3 点 cron 怎么配 | `@Scheduled(cron="0 0 3 * * ?")` + `spring.task.scheduling.pool.size=2`（防 cron 阻塞） | `module/preheat/HeatJobPreheater.java:run()` + `application.yml:spring.task.scheduling` |
| 多实例 cron 怎么防重 | F-8 加 RedisLock（见 Bullet 7） | `module/lock/HeatJobPreheaterLockTest.java` |
| 分片 key 怎么设计的 | `catalog:zone:<zoneId>` 按地理分片（防大 key）+ `catalog:hot:merchants` SET 存 Top N（O(log N) 查）+ `catalog:merchant:<id>` 单商户 SETEX 10min（详情读） | `module/preheat/RedisShardedWriter.java` + CONTEXT.md §5 |
| 层级 JSON 深度为啥 ≤3 | zone(1) → cuisine(2) → merchant(3);不超 3 因为 UI 一次性渲染 + 序列化体积可控 | `module/preheat/assembler/CatalogHierarchyAssembler.java:assemble()` |

---

## Bullet 5 · 点赞幂等 + 多级加速 / P99 < 50ms

| 追问 | 回答思路 | 关联代码 |
|---|---|---|
| P99 怎么测 | locust 30s 50 并发 50/50 混合流量（点赞 + 详情）/ `uv run locust -f locustfile_mix.py --tags mix-like-detail -u 50 -r 25 -t 30s` | `evidence/f5-p99_stats.csv`（34981 reqs,0 fail）+ `locustfile_mix.py` |
| 60s 幂等怎么做 | `SET like:idem:<sid>:<mid> 1 NX EX 60` / 第二次返回 `liked:false "already liked"` / 不依赖 DB, Redis 单命令原子 | `module/like/LikeService.java:like()` |
| 批量合并怎么写 | `LikeMessageConsumer` 每 100 条或 1s 触发 batch flush / `LikeRepository.saveAll` 一次落库 / 失败转 DLX | `module/like/LikeMessageConsumer.java` |
| DLX 怎么走 | 业务 catch 异常 → reject → MQ 走 `x-dead-letter-exchange` → 进 `like.db.write.dlq` / 不无限重试（避免放大事故） | `config/RabbitMQConfig.java` |
| 三级降级为啥这样排 | L0 Caffeine JVM-local POJO 引用（最快,无序列化）→ L1 Redis JSON（次快,跨实例共享）→ L2 MySQL（兜底） / 严格命中日志,debug 可追 | `module/catalog/MerchantQueryService.java` + `config/CacheConfig.java` |
| 为啥 Caffeine 容量这么设 | `merchantHotCache` 10k = 校园场景单日访问商户 1k × 10 buffer / `zoneCatalogCache` 200 = 12 zone × 10 cuisine × N merchant,实测足够 | `config/CacheConfig.java:merchantHotCache()` |
| 命中日志怎么看 | L0/L1/L2 INFO 日志带 key,debug 时 `grep "hit layer" app.log` | `MerchantQueryService.java:findById()` |

---

## Bullet 6 · 双层令牌桶

| 追问 | 回答思路 | 关联代码 |
|---|---|---|
| 为啥双层 | 单用户 + 全局;只看用户级会被 1 个用户的 spam 拖垮;只看全局会被 1 个用户独占;两层都通过才放行,2 维防护 | `module/ratelimit/RateLimitFilter.java:doFilterInternal()` |
| Lua 原子怎么写 | `EVAL` 传 4 参数：key, currentTime, refillRate, permits / 读桶 → 算 refill → 扣 permits → 写回 1 步完成 / Redis Lua 单线程执行,无竞态 | `src/main/resources/lua/token-bucket.lua` + `RedisTokenBucket.java:doExecute()` |
| Caffeine 降级数据怎么回写 | **不**回写;Redis 恢复后下次请求自然回到 Redis 路径;降级期间日志 WARN + `rate_limiter_degraded_total` Counter 自增给监控可见 | `RateLimitFilter.java:doFilterInternal()` + `RateLimitDegradationMonitor.java` |
| 429 + Retry-After 怎么算 | `Retry-After: 1` 表示 1 秒后再试;生产可改成基于桶 refill 速率的动态值 | `RateLimitFilter.java` |
| 为啥不用 Resilience4j / Sentinel | 自研可控 + 学习价值 + 项目规模适配;Resilience4j 限流模块在多桶 Spring Boot 集成复杂,Sentinel 是 Alibaba 重型方案 | ADR-0007（取舍记录） |
| 桶参数怎么配 | `application.yml:rate-limit.user.burst=100, rate=10/s` + `rate-limit.api.<endpoint>.burst/rate` | `application.yml:rate-limit` + `RateLimitProperties.java` |
| 热点 endpoint 怎么定 | 默认 1000/500s 通用配置;已知热点（如登录、推荐）单独配更高 burst | `RateLimitProperties.java` |

---

## Bullet 7 · 分布式锁 + 看门狗

| 追问 | 回答思路 | 关联代码 |
|---|---|---|
| 为啥 RedisLock 不用 Redisson | 自研可控 + 学习价值 + 项目规模适配（Redisson 6MB 类路径,不值得为 1 个锁加进来） | ADR-0007（取舍记录）|
| 续期为啥失败 3 次放弃 | 网络抖动可能在 1-2 次;3 次仍失败 = 真挂了,放弃避免无限续 / 同时 `Watchdog giving up key=... after 3 consecutive extend failures` 日志触发告警 | `module/lock/Watchdog.java:tick()` + `application.yml:campusfood.lock.max-failures` |
| 防误删怎么做 | `release(key, ownerToken)` Lua 先 GET 校验 ownerToken 再 DEL;A 的 token 释放不了 B 的锁 / IT 第 3 用例 `release_wrongOwnerToken_returnsFalse_andKeyRemains` 验证 | `module/lock/RedisLock.java:release()` + `src/main/resources/lua/lock-release.lua` |
| 2 个场景为啥放一起 | 都是"锁住跨实例共享资源"的同一模式;抽象成 `RedisLock` 一处实现,2 处复用 | `module/preheat/HeatJobPreheater.java:run()` + `module/like/LikeService.java:like()` |
| 锁 key 怎么设计 | `lock:preheat` 全局单 key + `lock:like:<sid>:<mid>` 锁细粒度;key 白名单 `[A-Za-z0-9_.-]+` 防注入（F-5 复用） | `RedisLock.java` + `ID_SAFE` Pattern |
| 看门狗为啥单线程 | 续期是 O(锁数量) 的遍历,单线程足够;多线程反而加锁 / 用 `ConcurrentHashMap` 注册表 | `Watchdog.java:ConcurrentHashMap<>` |
| 同 JVM 嵌套 deadlock 怎么防 | `HeatJobPreheater` 把 cron + manual 共用 lock,但实际工作方法 `doPreheat()` 私有持有,**不**递归加锁 | `HeatJobPreheater.java` |

---

## 工程亮点延伸（被问"还有别的吗"时）

| 领域 | ticket | 一句话答 | 代码锚点 |
|---|---|---|---|
| 可观测性 | F-9 | Micrometer + Prometheus scrape `/actuator/prometheus`;4 业务 + 11 系统指标 | `module/observability/MicrometerConfig.java` |
| Feature Flag | F-11 | Redis hash + Caffeine L1 + 4 FlagMode;Admin POST 双层鉴权 | `module/featureflag/FeatureFlagAspect.java` |
| CI/CD | F-10 | GitHub Actions 3 workflow:ci(编译+测试) / bench(压测 artifact) / release(多 JDK matrix) | `.github/workflows/{ci,bench,release}.yml` |
| Demo Readiness | F-6 | `bash init.sh` 6 阶段门禁 / `uv run locust` + JMeter 双工具 | `init.sh` + `init.ps1` |

---

## 通用救场话术

| 场景 | 话术 |
|---|---|
| 被追问"这数字怎么测的" | "JMeter / locust 在 `evidence/` 目录有原始 jtl / csv, 跑 `bash init.sh` 阶段 5/6 一键复现" |
| 被追问"为什么 partial-pass" | "诚实答:lab 条件 X 限制了 Y 指标, 但 Z 指标达标, 真实有数字见 `evidence/`" |
| 被追问"为什么不用 XX 框架" | "取舍记录在 ADR-000X, 项目规模下自研更可控 + 学习价值更高" |
| 被追问"线上出过故障吗" | "dev 环境跑通 7 bullet 全套, prod 部署路径在 `docker-compose.yml` 配齐, 上线后用 `bash init.sh` 阶段 4 health check 验证" |
| 被追问"如何排查线上问题" | "F-9 Prometheus 指标 + F-11 Feature Flag 切流 + F-7 限流 429 日志, 三件套定位" |
| 答不上来时 | "这个问题我的设计还没覆盖到, 但思路是 X, 后续我会按 Y 改进"（永远给思路而非硬撑） |