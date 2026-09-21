# ADR-0010: 接口文档定稿 — docs/api/api-reference.md 全量扩展

> **状态**:已采纳
> **日期**:2026-09-22
> **决策者**:用户 + orchestrator,2026-09-22 root session（用户在初轮以"定接口文档"指明范围;后续澄清"是整个后端的接口文档,不需要开干全栈"）
> **触发**:F-1 ~ F-14 全部 done,后端 demo 能力完整(7 bullet 已 evidence 化)。但 `docs/api/api-reference.md` 自 F-6 demo-readiness 2026-09-19 创建后未扩充,缺 F-7 限流 / F-9 可观测性 / F-11 FeatureFlag 接口,无法支持面试官自助查询与新接入同学快速 onboard。

## 背景

`docs/api/api-reference.md`(2026-09-19 由 F-6 demo-readiness 落地,4592 bytes)当前覆盖：

- §0 通用约定(响应格式 + 6 个错误码:0/40000/40100/40300/40400/40900/50000)
- §1 鉴权(`/api/auth/login`、`/api/auth/refresh`)
- §2 会话槽位(`/api/session/init|zone|cuisine|merchant`)
- §3 推荐(`/api/recommend`)
- §4 点赞(`/api/like/{merchantId}`)
- §5 商户目录(`/api/merchant/{id}`、`/api/merchant?zoneId=`)
- §6 预热管理(`/admin/preheat/trigger`)
- §7 健康检查(`/actuator/health*`)

落地后(2026-09-19 ~ 2026-09-22)已上线但**未入文档**的接口与错误码：

| 缺漏 | 来源 | 严重程度 |
|---|---|---|
| F-7 限流 42900(`Retry-After` header + Caffeine 降级状态) | commit `88cdd62` / F-7 ticket done 2026-09-19 | **高**(限流是 bullet,无文档会被面试官质疑"被限流时前端拿到什么") |
| F-9 Observability `/actuator/prometheus`(prod profile only)+ 4 业务 + 6 技术指标名 | commit `82c0eb1` / F-9 ticket done 2026-09-20 | **高**(指标是可观测性 bullet,无清单无法回答"你暴露了哪些指标") |
| F-11 FeatureFlag 公开 check(`GET /api/feature-flag/{key}`)+ Admin toggle(`POST /admin/feature-flag/{key}`)+ 4 FlagMode | commit `5901a7b` / F-11 ticket done 2026-09-20 | **高**(灰度是工程亮点附注,无接口文档面试官无法自助 curl) |
| F-8 分布式锁无外部接口,但错误码 40900 业务冲突适用于锁失败 | — | **中**(可读 F-7 接口说明引用) |
| 42200 Schema Violation(F-3 反思重试 2 次仍不合规) | commit `84a3131` | **中**(JSON Schema 失败目前无错误码,前端拿到 500) |

## 决策

**不动后端代码**(完全 doc-only 工作),只扩展 `docs/api/api-reference.md`：

| 操作 | 内容 |
|---|---|
| §0 错误码表扩展 | 新增 `42200`(Schema Violation)、`42900`(Rate Limited,带 Retry-After 语义说明) |
| §7 健康检查扩 | 加 `/actuator/prometheus` 端点(prod profile only)+ 4 业务指标名 + 6 技术指标名 |
| 新增 §8 限流(F-7) | `/api/**` 所有路径的限流语义 + 429 拒绝响应格式 + 降级指标 |
| 新增 §9 FeatureFlag(F-11) | 公开 check + Admin POST 接口 + 4 FlagMode(ENABLED/DISABLED/WHITELIST/PERCENTAGE)速查 |
| 锁定 §10 | **不写 demo endpoint 索引**(本轮不做全栈 demo)—— 留作 F-16 后续扩展时的 hooks |

## 约束

- 完全 doc-only,**不动任何 Java 代码 / application.yml / pom.xml**
- 文档内容必须与最新代码一致:路径 / 方法 / Body / 响应字段 / 错误码 / RBAC / Header 全部从 controller 源码 grep 出来
- 错误码完整化:`0 / 40000 / 40100 / 40300 / 40400 / 40900 / 42200 / 42900 / 50000`
- 每个新增 endpoint 段必须含:`curl 示例` + `权限要求` + `成功响应` + `失败响应`(401/403/429)+ `关键 Header`
- §0 通用约定维持现状(响应包装 `ApiResponse<T>` 不变)
- ADR-0007 边界不动(本 ADR 仅 doc,不动技术栈)

## 排除范围(不做)

- **不做全栈 demo**(用户 2026-09-22 明确"不需要开干全栈")—— F-16 全栈 frontend 任务留作未来扩展
- **不生成 OpenAPI 3 yaml**(不引入 springdoc-openapi 依赖)
- **不写 e2e 冒烟脚本**(`/scripts/demo.py` 已有,F-12 包装,CFR 不重写)
- **不覆盖 Service 内部接口**(只 Controller 公开 endpoint)
- **不写 ER 图**(数据模型以 MySQL Flyway migration 为真相源,不在 doc 重抄)

## 实施切片

| ticket | 内容 | 状态 |
|---|---|---|
| F-17 | `docs/api/api-reference.md` 扩展定稿(本 ADR 落地) | ready-for-agent → in-progress → done |
| F-15 | RBFA.order 修复(Spring AI advisor chain first-attempt 拦截恢复,F-14 §残留风险第 3 条已识别) | ready-for-agent(本 ADR 之前 fix,后续会话独立 commit) |
| F-16 | 全栈 demo Vue3 + Vite + Element Plus(**deferred / 未来扩展**) | wontfix(本轮不实施) |

## 后果

- 一会话内产出:`docs/api/api-reference.md` 扩展版(F-7/F-9/F-11 全覆盖)+ commit `[F-17]`
- ADR-0010 与项目主仓库 `.gitignore` 不冲突,文件路径 `docs/adr/` + `docs/api/` 均 tracked
- 不影响 `mvn test` / `mvn verify` / `init.ps1` 6 stage 门禁
- 后续 2 个 work pool 待命:
  - **F-15**(可立即开):RBFA order 调整一行 + 注释同步 + 单测验证,预计 5~8 minute 单 commit
  - **F-16**(未来扩展,无 deadline):全栈 demo,如真要做先开新 ADR + 新 ticket 体系
- 面试覆盖:面试官打开仓库 `docs/api/api-reference.md` 即可看到完整接口契约 + curl 复现路径,无需再翻代码

## 文件变更(tracked)

| 路径 | 类型 | 说明 |
|---|---|---|
| `docs/api/api-reference.md` | M | 主编辑对象(扩展 §0 错误码 + §7 + §8 + §9) |
| `docs/adr/0010-api-reference-finalize.md` | A | 本 ADR |
| `.scratch/campus-food-recommend/issues/17-F17-api-reference-finalize.md` | A | 工单(本轮 active) |
| `.scratch/campus-food-recommend/issues/15-F15-rbfa-order-fix.md` | A | 工单(后续 active) |
| `.scratch/campus-food-recommend/issues/16-F16-fullstack-vue3-demo.md` | A | 工单(deferred status) |

## 副作用

- 完全 doc-only,无运行时副作用
- 不动 `init.sh` / `init.ps1` 门禁
- 不动现有 5+2 bullet 的 evidence 量化口径
- 不动任何 ADR 决策(本 ADR 是 ADR-0007 子集,纯 doc 扩展,不引入新中间件/新栈)

## 反例(被显式排除)

- 不要在文档里把 `Retry-After` 值写成固定秒数(实际是 rate limit 配置项,动态)
- 不要在文档里把 Prometheus 指标名加上 tag 维度示例(避免承诺与实现不一致)
- 不要把 F-11 接口路径写"猜测"——必须 `grep @RequestMapping` 实测
