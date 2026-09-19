# CI/CD 架构 — GitHub Actions 3 Workflow 协作

> 本目录说明 `.github/workflows/` 下 3 个 workflow 的触发矩阵、职责边界、与本机 `init.ps1` 6 stage 验证门禁的关系。
>
> 决策来源:[ADR-0007 §F-10 / Q13](https://github.com/meisijiya/campus-food-recommend/blob/main/docs/adr/0007-multi-ticket-roadmap.md),[F-10 ticket](https://github.com/meisijiya/campus-food-recommend/blob/main/.scratch/campus-food-recommend/issues/10-F10-ci-cd.md)。

---

## 1. 三 Workflow 协作图

```
                        ┌─────────────────────────────────────────────┐
                        │  本机(Windows PowerShell,开发自验)          │
                        │  pwsh -File init.ps1                         │
                        │   1/6 mvn package                            │
                        │   2/6 mvn test                               │
                        │   3/6 docker compose config -q               │
                        │   4/6 docker compose up + /actuator/health   │
                        │   5/6 JMeter F-1 5000+ QPS                   │
                        │   6/6 locust F-5 P99 < 50ms                  │
                        └─────────────────────────────────────────────┘
                                       ▲
                                       │ (本机兜底,任何 stage 失败即阻塞合并)
                                       │
   PR open/sync ─────────────► ┌──────────────────┐
   (含 /label qps-sensitive)  │ pr-smoke.yml     │  W1 创建,本文不动
                              │  8 min,ubuntu    │
                              └────────┬─────────┘
                                       │ PR 含 qps-sensitive label 或 [qps] title
                                       ▼
                              ┌──────────────────┐
                              │ qps-sensitive.yml│  W1 创建,本文不动
                              │  12 min,ubuntu   │
                              │  + JMeter F-1    │
                              └────────┬─────────┘
                                       │ PR merged
                                       ▼
                              ┌──────────────────┐
                              │main-regression   │  ◄── 本次(W2)新增
                              │  15 min,ubuntu   │
                              │  mvn verify +    │
                              │  locust dry-run  │
                              │  + commit audit  │
                              │  + artifact      │
                              └──────────────────┘
```

---

## 2. 触发矩阵

| workflow | 触发 | runner | timeout | 范围 | artifact |
|---|---|---|---|---|---|
| **pr-smoke.yml**(W1) | `pull_request` open / synchronize | `ubuntu-latest` | 8 min | `mvn -B verify` + locust 语法 dry-run | `evidence/*.csv`(retention 7 天) |
| **qps-sensitive.yml**(W1) | PR label = `qps-sensitive` OR PR title 含 `[qps]` | `ubuntu-latest` | 12 min | pr-smoke + JMeter F-1 5000+ QPS(基于 `evidence/f1-jmeter.jmx`) | `evidence/*.jtl` + `evidence/*.csv`(retention 7 天) |
| **main-regression.yml**(W2) | `push main` + `workflow_dispatch` | `ubuntu-latest` | 15 min | `mvn -B verify -DskipBenchITs` + locust collect-only + commit audit + artifact 上传 | `evidence/*.csv` + `*.log` + `*.jtl` + `*.json`(retention **14 天**) |

> **双保险**:qps-sensitive.yml 的 label + title 双触发条件避免单一通道漏掉关键 PR,详见 §6。

---

## 3. 本地门禁 vs CI:职责切割

### 本机 `init.ps1` 仍是单一验证门禁(不可替代)

| stage | 本机 init.ps1 | CI main-regression | 原因 |
|---|---|---|---|
| 1/6 mvn package | ✅ | (合并到 stage 2) | — |
| 2/6 mvn test | ✅ | ✅ `mvn -B verify` | Spring Boot test 走 Mock profile |
| 3/6 docker compose config -q | ✅ | ❌ | **Ubuntu runner 无 docker 中间件**;compose 文件合法性只在 PR review + 本机兜底 |
| 4/6 compose up + /actuator/health | ✅ | ❌ | **Ubuntu runner 无 MySQL/Redis/RabbitMQ**;dev profile 启动需 docker compose;CI 不起中间件 |
| 5/6 JMeter F-1 5000+ QPS | ✅ | ❌ | **JMeter 走 Windows 路径** + 需 live cfr-app 实例 + Windows `D:\Environment\apache-jmeter-5.6.3\bin\jmeter.bat` |
| 6/6 locust F-5 P99 < 50ms | ✅ | ❌(改 collect-only) | **Ubuntu runner 无 live cfr-app**;locust 实际发请求压测仅在本机做;CI 只校验 locustfile 语法 / 导入 |

### 总结

- **本机 `init.ps1` 6/6 = 完整集成验证门禁**(包含中间件启动 + 真实压测)。
- **CI = 编译 + 单测 + 语法校验 + commit 规范审计 + 产物归档**,**不替代**本机门禁。
- 任何 ticket 在声称 done 之前必须本机 `pwsh -File init.ps1` 退出码 0 + 所有 6 stage 通过(AGENTS.md §3 / §5)。
- CI 失败必须先排查再合 main;本机门禁只兜底"提交人本地能跑",CI 兜底"main 上所有人 / 所有 runner 都能跑"。

---

## 4. 缓存策略

| workflow | 缓存内容 | 实现 | key |
|---|---|---|---|
| pr-smoke / qps-sensitive / main-regression | `~/.m2/repository` | `actions/setup-java@v4` 内置 `cache: 'maven'` | 自动基于 `**/pom.xml` hash;恢复回退到 `restore-keys` |

- 不单独缓存 `target/`:Maven 增量构建在第二次 PR 上 < 3 分钟(`mvn -B verify` 命中缓存增量 < 1 分钟),无需额外缓存层。
- 不缓存 uv/locust:`pip install uv` + `uv pip install --system locust` 在 ubuntu-latest 镜像上 < 30 秒,不值得缓存。
- 不缓存 evidence:每次 PR / main 都重新生成,无意义。

---

## 5. Artifact 策略

| workflow | artifact name | path | retention | if: always() |
|---|---|---|---|---|
| pr-smoke | `evidence-pr-<sha>` | `evidence/*.csv` | 7 天 | ✅ |
| qps-sensitive | `evidence-qps-<sha>` | `evidence/*.jtl` + `evidence/*.csv` | 7 天 | ✅ |
| **main-regression** | `evidence-main-<sha>` | `evidence/*.csv` + `*.log` + `*.jtl` + `*.json` | **14 天** | ✅ |

- `if: always()` 保证 mvn verify 失败时仍上传产物(便于事后定位)。
- `if-no-files-found: ignore` 防止空 evidence 目录导致 upload 步骤失败。
- main 分支 retention 长于 PR:14 天足够 PR-合并-合并-部署-回滚的一周节奏。
- `.gitignore` 已排除 `evidence/*.csv` / `evidence/*.jtl` / `evidence/*.json`(部分),artifact 在 GitHub UI 下载即可,不污染仓内。

---

## 6. qps-sensitive 触发细节(W1 同事实现)

PR 想触发 JMeter F-1 5000+ QPS 跑测,**任一**条件即可:

1. **PR 打了 label**:`qps-sensitive`
2. **PR title 含**:`[qps]`(大小写不敏感)

CI 配置示例(W1 同事落地):

```yaml
on:
  pull_request:
    types: [opened, synchronize, labeled, unlabeled]
jobs:
  qps:
    if: |
      contains(github.event.pull_request.labels.*.name, 'qps-sensitive') ||
      contains(github.event.pull_request.title, '[qps]')
    # ...
```

**为什么双保险**:
- label 适合"我改了限流 Lua,想看下 5000 QPS 下 Redis 抗不抗"(主动 opt-in)
- title 适合"紧急 hotfix,默认就当 QPS 敏感 PR 跑"(被动,merge 时省一步)
- 两个都设置时只跑一次(`if` 是 OR 不是 AND,不会重复跑)

---

## 7. Commit 消息规范与 CI 审计

### 规范(AGENTS.md §3 / §4 / §18)

```
[F-N][<Agent>] <summary>
```

- `[F-N]`:**必须**,N 为 ticket 编号(F-1 ~ F-12)
- `[<Agent>]`:可选,多 worker 模式下加 W1/W2/W3 用于审计并行谁改了什么(F-7 起开始用)
- `summary`:中文一句话,描述本 commit 干了什么

### CI 审计(main-regression.yml 的"commit audit"步骤)

- 触发时机:每次 push main 跑一次
- 扫描范围:最近 20 commit(`git log --oneline -20`)
- 失败条件:最近 20 commit **无任何** `[xxx]` 前缀(`grep -E '\[[A-Za-z0-9_-]+\]'`)
- 失败行为:`exit 1` 阻合并,要求 PR 作者补 commit prefix 后重跑
- 通过条件:有 ≥ 1 条带 ticket prefix 的 commit,允许少量 merge commit / 自动 chore commit 不带 prefix

### 修复示例

违规 commit:
```bash
abc1234 修复鉴权漏洞
```
修复(非交互 reword):
```bash
git rebase -i HEAD~1  # 把 pick 改成 reword
# 编辑器内改为:[F-1][W2] 修复鉴权漏洞
```

---

## 8. 排除范围(本 ADR 不做)

以下事项在本 ADR-0007 §F-10 决策时**显式排除**,留给未来 ADR:

1. **Docker 镜像产物**: `Dockerfile.ci` + `docker push ghcr.io/meisijiya/campus-food-recommend:pr-<num>`
   - **不做原因**:需要 GitHub `GITHUB_TOKEN` + container registry secrets 配置;本项目 demo 阶段不需要。
   - **何时引入**:实际部署时(预计 F-12 之后,不在 F-7~F-12 路线图内)。

2. **完整 init.ps1 6 stage 在 CI 跑**:
   - **不做原因**:Ubuntu runner 无 docker compose / Windows JMeter / live cfr-app;硬上会需要 GitHub Actions services + self-hosted runner,复杂度溢出 demo 边界。
   - **何时引入**:有 self-hosted runner + 真中间件环境时。

3. **多 runner matrix**(ubuntu + macOS + Windows):
   - **不做原因**:本项目 lockfile 锁 JDK 21 + Spring Boot 3.5.16,跨平台差异不显著;Ubuntu 单 runner 已覆盖 99% 用例。
   - **何时引入**:引入 native binary / JNI 时再考虑。

4. **Slack / 钉钉通知**:
   - **不做原因**:demo 阶段用户直接看 GitHub Actions UI;通知噪音大于价值。
   - **何时引入**:多 PR 并行频繁时。

---

## 9. 文件落点

| 路径 | 创建者 | 状态 |
|---|---|---|
| `.github/workflows/pr-smoke.yml` | F-10 W1 | 待落地 |
| `.github/workflows/qps-sensitive.yml` | F-10 W1 | 待落地 |
| `.github/workflows/main-regression.yml` | F-10 **W2**(本次) | ✅ 已落地 |
| `docs/cicd/README.md` | F-10 **W2**(本次) | ✅ 已落地 |

---

## 10. 参考

- [ADR-0007 §F-10 / Q13 多 Ticket 路线图](../adr/0007-multi-ticket-roadmap.md)
- [F-10 ticket](https://github.com/meisijiya/campus-food-recommend/blob/main/.scratch/campus-food-recommend/issues/10-F10-ci-cd.md)
- [ADR-0003 技术栈版本表](../adr/0003-stack-versions-and-jdk21.md)(JDK 21 / Spring Boot 3.5.16)
- [ADR-0004 locust 替代 wrk](../adr/0004-locust-replaces-wrk.md)(locust 工具链)
- [ADR-0005 JMeter for F-1 QPS evidence](../adr/0005-jmeter-for-f1-qps-evidence.md)(JMeter 仅走 Windows 本机)
- [AGENTS.md §3 / §4 / §18](https://github.com/meisijiya/campus-food-recommend/blob/main/AGENTS.md)(commit 规范 + 多 worker 边界)