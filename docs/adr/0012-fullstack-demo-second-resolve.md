# ADR-0012: 全栈 demo 二次决议 — F-16.1+ 实施路线图

> **状态**:已采纳
> **日期**:2026-09-22
> **决策者**:用户 + orchestrator,grill-with-docs skill(3 轮 7 题:Round 1 Q1 / Round 2 Q2-Q3 / Round 3 Q4-Q6-Q7)
> **触发**:F-20 design.md(ADR-0011)落地后,用户 2026-09-22 root session 显式"开新一轮"启动 F-16.1+ 实施。按 ADR-0011 决策 8,启动实施必须先开本 ADR + grill 重启,**不允许跳过直接 `npm create vite`**。

---

## 决策一览(基于 grill 答复)

| # | 决策 | 答复 | 取代 |
|---|---|---|---|
| D1 | 新一轮范围 | F-16.1+ 前端骨架实施 | 不实施 / 新功能 |
| D2 | 工单文件创建 | F-16.1 ~ F-16.5 **本轮一起创建**,status 全 `ready-for-agent`;F-16.1 标 `in-progress` | 分批 / 全 pending |
| D3 | F-16.1 骨架栈 | **严格空壳**:Vue3 + Vite + Pinia + Vue Router 4 + Axios + Tailwind + tokens.css 同步 + 4 view 占位 + gitignore | + JWT / + 测试底座 / 全栈 |
| D4 | 包管理器 | **npm** | pnpm / yarn |
| D5 | Node 版本 | **本机 Node v24.4.0(实测,2026.9)**,不锁 22 LTS | — |
| D6 | 占位 view 内容 | **占位 + 视觉 token demo**(色块 / 字号示例) | 纯空白 / + 路由状态卡 |
| D7 | init.ps1 stage 8 | **build + vue-tsc --noEmit**,`frontend/` 不存在时跳过 | 不加 / 仅 build / + vitest |

---

## D1 — 新一轮范围 = F-16.1+ 前端骨架实施

- **被推翻方案**:
  - 不开新一轮 ticket,只 polish 现有 design.md(本轮回到 deliverable 状态,不推进)
  - 开新一组 bullet / 全新功能(与设计决策无关)
- **理由**:用户上一轮"open新一轮"连续确认走 F-16.1+ 实施;design.md 既然已达"可用可上线的 demo 状态",**实施路径就在桌面上**;不走 D1 的话 design.md 停在契约层不产生工程价值,违背 Q1 锁定。

## D2 — F-16.1~F-16.5 工单文件本轮一起创建

- **被推翻方案**:
  - 分批:本轮只创建 F-16.1,F-16.2+ 等 F-16.1 done 后逐个开(节奏更稳但信息散落多会话)
  - 全 pending:5 个工单一并创建但 status 全 pending,F-16.1 in-progress 由用户拍板(留更多主动权但易漏)
- **理由**:5 子工单 scope 已在 ADR-0011 §2.2 锁定,工单文件是 status tracking 占位,一次性创建清晰可追溯;与 F-7~F-9 多 ticket 路线图(ADR-0007)同一模式。

## D3 — F-16.1 严格空壳骨架

- **被推翻方案**:
  - + JWT 拦截器:把 axios client + 401 auto refresh + stores/auth.ts 提到 F-16.1
  - + 测试底座:Vitest + Playwright 提前到 F-16.1
  - 全栈骨架:Recharts / lucide-vue-next 全部就位
- **理由**:
  - JWT 拦截器强依赖 login 流的字段契约(Authorization header / 401 状态码 / refreshToken 复用语义),提前会让 F-16.2 无做事可传;login 页本身是 F-16.2 的 scope
  - 测试底座重要,但 F-16.1 引入测试会让工单变满;Vite 启动 + 4 view 占位 + tokens.css + gitignore 已经够一个 ticket scope
  - Recharts / lucide-vue-next 是 F-16.3 / F-16.4 内容,提前违反 AGENTS.md §11 one ticket at a time

## D4 — 包管理器 npm

- **被推翻方案**:pnpm(磁盘省 / monorepo 友好 / 本机需装);yarn(主流之一 / 2026 年新项目少)
- **理由**:本机无 pnpm / yarn;npm 与后端 Maven 同质命名(`pack.lock` vs `package-lock.json`);F-16.1 是单 repo 单 package,npm 足够。

## D5 — Node 版本不锁(本机实测 v24.4.0)

- **被推翻方案**:Node 22 LTS / 20 LTS
- **理由**:本机实测 v24.4.0(2026.9),24 LTS 已稳定;package.json `engines` 字段不锁(避免 dev 机器缺 node 版本时阻塞),只约束 Vite 5 / Vue 3.5 / TS 5.5 + Tailwind 3.4 在 Node 24 LTS 已验证。

## D6 — 占位 view = 占位 + 视觉 token demo

- **被推翻方案**:纯空白 + `<h1>`(最薄但招实习现场无法截图);占位 + 路由状态卡(信息多实施成本高)
- **理由**:F-16.1 是 design.md 视觉系统第一次落地进工程,占位 view 顺便演示 token 是免费的招实习现场卖点;README 截图可直接取材。

## D7 — init.ps1 stage 8 = build + vue-tsc

- **被推翻方案**:不加(仅验证 artifact);加只 build(无 type check);加 build + tsc + vitest(scope creep)
- **理由**:`npm run build` 触发 vue-tsc 已含 type check,但 stage 8 显式 `vue-tsc --noEmit` + `vite build` 两步分开校验更明确;`frontend/` 不存在时必须跳过(防 init.ps1 在只 commit ADR + design.md 的早期 commit 上失败)。

---

## 排除范围(不做)

- **不做后端代码改动**:F-16.1+ 是纯前端工程
- **不做支付 / 配送 / 订单**:CONTEXT.md §8
- **不做真 ML 推荐**:CONTEXT.md §8
- **不做 i18n 国际化**:只中文
- **不做暗色模式**:只浅色
- **不引入 Sentry / 任何 APM 工具**:本 demo 不引入
- **不引 Element Plus / Naive UI / Ant Design Vue**:见 ADR-0011 决策 1
- **不引 iframe Grafana**:见 ADR-0011 决策 7
- **不引 pnpm / yarn**:见 D4

---

## 实施切片(F-16.1~F-16.5 子工单,本轮一起创建 status)

| ticket | 内容 | 状态 | 依赖 |
|---|---|---|---|
| **F-16.1** 骨架 | Vite + Vue3 + Pinia + Router 4 + Axios + Tailwind + tokens.css 同步 + 4 view 占位(占位 + 视觉 token demo)+ gitignore + init.ps1 stage 8 | active | ADR-001 |
| **F-16.2** 登录 + 主路径 | 登录页 + main 嵌套路由(zone→cuisine→recommend→detail)+ JWT 拦截器 + 401 自动 refresh + 5 个核心组件(BaseButton/BaseCard/BaseTag/HitTierBadge/TokenCounter) | ready-for-agent | F-16.1 |
| **F-16.3** 自绘指标 | Recharts 引入 + lib/prometheus-parser.ts + ObservabilityView 完整版(4 业务 + 6 技术指标 + ErrorState) | ready-for-agent | F-16.1 |
| **F-16.4** Admin | 3 sub-tab(Preheat + FeatureFlag + RateLimit)+ CountdownButton + lucide-vue-next + Vitest(关键 store 单测) | ready-for-agent | F-16.2 |
| **F-16.5** Nginx 部署 | nginx.conf 静态文件 try_files + docker-compose 端到端 smoke + Playwright e2e(招实习现场完整旅程) | ready-for-agent | F-16.4 |

> **AGENTS.md §11 one ticket at a time**:F-16.1 是当前 active,F-16.2+ 状态均为 `ready-for-agent` 但 inactive。完成 F-16.1 后由用户拍板 next in-progress。

---

## 后果

- 一会话产出:
  - `docs/adr/0012-fullstack-demo-second-resolve.md`(本 ADR)
  - `.scratch/campus-food-recommend/issues/21-F16.1-frontend-skeleton.md` ~ `21-F16.5` 共 5 工单(.scratch/ 受 git 忽略,不入 commit)
  - `frontend/` 工程 + tokens.css + 4 view 占位(占位 + 视觉 token demo)
  - `.gitignore` 加 `frontend/node_modules/` + `frontend/dist/`
  - `init.ps1` 加 stage 8 frontend build + tsc 校验
- commit 链:
  - `[F-16.1] plan: ADR-0012`(路线图 commit)
  - `[F-16.1] skeleton: frontend + init.ps1 stage 8`(实施 commit)
- F-16 ticket 状态:wontful / deferred 保持(本 ADR 是 F-16.1+ 实施决议,F-16 wontful 是"是否做"的元决策,本轮已经翻转;F-16.1+ 是"怎么做"的具体工单)
- 影响 `init.ps1` 8 stage 验证:1/8~6/8 后端 + 7/8 design.md + 8/8 frontend build & tsc
- **后续会话**:F-16.1 done 后 F-16.2 in-progress,逐个推进

---

## 文件变更(tracked)

| 路径 | 类型 | 说明 |
|---|---|---|
| `docs/adr/0012-fullstack-demo-second-resolve.md` | A | 本 ADR |
| `frontend/` | D | Vite + Vue3 工程目录(Q3=a 空壳骨架) |
| `frontend/package.json` / `tsconfig.json` / `vite.config.ts` / `tailwind.config.ts` 等 | A | 工程配置 |
| `frontend/src/styles/tokens.css` | A | 从 ADR-0011 §8 直接拷 |
| `frontend/src/styles/base.css` | A | @tailwind base/components/utilities + reset |
| `frontend/src/router/index.ts` | A | 4 view 路由(Q6=b 占位 + 视觉 token demo)|
| `frontend/src/views/*.vue` | A | LoginView / MainView / AdminView / ObservabilityView / NotFoundView 5 文件 |
| `.gitignore` | M | 加 `frontend/node_modules/` + `frontend/dist/` + `frontend/.vite/` |
| `init.ps1` | M | 加 stage 8 frontend build + tsc |

---

## 副作用

- **新增工程目录** `frontend/`,不影响后端 Java 工程
- `init.ps1` 阶段数 7→8,后端 6 stage 不动;stage 7 doc 校验不动;stage 8 在 `frontend/` 不存在时跳过(防早期 commit 失败)
- 不引入任何新后端中间件(MySQL / Redis / RabbitMQ / Caffeine / Spring AI 边界不变)
- F-16 wontful 状态由"是否做"翻转为本 ADR 决议"如何做",F-16.1+ 子工单保持各自独立 status

---

## 反例(被显式排除)

- 不要在 F-16.1 引入 JWT 拦截器(login 是 F-16.2 scope)
- 不要在 F-16.1 引入 Vitest / Playwright(测试是 F-16.4 / F-16.5 scope)
- 不要在 F-16.1 引入 Recharts(指标页是 F-16.3 scope)
- 不要在 F-16.1 引入 lucide-vue-next(图标是 F-16.4 scope)
- 不要把 F-16 wontful 状态"翻"成 in-progress(F-16.1+ 是子工单独立 management)
- 不要 stage 8 在 `frontend/` 不存在时 exit 1(必须跳过)
- 不要在 `init.ps1` 跨 stage 共享变量(每个 stage 独立)
- 不要 F-16.1 scope creep 到 F-16.2 / F-16.3 / F-16.4 的内容