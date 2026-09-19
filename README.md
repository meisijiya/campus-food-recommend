# 校园美食推荐平台(campus-food-recommend)

> 校园场景下"吃什么"的轻量化推荐后端 demo。**逐条复现简历 5 条带量化指标的项目 bullet**。
> 技术栈:JDK 21 + Spring Boot 3.5.16 + Spring AI Alibaba + MySQL 8.4 + Redis 7.4 + RabbitMQ 3.13。

## 简历 5 bullet 量化表

| # | bullet | 量化指标 | evidence |
|---|---|---|---|
| **F-1** | 面向扩缩容的无状态架构(JWT + Docker Compose + Nginx) | **JMeter 500 并发 5290 QPS / 0 err** | `evidence/f1-jmeter-500t-60s.jtl` |
| **F-2** | 会话槽位约束的渐进式检索(Redis 会话状态 + Skill 模块) | **AI 调用 Token ↓ ~35%** | `evidence/f2-token-reduction.json` |
| **F-3** | 结构化输出与反思重试(JSON Schema + AI 自反思) | **JSON 合规 88%**(反思重试 + 规则降级) | F-3 unit + IT |
| **F-4** | 离线数据加工与缓存预热(Spring Task + 层级 JSON + Redis 分片) | **详情 P99: 41ms → 15ms(-63%)** | `evidence/f4-p95-*_stats.csv` |
| **F-5** | 缓存一致性与多级加速(Redis 原子 + RabbitMQ + Caffeine) | **mix-like-detail Aggregated P99 = 40ms < 50ms** | `evidence/f5-p99_stats.csv` |

## 5 分钟上手

### 路径 A:docker compose(完整链路,Nginx + MySQL + Redis + RabbitMQ + App)

```bash
# 首次启动前确保本机 3306 端口空闲(避免与本机 MySQL service 冲突)
net stop MySQL

# 一键起 5 个容器(mysql / redis / rabbitmq / app / nginx)
docker compose up -d

# 等全部 healthcheck 转 healthy
docker compose ps

# 验证:nginx 转发 → app /actuator/health
curl http://localhost/actuator/health
```

应用监听 `http://localhost`(Nginx 80 → app 8080)。

### 路径 B:本地开发(不走 docker)

```bash
./mvnw spring-boot:run
# 默认 dev profile + MockChatModel + H2 内存数据库 + 不起 Redis/RabbitMQ
```

应用监听 `http://localhost:8080`。

### 路径 C:Bench profile(切真实百炼 DashScope API)

```bash
SPRING_PROFILES_ACTIVE=bench DASHSCOPE_API_KEY=sk-xxx ./mvnw spring-boot:run
```

> ⚠️ **仅在 F-3 量化证据采集阶段使用**;`./mvnw test` 默认走 `dev` profile + `MockChatModel`,CI/本地测试阶段**绝不启用**真实 API,避免金钱损失。

## 端到端演示(5 bullet 一条 curl 流)

```bash
# 1. 登录(JWT 无状态)
TOKEN=$(curl -s -X POST http://localhost/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo"}' | jq -r .data.accessToken)

# 2. F-2 会话槽位:INIT → Zone → Cuisine
curl -s -X POST http://localhost/api/session/init   -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost/api/session/zone   -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"zoneId":3}'
curl -s -X POST http://localhost/api/session/cuisine -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"cuisineId":12}'

# 3. F-3 推荐 + 反思重试
curl -s -X POST http://localhost/api/recommend -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"userMessage":"清淡,不要辣","sessionId":"demo-001"}'

# 4. F-5 点赞幂等(60s 内重复只生效一次)
curl -s -X POST http://localhost/api/like/7 -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost/api/like/7 -H "Authorization: Bearer $TOKEN"   # → liked:false

# 5. F-4 + F-5 商户详情(Caffeine L0 → Redis L1 → MySQL L2)
curl -s http://localhost/api/merchant/7 -H "Authorization: Bearer $TOKEN"
```

一键演示脚本:`pwsh -File scripts/demo.ps1`(Windows) / `bash scripts/demo.sh`(Linux/macOS)。

## 技术栈

| 维度 | 版本 | 决策 |
|---|---|---|
| JDK | 21 LTS | ADR-0003 |
| Spring Boot | 3.5.16 | ADR-0003 |
| Spring AI Alibaba DashScope | 1.1.2.2 | ADR-0003 |
| MySQL / Redis / RabbitMQ | 8.4 / 7.4 / 3.13-management | docker |
| 鉴权 | JJWT 0.12.6 无状态 | ADR-0002 |
| Schema 校验 | networknt json-schema-validator 1.5.2 | ADR-0003 |
| 压测 | locust + uv venv(JMeter 仅 F-1) | ADR-0004 / ADR-0005 |

## 模块索引

```
com.meisijiya.campusfood
├── CampusFoodApplication.java
├── common/                   // ApiResponse / GlobalExceptionHandler / TokenEstimator
├── config/                   // Security / Cache / Rabbit / Chat (Mock/DashScope 双 Profile)
└── module/
    ├── auth/                 // F-1: JwtService / AuthController / AuthService
    ├── catalog/              // F-2 + F-5
    │   ├── session/          // SessionStage / SessionSlotStateMachine / SessionController
    │   ├── skill/            // Skill / ZoneSkill / CuisineSkill / MerchantSkill(渐进检索)
    │   └── MerchantController + MerchantQueryService(L0→L1→L2 三级降级)
    ├── recommend/            // F-3
    │   ├── schema/           // RecommendationSchema + JsonSchemaValidator
    │   ├── advisor/          // ReflectiveRetryAdvisor + RuleBasedFallbackAdvisor
    │   ├── MockChatModel     // dev/test/it profile 测试替身
    │   └── RecommendController + RecommendService
    ├── like/                 // F-5: LikeService / LikeController / LikeMessageConsumer
    └── preheat/              // F-4
        ├── heat/             // Merchant / Cuisine / Zone / Like / Order 实体
        ├── assembler/        // CatalogHierarchyAssembler(层级 JSON)
        ├── RedisShardedWriter // 16 片
        ├── HeatJobPreheater   // @Scheduled 离线任务
        └── PreheatAdminController
```

## 测试

```bash
# 单元 + 集成测试(默认 Mock profile,不产 LLM 花费)
./mvnw test                  # Surefire 148 + Failsafe 11 + 1 skipped(RecommendBenchIT)

# 6 阶段验证门禁(等价于 CI):编译 → 测试 → compose 合法性 → 健康检查 → JMeter → locust
bash init.sh                 # Linux / macOS
pwsh -File init.ps1          # Windows PowerShell
```

## 文档导航

- `CONTEXT.md` — 领域语言(术语表)
- `ARCHITECTURE.md` — 架构图 + 5 bullet 实现落点 + 关键数据流
- `docs/api/api-reference.md` — 11 个端点完整列表 + curl
- `docs/adr/` — 决策记录(只增不删)
- `.scratch/campus-food-recommend/issues/` — 工单系统(状态来源)
- `evidence/` — 压测 / 量化数据(JTL / CSV / JSON,gitignored)

## License

MIT — 详见 [LICENSE](LICENSE)。
