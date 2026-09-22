# ARCHITECTURE.md — 校园美食推荐平台架构

> 本文件是 README 的"工程师向补充",面向招实习面试官中可能深入代码的读者。
> 配套文档:`README.md`(招实习视角)、`CONTEXT.md`(领域语言)、`docs/adr/`(决策记录)。

## 1. 系统总览

```
                          ┌──────────────┐
                ┌────────▶│  Nginx 80    │◀──── Host 80
                │         └──────┬───────┘
                │                │ upstream
                │         ┌──────▼─────────────────────────┐
                │         │  cfr-app (Spring Boot 3.5.16)   │
                │         │  8080 / stateless / JWT         │
                │         │                                  │
                │         │  module/auth     ─ JwtService   │
                │         │  module/session  ─ 槽位 FSM     │
                │         │  module/recommend─ AI 反思重试   │
                │         │  module/like     ─ Redis NX+MQ  │
                │         │  module/catalog  ─ L0→L1→L2    │
                │         │  module/preheat  ─ Scheduled    │
                │         └─┬──────────┬──────────┬────────┘
                │           │          │          │
                │     ┌─────▼───┐ ┌────▼────┐ ┌───▼─────┐
                │     │ MySQL   │ │ Redis   │ │RabbitMQ │
                │     │ 8.4     │ │ 7.4     │ │3.13-mgmt│
                │     └─────────┘ └─────────┘ └─────────┘
                │
                └──── locust 压测 (host 网络,直连 8080)
```

## 2. 简历 5 bullet 实现落点

| bullet | 关键实现 | 量化 |
|---|---|---|
| F-1 无状态架构(JWT+Compose+Nginx) | `config/SecurityConfig`(`STATELESS`)、`module/auth/{Jwt,Auth}*`、`docker-compose.yml`、`nginx/nginx.conf` | JMeter 500 并发 **5290 QPS** |
| F-2 会话槽位 + 渐进检索 | `module/catalog/session/{SessionStage,SessionSlotStateMachine}.java`、`module/catalog/skill/{Zone,Cuisine,Merchant}Skill.java` | Token ↓ **99.8%**(baseline 19098 → skill 34)|
| F-3 结构化输出 + 反思重试 | `module/recommend/schema/JsonSchemaValidator` + `module/recommend/advisor/{ReflectiveRetry,RuleBasedFallback}Advisor.java` | 100% 合规(F-14.1 amend 后 first_attempt 100/100)|
| F-4 离线预热 + Redis 分片 | `module/preheat/HeatJobPreheater`(@Scheduled)、`module/preheat/RedisShardedWriter`(16 片)、`module/preheat/assembler/CatalogHierarchyAssembler` | 详情 P99 **41ms→15ms** |
| F-5 缓存一致性 + 多级加速 | `module/like/LikeService`(Redis NX 60s)、`module/like/LikeMessageConsumer`(@RabbitListener 100/1s)、`module/catalog/MerchantQueryService`(Caffeine→Redis→MySQL) | **P99 < 100ms(78/76ms),26158 reqs 0 fail**(burst=100000 临时覆盖) |

## 3. 关键数据流

### 3.1 推荐请求端到端(F-2 + F-3)

```
client ─POST /api/recommend─▶ Nginx ─▶ app ─▶ RecommendController
                                                  │
                                                  ▼
                                       RecommendService
                                                  │
                       ┌──────────────────────────┼────────────────────────┐
                       │                          │                        │
                       ▼                          ▼                        ▼
                SessionService             ReflectiveRetryAdvisor    RuleBasedFallback
                  (槽位校验)               (fail→reflect→retry≤2)    (兜底)
                       │                          │                        │
                       ▼                          ▼                        ▼
                  SkillRegistry ──────── MockChatModel (dev profile) ──▶ Recommendation
                                            DashScopeChatModel (bench)
                                                  │
                                                  ▼
                                         JsonSchemaValidator
                                                  │
                                       fail ▲─────┴─────▶ pass
                                       retry(≤2)            ▼
                                                  RecommendationSchema (返回)
```

### 3.2 点赞端到端(F-5 幂等 + 一致性)

```
client ─POST /api/like/{merchantId}─▶ LikeController ─▶ LikeService
                                                        │
                                                        ▼
                                          Redis SET like:idem:<sid>:<mid> 1 NX EX 60
                                          ▲                                   │
                                          │                                   │
                                       失败 ─"已点赞" 200                    成功
                                                                              ▼
                                                            rabbitTemplate.send("like.db.write", LikeMessage)
                                                                              │
                                                                              ▼
                                                            LikeMessageConsumer (@RabbitListener)
                                                                              │
                                                            100 条 OR 1s 批量合并写 MySQL.likes
                                                                              │
                                                            失败 ─▶ DLQ "like.db.write.dlq"
```

### 3.3 详情三级降级(F-4 预热 + F-5 L0)

```
client ─GET /api/merchant/{id}─▶ MerchantController ─▶ MerchantQueryService
                                                       │
                       ┌───────────────────────────────┼──────────────────────────────┐
                       │                               │                              │
                       ▼                               ▼                              ▼
                  L0 Caffeine                   L1 Redis (JSON)                L2 MySQL
                  Optional.empty                  null=负缓存                      findById
                       │  miss                          │  miss                          │
                       └───────────────────────────────┴──────────────────────────────▶┘
                                                                                       │
                                                                                       ▼
                                                  Redis ShardedWriter.set(zone=hash(id) % 16)
                                                                                       │
                                                                                       ▼
                                                  Caffeine.put → 后续命中 L0
```

## 4. 关键技术决策

| 主题 | 决策 | ADR |
|---|---|---|
| 状态来源 | Tracker 模式:Local Markdown 工单 + `.scratch/` 物理隔离 | ADR-0001 |
| 技术栈 | JDK 21 / Spring Boot 3.5.16 / Spring AI Alibaba 1.1.2.2 | ADR-0003 |
| 鉴权 | JJWT 0.12.6 无状态 | ADR-0002 |
| 压测 | locust + uv venv(JMeter 仅 F-1 evidence 用) | ADR-0004 / ADR-0005 |
| Demo readiness | 本 ADR 范围 | ADR-0006 |

## 5. 模块依赖图

```
                              ┌────────────┐
                              │ common     │ ─ ApiResponse / 异常
                              └─────┬──────┘
                                    │
        ┌───────────────┬───────────┼───────────┬──────────────┐
        │               │           │           │              │
        ▼               ▼           ▼           ▼              ▼
   ┌────────┐   ┌────────────┐ ┌────────┐ ┌────────┐   ┌────────────┐
   │ auth   │   │ catalog    │ │recommend│ │ like   │   │ preheat    │
   │        │   │  ├ session │ │         │ │        │   │            │
   │        │   │  ├ skill   │ │ schema  │ │        │   │  ├ heat    │
   │        │   │  └ query   │ │ advisor │ │        │   │  └ assemble│
   └────┬───┘   └─────┬──────┘ └────┬────┘ └────┬───┘   └─────┬──────┘
        │             │             │           │             │
        └─────────────┴──────┬──────┴───────────┴─────────────┘
                             │
                       ┌─────▼─────┐
                       │  config   │ ─ Security / Cache / Rabbit / Chat
                       └───────────┘
```
