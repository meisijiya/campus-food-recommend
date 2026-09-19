# API Reference

> 所有端点 base URL 为 `http://localhost`(经 Nginx 80)或 `http://localhost:8080`(直连应用)。
> 除 `/api/auth/**` 与 `/actuator/health/**` 外,所有路径需 `Authorization: Bearer <accessToken>`。
> 鉴权失败:401(JSON);权限不足:403(JSON)。统一返回 `ApiResponse<T>` 包装。

## 0. 通用约定

### 请求 / 响应

```jsonc
// 成功
{ "code": 0, "message": "ok", "data": { ... } }

// 失败
{ "code": 40100, "message": "unauthorized", "data": null }
```

### 错误码(ErrorCode 枚举)

| code | 含义 |
|---|---|
| 0 | ok |
| 40000 | 请求参数非法(Bean Validation 失败) |
| 40100 | 未鉴权(JWT 缺失 / 过期 / 伪造) |
| 40300 | 鉴权成功但无权限 |
| 40400 | 资源不存在 |
| 40900 | 业务冲突(如重复点赞 / 非法状态机跳转) |
| 50000 | 内部异常 |

## 1. 鉴权(`/api/auth/**` — 免鉴权)

### 1.1 `POST /api/auth/login`

请求:
```json
{ "username": "demo", "password": "demo" }
```
响应:
```json
{ "code": 0, "data": {
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc...",
  "expiresIn": 7200,
  "tokenType": "Bearer"
} }
```
预设账号(内存用户表,见 `module/auth/UserDetailsServiceImpl`):

| username | password | 角色 |
|---|---|---|
| `demo` | `demo` | STUDENT |
| `admin` | `admin` | STUDENT + ADMIN |

### 1.2 `POST /api/auth/refresh`

请求:
```json
{ "refreshToken": "eyJhbGc..." }
```
响应同 `1.1`。

## 2. 会话槽位(`/api/session/**` — 需鉴权)

会话四阶段严格单向流转:`INIT → Zone → Cuisine → Merchant`。非法跳转直接 409。

### 2.1 `POST /api/session/init`

初始化会话(回退到 INIT 阶段)。

请求:空 body。
响应:
```json
{ "code": 0, "data": { "stage": "INIT", "zone": null, "cuisine": null, "merchant": null } }
```

### 2.2 `POST /api/session/zone`

请求:
```json
{ "zoneId": 3 }
```
响应:
```json
{ "code": 0, "data": { "stage": "ZONE", "zone": 3, "cuisine": null, "merchant": null } }
```

### 2.3 `POST /api/session/cuisine`

请求:
```json
{ "cuisineId": 12 }
```
约束:必须先有 `zone`。

### 2.4 `POST /api/session/merchant`

请求:
```json
{ "merchantId": 42 }
```
约束:必须先有 `cuisine`。

## 3. 推荐(`/api/recommend` — 需鉴权)

### 3.1 `POST /api/recommend`

请求:
```json
{
  "userMessage": "我想吃点清淡的,不要辣",
  "sessionId": "demo-session-001"
}
```
响应:
```json
{ "code": 0, "data": {
  "recommendations": [
    { "merchantId": 7, "name": "清真食堂", "reason": "清淡口味,无辣菜品" }
  ],
  "tokensUsed": 184,
  "modelVersion": "mock-v1"
} }
```
**降级路径**:`MockChatModel(dev)` → `DashScopeChatModel(bench/smoke)` → `RuleBasedFallbackAdvisor`。
**重试路径**:JSON Schema 校验失败 → `ReflectiveRetryAdvisor` 反思重试(≤2 次)。

## 4. 点赞(`/api/like/**` — 需鉴权)

### 4.1 `POST /api/like/{merchantId}`

幂等语义:同一 `(studentId, merchantId)` 60 秒内只生效一次。

响应(首次):
```json
{ "code": 0, "data": { "liked": true, "merchantId": 7, "at": "2026-09-19T19:30:00Z" } }
```
响应(重复):
```json
{ "code": 0, "data": { "liked": false, "merchantId": 7, "message": "已点赞" } }
```

底层:`Redis SET like:idem:<sid>:<mid> 1 NX EX 60` → 通过则 `rabbitTemplate.send("like.db.write", LikeMessage)`。
落库失败消息进 DLQ `like.db.write.dlq`。

## 5. 商户目录(`/api/merchant/**` — 需鉴权)

### 5.1 `GET /api/merchant/{id}`

三级降级读取:`Caffeine L0 → Redis L1 → MySQL L2`。

响应:
```json
{ "code": 0, "data": {
  "id": 7, "name": "清真食堂", "zoneId": 3, "cuisineId": 12,
  "heat": 87, "openHours": "06:30-21:00",
  "hitTier": "L0"   // 调试用,生产可关
} }
```

### 5.2 `GET /api/merchant?zoneId=3`

按商圈批量获取(同样走 L0→L1→L2)。

## 6. 预热管理(`/admin/preheat/**`)

### 6.1 `POST /admin/preheat/trigger`

手动触发离线预热任务(默认 `@Scheduled` 每 5 分钟跑)。

响应:
```json
{ "code": 0, "data": {
  "merchantCount": 103,
  "zoneCount": 12,
  "hotCount": 10,
  "elapsedMs": 612
} }
```

权限:
- `dev` / `test` / `it` profile:免鉴权(本地调试)
- 其他 profile:需 `ROLE_ADMIN`

## 7. 健康检查(`/actuator/**`)

| 端点 | 鉴权 | 含义 |
|---|---|---|
| `/actuator/health` | 否 | 聚合状态 |
| `/actuator/health/liveness` | 否 | k8s liveness 探针(**不查外部依赖**) |
| `/actuator/health/readiness` | 否 | k8s readiness 探针(含 Redis/Rabbit/MySQL) |
| `/actuator/info` | 是 | 已禁用(防止 git/build/env 泄露) |
| `/actuator/metrics` | 是 | Prometheus 指标 |
