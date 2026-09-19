# ADR-0006: Demo Readiness — 生产化 Hardening 范围

> **状态**:已采纳
> **日期**:2026-09-19
> **决策者**:用户 + orchestrator
> **触发**:5-bullet(F-1 ~ F-5)流水线 2026-09-19 done,用户在 root session 21:19 显式授权"先做 2(生产化 hardening),后做 3(实战新 bullet)"。

## 背景

AGENTS.md §1 写明项目目标是**逐条复现简历 5 bullet**,§7 强调"任何不在 bullet 范围内的需求都必须先开 ADR"。F-6 demo-readiness 不在简历 bullet 范围,本 ADR 显式授权。

## 决策

新增 `F-6 demo-readiness` ticket,范围仅限"招实习前可演示的 demo 形态",**禁止**趁机扩展业务功能(那是 F-7+ 范围,见后续 grill-with-docs 讨论)。

### 纳入范围

| # | 项目 | 理由 |
|---|---|---|
| 1 | Health Groups(liveness / readiness) | k8s 标准探针模式,招实习面试官提"生产化"时可演示 |
| 2 | LICENSE(MIT) | 招实习友好,公开仓必备 |
| 3 | ARCHITECTURE.md(mermaid) | 5 bullet 数据流图,辅助面试官 5 分钟看懂 |
| 4 | README.md 重写 UTF-8 | 现状 GBK 错乱,首屏直接劝退 |
| 5 | docs/api/api-reference.md | 11 个端点列表 + curl |
| 6 | scripts/demo.{sh,ps1} | 一键演示,降低面试官认知成本 |
| 7 | .editorconfig / .gitattributes | 仓面印象分 |

### 排除范围(留给 F-7+)

- Springdoc OpenAPI + Swagger UI — 视觉加分但体积大,且面试官更爱读 README + curl
- GitHub Actions CI/CD 完整 pipeline — 用户已在 init.ps1 跑门禁,CI 不是优先级
- Prometheus / Grafana 监控集成 — Spring Boot Actuator metrics 已暴露,够用
- 容器镜像推送到 Docker Hub / Harbor — 用户没部署需求
- k8s manifests / Helm chart — 同上
- HTTPS / Let's Encrypt — 本地 demo 无意义

## 约束

- 不引入新中间件(AGENTS.md §9)
- 包名前缀 `com.meisijiya.campusfood.*` 不变
- 一致性:`init.ps1` 6/6 stage 仍 PASS,新代码必须有相应测试覆盖
- commit 信息含 `[F-6]` 前缀

## 后果

- 用户 21:19 message "先做2,后做3,3 用 grill-with-docs" 已被本 ADR + ticket 拆分
- 后续 F-7+ 实战新 bullet 的方向由 `mattpocock-skills:grill-with-docs` 决定
