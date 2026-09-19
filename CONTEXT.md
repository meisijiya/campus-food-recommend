# CONTEXT.md — 校园美食推荐平台 · 领域语言

> 本文件承载代码表达不了的领域用语,**与代码互补、不重复代码已表达的内容**。
> 任何术语变更先改本文件,再改代码;反过来禁止(术语飘移是协作第一杀手)。

---

## 1. 角色与用户旅程

| 术语 | 含义 |
|---|---|
| 学生(Student) | 平台主要用户,带校园身份(学校 + 学号 + 学院)。JWT sub 即 studentId。 |
| 商户(Merchant) | 入驻的校园食堂窗口或周边餐饮店,有营业时段、所属商圈、菜系标签。 |
| 商圈(Zone) | 商户地理聚合,如"东区食堂圈""南门小吃街"。会话槽位第 2 阶。 |
| 菜系(Cuisine) | 商户的菜系分类标签,如"川菜""西餐"。会话槽位第 3 阶。 |

---

## 2. 会话槽位(对应 F-2 bullet)

```
INIT → Zone → Cuisine → Merchant
```

四阶段严格单向流转,**回退只能回到 INIT**(超时或用户主动重置)。每阶段槽位是 Redis 的一个 hash field,过期时间 30 分钟,空闲超时即回 INIT。

| 阶段 | 槽位 key | value |
|---|---|---|
| INIT | `session:<sid>:stage` | `INIT` |
| Zone | `session:<sid>:zone` | zoneId |
| Cuisine | `session:<sid>:cuisine` | cuisineId |
| Merchant | `session:<sid>:merchant` | merchantId |

非法跳转(如 INIT → Cuisine)直接 400,绝不自动补默认值。

---

## 3. 目录数据(对应 F-2 Skill 模块 / F-4 预热)

| 术语 | 含义 |
|---|---|
| 目录(Catalog) | zone / cuisine / merchant 三层树形数据,变化频次低、读多写少。 |
| Skill 模块 | Spring `@Component`,每个封装一类目录的"按需加载"接口,如 `ZoneSkill` / `CuisineSkill` / `MerchantSkill`。AI 提示词只引模块名,运行时按需注入真实数据。 |
| 层级 JSON | `MerchantCatalog` 包含 `zones[]`,每个 `zone` 含 `cuisines[]`,每个 `cuisine` 含 `merchants[]`,深度 ≤ 3。 |

---

## 4. 推荐与 AI 输出(对应 F-3)

| 术语 | 含义 |
|---|---|
| 结构化查询 | AI 返回的不是自然语言,而是符合 `RecommendationSchema` 的 JSON。 |
| JSON Schema | 在 `application.yml` 中可声明,服务端用 `JsonSchemaValidator` 校验。 |
| 反思重试(Reflective Retry) | 校验失败时把错误信息回传 AI 让它自我修正,最多 2 次;仍失败则降级为基于标签的规则推荐。 |

---

## 5. 缓存分层(对应 F-4 / F-5)

| 层 | 介质 | 内容 | 失效策略 |
|---|---|---|---|
| L0 | JVM Caffeine | 极热商户详情、目录热点 | LRU 5 min,容量 10k |
| L1 | Redis | 目录全量、商户热度 Top N | 凌晨预热;运行时写后失效(主动) |
| L2 | MySQL | 源数据 | 主库写、从库读(预留,当前单实例) |

---

## 6. 一致性原语(对应 F-5)

| 术语 | 含义 |
|---|---|
| 幂等点赞 | 同一 studentId 对同一 merchantId 在 60s 内重复 POST,只生效一次。Redis `SET key 1 NX EX 60` 前置拦截,然后投递 RabbitMQ 异步落库。 |
| 异步落库 | `like.db.write` 队列,消费者批量合并写 MySQL,失败进死信队列人工补偿。 |
| 极热商户 | 热度分 ≥ 阈值的 Top N(凌晨离线计算),进入 Caffeine 永久热点集。 |

---

## 7. 量化指标口径(简历 bullet 复用)

| 指标 | 计算方式 | 验证手段 |
|---|---|---|
| 单节点 5000+ QPS | `wrk -t 8 -c 200 -d 30s` 打 `/api/health` 与一个查询接口 | F-1 完成时跑 |
| Token 消耗 ↓35% | 同一对话任务,引入 Skill 模块前后对比 prompt token 数 | F-2 完成时跑 |
| 首次响应合规率 88% | 跑 100 条结构化查询样本,统计 JSON Schema 校验一次通过率 | F-3 完成时跑 |
| 响应时间 ↓60% | 对比预热前后的 P95 查询延迟 | F-4 完成时跑 |
| P99 < 50ms | 压测点赞 + 详情读混合流量,采集 P99 | F-5 完成时跑 |

---

## 8. 不在范围内(明确不做)

- 支付、配送、订单生命周期。
- 商户入驻审核工作流。
- 真正的机器学习推荐模型(简历强调"AI 个性化推荐",本 demo 用 Spring AI + 规则,模型部分以 Skill 模块封装的形式留口子)。
- 多租户、多校区(单校区假设)。
- 任何生产级可观测性(只用 Spring Boot Actuator 基础指标)。

---

## 9. Token 计量口径(对应 F-2 量化证据)

| 项 | 取数来源 |
|---|---|
| Prompt token 数 | Spring AI `ChatResponse` 的 `metadata.usage.promptTokens`(百炼真实响应);Mock profile 下用 `MockChatModel` 自带的 token 估算器(基于 tiktoken 启发式按字符数 / 4 估算) |
| Completion token 数 | 同上 `metadata.usage.completionTokens` |
| F-2 量化证据 | 50 轮同任务对话,引入 Skill 模块前后 prompt token 数对比,降幅 ≥ 35% 即达标 |

**纪律**:F-2 evidence 段必须同时给出两个数字(引入前 / 引入后),以及计算脚本路径(`tools/token-counter.py`)。

---

## 10. Profile 与 ChatModel(对应 F-3 双 Profile)

| Profile | ChatModel 实现 | 何时启用 | 资源消耗 |
|---|---|---|---|
| `dev` / `test` / `it`(默认) | `MockChatModel`(自写规则式应答器,`config` 包内 `@Configuration @Profile("!bench & !smoke")`)| 不设 `SPRING_PROFILES_ACTIVE` 即默认 | 零 API 调用,零费用 |
| `bench` / `smoke` | `DashScopeChatModel`(阿里云百炼,`spring-ai-alibaba-starter-dashscope` 1.1.2.2)| `SPRING_PROFILES_ACTIVE=bench ./mvnw spring-boot:run` | 走真实 API,产生 token 计费 |

**纪律**:`./mvnw test` 与本地开发**禁止**启用 `bench` / `smoke` profile(用户 2026-09-18 Q2 明确"测试一定要先 mock 数据跑通,不然会造成金钱损失")。F-3 量化证据采集阶段才切真。

---

## 11. 限流(对应 F-7 即将落地的 bullet)

| 术语 | 含义 |
|---|---|
| 双层令牌桶 | 同一请求先过用户级桶(防单用户滥用)再过 API 全局桶(防系统过载),两层都通过才放行。 |
| 用户级桶 | key `rate:user:<sid>`,容量 + 速率按用户画像(普通 / VIP / 黑名单)配置。 |
| API 全局桶 | key `rate:api:<endpoint>`,容量按接口特性(`/api/like` 容量大,`/api/recommend` 容量小)。 |
| 令牌桶 Lua 脚本 | Redis 单脚本原子执行"读桶 → 计算新令牌数 → 写回 → 返回是否放行",避免 read-modify-write 竞态。 |
| Caffeine 进程内降级 | Redis 不可用时切到 JVM-local 令牌桶(同接口、容量较小),Redis 恢复后异步回写计数。降级期间日志 WARN + 指标 `rate_limiter_degraded_total` 自增。 |
| 拒绝响应 | HTTP 429 + `Retry-After` header + JSON `{code: 42900, message: "rate limited"}`。 |

**key 命名**:`rate:user:<sid>` 与 `rate:api:<endpoint>`,前者按用户维度、后者按接口维度,互不交叉。token 计算在 Lua 脚本里完成,客户端无感。

**纪律**:降级路径必须显式(不能 silent fallback);降级指标必须有,否则面试官追问"你怎么知道 Redis 挂了"答不上。

---

## 12. 分布式锁(对应 F-8 即将落地的 bullet)

| 术语 | 含义 |
|---|---|
| 锁 key | `lock:preheat:global`(预热防重)与 `lock:like:<sid>:<mid>`(点赞幂等升级)两类;key 必须做白名单字符校验(防注入)。 |
| SETNX + TTL | `SET key <ownerToken> NX EX 30` 原子获取;ownerToken 是 UUID,用于"谁持锁"的识别。 |
| 看门狗(Watchdog) | 后台调度线程每 10 秒续期一次(将 TTL 重置为 30s),业务执行超时可自动续。续期失败 3 次主动放弃 + 抛异常。 |
| 锁释放 | 必须 Lua 脚本校验 ownerToken 后 DEL,防止 A 释放 B 的锁(TTL 过期导致 ownerToken 变化)。 |
| RedLock 单 Redis 假设 | 本 demo 单 Redis 实例,SETNX 已足够;多 Redis RedLock 留 ADR 标记未来扩展(避免单 Redis 挂了锁失效)。 |

**纪律**:每个 `lock:*` key 必须配 ownerToken,不能裸用 `SETNX EX`(否则 release 时会误删别人锁);F-5 现有 `like:idem:*` 与 F-8 新加 `lock:like:*` 是两个独立 key,不互相替代(前者是 60s 幂等窗口,后者是分布式强一致性)。

---

## 13. 可观测性(对应 F-9 即将落地的 bullet)

### 13.1 业务指标(自定义,4 个)

| 指标名 | 类型 | tag | 含义 |
|---|---|---|---|
| `like_count_total` | Counter | `endpoint` | 点赞累计次数(F-5 业务量)|
| `recommend_latency_seconds` | Timer | `endpoint`, `hit_tier` | 推荐请求端到端延迟,tag hit_tier ∈ {mock, dashscope, fallback} |
| `cache_hit_ratio` | Gauge | `cache_name`, `hit_tier` | 缓存命中率(F-4/F-5 L0/L1/L2 命中分布),hit_tier ∈ {L0, L1, L2, miss} |
| `session_stage_distribution` | Gauge | `stage` | 会话槽位阶段分布(INIT / ZONE / CUISINE / MERCHANT),反映用户在哪个阶段流失 |

### 13.2 技术指标(Micrometer 自动导出,6 个)

| 指标名 | 来源 | 含义 |
|---|---|---|
| `tomcat_threads_busy` | `tomcat.threads.busy` | Tomcat 工作线程占用,反映并发压力 |
| `hikari_pool_active` | `hikaricp.connections.active` | DB 连接池活跃数,反映 DB 压力 |
| `redis_pool_active` | `lettuce-native-thread.pool.size` | Redis 连接池使用情况 |
| `jvm_memory_used_bytes` | `jvm.memory.used` | JVM 堆内存使用 |
| `gc_pause_seconds` | `jvm.gc.pause` | GC 暂停时间(关键 SLO 指标) |
| `http_server_requests_seconds_count` | `http.server.requests` | HTTP 状态码分布(2xx/4xx/5xx 比例)|

### 13.3 暴露路径

- `GET /actuator/prometheus` — Prometheus 格式(仅 prod profile 启用,dev 关闭避免开发时输出噪声)
- Grafana dashboard:`docs/observability/grafana-overview.json`(F-9 落地时产出)
- 告警规则:`docs/observability/alerts.yml`(后续,F-9 不强制)

**纪律**:tag 维度不允许含 userId / merchantId 等高基数字段(否则 Prometheus 内存爆炸);`hit_tier` 是低基数枚举,安全。

---

## 14. CI/CD 与灰度(对应 F-10 / F-11 即将落地的非 bullet 工程项)

| 术语 | 含义 |
|---|---|
| CI 触发 | PR open/sync → 跑 `mvn verify` + locust F-5 P99 smoke;`main` push → 跑完整 `init.ps1` 6 stage(含 JMeter) |
| QPS 敏感 PR | PR 标题含 `[qps]` tag 或 label → 额外跑 JMeter F-1 5000+ QPS 验证 |
| Feature Flag | `FeatureFlagService.isEnabled(flagKey, userContext)` 接口;Redis hash 存 flag 配置;支持三种模式:功能开关 / 白名单 / 比例灰度 |
| 灰度回滚 | 改 Redis hash `feature_flags` 立即生效,无需重启;`/admin/feature-flag/reload` 主动重载(可选)|

**纪律**:三种灰度模式都必须在 `docs/observability/demo-scenarios.md` 留 demo curl 路径(招实习现场可演);不做真流量调度(代价大,价值小)。