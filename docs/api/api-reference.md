# API Reference

> **base URL**: `http://localhost`(经 Nginx 80)或 `http://localhost:8080`(直连应用)
> **认证豁免**: `/api/auth/**`、`/api/feature-flag/**`、`/actuator/**` 路径白名单,无需 Bearer
> **限流豁免**: `RateLimitFilter` 同时跳过 `/api/auth/**`、`/actuator/**`、`/error`(防登录锁死 + k8s 探针误杀 + Spring 内部错误转发)
> **响应包装**: 全部响应统一包成 `ApiResponse<T>`,HTTP 状态码由异常处理决定(`GlobalExceptionHandler` 映射)
> **错误码表**: 6 个公开码 + 2 个 F-11 内部保留常量(脚注说明)
> **最近一次定稿**: 2026-09-22 字段对齐 hotfix(F-17 基础上逐项核对 Controller / Service / Entity 源码,修齐 9 处不一致)
> **覆盖范围**: F-1 / F-2 / F-3 / F-4 / F-5 / F-6 / F-7 / F-8 / F-9 / F-11(共 10 个 ticket 后落地的接口)

---

## 0. 通用约定

### 0.1 响应包装

```jsonc
// 成功
{ "code": 0, "message": "ok", "data": { /* 业务载荷 */ } }

// 失败
{ "code": 40100, "message": "unauthorized", "data": null }
```

### 0.2 错误码表（6 个公开码）

错误码完全定义于 `com.meisijiya.campusfood.common.exception.ErrorCode`,与 `GlobalExceptionHandler.toCode(HttpStatus)` 严格 1:1 映射。

| code | HTTP status | 含义 | 触发路径 |
|---|---|---|---|
| `0` | 200/201 | ok | Controller 正常返回 |
| `40000` | 400 | 请求参数非法 | `ApiException(BAD_REQUEST)` / `MethodArgumentNotValidException` / `ConstraintViolationException` |
| `40100` | 401 | 未鉴权(JWT 缺失 / 过期 / 伪造) | `AuthenticationException` → `GlobalExceptionHandler.handleAuth` |
| `40300` | 403 | 鉴权成功但无权限 | `AccessDeniedException` → `GlobalExceptionHandler.handleAccessDenied` / `@PreAuthorize` 失败 |
| `40400` | 404 | 资源不存在 | `ApiException(NOT_FOUND)`(例:`FeatureFlagController.check` flagKey 未注册 → 响应 `40400`) |
| `42900` | 429 | 限流拒绝 | `RateLimitFilter` 双层令牌桶任一层失败,直接写入响应(不走 `ApiException`) |
| `50000` | 500 | 内部异常 | `Exception` 兜底 → `GlobalExceptionHandler.handleAny` |

**F-11 内部保留常量**(用于日志 grep,**不**单独出现在 HTTP 响应 code 字段,统一映射到上表 40400 / 40000):

- `40001 FEATURE_FLAG_INVALID_CONFIG` — FlagConfig 校验失败(mode 缺失 / PERCENTAGE 越界 / WHITELIST_ONLY 缺 list),由 `FeatureFlagAdminController.set` 抛 `ApiException(BAD_REQUEST)`,响应 `40000`,日志用此常量
- `40401 FEATURE_FLAG_NOT_FOUND` — flagKey 未注册,由 `FeatureFlagController.check` / `FeatureFlagAdminController.get` 抛 `ApiException(NOT_FOUND)`,响应 `40400`,日志用此常量

> **不存在 40900 业务冲突 / 422xx Schema Violation**: F-2 会话非法跳转走 `40000`(BAD_REQUEST 业务校验),不预留独立 code;F-3 schema 不合规走 `RuleBasedFallbackAdvisor` 兜底 schema 合规 JSON,接口仍 200(`code=0`),**永不抛 422**。

### 0.3 限流快速参考

所有非豁免路径(`/api/**` 中除 `/api/auth/**` 外)都过双层令牌桶:

| 维度 | 命中 401 时 | 命中限流 | 默认放行上限(`application.yml`) |
|---|---|---|---|
| API 全局桶 | `key=api:{METHOD}:{URI}` | 任何流量超出 | `burst=1000`、`rate=500/s` |
| 用户级桶 | `key=user:{sub}` or `user:anonymous` | 单 sid 滥用 | `burst=100`、`rate=10/s` |

详见 §8。

---

## 1. 鉴权(`/api/auth/**` — 免鉴权 + 免限流)

### 1.1 `POST /api/auth/login`

**请求**

```json
{ "username": "demo", "password": "demo" }
```

**响应 200**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "accessToken": "eyJhbGc...",
    "refreshToken": "eyJhbGc...",
    "expiresIn": 7200
  }
}
```

> `AuthResponse` record 只有这 3 个字段(`AuthService.java:75`):**没有 `tokenType` 字段**——前端调用时按 OAuth2 Bearer 约定固定在 `Authorization: Bearer <accessToken>` 头里,与响应体无关。

**失败**
- `40100`(错误密码):`{ "code": 40100, "message": "用户名或密码错误", "data": null }`
- `40000`(缺 username / password / 类型错误)

**预设账号**(内存 `UserDetailsServiceImpl`):

| username | password | 角色 |
|---|---|---|
| `demo` | `demo` | STUDENT |
| `admin` | `admin` | STUDENT + ADMIN |

### 1.2 `POST /api/auth/refresh`

**请求**:`{ "refreshToken": "eyJhbGc..." }`
**响应**:同 `1.1`(返新 accessToken,refreshToken 复用)
**失败**:`40100`(refresh 过期 / 伪造 / 缺失)

---

## 2. 会话槽位(`/api/session/**` — 需鉴权 + 过限流)

会话四阶段严格单向流转:`INIT → Zone → Cuisine → Merchant`。非法跳转直接 `40000`。

### 2.1 `POST /api/session/init`

**鉴权**:Bearer
**请求**:空 body
**响应 200**

```json
{
  "code": 0,
  "data": {
    "stage": "INIT",
    "zoneId": null,
    "cuisineId": null,
    "merchantId": null
  }
}
```

> **请求体通用**:`{ "value": "<id>" }` —— DTO 是 `record SlotRequest(@NotBlank String value)`,`value` 是字符串(对应 `Merchant.id` / `zoneId` / `cuisineId` 都是 String,非 Long)。**所有 ID 走 `value` 字段**,不叫 `zoneId` / `cuisineId` / `merchantId`。
> **响应字段**:`SessionContext` record 字段是 `stage / zoneId / cuisineId / merchantId`(全名带 `Id` 后缀);Redis 里也是字符串形式(`session:<sid>:zone = "Z-3"`)。

### 2.2 `POST /api/session/zone`

**请求**:`{ "value": "Z-3" }`
**响应 200**:`{ "stage": "ZONE", "zoneId": "Z-3", "cuisineId": null, "merchantId": null }`
**前置**:必须在 `INIT` 阶段,否则 `40000 非法跳转`

### 2.3 `POST /api/session/cuisine`

**请求**:`{ "value": "C-12" }`
**前置**:必须在 `ZONE` 阶段,否则 `40000`

### 2.4 `POST /api/session/merchant`

**请求**:`{ "value": "M-42" }`
**前置**:必须在 `CUISINE` 阶段,否则 `40000`

> **底层**: 状态完全在 Redis(`session:<sid>:stage` / `:zone` / `:cuisine` / `:merchant`),空闲 30 分钟自动回 `INIT`(`EXPIRE 1800`,写入重置)。

---

## 3. 推荐(`/api/recommend` — 需鉴权 + 过限流)

### 3.1 `POST /api/recommend`

**鉴权**:Bearer(`@PreAuthorize("isAuthenticated()")`)
**请求**:空(后端从 JWT `sub` 取 sid)

**响应 200**(dev/test/it profile 走 `MockChatModel`):

```json
{
  "code": 0,
  "data": {
    "content": "{\"merchantId\":[\"m-001\",\"m-002\"],\"reason\":\"...\",\"confidence\":0.85}",
    "stage": "INIT",
    "promptTokens": 184,
    "completionTokens": 96
  }
}
```

**字段说明**

| 字段 | 类型 | 含义 |
|---|---|---|
| `content` | string | **AI 输出的原始 JSON 字符串**,严格符合 `RecommendationSchema`。前端需要 `JSON.parse(content)` 拿 `merchantId[] / reason / confidence`。 |
| `stage` | string(INIT/ZONE/CUISINE/MERCHANT) | 当前会话阶段 |
| `promptTokens` | int/null | Spring AI prompt token 数(Mock profile 用 tiktoken 启发式估算) |
| `completionTokens` | int/null | 同上 |

> **没有 `recommendations[]` / `modelVersion` 字段**:`RecommendationResult` record 只有这 4 个字段;AI 返回的 merchant 信息在 `content` 里(已 schema 合规)。

**降级路径**:

```
MockChatModel(dev/test/it)
    ↓ 不合规
DashScopeChatModel(bench/smoke,真实百炼)
    ↓ 不合规
ReflectiveRetryAdvisor(≤2 次反思重试)
    ↓ 仍不合规
RuleBasedFallbackAdvisor(MOCK_CATALOG 兜底)
    ↓ 最终
ApiResponse.ok(schema 合规 JSON)
```

**失败**:
- `40100` 未鉴权
- `40000` 参数校验失败
- `50000` 推荐链路异常(罕见;fallback 已兜底绝大多数失败)

> **schema 校验永远成功兜底**: 即使 `ReflectiveRetryAdvisor` 重试 2 次仍不合规,`RuleBasedFallbackAdvisor` 会替换成 `MOCK_CATALOG` 5 个商户中按标签排序的合规 JSON。接口**不会**返 422。

---

## 4. 点赞(`/api/like/**` — 需鉴权 + 过限流)

### 4.1 `POST /api/like/{merchantId}`

**鉴权**:Bearer
**路径变量 `merchantId`**:字符串(非 Long),非空,长度 ≤ 64

**幂等语义**: 同一 `(studentId, merchantId)` 60 秒内重复 POST 只生效一次。

**响应 200(首次)**

```json
{ "code": 0, "message": "ok", "data": { "liked": true } }
```

**响应 200(60s 内重复)**

```json
{ "code": 0, "message": "already liked", "data": { "liked": false } }
```

> 注意:重复点赞 HTTP 也是 200,`code=0` ——业务层面视为成功但幂等拦截。前端**不需要弹错**,根据 `liked` 决定 UI 状态(已点赞 → 高亮,未点赞 → 普通)。

**底层**

```
Redis SET like:idem:<sid>:<mid> 1 NX EX 60
  ├─ 成功 → rabbitTemplate.send("like.db.write", LikeMessage)
  └─ 失败 → 返 "liked=false, message=already liked"(不投递 MQ)
```

落库失败消息进 DLQ `like.db.write.dlq`(人工补偿)。

**失败**:`40100` / `40000`(merchantId 或 studentId 为空 / 长度 > 64 / 含非法字符,白名单 `[A-Za-z0-9_.-]+`,违例 message: `"<field> contains illegal chars (allowed: [A-Za-z0-9_.-])"`,校验在 `LikeService.validateId`——`studentId` 来自 JWT sub,实际触发概率极低,由 `JwtAuthenticationFilter` 优先兜底为 401)/ `50000`

---

## 5. 商户目录(`/api/merchant/**` — 需鉴权 + 过限流)

### 5.1 `GET /api/merchant/{id}`

**鉴权**:Bearer
**路径变量 `id`**:字符串(非 Long),非空,长度 ≤ 64

**响应 200**

```json
{
  "code": 0,
  "data": {
    "id": "m-001",
    "name": "清真食堂",
    "zoneId": "Z-3",
    "cuisineId": "C-12",
    "tags": "清真,快餐,面食",
    "heatScore": 87.5
  }
}
```

**字段说明**

| 字段 | 类型 | 备注 |
|---|---|---|
| `id` / `zoneId` / `cuisineId` | string | 主键与外键,业务方提供,非自增 |
| `name` | string | 展示名 |
| `tags` | string/null | 逗号分隔的标签字符串 |
| `heatScore` | double/null | 凌晨预热算出的热度分 |

三级降级读取:`Caffeine L0 → Redis L1 → MySQL L2`,缓存命中是**透明过程** ——响应字段不包含 `hitTier` / `openHours`(这是 `findById` 标准输出,前端无需关心命中层)。

> 另有 `MerchantQueryService.findDetailById()`(`@FeatureFlag("merchant-detail-new")`)会在 `merchant-detail-new` flag 开启时追加 `openHours` + `featureFlag` 字段,默认走 `MerchantController.getById` 不走 detail 路径。

**失败**:`40100` / `40400`(`MerchantQueryService` 抛 `ApiException(NOT_FOUND)`,message: `"merchant not found: <id>"`)/ `42900` / `50000`

### 5.2 `GET /api/merchant?zoneId=Z-3`

按商圈批量获取(同样走 `L0→L1→L2`)。`zoneId` 是字符串(对应 `Merchant.zoneId` 是 String)。

**响应**

```json
{
  "code": 0,
  "data": [
    { "id": "m-001", "name": "清真食堂", ... },
    { "id": "m-002", "name": "黄焖鸡米饭", ... }
  ]
}
```

**失败**:`40000`(`zoneId` 超长) / `40100` / `42900`。**zoneId 不存在时返空列表**(`data: []`),不报 404。

---

## 6. 预热管理(`/admin/preheat/**`)

> ⚠️ **只在 dev profile 注册**:`PreheatAdminController` 类标了 `@Profile("dev")`。非 dev profile(prod / bench / smoke / test / it)下该 bean 不存在,端点直接 404。
> 生产用凌晨定时任务 `@Scheduled(cron="0 0 3 * * ?")` 由 `HeatJobPreheater.run()` 自动跑(F-8 多实例防重走 `RedisLock` 同一把 `LOCK_KEY`)。

### 6.1 `POST /admin/preheat/trigger`

**Profile**:`dev` only(Controller `@Profile("dev")`)
**鉴权**:`dev` profile 下免鉴权(`SecurityConfig` permitAll + controller 仅 dev 注册);若非 dev profile 手动启用,`SecurityConfig.access()` 要求 `ROLE_ADMIN`。

**请求**:空 body
**响应 200**

```json
{
  "code": 0,
  "data": {
    "timestamp": "2026-09-22T01:00:00Z",
    "merchantCount": 103,
    "zoneCount": 12,
    "hotCount": 10,
    "elapsedMs": 612
  }
}
```

**字段说明**

| 字段 | 类型 | 含义 |
|---|---|---|
| `timestamp` | string(ISO-8601 UTC) | 预热完成时刻 |
| `merchantCount` | int | 本次参与计算的商户数 |
| `zoneCount` | int | 写入的 Redis 分片数 |
| `hotCount` | int | 写入 `catalog:hot:merchants` 的 Top N 数 |
| `elapsedMs` | long | 整次预热耗时(毫秒) |

底层:`MerchantHeatCalculator` 从 MySQL 订单 / 点赞表算热度,`CatalogHierarchyAssembler` 装 zone→cuisine→merchant 层级 JSON,`RedisShardedWriter` 按 zone 分片 SETEX。

**失败**:非 dev profile → 404;非 ADMIN 在 prod → `40300` / `40100`;Redis / DB 异常 → `50000`

---

## 7. 健康检查(`/actuator/**` — 免鉴权 + 免限流)

| 端点 | 鉴权 | 含义 |
|---|---|---|
| `GET /actuator/health` | 否 | 聚合状态(默认 group:无;返回 `{status: UP}` 或含各组件 detail) |
| `GET /actuator/health/liveness` | 否 | k8s liveness 探针:**不查外部依赖**(只 `livenessState`),防 Redis 抖动触发 pod 误杀 |
| `GET /actuator/health/readiness` | 否 | k8s readiness 探针(包含 `livenessState` + `redis` + `rabbit` + `db`,依赖未就绪则不接流量)|
| `GET /actuator/info` | 否 | **已禁用**(`management.endpoint.info.enabled=false`,防 git/build/env 变量通过 `/actuator/info` 泄漏)|
| `GET /actuator/metrics` | 否 | 列出所有 metric 名 |
| `GET /actuator/prometheus` | 否 | Prometheus 格式导出,**仅 prod profile 启用**(`MANAGEMENT_PROMETHEUS_EXPORT=true`)|

### 7.1 业务指标(F-9 自定义,8 个)

由 `MicrometerConfig` / 各 Service 注册:

| 指标名 | 类型 | 主要 tag | 含义 |
|---|---|---|---|
| `like_count_total` | Counter | `endpoint` | 点赞累计次数(F-5/F-9 业务量)|
| `recommend_latency_seconds` | Timer | `endpoint`, `hit_tier` | 推荐端到端延迟,`hit_tier ∈ {mock, dashscope, fallback}` |
| `cache_hit_ratio` | Counter | `cache_name`, `hit_tier` | 缓存命中率(L0/L1/L2 hit + miss 四档)|
| `session_stage_distribution` | Counter | `stage` | 会话槽位阶段分布(INIT/ZONE/CUISINE/MERCHANT)|
| `rate_limiter_degraded_total` | Counter | `source` | 限流降级次数(`source=REDIS_TO_CAFFEINE`,F-7 可观测性)|
| `feature_flag_check_total` | Counter | `flag_key`, `enabled` | FeatureFlag check 调用计数(F-11)|
| `flag_hit_timer_seconds` | Timer | `flag_key`, `action` | FeatureFlag 拦截路径耗时(`action ∈ {hit, miss, disabled}`)|
| `flag_reload_total` | Counter | `source` | FeatureFlag reload 触发计数(`source=admin / scheduled`,见 `FeatureFlagAdminController.reload`)|

### 7.2 技术指标(Spring Boot Actuator 自动暴露,6 个基线)

| 指标名 | Micrometer 默认名 | 含义 |
|---|---|---|
| `tomcat_threads_busy` | `tomcat.threads.busy` | Tomcat 工作线程占用,反映并发压力 |
| `hikari_pool_active` | `hikaricp.connections.active` | DB 连接池活跃数 |
| `redis_pool_active` | `lettuce-native-thread.pool.size` | Redis 连接池使用 |
| `jvm_memory_used_bytes` | `jvm.memory.used` | JVM 堆内存 |
| `gc_pause_seconds` | `jvm.gc.pause` | GC 暂停时间(SLO 关键)|
| `http_server_requests_seconds_count` | `http.server.requests` | HTTP 请求状态码分布(2xx/4xx/5xx)|

> **tag 维度不允许含 userId / merchantId 等高基数字段**(避免 Prometheus 内存爆炸)。`hit_tier` / `endpoint` / `source` / `flag_key` 是低基数枚举,安全。

### 7.3 Prometheus 启用

```bash
# dev / test 不启用(dev 默认)
MANAGEMENT_PROMETHEUS_EXPORT=true SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run
# 或 docker-compose(已配环境变量)
docker compose up app nginx
# 然后:
curl -s http://localhost/actuator/prometheus | head -40
```

**Grafana dashboard**: `docs/observability/grafana-overview.json`(F-9 落地版本)

---

## 8. 限流(`42900` + `Retry-After`)

### 8.1 触发条件

`RateLimitFilter`(`OncePerRequestFilter`,通过 `SecurityConfig.addFilterBefore` 注册到 Spring Security chain,在 `JwtAuthenticationFilter` 之前执行)对每个非豁免请求做双层令牌桶校验:

```
请求进入
  ↓
是否豁免(/api/auth/**, /actuator/**, /error)?  ─是→ chain.doFilter 直通
  ↓ 否
api 层: tryAcquire(api:{METHOD}:{URI})     ─失败→ 429
  ↓ 通过
user 层: tryAcquire(user:{sub} 或 user:anonymous)   ─失败→ 429
  ↓ 通过
chain.doFilter
```

### 8.2 拒绝响应

**HTTP 状态**:`429 Too Many Requests`
**Headers**:`Retry-After: 1`(秒,固定;token bucket 下一秒补)
**Body**(`ApiResponse<Void>`):

```json
{ "code": 42900, "message": "rate limited", "data": null }
```

> 注意:`code=42900` 不是 ApiException 路径,而是 `RateLimitFilter` 直接通过 `objectMapper.writeValue(response.getWriter(), ...)` 写响应(避免被 `GlobalExceptionHandler` 吃成 500)。

### 8.3 降级路径(Redis 不可达时)

`acquireWithFallback()` 顺序:

1. 主路径 `redisLimiter.tryAcquire(key)`
2. Redis 抛 `RateLimiterBackendException` → 降级日志 WARN + `RateLimitDegradationMonitor.recordDegradation(SOURCE_REDIS_TO_CAFFEINE)` 自增 + 切 `caffLimiter` 重试
3. Caffeine 也失败 → 拒绝(返 429)

### 8.4 降级可观测性

每次 Redis → Caffeine 降级会自增指标 `rate_limiter_degraded_total{source="REDIS_TO_CAFFEINE"}`(`RateLimitDegradationMonitor` 注册),见 §7.1。可用于回答"Redis 挂了多久、什么时候降级、降级频率"。

### 8.5 配置项(`application.yml`)

```yaml
rate-limit:
  user:
    burst: 100        # 单用户桶容量(token 数)
    rate: 10.0        # 单用户每秒补充速率
  api:
    burst: 1000       # API 全局桶容量
    rate: 500.0       # API 全局每秒补充速率
  script-location: scripts/
```

`Retry-After` 当前为固定 1 秒,不走配置;token bucket 配置变更需重启应用生效。

### 8.6 curl 示例

```bash
# 业务请求(200)
curl -i -X POST http://localhost:8080/api/merchant/7 -H "Authorization: Bearer $TOKEN"

# 触发限流(429 + Retry-After)
curl -i -X POST http://localhost:8080/api/merchant/7 -H "Authorization: Bearer $TOKEN" -H "Connection: close"
HTTP/1.1 429
Retry-After: 1
Content-Type: application/json

{"code":42900,"message":"rate limited","data":null}
```

---

## 9. FeatureFlag(`/api/feature-flag/**` 公开 + `/admin/feature-flag/**` 需 ADMIN)

### 9.1 公开 check — `GET /api/feature-flag/{flagKey}/check`

**鉴权**:免鉴权(SecurityConfig `permitAll`)
**限流**:过(计入 API 桶)

**Query 参数**:`studentId` 可选,缺失时灰度决策走安全路径

**响应 200**

```json
{
  "code": 0,
  "data": {
    "flagKey": "recommend-v2",
    "enabled": true,
    "mode": "ALL_ON",
    "studentId": 12345
  }
}
```

**失败**:
- `40000`(`flagKey` 为空)
- `40400`(flagKey 未在 Redis hash / yml default-flags 注册)

> `FeatureFlagController` 先调 `getConfig()` 判存在(防御性 `null` → 404),再调 `isEnabled()` 拿决策。这样 demo 客户端能明确知道"flag 没注册"而不是默默返 false。

### 9.2 Admin 接口(`/admin/feature-flag/**` — 需 ADMIN)

**鉴权**:
- `SecurityConfig`: `authorizeHttpRequests` 设 `hasRole("ADMIN")`(URL 级)
- `@PreAuthorize("hasRole('ADMIN')")`(类级,method-level defense-in-depth)
- demo account:`admin / admin`

#### 9.2.1 `GET /admin/feature-flag` — 列出所有 flagKey

**响应 200**:`{ "code": 0, "data": ["recommend-v2", "like-cache-bypass", "merchant-detail-new"] }`(TreeSet 去重排序)

#### 9.2.2 `GET /admin/feature-flag/{flagKey}` — 读单个 flag 配置

**响应 200**:`{ "code": 0, "data": { "mode": "ALL_ON", "percentage": null, "whitelist": null, "enabled": true } }`
**失败**:`40400`(flagKey 未注册)

#### 9.2.3 `POST /admin/feature-flag/{flagKey}` — 修改 flag 配置

**请求 body**(JSON,字段随 `mode` 而异):

```jsonc
// mode=ALL_ON
{ "mode": "ALL_ON" }

// mode=ALL_OFF
{ "mode": "ALL_OFF" }

// mode=WHITELIST_ONLY
{ "mode": "WHITELIST_ONLY", "whitelist": [1, 2, 3] }

// mode=PERCENTAGE
{ "mode": "PERCENTAGE", "percentage": 25 }
```

**响应 200**:返回修改后的 FlagConfig(`{ mode, percentage, whitelist, enabled }`)
**失败**:
- `40000` body 为空 / JSON 格式错 / mode 缺失 / PERCENTAGE 越界(非 [0,100])/ WHITELIST_ONLY 缺 list
- `40300` 非 ADMIN
- `40100` 未鉴权

**底层**:`FeatureFlagService.setConfig(flagKey, FlagConfig)` 同步写 Redis hash `feature_flags` + 失效 Caffeine 缓存(< 1ms 生效)。

### 9.3 FlagMode 速查(4 种)

| `mode` | 含义 | 关键校验 |
|---|---|---|
| `ALL_ON` | 全员启用 | 无 |
| `ALL_OFF` | 全员禁用 | 无 |
| `WHITELIST_ONLY` | 仅白名单 sid 启用 | `whitelist` 非 null(可空数组)|
| `PERCENTAGE` | 按百分比灰度 | `percentage ∈ [0, 100]` |

### 9.4 默认 flag 集合(`application.yml` `feature-flag.default-flags`)

| flagKey | mode | 说明 |
|---|---|---|
| `recommend-v2` | `ALL_ON` | 全员启用新版推荐路径 |
| `like-cache-bypass` | `WHITELIST_ONLY`, whitelist=[1,2,3] | 仅 IT / 演示 sid 绕过 Redis L1 缓存 |
| `merchant-detail-new` | `ALL_ON` | 全员启用新版商户详情 |

`FeatureFlagRedisConfig` 启动期检查:Redis hash `feature_flags` 无值则回退到 yml 兜底,F-9 4 业务指标 demo 现场 Grafana 可见。

### 9.5 curl 示例

```bash
# 公开 check(免鉴权)
curl -i http://localhost:8080/api/feature-flag/recommend-v2/check?studentId=1
{"code":0,"data":{"flagKey":"recommend-v2","enabled":true,"mode":"ALL_ON","studentId":1}}

# Admin 改 flag
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | jq -r '.data.accessToken')

curl -X POST http://localhost:8080/admin/feature-flag/recommend-v2 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"PERCENTAGE","percentage":10}'

# 验证生效
curl http://localhost:8080/api/feature-flag/recommend-v2/check?studentId=1
```

---

## 附录 A: 鉴权豁免路径全集

| 路径 | 鉴权豁免 | 限流豁免 | 备注 |
|---|---|---|---|
| `/api/auth/**` | ✅ | ✅ | 登录/refresh 不被自己限流 |
| `/api/feature-flag/**` | ✅(公开 check)| ❌ | Admin 部分仍需 ADMIN |
| `/actuator/**` | ✅ | ✅ | k8s 探针不被误杀 |
| `/error` | — | ✅ | Spring 内部错误转发 |

## 附录 B: 错误码速查表

```
40100 未鉴权     40300 无权限       40400 资源不存在
40000 参数非法   42900 限流拒绝     50000 内部错误
              0   ok

(F-11 内部:40001 配置无效,40401 flag 不存在 — 仅日志,响应仍走 40000/40400)
```

## 附录 C: Prometheus 探针示例

```bash
# 指标名清单
curl -s http://localhost:8080/actuator/metrics | jq -r '.names[] | select(test("^like_|^recommend_|^cache_|^session_|^rate_|^feature_flag|^flag_"))'

# 业务 like_count_total
curl -s http://localhost:8080/actuator/prometheus | grep '^like_count_total' | head

# 限流降级次数
curl -s http://localhost:8080/actuator/prometheus | grep '^rate_limiter_degraded_total'

# JVM GC 暂停
curl -s http://localhost:8080/actuator/prometheus | grep '^jvm_gc_pause_seconds_count'
```
