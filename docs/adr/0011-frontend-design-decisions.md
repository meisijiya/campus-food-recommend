# ADR-0011: Demo 前端设计基调 — 完整 SPA 工程契约(`docs/design/frontend/`)

> **状态**:已采纳
> **日期**:2026-09-22
> **决策者**:用户 + orchestrator,grill-with-docs skill(3 轮 12 题)
> **触发**:F-17 接口文档定稿(ADR-0010)后,用户要求"根据接口文档设计 demo 前端"。本 ADR 是设计基调,**不是实施许可**。

---

## 背景

F-17 已定稿 `docs/api/api-reference.md`,后端 9 类接口契约完整。用户在 2026-09-22 root session 进一步澄清:

> "**完全可用的前端,能够跟后端完全联调,能够展示后端能力的前端,同时符合我们项目的描述:校园食物推荐平台。本轮对话先定下前端的 design.md**"

显式拆开两个语义:

- **目标态**:完整可联调前端(可上线 demo 状态)
- **本轮范围**:只产出 design.md(契约文档),不实施

F-16 ticket 当前 `wontful / deferred`(2026-09-22 用户明确"不需要开干全栈")。本 ADR 不翻 wontful 状态,但**记录完整前端的工程基调**,供未来 F-16.1+ 实施时直接照单消费。

### 与现有资产的关系

| 资产 | 关系 |
|---|---|
| `docs/api/api-reference.md`(F-17 done) | 接口契约唯一真相源,design.md **不复制字段定义**,仅描述前端如何消费 |
| `CONTEXT.md`(校园主题术语 + 缓存分层 + 限流降级) | design.md 不重抄术语,引用即可 |
| ADR-0010(接口文档定稿) | 本 ADR 是其下游消费方 |
| F-16 ticket | **保持 wontful / deferred**;本 design.md 是 F-16.1+ 未来实施的契约参考 |
| `init.ps1` 6 stage | **新增 stage 7 frontend-design** 校验 design.md;后端 6 stage 不动 |

---

## 决策

9 项关键决策(Q10 答复 c:全 9 项都入 ADR + 每条决策后加"被推翻的替代方案"小段)。

### 决策 1 · 不引入 Element Plus / 任何组件库

- **采用**:Tailwind CSS utility + 少量自封装组件(`BaseButton` / `BaseInput` / `BaseCard` / `BaseTag` / `HitTierBadge` / `CountdownButton` / `TokenCounter` 等 14 个)
- **被推翻的方案**:Element Plus(850KB gzipped)+ 全量引入;Naive UI;Ant Design Vue
- **理由**:
  - 反 AI aesthetic(AGENTS.md §9 不变量第 6 条):Element Plus 是 AI 默认推荐,**所有 AI 生成的 Vue 项目撞库**
  - 校园暖色定制:Element Plus 主题色覆写成本 ≥ Tailwind 自定义
  - bundle size:850KB gzipped 对 demo 现场首屏不友好
  - 招实习现场可读性:自封装组件让代码评审时"前端工程"显得更精致

### 决策 2 · hash mode 路由(`#/main`)

- **采用**:Vue Router 4 `createWebHashHistory`
- **被推翻的方案**:history mode(`createWebHistory`)
- **理由**:
  - Nginx 静态文件 serve 不需要 `try_files /index.html` fallback
  - 本地 `python -m http.server` / `npx serve dist/` 都能跑,**演示稳定性高**
  - 招实习现场换设备 / 换网络不踩 404 坑
- **代价**:URL 带 `#` 视觉略丑,招实习现场 100% 可读

### 决策 3 · 401 自动 refresh(axios 拦截器)

- **采用**:401 触发后**串行化**调用 `/api/auth/refresh`,成功重放原请求,失败清 store 跳 `/#/login`
- **被推翻的方案**:401 直接跳登录
- **理由**:
  - 用户体验连续性(accessToken 2 小时 TTL,频繁手动重新登录不可接受)
  - refreshToken 复用,**只在 refreshToken 也过期时才跳登录**
  - 招实习现场"我手快多次点击推荐"也不会被踢下线
- **代价**:实现略复杂(模块级 `refreshing: Promise<string> | null` 串行化)

### 决策 4 · 顶层 3 tab 而非 5+ tab 平铺

- **采用**:`/#/main`(主路径)+ `/#/admin`(Admin)+ `/#/observability`(指标)
- **被推翻的方案**:9 类接口各开一个 tab(共 9 tab)、4 tab(拆分点赞 demo)
- **理由**:
  - 招实习现场需要"故事感":3 tab 对应"做什么 → 怎么管 → 看什么"
  - 9 tab 平铺让"招实习现场 demo"显得工具化,丢叙事
  - 拆分 `/like-demo` 让"主路径闭环"叙事不完整(点赞嵌在主路径)
- **代价**:Admin tab 折叠 3 sub-tab(preheat / feature-flag / rate-limit),视觉略密集

### 决策 5 · hit_tier 三档显式标注(mock / dashscope / fallback)

- **采用**:推荐结果卡片右上角 `<HitTierBadge tier="...">` 角标,颜色严格按 visual-system.md §2.6 三档映射
- **被推翻的方案**:不显式标注(只看后端日志)
- **理由**:
  - F-3 bullet 现场讲解核心:**看一眼角标就懂这次推荐命中哪一层**
  - 招实习现场能答"为什么有 fallback"——后端反思重试 2 次仍不合规 → RBFA 兜底
  - 颜色 token 集中管理,**反例**避免每个视图自选色

### 决策 6 · 429 倒计时按钮(`CountdownButton`)

- **采用**:限流后用 `<CountdownButton retryAfter=1 onRetry>` 倒计时按钮,1 秒后恢复
- **被推翻的方案**:全局 toast + 禁用按钮 N 秒
- **理由**:
  - 招实习现场演示 F-7 bullet 的关键 UX:**主动触发 → 看拒绝 → 看恢复**
  - 用户视角"1 秒后我能再点"比"被禁 5 秒"体验好
  - 满足 `Retry-After: 1` header 语义
- **代价**:组件增加 1 个

### 决策 7 · 自绘 Recharts 而非 iframe Grafana

- **采用**:`/actuator/prometheus` 用自研 `parsePrometheus()` 解析 + Recharts 渲染 4 业务 + 6 技术指标
- **被推翻的方案**:iframe 嵌入 `http://localhost:3000/d/xxx`
- **理由**:
  - 招实习现场 + 简历截图需"前端自解释";Grafana 是依赖服务,离线截图黑盒
  - 远程面试录屏时 Recharts 动画清晰可读,Grafana 加载慢
  - 实现成本可控(1 个 .vue + 1 个 parser,约 4 小时)
- **代价**:Bundle 增加 ~80KB(Recharts 按需引目标),首屏稍慢(< 1.5s LCP 仍达标)

### 决策 8 · 本轮只出 design.md,前端工程暂不实施

- **采用**:产出 5 个 design.md + 1 个 mock HTML + 本 ADR,不创建 `frontend/` 目录、不 `npm install`、不跑 Vite
- **被推翻的方案**:直接开 F-16.1 骨架
- **理由**:
  - F-16 当前 wontful / deferred,用户明确"本轮对话先定下 design.md"
  - design.md 是契约而非实施;实施阶段 scope / 边界 / 新栈引入**仍需独立 grill**
  - 与 AGENTS.md §11 "one ticket at a time" 一致
- **未来启动**:用户开新一轮显式"开干前端" → 开 ADR-0012"全栈 demo 二次决议" → grill 重启 → 建 F-16.1+ 子工单

### 决策 9 · init.ps1 新增 stage 7 frontend-design 校验

- **采用**:`init.ps1` 6 stage 不动;**新增 stage 7** 校验 5 个 design.md 存在 + 行数下限 + visual-system.md 含 `--color-primary:` / `--space-4:` grep 关键字
- **被推翻的方案**:人工自检、不加 stage;或者 stage 校验过细(检查章节标题、检查 CSS 变量值)
- **理由**:
  - AGENTS.md §5 "init.sh 是单一可执行约束,任何完成声明必须有证据",design.md 是项目产物,加 stage 与该原则一致
  - 行数下限避免"只写 10 行的 design.md";grep 关键字保证 visual token 已声明
  - 不检查章节标题、不检查 CSS 变量值,避免"满足 grep 的设计稿"反模式

---

## 排除范围(不做)

- **后端代码改动**:design.md 不触发任何 Java / application.yml / pom.xml 变更
- **支付 / 配送 / 订单**:CONTEXT.md §8 已划出
- **商户入驻审核工作流**:不在 demo
- **真 ML 推荐模型**:CONTEXT.md §8,本 demo 用规则 + Spring AI
- **多校区 / 多租户**:单校区假设
- **iframe Grafana / 任何"黑盒"展示**:见决策 7
- **Element Plus / 任何组件库**:见决策 1
- **Sentry / 任何 APM 工具**:本 demo 不引入(F-16.1+ 可考虑)
- **i18n 国际化**:只中文(招实习现场 + 简历中文)
- **暗色模式**:只浅色(招实习现场屏幕常亮),dark mode 留 F-16.5+

---

## 实施切片(本轮)

| ticket | 内容 | 状态 |
|---|---|---|
| **本 ADR 落地** | 5 个 design.md + 1 个 mock + 1 个 ADR + init.ps1 stage 7 | **ready-for-agent → in-progress → done**(本会话内) |
| F-16 | 全栈 demo Vue3 + Vite + Tailwind | wontful / deferred(本轮不实施) |
| F-16.1+ | 未来实施子工单 | 留作未来扩展,需新 ADR(候选 ADR-0012) |

---

## 后果

- 本会话产出:
  - `docs/design/frontend/overview.md`(≥80 行)
  - `docs/design/frontend/visual-system.md`(≥150 行 + CSS 变量)
  - `docs/design/frontend/architecture.md`(≥150 行)
  - `docs/design/frontend/api-contract.md`(≥120 行)
  - `docs/design/frontend/observability-and-launch.md`(≥120 行)
  - `docs/design/frontend/mock/login.html`(纯静态)
  - `docs/adr/0011-frontend-design-decisions.md`(本文件,9 项决策 + 推翻方案)
  - `init.ps1` 加 stage 7/7(后端 6 stage 不动)
  - `docs/adr/0010-api-reference-finalize.md` 不动
- F-16 ticket 加 "design reference" 章节指向本 design.md(F-16 仍 wontful)
- `docs/api/api-reference.md` 是接口契约唯一真相源;design.md **不复制字段定义**
- 后续会话:
  - 用户开新一轮若说"开干前端",必须先开 ADR-0012 → grill 重启 → 建 F-16.1+ 体系
  - 不允许跳过 ADR-0012 直接 `npm create vite`(本 design.md 是契约参考,不替代独立决策)

---

## 文件变更(tracked)

| 路径 | 类型 | 说明 |
|---|---|---|
| `docs/design/frontend/overview.md` | A | 设计稿总入口(项目目标 / 范围 / 旅程 / 原则 / DoD)|
| `docs/design/frontend/visual-system.md` | A | 色板 / 字号 / 间距 / 圆角 / 阴影 / CSS 变量 / Tailwind 映射 |
| `docs/design/frontend/architecture.md` | A | 信息架构 / 路由树 / Pinia 切片 / 组件清单 |
| `docs/design/frontend/api-contract.md` | A | 9 类接口契约 / 鉴权 / 错误 / 429 UX / hit_tier |
| `docs/design/frontend/observability-and-launch.md` | A | 自绘指标 / 上线清单 / demo 脚本 / 截图清单 |
| `docs/design/frontend/mock/login.html` | A | 单文件静态 HTML(login 视觉基调) |
| `docs/adr/0011-frontend-design-decisions.md` | A | 本 ADR |
| `init.ps1` | M | 加 stage 7/7 frontend-design 校验 |
| `.scratch/campus-food-recommend/issues/16-F16-fullstack-vue3-demo.md` | M | 加 "design reference" 章节 |

---

## 副作用

- 完全 doc-only,**不动 Java / application.yml / pom.xml**
- 不影响 `mvn test` / `mvn verify` / 后端 6 stage
- init.ps1 stage 7 是**新增校验**,不替代后端 6 stage(后端 stage 1~6 仍必须通过)
- 不引入任何新中间件(完全文档 + 1 个静态 HTML)
- F-16 wontful 状态保持(本 ADR **不**翻 F-16 to in-progress)

---

## 反例(被显式排除)

- 不要用 Element Plus / Ant Design Vue / Naive UI(决策 1)
- 不要用 history mode(决策 2)
- 不要把 hit_tier 角标隐藏到"开发者模式"(决策 5)
- 不要用 iframe Grafana(决策 7)
- 不要在 design.md 复制 api-reference.md 字段定义(架构层引用即可)
- 不要把 demo mock HTML 加 `<script>` 标签(F-16 wontful 边界)
- 不要在 stage 7 校验章节标题 / CSS 变量值,避免"满足 grep 的设计"(决策 9)
- 不要让前端工程化跳过 ADR-0012 直接开 F-16.1+(决策 8)