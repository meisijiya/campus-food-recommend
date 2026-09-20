# F-11 Feature Flag · Demo Scenarios

> **Status:** F-11 W3 产出物(与 `feature-flag` 模块 + admin / public 接口配套)
> **适用场景:** 招实习面试现场演示 — 三个 curl 路径触发三种 FlagMode 切换 + 一组业务观察
> **前置依赖:** F-11 W1(Redis 配置中心 + Service + 4 FlagMode)+ W2(`/api/feature-flag/{}/check` 公开端点 + `/admin/feature-flag/{}` ADMIN 改配置端点)+ W3(@FeatureFlag 注解 + Aspect 接入 3 个 demo)
> **不破坏现有调用栈:** 业务方法签名不变,AOP 拦截在原方法体外层

---

## 0. 演示环境前置

```bash
# 起完整应用 + 中间件(W1 docker/observability/ + docker-compose.yml)
docker compose up -d mysql redis rabbitmq app

# 健康检查
curl -s http://127.0.0.1:8080/actuator/health | jq .
```

F-11 是无状态接入:不引入新中间件,仅复用现有 Redis(配置中心)+ Micrometer 指标体系。

---

## 1. Demo 路径 #1 — `recommend-v2`(PERCENTAGE 20% 灰度)

### 触发的指标

| Metric | 含义 | 期望 |
|---|---|---|
| `feature_flag_check_total{flag="recommend-v2", decision="true"}` | 灰度命中数 | 流量占比 ≈ 20% |
| `feature_flag_check_total{flag="recommend-v2", decision="false"}` | 灰度未命中数 | 流量占比 ≈ 80% |
| `flag_hit_timer_seconds{flag="recommend-v2"}` | 命中分支方法耗时 Timer | count = 命中数 |

### Curl 路径(启用 20% 灰度)

```bash
# 0) 登录拿 ADMIN token(改 flag 需要 ADMIN 角色)
ADMIN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin-pwd"}' \
  | jq -r '.data.accessToken')

# 1) 启用 20% 灰度(PERCENTAGE 模式)
curl -s -X POST http://127.0.0.1:8080/admin/feature-flag/recommend-v2 \
  -H "Authorization: Bearer $ADMIN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"PERCENTAGE","percentage":20}'
# → {"code":0,"message":"ok","data":{"mode":"PERCENTAGE","percentage":20,"whitelist":[]}}
```

### 验证(公开 check 接口 — 无需登录)

```bash
# 2) 批量 100 次 check,验证 20% 命中率
for i in {1..100}; do
  curl -s "http://127.0.0.1:8080/api/feature-flag/recommend-v2/check?studentId=$i" \
    | jq -r '.data.enabled'
done | sort | uniq -c
# 预期(允许 ±10 误差):
#    20 true
#    80 false
```

### 推荐调用观察日志侧灰度

```bash
# 3) 拿普通用户 token,触发 RecommendService.recommend(Aspect 拦截)
ACCESS=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo-stu-1","password":"password"}' \
  | jq -r '.data.accessToken')

# 推荐 50 次(命中分支会 log.info "recommend-v2 path taken for studentId={}")
for i in {1..50}; do
  curl -s -X POST http://127.0.0.1:8080/api/recommend \
    -H "Authorization: Bearer $ACCESS" >/dev/null
done

# 验证日志侧 v2 路径占比 ≈ 20%
docker logs cfr-app 2>&1 | grep "recommend-v2 path taken" | wc -l
# 预期约 10 条(50 次 × 20% 灰度)
```

### 回滚(关闭 flag)

```bash
curl -s -X POST http://127.0.0.1:8080/admin/feature-flag/recommend-v2 \
  -H "Authorization: Bearer $ADMIN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"ALL_OFF"}'
# → flag 关闭,Aspect 不再进入方法体
```

### 现场讲述要点

> "`recommend-v2` 是 PERCENTAGE 灰度模式 — 我们按 `flagKey + ":" + studentId` 做 hash 取模 100,
> 严格小于 percentage 才算命中。这保证同一 studentId 在同一 flag 下结果稳定,
> 但不同 flag 之间独立分布。Aspect 在调用业务方法前先查 flag — flag 关闭直接跳过方法体,
> 开启走原方法并打 `flag_hit_timer_seconds{flag=recommend-v2}` Timer。
> 我们用 Micrometer Counter `feature_flag_check_total{flag, decision}` 反向验证灰度比例是否准确 —
> 这是 observability 反推 control plane 的标准做法。"

---

## 2. Demo 路径 #2 — `like-cache-bypass`(WHITELIST_ONLY)

### 触发的指标

| Metric | 含义 |
|---|---|
| `feature_flag_check_total{flag="like-cache-bypass", decision="true"}` | 白名单内 sid 命中数 |
| `feature_flag_check_total{flag="like-cache-bypass", decision="false"}` | 白名单外 sid 命中数 |

### Curl 路径(白名单)

```bash
# 1) 设置白名单 = [1, 2, 3](内部员工 / 种子用户)
curl -s -X POST http://127.0.0.1:8080/admin/feature-flag/like-cache-bypass \
  -H "Authorization: Bearer $ADMIN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"WHITELIST_ONLY","whitelist":[1,2,3]}'
```

### 验证 check 接口

```bash
# 白名单内 sid=1 → enabled=true
curl -s "http://127.0.0.1:8080/api/feature-flag/like-cache-bypass/check?studentId=1" | jq .
# → {"code":0,"data":{"flagKey":"like-cache-bypass","enabled":true,"mode":"WHITELIST_ONLY","studentId":1}}

# 白名单外 sid=999 → enabled=false
curl -s "http://127.0.0.1:8080/api/feature-flag/like-cache-bypass/check?studentId=999" | jq .
# → {"code":0,"data":{"flagKey":"like-cache-bypass","enabled":false,"mode":"WHITELIST_ONLY","studentId":999}}
```

### 真实业务效果(LikeService.like 拦截演示)

```bash
# 拿普通用户 token
ACCESS=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo-stu-1","password":"password"}' \
  | jq -r '.data.accessToken')

# sid=1(白名单内)点赞 → Aspect 放行,走 LikeService.like 原方法
#   但 like-cache-bypass 的"直查 MySQL 旁路"逻辑是 demo 接入骨架 ——
#   真实业务旁路在后续 ticket 加(W3 仅展示 AOP 拦截 + flag 决策)
curl -s -X POST "http://127.0.0.1:8080/api/like/M-001" \
  -H "Authorization: Bearer $ACCESS"
# → {"code":0,"message":"ok","data":{"liked":true}}
```

### 回滚(关闭白名单)

```bash
curl -s -X POST http://127.0.0.1:8080/admin/feature-flag/like-cache-bypass \
  -H "Authorization: Bearer $ADMIN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"ALL_OFF"}'
```

### 现场讲述要点

> "`like-cache-bypass` 是 WHITELIST_ONLY 模式 — 内部员工 sid 在白名单内时,
> Aspect 放行走原方法(后续 ticket 可以在 flag 开启分支里加 'L1 Redis 旁路,直查 MySQL' 的逻辑 —
> W3 仅展示 AOP 拦截骨架);白名单外 Aspect 直接跳过方法体,返 defaultOn(false)。
> LikeService.like 返回 boolean — Aspect 用 `defaultOn` 避免 boolean unbox NPE,
> 这是 F-11 设计纪律里 @FeatureFlag 注解 `defaultOn` 字段的语义核心。"

---

## 3. Demo 路径 #3 — `merchant-detail-new`(ALL_ON / ALL_OFF 功能开关)

### 触发的指标

| Metric | 含义 |
|---|---|
| `feature_flag_check_total{flag="merchant-detail-new", decision="true"}` | 开关开启次数 |
| `feature_flag_check_total{flag="merchant-detail-new", decision="false"}` | 开关关闭次数 |

### Curl 路径(开启功能开关)

```bash
# 1) 开启 — 后续 MerchantQueryService.findDetailById() 会注入 openHours 字段
curl -s -X POST http://127.0.0.1:8080/admin/feature-flag/merchant-detail-new \
  -H "Authorization: Bearer $ADMIN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"ALL_ON"}'
# → {"code":0,"data":{"mode":"ALL_ON","whitelist":[],"percentage":0}}
```

### 验证 — 直接调 Service(需要写一段短测试或 dev tools 调)

`MerchantQueryService.findDetailById(String merchantId)` 是 W3 接入点 ——
flag 开启时返回 Map 包含 `openHours` + `featureFlag` 两个增量字段;
flag 关闭时只返回基本字段(`id`/`zoneId`/`cuisineId`/`name`/`tags`/`heatScore`/`found`)。

```java
// F-11 W3 集成测试已覆盖该路径(FeatureFlagIT.demo3_merchantDetailNew_allOn_aspectInjectsOpenHours)
Map<String, Object> detail = merchantQueryService.findDetailById("M-NOODLE");
assertThat(detail).containsEntry("openHours", "09:00-22:00");
assertThat(detail).containsEntry("featureFlag", "merchant-detail-new:ON");
```

### 回滚(关闭功能开关)

```bash
curl -s -X POST http://127.0.0.1:8080/admin/feature-flag/merchant-detail-new \
  -H "Authorization: Bearer $ADMIN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"ALL_OFF"}'
# → Aspect 不再注入 openHours 字段,findDetailById 仅返回基本字段
```

### 现场讲述要点

> "`merchant-detail-new` 是 ALL_ON / ALL_OFF 二态开关 — flag 开启时 Aspect 在
> findDetailById() 返回的 Map 里追加 `openHours` + `featureFlag` 两个字段,关闭时
> 仅返回基本字段。这里特意没用 controller 改 + Merchant 实体加字段的方案 —
> F-11 纪律要求'flag 增量字段不污染领域模型',所以走 Aspect 注入到 Map 返回值,
> Merchant 实体保持只读。这就是 W3 的文件所有权表里 MerchantQueryService 唯一允许
> 改动的方法,Merchant.java 完全只读的核心约束。"

---

## 4. 三个 demo 一页纸总结

| 路径 | FlagMode | 默认配置 | 启用操作 | 验证 curl | 回滚 |
|---|---|---|---|---|---|
| **#1 recommend-v2** | PERCENTAGE 20% | yml | `POST /admin/feature-flag/recommend-v2 -d '{"mode":"PERCENTAGE","percentage":20}'` | 100 次 `/check?studentId=N` 验证 20% 命中率 | `mode:"ALL_OFF"` |
| **#2 like-cache-bypass** | WHITELIST_ONLY [1,2,3] | yml | `POST /admin/feature-flag/like-cache-bypass -d '{"mode":"WHITELIST_ONLY","whitelist":[1,2,3]}'` | sid=1 → true; sid=999 → false | `mode:"ALL_OFF"` |
| **#3 merchant-detail-new** | ALL_OFF | yml | `POST /admin/feature-flag/merchant-detail-new -d '{"mode":"ALL_ON"}'` | 直接调 Service / `findDetailById` 看 Map 含 openHours | `mode:"ALL_OFF"` |

---

## 5. 指标观测(对应 §18.4 ownership)

- `feature_flag_check_total{flag, decision}` — W1 Service 自增,W3 业务接入后立即可观察
- `flag_hit_timer_seconds{flag}` — W3 Aspect 自增,验证 flag 命中分支实际耗时
- 3 个 demo 都对应 1 个 flag × 2 个 decision tag(`true`/`false`),最多 6 个 time series
- Prometheus scrape 路径:`/actuator/prometheus`(F-9 W2 已搭)
- Grafana panel:见 `docs/observability/grafana-overview.json`(F-9 W3 产出)

---

## 6. 面试官追问预案

**Q:AOP 拦截能不能改业务返回类型?**
> "默认不能。Aspect 通过 `defaultOn()` 处理 boolean 返回类型避免 NPE;
> 其他返回类型 flag 关闭时返 null,由 controller / DTO 自行兜底。
> 如果业务需要更精细的 'skip 语义响应',推荐用 DTO 包装 + Spring `ResponseStatusException`,
> 不要在 Aspect 里 throw — Aspect 是非业务逻辑的横切关注点,异常应来自业务层。"

**Q:为什么要用 @FeatureFlag 注解而不是手工 if/else?**
> "三个好处 ——
> 1) **关注点分离**:业务方法体只关心'我做什么',flag 决策是横切的'我什么时候做';
> 2) **统一 metric**:所有 flag 决策走 FeatureFlagService,Counter `feature_flag_check_total{flag, decision}` 自动累加,无需每个业务方法手写;
> 3) **admin 改 flag 不需要重启**:Caffeine 60s TTL 内 + admin POST 同步失效,业务代码完全无感知。"

**Q:flag 异常怎么办?Redis 挂了影响业务吗?**
> "F-11 启动期 Redis 不可达会降级到 yml default-flags,应用仍可启动;
> 运行时 isEnabled() 抛异常时 Aspect catch 后走 defaultOn 安全默认 —
> **flag 不能阻塞业务** 是 F-11 设计纪律的核心约束(与 F-7 限流 '启动失败立即崩' 策略相反)。"

**Q:tag `flag` 会不会撑爆 Prometheus?**
> "tag 维度是 `flag ∈ {recommend-v2, like-cache-bypass, merchant-detail-new, ...}` 几个有限枚举 +
> `decision ∈ {true, false}` 2 个 — 都是低基数。F-11 discipline 里禁止把 studentId / userId
> 进 tag(违反 Prometheus tag 基数爆炸红线)。"

**Q:4 种 FlagMode 怎么选?**
> "ALL_ON = 功能开关(永远开);ALL_OFF = 占位 / 灰度下线;
> WHITELIST_ONLY = 内部员工 / 种子用户 / 排查 case;
> PERCENTAGE = 真正的灰度发布 — 同一 flagKey + 不同 sid 哈希取模保证稳定分布。"