# F-11 Feature Flag · Startup Scenarios · Prod Evidence

> **Status:** F-13 polish 实战验证(2026-09-21 22:21 CST,cfr-app + Redis 实跑)
> **场景范围:** `FeatureFlagRedisConfig.loadFromRedis` 启动期 4 种 fallback 行为
> **配套:** `docs/observability/feature-flag-defaults.md`(理论契约)+ `src/test/.../FeatureFlagIT.java` 4 个新测试(in-process 验证)
> **本文档定位:** 真实环境端到端 curl 验证,招实习面试现场 + 故障复盘用

---

## 0. 测试环境前置

- 4 cfr 容器都 healthy:`cfr-mysql / cfr-redis / cfr-rabbitmq / cfr-app`
- cfr-app 用最新 jar(本会话 commit `020d945` 后的 build)
- Redis 初始状态(测试开始前):`feature_flags` hash 含上次 IT 测试残留的 recommend-v2=ALL_OFF / like-cache-bypass=WHITELIST_ONLY[1,2,3] / merchant-detail-new=ALL_OFF
- 测试结束后已 DEL feature_flags,cfr-app 重启,所有 flag 回到 yml ALL_ON / WHITELIST_ONLY[1,2,3] / ALL_ON

---

## 1. Scenario 1 · Redis hash empty(cold start)

**Setup**
```bash
docker exec cfr-redis redis-cli DEL feature_flags   # 返 (integer) 1
docker compose restart app
```

**Verification(15s 后 cfr-app healthy)**

```text
GET /actuator/health
→ {"status":"UP","groups":["liveness","readiness"]}

GET /api/feature-flag/recommend-v2/check?studentId=100
→ {"code":0,"message":"ok","data":{"flagKey":"recommend-v2","enabled":true,"mode":"ALL_ON","studentId":100}}

GET /api/feature-flag/like-cache-bypass/check?studentId=1
→ {"code":0,"message":"ok","data":{"flagKey":"like-cache-bypass","enabled":true,"mode":"WHITELIST_ONLY","studentId":1}}

GET /api/feature-flag/merchant-detail-new/check
→ {"code":0,"message":"ok","data":{"flagKey":"merchant-detail-new","enabled":true,"mode":"ALL_ON","studentId":null}}
```

**cfr-app 日志关键行**
```text
INFO  c.m.c.m.f.FeatureFlagRedisConfig : FeatureFlag Redis hash 'feature_flags' is empty (cold start, will use yml defaults)
INFO  c.m.c.m.f.FeatureFlagRedisConfig : FeatureFlag initial configs loaded: total=3 (redis=0, default=3)
```

**结论** ✅ Redis empty → 3 个 demo flag 全走 yml default(ALL_ON / WHITELIST_ONLY [1,2,3] / ALL_ON)。

---

## 2. Scenario 2 · Redis unreachable(降级启动)

**Setup**
```bash
docker compose stop redis          # cfr-redis Stopped
docker compose restart app
```

**Verification(cfr-app 启动 20s 后;Redis 已停)**

```text
GET /actuator/health
→ {"status":"DOWN","groups":["liveness","readiness"]}
  (readiness 包含 redis 健康检查 → DOWN,这是预期:Redis 真停了)

GET /api/feature-flag/recommend-v2/check?studentId=100
→ {"code":0,"message":"ok","data":{"flagKey":"recommend-v2","enabled":true,"mode":"ALL_ON","studentId":100}}

GET /api/feature-flag/like-cache-bypass/check?studentId=1
→ {"code":0,"message":"ok","data":{"flagKey":"like-cache-bypass","enabled":true,"mode":"WHITELIST_ONLY","studentId":1}}

GET /api/feature-flag/merchant-detail-new/check
→ {"code":0,"message":"ok","data":{"flagKey":"merchant-detail-new","enabled":true,"mode":"ALL_ON","studentId":null}}
```

**cfr-app 日志关键行**
```text
WARN  c.m.c.m.f.FeatureFlagRedisConfig : Failed to load feature flags from Redis (RedisConnectionFailureException: ...); falling back to yml defaults
INFO  c.m.c.m.f.FeatureFlagRedisConfig : FeatureFlag initial configs loaded: total=3 (redis=0, default=3)
```

**结论** ✅ Redis 不可达 → catch `RedisConnectionFailureException` → 3 个 flag 全走 yml default;**应用不阻塞**(虽然 readiness probe 显示 DOWN,但 feature flag 端点 100% 可用)。

> **重要设计观察**:F-11 flag 是非关键路径,Redis 不可达时**降级启动**而非 panic。这与 F-7 限流桶的"启动失败立即崩"策略不同 — flag 的 fallback 比限流更宽容。

---

## 3. Scenario 3 · Redis contains malformed JSON(脏数据)

**Setup**
```bash
docker compose start redis
# 故意写入 2 条 malformed entry
docker exec cfr-redis redis-cli HSET feature_flags recommend-v2 'this-is-not-valid-json-garbage'
docker exec cfr-redis redis-cli HSET feature_flags like-cache-bypass '{"mode":"WHITELIST_ONLY","whitelist":[42,99],"percentage":0}'
# 注:PowerShell + docker exec shell 嵌套导致 like-cache-bypass 值变成 {mode:WHITELIST_ONLY,whitelist:[42,99],percentage:0} (无引号),仍是 malformed
docker compose restart app
```

**Verification**

```text
GET /api/feature-flag/recommend-v2/check?studentId=100
→ {"code":0,"message":"ok","data":{"flagKey":"recommend-v2","enabled":true,"mode":"ALL_ON","studentId":100}}

GET /api/feature-flag/like-cache-bypass/check?studentId=42
→ {"code":0,"message":"ok","data":{"flagKey":"like-cache-bypass","enabled":false,"mode":"WHITELIST_ONLY","studentId":42}}

GET /api/feature-flag/like-cache-bypass/check?studentId=1
→ {"code":0,"message":"ok","data":{"flagKey":"like-cache-bypass","enabled":true,"mode":"WHITELIST_ONLY","studentId":1}}

GET /api/feature-flag/merchant-detail-new/check
→ {"code":0,"message":"ok","data":{"flagKey":"merchant-detail-new","enabled":true,"mode":"ALL_ON","studentId":null}}
```

**cfr-app 日志关键行**
```text
WARN  c.m.c.m.f.FeatureFlagRedisConfig : Skip malformed FlagConfig in Redis hash: flag=recommend-v2, value=this-is-not-valid-json-garbage, err=com.fasterxml.jackson.core.JsonParseException: Unrecognized token 'this'
WARN  c.m.c.m.f.FeatureFlagRedisConfig : Skip malformed FlagConfig in Redis hash: flag=like-cache-bypass, value={mode:WHITELIST_ONLY,whitelist:[42,99],percentage:0}, err=com.fasterxml.jackson.core.JsonParseException: Unexpected character ('m' (code 109))
INFO  c.m.c.m.f.FeatureFlagRedisConfig : FeatureFlag initial configs loaded: total=3 (redis=0, default=3)
```

**结论** ✅ Redis 2 条 malformed → 单条 skip + WARN(不阻塞整个 hash)→ yml 全部兜底;**应用仍可用**。

> **意外收获**:PowerShell + docker exec shell 嵌套导致外部单引号被吞掉,实际写入 Redis 的 JSON 缺外层 key 引号(`{mode:...}` 而不是 `{"mode":...}`),这恰好是 Scenario 3 想要的"反例"输入。**面试演示时可现场重放这个 case**。

---

## 4. Scenario 4 · Redis partial + yml fill(merge 语义)

**Setup**
```bash
docker exec cfr-redis redis-cli DEL feature_flags
# 只设 recommend-v2 一个 flag,value 故意跟 yml 不一样(yml ALL_ON,这里 ALL_OFF)
docker exec cfr-redis redis-cli HSET feature_flags recommend-v2 '{"mode":"ALL_OFF","whitelist":[],"percentage":0}'
docker compose restart app
```

**Verification**

```text
GET /api/feature-flag/recommend-v2/check?studentId=100
→ {"code":0,"message":"ok","data":{"flagKey":"recommend-v2","enabled":false,"mode":"ALL_OFF","studentId":100}}
  (Redis ALL_OFF 赢 over yml ALL_ON)

GET /api/feature-flag/like-cache-bypass/check?studentId=1
→ {"code":0,"message":"ok","data":{"flagKey":"like-cache-bypass","enabled":true,"mode":"WHITELIST_ONLY","studentId":1}}
  (yml whitelist [1,2,3],Redis 没这个 key → yml 兜底)

GET /api/feature-flag/like-cache-bypass/check?studentId=42
→ {"code":0,"message":"ok","data":{"flagKey":"like-cache-bypass","enabled":false,"mode":"WHITELIST_ONLY","studentId":42}}
  (sid=42 不在 yml 白名单 → false)

GET /api/feature-flag/merchant-detail-new/check
→ {"code":0,"message":"ok","data":{"flagKey":"merchant-detail-new","enabled":true,"mode":"ALL_ON","studentId":null}}
  (Redis 没这个 key → yml ALL_ON 兜底)
```

**cfr-app 日志关键行**
```text
INFO  c.m.c.m.f.FeatureFlagRedisConfig : FeatureFlag initial configs loaded: total=3 (redis=1, default=3)
```

**结论** ✅ Merge 语义正确:
- Redis 1 个 key(recommend-v2 ALL_OFF)**覆盖** yml 同一 key 的 ALL_ON
- Redis 缺失的 key(like-cache-bypass / merchant-detail-new)由 yml `putIfAbsent` 填补
- `redis=1, default=3` 数字反映:Redis 提供了 1 个,yml 有 3 个(其中 1 个被覆盖,2 个被 putIfAbsent)

---

## 5. 4 场景对比表

| Scenario | Redis 操作 | 期望最终决策 | 实测结果 | cfr-app 行为 |
|---|---|---|---|---|
| 1 cold start | DEL `feature_flags` | 3 flag 全走 yml ALL_ON/WHITELIST_ONLY[1,2,3]/ALL_ON | ✅ 全部命中 | UP,健康检查 UP |
| 2 Redis 不可达 | stop cfr-redis + restart app | 3 flag 全走 yml | ✅ 全部命中 | 启动成功,readiness DOWN(redis probe),flag 端点 100% 可用 |
| 3 脏数据 | HSET 2 条 malformed + restart | yml 兜底所有 flag,WARN 记录跳过条目 | ✅ 全部命中 | UP,日志 2 条 WARN + `redis=0` |
| 4 Redis 部分 | DEL + HSET 仅 recommend-v2 ALL_OFF | Redis 赢 over yml + yml 填补缺失 | ✅ 全部命中 | UP,`redis=1, default=3` |

---

## 6. 复盘要点

1. **Merge 语义是 putIfAbsent**:Redis 优先于 yml,yml 只填补 Redis 缺失的 key。这跟"yml 是默认值"的直觉一致。
2. **3 种 fallback 都正确**:empty / unreachable / malformed → 全部走 yml default,应用不挂。
3. **设计取舍**:Redis 不可达时 flag 是降级启动(可接受),F-7 限流桶则是 panic 启动(必须)—— 体现了"非关键路径宽松,关键路径严格"的设计分层。
4. **API 设计**:所有 4 场景下,`/api/feature-flag/{key}/check` 公开端点 100% 正常返 200 — 不需要客户端区分 flag 来源,业务代码无感。

## 7. 重跑脚本

`scripts/f11-startup-verify.ps1` 提供一键复跑:停起 Redis + 重启 cfr-app + 4 场景 curl 验证 + 还原状态。详见脚本头部注释。