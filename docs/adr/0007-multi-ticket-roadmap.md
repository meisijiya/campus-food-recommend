# ADR-0007: 多 Ticket 路线图 — F-7 ~ F-12

> **状态**:已采纳
> **日期**:2026-09-19
> **决策者**:用户 + orchestrator,grill-with-docs skill(3 轮 15 题)
> **触发**:F-6 demo-readiness done,用户在 2026-09-19 21:19 root session 显式授权"先做 2(F-6),后做 3(实战新 bullet 用 grill-with-docs 好好讨论)"

## 背景

5-bullet(F-1 ~ F-5)+ demo-readiness(F-6)流水线 2026-09-19 done。用户希望在此 demo 基础上继续扩展,但范围需要先 grill 清楚,避免做无用功。

## 决策

**新增 6 个 ticket(F-7 ~ F-12),按顺序实施,严格遵守现有技术栈**(MySQL / Redis / RabbitMQ / Caffeine / Spring AI),仅显式引入 Prometheus + Grafana 作为可观测性工具(原 §9 边界的小幅扩展,本 ADR 授权)。

### 路线图总览

```
F-7 限流 → F-8 分布式锁 → F-9 可观测性 → F-10 CI/CD → F-11 灰度 → F-12 简历包装
(bullet)    (bullet)        (工程亮点附注)  (工程项)    (工程项)    (产出形态)
```

| ticket | 范围 | 依赖 | 简历形态 |
|---|---|---|---|
| **F-7** Rate Limit | 双层令牌桶(用户级 + API 全局),Redis Lua,Caffeine 降级 | F-1 鉴权 + Redis | **新 bullet** |
| **F-8** Distributed Lock | 预热防重(@Scheduled 多实例)+ 点赞幂等升级,SETNX + 看门狗 | F-4 @Scheduled + F-5 点赞 + Redis | **新 bullet** |
| **F-9** Observability | 业务指标 4 + 技术指标 6,Micrometer + Prometheus + Grafana | F-4 / F-5 缓存层 | **工程亮点附注** |
| **F-10** CI/CD | GitHub Actions:PR 跑 locust,main 跑 init.ps1 6 stage | F-1 ~ F-9 全 done | 工程亮点附注 |
| **F-11** Feature Flag | Redis 配置中心 + 三种灰度模式(开关/白名单/比例)| F-7 限流 + F-9 指标 | 工程亮点附注 |
| **F-12** Resume Crafting | resume-crafting 把 5 + 2 = 7 bullet 打包 + cheat sheet | F-7 ~ F-11 全 done | **产出形态** |

### F-7 ~ F-11 设计决策细节(grill 15 题答案沉淀)

| 决策点 | 决定 | 来源 |
|---|---|---|
| 限流维度 | 双层:用户级(`rate:user:<sid>`) + API 全局(`rate:api:<endpoint>`) | Q6=a |
| 限流算法 | 令牌桶(Lua 原子) | Q6=a |
| 限流降级 | Caffeine 进程内降级(Redis 挂时切 JVM-local) | Q10=c |
| 锁场景 | (a) 预热防重 + (b) 点赞幂等升级 | Q7=d |
| 锁 TTL + 续期 | 30s + 10s(标准实践) | Q11=a |
| 业务指标 | 4 个:like_count / recommend_latency / cache_hit_ratio / session_stage_distribution | Q8 |
| 技术指标 | 6 个:tomcat_threads / hikari_pool / redis_pool / jvm_memory / gc_pause / http_requests | Q8 |
| tag 维度 | endpoint + hit_tier(L0/L1/L2) | Q12=b |
| CI 压测 | PR 跑 locust;main 跑完整 init.ps1;QPS 敏感 PR 加跑 JMeter | Q13=d |
| 灰度模式 | 三种:功能开关 + 白名单 + 比例灰度 | Q14=c |
| 简历 bullet | 7 bullet(5 旧 + 限流 + 分布式锁),可观测性 + 灰度作为附注 | Q15=b |

### 技术栈扩展边界(本 ADR 显式授权)

| 中间件 | 原 §9 状态 | 本 ADR 决定 | 理由 |
|---|---|---|---|
| Prometheus | ❌ 未授权 | ✅ 授权 | 可观测性工具,非核心业务中间件 |
| Grafana | ❌ 未授权 | ✅ 授权 | 可视化层,无业务侵入 |
| GitHub Actions | ❌ 未授权 | ✅ 授权 | CI/CD,仓内 .github/workflows/ |
| 其他中间件 | 继续禁止 | 不变 | 严格 §9 边界 |

### 排除范围

- 引入 Sentinel / Resilience4j 做限流(自研 Redis Lua,见 F-7)
- 引入 Redisson 做分布式锁(自研 SETNX + 看门狗,见 F-8)
- 引入 Spring Cloud Sleuth / OpenTelemetry 做 trace(留 F-13+ 扩展)
- 引入 Nacos / Consul 做配置中心(Redis hash 已够用,F-11)
- 真流量调度(按比例切流量到不同版本)— 仅做 feature flag 接口,demo 场景演示

## 约束

- 单 session 只做一个 ticket(沿用 AGENTS.md §11 one ticket at a time)
- 每个 ticket 都跑 `bash init.sh` / `pwsh -File init.ps1` 6/6 stage 验证
- commit 信息含 `[F-N]` 前缀
- 不动现有 F-1 ~ F-5 业务代码,只扩展
- 严格遵守 `init.sh` / `init.ps1` 门禁

## 后果

- F-7 ~ F-11 6 个 ticket skeleton 已落 `.scratch/campus-food-recommend/issues/`,状态 `pending`,等下次 session 启动实施
- 用户 23:11 grill 收尾后,本 ADR 取代"模糊的方向"为可执行清单
- F-12 简历包装在 F-7 ~ F-11 全 done 之后做,产出 `resume-crafting` skill 调用
