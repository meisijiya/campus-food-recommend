# F-9 Observability · Demo Scenarios

> **Status:** F-9 W3 产出物(与 `grafana-overview.json` 配对使用)
> **适用场景:** 招实习面试现场演示 — 三个 curl 路径触发 4 个自定义业务指标 + 2 个技术指标
> **前置依赖:** F-9 W1(指标埋点) + W2(Prometheus/Grafana 容器)已落地;本文件不写生产代码

---

## 0. 演示环境前置

```bash
# 起完整应用 + 监控栈(W2 docker/observability/ + docker-compose.yml prometheus + grafana service)
docker compose up -d mysql redis rabbitmq app prometheus grafana

# 健康检查
curl -s http://127.0.0.1:8080/actuator/health | jq .
curl -s http://127.0.0.1:9090/-/healthy  # prometheus
curl -s http://127.0.0.1:3000/api/health # grafana(admin/admin)
```

打开 Grafana:<http://127.0.0.1:3000/d/cfr-observability-overview> → 加载
`docs/observability/grafana-overview.json`(uid=`cfr-observability-overview`)。

刷新间隔 dashboard 设 10s,Prometheus 默认 15s 抓一次 — 触发 demo curl 后约 30s 内
能看到对应 panel 数据。

---

## 1. Demo 路径 #1 — 点赞(`POST /api/like/{merchantId}`)

### 触发的指标

| Panel | 指标 | 含义 | 期望变化 |
|---|---|---|---|
| **P1** | `like_count_total` | 点赞速率,tag endpoint=like | rate(5m) 上升 |
| **P4** | `session_stage_distribution` | 会话阶段分布(若 sid 已推进到 MERCHANT) | MERCHANT bucket 增加 |

### Curl 路径

```bash
# 0) 登录拿 JWT(JWT sub 即 studentId)
ACCESS=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo-stu-1","password":"password"}' \
  | jq -r '.data.accessToken')

# 1) 单次点赞
curl -s -X POST "http://127.0.0.1:8080/api/like/M-001" \
  -H "Authorization: Bearer $ACCESS"
# → {"code":0,"message":"ok","data":{"liked":true}}

# 2) 60s 内重复点赞(幂等返 false)
curl -s -X POST "http://127.0.0.1:8080/api/like/M-001" \
  -H "Authorization: Bearer $ACCESS"
# → {"code":0,"message":"already liked","data":{"liked":false}}

# 3) 批量点赞不同 merchantId(P1 panel 看 rate(5m) 上升的关键)
for mid in M-002 M-003 M-004 M-005 M-006; do
  curl -s -X POST "http://127.0.0.1:8080/api/like/$mid" \
    -H "Authorization: Bearer $ACCESS" >/dev/null
done

# 4) 推 session 到 MERCHANT stage(影响 P4)
curl -s -X POST http://127.0.0.1:8080/api/session/init \
  -H "Authorization: Bearer $ACCESS" >/dev/null
curl -s -X POST http://127.0.0.1:8080/api/session/zone \
  -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' -d '{"zoneId":"Z-east"}' >/dev/null
curl -s -X POST http://127.0.0.1:8080/api/session/cuisine \
  -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' -d '{"cuisineId":"C-sichuan"}' >/dev/null
curl -s -X POST http://127.0.0.1:8080/api/session/merchant \
  -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' -d '{"merchantId":"M-001"}' >/dev/null
```

### 截图位

- `evidence/f9-demo-1-p1-like-rate.png` — Grafana P1 panel,rate(like_count_total[5m]) ≈ 0.5~1 ops
- `evidence/f9-demo-1-p4-session-stage.png` — Grafana P4 panel,MERCHANT bucket 增长

### 配套 Grafana PromQL

```
# P1
sum by (endpoint) (rate(like_count_total[5m]))

# P4
sum by (stage) (session_stage_distribution)
```

### 现场讲述要点

> "点赞走 LikeService.like() —— Redis SETNX EX 60 做 60s 幂等,然后 RabbitMQ 投
> `like.db.write` 异步落库。每次 `like()` 调用 `Counter.builder("like_count")`
> 自增 +1。Micrometer 指标名 `like_count` 经 Prometheus exporter 转成
> `like_count_total`(下划线 + Counter 后缀),这是 Prometheus 命名规范。"
>
> "session_stage_distribution 是 Gauge,反映用户在 INIT/ZONE/CUISINE/MERCHANT
> 四个阶段的实时分布 —— 看哪个阶段掉人多就调推荐策略,而不是空猜。"

---

## 2. Demo 路径 #2 — 推荐(`POST /api/recommend`)

### 触发的指标

| Panel | 指标 | 含义 | 期望变化 |
|---|---|---|---|
| **P2** | `recommend_latency_seconds` | 推荐 P95 延迟,tag hit_tier ∈ {mock, dashscope, fallback} | P95 曲线出现,带 hit_tier 分线 |
| **P4** | `session_stage_distribution` | 会话阶段分布 | ZONE/CUISINE/MERCHANT bucket 增长 |

### Curl 路径

```bash
# 0) 同 §1.0 拿 ACCESS

# 1) 推 session 到 ZONE / CUISINE(影响 P4)
curl -s -X POST http://127.0.0.1:8080/api/session/init \
  -H "Authorization: Bearer $ACCESS" >/dev/null
curl -s -X POST http://127.0.0.1:8080/api/session/zone \
  -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' -d '{"zoneId":"Z-east"}' >/dev/null

# 2) 单次推荐
curl -s -X POST http://127.0.0.1:8080/api/recommend \
  -H "Authorization: Bearer $ACCESS" | jq .

# 3) 批量 50 次推荐(P2 panel histogram 才有足够样本)
for i in $(seq 1 50); do
  curl -s -X POST http://127.0.0.1:8080/api/recommend \
    -H "Authorization: Bearer $ACCESS" >/dev/null
done

# 4) (可选)切到 bench profile 跑真实 DashScope API — 看 hit_tier=dashscope 分支
#   SPRING_PROFILES_ACTIVE=bench DASHSCOPE_API_KEY=... ./mvnw spring-boot:run
#   然后同 (3) 跑批量 — P2 panel 多一条 hit_tier=dashscope 的曲线
```

### 截图位

- `evidence/f9-demo-2-p2-recommend-latency.png` — Grafana P2 panel,P95 曲线 +
  hit_tier=mock 分线
- `evidence/f9-demo-2-p4-session-stage.png` — Grafana P4 panel,ZONE/CUISINE/MERCHANT bucket 变化

### 配套 Grafana PromQL

```
# P2
histogram_quantile(0.95, sum by (le, hit_tier) (rate(recommend_latency_seconds_bucket[5m])))
```

### 现场讲述要点

> "RecommendService.recommend() 包了一层 `Timer.builder("recommend_latency_seconds")`
> 配 `tag("hit_tier", "mock"|"dashscope"|"fallback")` ——
> dev/test profile 用 `MockChatModel`(本 demo 默认),`hit_tier=mock`;
> bench/smoke profile 走百炼真实 API,`hit_tier=dashscope`;
> 反思重试 2 次仍失败走 RuleBasedFallbackAdvisor,`hit_tier=fallback`。"
>
> "Timer 类型 Prometheus exporter 会输出 `_bucket` / `_sum` / `_count` 三种 series,
> `histogram_quantile(0.95, ...)` 用 bucket 反推 P95 ——
> dashboard 用 `by (le, hit_tier)` 拆分,hit_tier 是低基数枚举,不会撑爆 Prometheus 内存。"

---

## 3. Demo 路径 #3 — 商户详情(`GET /api/merchant/{id}`)

### 触发的指标

| Panel | 指标 | 含义 | 期望变化 |
|---|---|---|---|
| **P3** | `cache_hit_ratio` | 缓存命中率,tag cache_name + hit_tier ∈ {L0, L1, L2, miss} | hit_tier=L0/L1/L2/miss 四条线 |
| **P6** | `http_server_requests_seconds_count` | HTTP 状态码速率 | 200 速率上升 |

### Curl 路径

```bash
# 0) 同 §1.0 拿 ACCESS

# 1) 首次查商户(L0 miss → L1 miss → L2 hit → 回填 L0 + L1,cache_hit_ratio L2 增)
curl -s "http://127.0.0.1:8080/api/merchant/M-001" \
  -H "Authorization: Bearer $ACCESS" | jq .

# 2) 同一商户第二次查(L0 hit,JVM-local 1ms 内返)
curl -s "http://127.0.0.1:8080/api/merchant/M-001" \
  -H "Authorization: Bearer $ACCESS" >/dev/null

# 3) 不同商户重复(L0 miss 但 L1 hit — Redis JSON 反序列化回填 L0)
for mid in M-002 M-003 M-004 M-001 M-002; do
  curl -s "http://127.0.0.1:8080/api/merchant/$mid" \
    -H "Authorization: Bearer $ACCESS" >/dev/null
done

# 4) 查不存在的商户(L0 negative hit / L1 negative hit,miss 路径)
curl -s "http://127.0.0.1:8080/api/merchant/M-NONEXISTENT" \
  -H "Authorization: Bearer $ACCESS"
# → 404 (ApiException → GlobalExceptionHandler → {code:40400,...})
```

### 截图位

- `evidence/f9-demo-3-p3-cache-hit-ratio.png` — Grafana P3 panel,L0/L1/L2/miss 四条线
- `evidence/f9-demo-3-p6-http-status.png` — Grafana P6 panel,200 速率上升,404 偶现

### 配套 Grafana PromQL

```
# P3
sum by (cache_name, hit_tier) (cache_hit_ratio)

# P6
sum by (status) (rate(http_server_requests_seconds_count[5m]))
```

### 现场讲述要点

> "商户详情走 `MerchantQueryService.findById()`,严格 L0 Caffeine → L1 Redis → L2 MySQL
> 降级链路(F-5 acceptance)。F-9 W1 在每条命中路径打 `Gauge.builder("cache_hit_ratio")`
> 带 tag `cache_name=merchant` + `hit_tier=L0|L1|L2|miss`。"
>
> "为什么 hit_tier 是 `Gauge` 不是 `Counter`? —— Cache 命中率是"当前快照"语义,不是
> 累积计数;Gauge 每次 scrape 报一个瞬时值。Counter 适合 `like_count_total` 这种"永远
> 单调递增"的语义。"
>
> "P6 是 `http_server_requests_seconds_count`(Micrometer 自动暴露的
> `http.server.requests` Counter),按 status 分桶看 2xx/4xx/5xx 比例 ——
> 5xx 突增是 PagerDuty 级别告警,4xx 突增是攻击或客户端 bug。"

---

## 4. 三个 demo 路径汇总表(给面试官一页纸)

| 路径 | HTTP | 业务指标 | 技术指标 | 现场亮点 |
|---|---|---|---|---|
| **#1 点赞** | `POST /api/like/{mid}` | P1 like_count_total ↑<br>P4 session_stage_distribution(若推到 MERCHANT) | P6 http 4xx/2xx 速率 | 60s SETNX 幂等 + RabbitMQ 异步落库 |
| **#2 推荐** | `POST /api/recommend` | P2 recommend_latency_seconds(P95 by hit_tier)↑<br>P4 session_stage_distribution(若推到 ZONE/CUISINE) | P2 反映 mock vs dashscope vs fallback 三档 | Spring AI 双 Profile + RRA 反思重试 + RBFA 规则降级 |
| **#3 商户详情** | `GET /api/merchant/{id}` | P3 cache_hit_ratio(by L0/L1/L2/miss)↑ | P6 http 200/404 速率 | F-5 严格 L0→L1→L2 三级降级 + Caffeine L0 |

---

## 5. 截图与 evidence 落点

每个 demo 路径对应两张截图位(共 6 张),落 `evidence/` 目录
(`evidence/f9-demo-{1,2,3}-p{1,2,3,4,6}-*.png`,`.gitignore` 已 exclude)。

Prometheus scrape 文本全量落 `evidence/f9-prometheus-scrape.log`
(由 `ObservabilityIT.java` 验证 4 个自定义指标名都在 scrape 输出里)。

---

## 6. 面试官追问预案

**Q:为什么用 Micrometer 而不是直接接 Prometheus SDK?**
> "Micrometer 是 SLF4J 风格的 metrics facade —— 一套 API,后端可换(Prometheus / Datadog /
> New Relic)。换监控栈不用改业务代码,只换 `MeterRegistry` 实现。F-9 选 Micrometer 锁
> Prometheus 是因为开源 + 自托管免费 + 招实习场景面试官熟。"

**Q:hit_tier 这个 tag 维度会不会撑爆 Prometheus?**
> "tag 维度是 `hit_tier ∈ {mock, dashscope, fallback}` 3 个枚举 + `cache_name` 5 个 +
> `stage` 4 个 —— 全部低基数。Prometheus tag 高基数爆炸(比如 userId / merchantId)才
> 是真问题,F-9 纪律里 CONTEXT §13 明确禁止 userId / merchantId 进 tag。"

**Q:Counter 和 Gauge 的选择标准?**
> "永远单调递增 → Counter(点赞次数 / HTTP 请求数 / 异常数);
> 当前快照值 → Gauge(缓存命中率 / 内存使用 / session 阶段分布);
> 想要 P95 / 平均 → Timer(recommend 延迟,自动导出 _bucket/_sum/_count)。"
