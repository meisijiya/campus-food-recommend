# ADR-0009: Demo & Verification Hardening — 3 项 polish batch

> **状态**:已采纳
> **日期**:2026-09-21
> **决策者**:用户 + orchestrator
> **触发**:F-12 done 后,用户授权"F-1 JMX 30s pre-warm / F-11 default flag ALL_ON demo hardening / init.ps1 stage 6 与 F-7 限流兼容性 一起做"(2026-09-21 01:34 root session)

## 背景

Roadmap F-1~F-12 全 done (commit fa3392b) 后,简历材料就绪。但 init.sh 6/6 stage 在最后两张 ticket 完成时已出现 3 个 polish 缺口,会影响 demo 现场 + 下次冷启复测可信度:

1. **F-1 JMX 缺少 pre-warm 阶段**:cold start 测量会得到偏低 RPS,重复触发 F-1 复审→bullet 修订讨论的循环(2026-09-21 上次会话踩过)
2. **F-11 default flag `recommend-v2` PERCENTAGE 20% + `merchant-detail-new` ALL_OFF**:导致 F-9 4 业务指标 2/4(`recommend_total` 80% 漏,`merchant_view_total` 100% 漏)在 Prometheus scrape 里不出现
3. **init.ps1 stage 6 locust 在 F-7 限流上线后**:50 user burst 撞穿 user-level 100 burst,98.5% 收到 429,stage 6 走 WARN 容忍退 0 但 dashboard 看像 fail

## 决策

**3 项 polish 合并到 F-13 ticket `Demo & Verification Hardening`,一次性做完**:

### 1. F-1 JMX 加 30s pre-warm ThreadGroup

**修法**:`evidence/f1-jmeter.jmx` 加 TG1 (500 threads, ramp 30s, duration 30s, 同一 HTTP sampler) 在 TG2 (500 threads, ramp 10s, duration 60s) 之前。TG1 跑完 JVM 已 JIT warm + Tomcat accept queue 已 hot, TG2 采稳态 evidence。

**风险**:init.ps1 阶段 5 从 60s 拉到 90s (+30s)。可接受。

**bullet 影响**:F-1 bullet "单节点 5000+ QPS" 仍成立(稳态均值 5243 RPS 跨过阈值);TG1 段不计入 evidence,只 warm。

### 2. F-11 default flag `recommend-v2` ALL_ON + `merchant-detail-new` ALL_ON

**修法**:`application.yml` `feature-flag.default-flags` 改:
```yaml
recommend-v2:
  mode: ALL_ON    # was PERCENTAGE/percentage=20
like-cache-bypass:
  mode: WHITELIST_ONLY
  whitelist: [1, 2, 3]   # 保持,F-5 多级缓存 bullet 仍成立
merchant-detail-new:
  mode: ALL_ON    # was ALL_OFF
```

**关键边界**:`like-cache-bypass` **不**改 ALL_ON。原因:ALL_ON 让所有 sid 直查 MySQL,完全绕过 Redis L1 → F-5 bullet"P99 28/25ms"失效。WHITELIST_ONLY [1,2,3] 是 demo 验证用,小流量不影响 F-5。

**prod 行为风险**:prod 启动时 Redis hash 没值(冷启动) → 用 default ALL_ON → 全功能开放。**但 prod 启动后 admin 设 PERCENTAGE 20% → 立即生效(Caffeine L1)**,所以 default 改动**不**影响 prod 长期行为,只影响"Redis 没值时的冷启动 fallback"。

**bullet 影响**:
- F-9 4 业务指标全可见 ✓
- F-5 "P99 28/25ms" 仍成立(`like-cache-bypass` WHITELIST_ONLY 保持)
- F-11 bullet "4 FlagMode" 不变(default 改了不影响 FlagMode 数量)

### 3. init.ps1 stage 6 加 F-7 限流 PASS-WARN 路径

**修法**:`init.ps1` 阶段 6 解析 `evidence/f5-p99_failures.csv`,统计 HTTPError 429 占比:
- 429 占比 < 10% → PASS (现有路径)
- 429 占比 ≥ 10% → PASS-WARN (输出 "F-7 限流设计行为触发,WARN 容忍" + exit 0)
- 其他 error 占比 > 5% → FAIL (exit 1)

**理由**:50 user × rps 撞穿 user bucket 是 F-7 上线后的设计行为(用户 1 个 spam 就能触发),不是 bug。Init 作为"单一可执行约束"的信号可信度保持。

**风险**:无;只改 init.ps1 不改业务代码。

## 约束

- 单 ticket 一次过(沿用 AGENTS.md §11)
- 跑 `pwsh -File init.ps1` 6/6 stage 验证
- commit 信息含 `[F-13]` 前缀
- 不动 F-1~F-12 业务代码,只扩展
- 严格遵守 `init.sh` / `init.ps1` 门禁

## 后果

- 3 项 polish 完成,Roadmap F-1~F-13 全 done
- F-1 复测时 cold start 数字不再偏低,无 bullet 修订风险
- F-9 4 业务指标 demo 现场 Grafana 全可见,面试官问"看板呢"时可直接展示
- init.ps1 stage 6 信号干净(WARN ≠ FAIL,FAIL 只在非 429 错误时触发)

## 排除范围

- 不改 application.yml `rate-limit` 段(不改 prod 限流参数)
- 不改 F-11 admin POST 接口签名(向后兼容)
- 不改 F-1 Tomcat 800 线程(已 F-1 时配套,不动)
- 不修 F-11 PERCENTAGE/WHITELIST_ONLY 内部实现(只改 yaml default)