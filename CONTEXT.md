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