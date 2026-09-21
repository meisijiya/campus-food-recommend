# F-11 Feature Flag · Default Behavior & Startup Scenarios

> **Status:** F-13 polish-B + F-11 polish 后 prod 实战验证产出物(对应 `FeatureFlagRedisConfig.loadFromRedis` 启动期 fallback 路径)
> **适用场景:** 招实习面试 / 故障排查 — 4 种启动场景下 FeatureFlag 的兜底行为 + 运行时观察方法
> **前置依赖:** F-11 W1(`FeatureFlagRedisConfig.loadFromRedis` + `DefaultFeatureFlagService.setConfig` 同步写 Redis hash + 失效 Caffeine)
> **配套产出:**
> - `src/test/.../FeatureFlagIT.java` 4 个新测试(case A/B/C/D)— in-process 验证
> - `evidence/f11-startup-scenarios.md` — 真实环境端到端 curl 验证(2026-09-21 跑出,4 场景 PASS)
> - `scripts/f11-startup-verify.ps1` — 一键复跑 4 场景(本机 + docker daemon 在线即可)
> **不破坏现有调用栈:** 仅补充 prod 启动期行为契约,业务方法签名不变

---

## 1. 启动期 4 种场景

`FeatureFlagRedisConfig.loadFromRedis()` 在 Spring 启动期被调用一次,把 Redis hash `feature_flags` 全量 entries 与 `application.yml` 中 `feature-flag.default-flags` 合并到内存 Map(`volatile Map<String, FlagConfig> configs`)。merge 语义:**Redis 优先**(Redis 有值用 Redis,Redis 缺失用 yml 兜底,`putIfAbsent` 语义)。

| # | 场景 | Redis hash 状态 | loadFromRedis 行为 | merged 结果 | service 是否可启动 |
|---|---|---|---|---|---|
| 1 | **冷启动**(首次部署) | hash key 存在但 entries 为空 Map | `raw == null \|\| raw.isEmpty()` → 返空 Map,日志 INFO | 全部走 yml `default-flags` | ✅ |
| 2 | **Redis 不可达** | `opsForHash().entries()` 抛 `RedisConnectionFailureException` | `catch (RuntimeException)` → 返空 Map,日志 WARN | 全部走 yml `default-flags` | ✅(降级启动) |
| 3 | **Redis 含脏数据** | 部分 entries 是 malformed JSON(反序列化失败) | 单条 skip + WARN 日志,不阻塞整个 hash 加载 | malformed skip,合法条目用 Redis + yml 填补缺失 | ✅ |
| 4 | **Redis + yml 部分交集** | Redis 有 flagA/flagB,yml 有 flagB/flagC | 完整反序列化 Redis → merged.putIfAbsent(yml flagC) | flagA 来自 Redis / flagB 来自 Redis(覆盖 yml) / flagC 来自 yml | ✅ |

**关键设计取舍:**
- Redis 不可达**不阻塞应用启动**(`log.warn` + 继续),与 F-7 限流桶"启动失败立即崩"策略不同 — flag 是非关键路径,降级到 yml default 是可接受的。
- yml `default-flags` 永远作为兜底存在,但**只填补 Redis 缺失的 key,不会覆盖 Redis 已有值**(避免 yml 改动污染 Redis 运行时配置)。

---

## 2. 三个 demo flag 的当前默认值(F-13 hardening 后)

| flagKey | mode | 业务含义 | yml 行号 | 备注 |
|---|---|---|---|---|
| `recommend-v2` | `ALL_ON` | 新推荐路径全量开启(让 F-9 Grafana `recommend_total` 指标持续可见) | `application.yml:142-143` | F-13 改的;F-11 W3 时是 `PERCENTAGE 20` |
| `like-cache-bypass` | `WHITELIST_ONLY [1, 2, 3]` | 白名单 sid 直查 MySQL,绕过 Redis L1 缓存(对比性能用) | `application.yml:144-146` | **F-5 bullet "P99 28/25ms" 依赖此边界,不可改 ALL_ON** |
| `merchant-detail-new` | `ALL_ON` | 商家详情 Map 额外字段 `openHours` + `featureFlag:ON` 全量开放 | `application.yml:147-148` | F-13 改的;F-11 W3 时是 `ALL_OFF` |

---

## 3. 运行时观察方法

### 3.1 公开 check 端点(无需鉴权)

```bash
# 推荐用法:招实习现场演示 + 排障
curl 'http://127.0.0.1:8080/api/feature-flag/recommend-v2/check?studentId=100'
# 预期:{"flagKey":"recommend-v2","enabled":true,"mode":"ALL_ON","studentId":100}

curl 'http://127.0.0.1:8080/api/feature-flag/merchant-detail-new/check'
# 预期:{"flagKey":"merchant-detail-new","enabled":true,"mode":"ALL_ON","studentId":null}

curl 'http://127.0.0.1:8080/api/feature-flag/like-cache-bypass/check?studentId=1'
# 预期:{"flagKey":"like-cache-bypass","enabled":true,"mode":"WHITELIST_ONLY","studentId":1}
curl 'http://127.0.0.1:8080/api/feature-flag/like-cache-bypass/check?studentId=999'
# 预期:{"flagKey":"like-cache-bypass","enabled":false,"mode":"WHITELIST_ONLY","studentId":999}
```

### 3.2 Admin POST 端点(需 ADMIN JWT,改 Redis hash)

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -r .data.accessToken)

# 切换 recommend-v2 → PERCENTAGE 50
curl -X POST http://127.0.0.1:8080/admin/feature-flag/recommend-v2 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"mode":"PERCENTAGE","percentage":50}'

# 立即生效(下次请求已看到新 mode,无需重启应用)
curl 'http://127.0.0.1:8080/api/feature-flag/recommend-v2/check?studentId=42'
# 预期:{"flagKey":"recommend-v2","enabled":<true|false 取决于 hash>,"mode":"PERCENTAGE","studentId":42}
```

### 3.3 Prometheus 指标(`/actuator/prometheus`,需 prod profile)

| Metric | 标签 | 含义 |
|---|---|---|
| `feature_flag_check_total` | `flag={flagKey}`, `decision={true\|false}` | 每次 `isEnabled` 调用计数 — 反向验证灰度比例(`true / (true + false)` ≈ `PERCENTAGE n`) |
| `feature_flag_update_total` | `flag={flagKey}` | Admin POST 触发 `setConfig` 次数 — 监控灰度节奏 |
| `flag_hit_timer_seconds` | `flag={flagKey}` | `isEnabled` 决策耗时直方图(应 < 1ms,Caffeine 命中) |

Grafana panel 已搭,见 `docs/observability/grafana-overview.json`。

### 3.4 启动日志关键字

```text
# 场景 1(cold start)
INFO ... FeatureFlag Redis hash 'feature_flags' is empty (cold start, will use yml defaults)

# 场景 2(Redis 不可达)
WARN ... Failed to load feature flags from Redis (RedisConnectionFailureException: ...); falling back to yml defaults

# 场景 3(脏数据)
WARN ... Skip malformed FlagConfig in Redis hash: flag=xxx, value={...}, err=UnrecognizedPropertyException

# 任何场景的最终合并结果
INFO ... FeatureFlag initial configs loaded: total=3 (redis=0, default=3)
INFO ... DefaultFeatureFlagService initialized: total flags=3, enabled=true
```

---

## 4. FeatureFlagIT 4 个 prod 启动验证测试

`src/test/java/com/meisijiya/campusfood/module/featureflag/FeatureFlagIT.java` 在 F-13 polish-B 阶段补写 4 个新测试,对应上文 4 种启动场景的运行时行为验证(代码路径已被 Spring context 启动期固化,直接断言合并后的 `configs` / `properties.defaultFlagsView()` / `listFlags()` / `setConfig` 立即生效路径)。

| 测试方法 | @DisplayName | 验证目的 |
|---|---|---|
| `caseA_redisHashEmpty_ymlDefaultsApplied` | case A: Redis hash empty at startup → yml default-flags 3 个全部生效 | 场景 1 + 2:`properties.defaultFlagsView()` 含 3 个 key,`listFlags()` 含 3 个 demo flag |
| `caseB_setConfigImmediatelyEffective` | case B: setConfig 写后 configs 内存 Map 立刻反映 Redis 值 | 场景 4:`setConfig` 同步覆盖 yml default + `isEnabled` 立即反映新 mode |
| `caseC_yamlNotConfigured_redisNotWritten_isEnabledReturnsFalse` | case C: yml 没配 + Redis 没写的 flag → isEnabled 安全返 false | 边界:flagKey 既不在 yml 也不在 Redis → `isEnabled` 返 false(安全默认) |
| `caseD_setConfigNull_throwsButServiceHealthy` | case D: setConfig(null config) 抛 IllegalArgumentException,service 不挂掉 | 健壮性:参数校验在 Redis 写之前,异常不污染 `configs` / Caffeine |

**当前测试统计**:`Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`(原 9 + 新 4,F-13 polish-B 2026-09-21)。

---

## 5. 排障 checklist(招面试 / 故障复盘用)

1. **flag 没生效?** → `curl /api/feature-flag/{key}/check` 看当前 mode + enabled,先确认 Redis hash 真实值
2. **应用启动慢?** → grep 日志 `Failed to load feature flags from Redis`,Redis 不可达会触发降级但不阻塞
3. **灰度比例不准?** → Prometheus `feature_flag_check_total{flag,decision}` 反推 `true / (true+false)` 应 ≈ yml / Redis 配置的 PERCENTAGE
4. **admin POST 后没生效?** → 确认 `setConfig` HTTP 200 + 立即再调 `check` 端点(不走 Caffeine 缓存路径)
5. **改动 yml 没生效?** → yml 仅启动期生效,运行时改 yml **必须重启应用**;运维期改 flag 走 admin POST 写 Redis hash(立即生效)
6. **复跑 4 启动场景验证?** → `pwsh -File scripts/f11-startup-verify.ps1`(需 docker daemon + cfr 容器),EXIT=0 即 4/4 验证通过;evidence 落到 `evidence/f11-startup-scenarios.md` 留作下次对照