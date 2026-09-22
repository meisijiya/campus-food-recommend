# Demo 前端 design.md · 概述

> **本文件**:设计稿总入口。frontend UI 与工程化的**基调**。所有后续 F-16.1+ 子工单按本基调展开。
> **范围**:仅文档 + 1 个静态 mock HTML,不含 Vue 工程代码(`frontend/` 目录不创建、`package.json` 不创建、`npm install` 不执行)。
> **F-16 状态**:**wontful / deferred** 保持不变(本轮不动 F-16 不实施红线);本设计稿是未来实施的契约。
> **配套文件**:`visual-system.md` / `architecture.md` / `api-contract.md` / `observability-and-launch.md` + `mock/login.html` + ADR-0011。

---

## 1. 项目目标

**双场景兼容**:

| 场景 | 优先级 | 含义 |
|---|---|---|
| 招实习现场演示 | **主** | 面试官盯屏幕,讲解人按 bullet 路径带跑。关键节点要有"现场可演卖点"(hit_tier / 429 倒计时 / token 数显式)。 |
| 简历附件 / GitHub README 截图 | **次** | 首屏 1 秒传达"校园 + 推荐 + 工程感"。克制专业,无花哨动效。 |

**质量门槛**:**可用可上线的 demo 状态**——设计稿达到"明天交前端工程师照着实施就能上线"的可信度,不是低保真 wireframe。

---

## 2. 范围

### 2.1 本轮交付(已定)

- `docs/design/frontend/overview.md`(本文件)
- `docs/design/frontend/visual-system.md`(色板/字号/间距/圆角/阴影/CSS 变量,Tailwind token 映射)
- `docs/design/frontend/architecture.md`(信息架构 / 路由树 / 状态管理 / 组件清单)
- `docs/design/frontend/api-contract.md`(9 类接口契约 / 鉴权 / 错误 / 429 UX)
- `docs/design/frontend/observability-and-launch.md`(自绘指标 UI / 上线清单 / demo 脚本)
- `docs/design/frontend/mock/login.html`(单文件静态 HTML,招实习现场演示登录页基调)
- `docs/adr/0011-frontend-design-decisions.md`(9 项关键决策 + 每项"被推翻的替代方案")

### 2.2 未来实施(留作 F-16.1+ 子工单)

> 实施不在本轮。下一轮若开干,按 ADR-0011 第 8 项决策:`按本设计实施 = 新开 ADR(F-16.1 起步) + F-16.1 子工单体系 + 重审 init.ps1 stage 7`。

| 工程价值 | 内容 |
|---|---|
| F-16.1 | Vite + Vue3 + Pinia + Vue Router 4 + Axios 骨架;`frontend/` 目录;`.gitignore` 加 `frontend/node_modules/` / `frontend/dist/` |
| F-16.2 | 路由 + JWT 拦截 + 自动 refresh + 5 个核心组件 |
| F-16.3 | Recharts 自绘 `/actuator/prometheus` 解析器 + 4 业务 + 6 技术指标页 |
| F-16.4 | Admin tab 折叠 Preheat + Feature Flag + 限流调试 |
| F-16.5 | Nginx 80 静态文件 serve + `docker-compose up app nginx` 端到端 |

---

## 3. 不在范围(明确不做)

- **后端代码改动**:本设计稿不触发任何 Java / application.yml / pom.xml 变更。后端接口契约以 `docs/api/api-reference.md`(F-17 已定稿)为唯一真相源。
- **支付 / 配送 / 订单**:CONTEXT.md §8 已划出,本设计稿不引入。
- **商户入驻审核**:不在 demo 范围。
- **真 ML 推荐**:CONTEXT.md §8,本 demo 用规则 + Spring AI。
- **多校区 / 多租户**:CONTEXT.md §8,单校区假设。
- **Element Plus / 任何组件库**:见 ADR-0011 决策 1。
- **iframe Grafana**:见 ADR-0011 决策 7。

---

## 4. 用户角色与旅程

### 4.1 角色

| 角色 | 标识 | 主要页面 | 权限 |
|---|---|---|---|
| 学生(Student) | JWT `sub` = studentId | login / main / detail | `/api/auth/**` + `/api/merchant/**` + `/api/recommend` + `/api/like/**` + `/api/session/**` + `/api/feature-flag/check` |
| 管理员(Admin) | `ROLE_ADMIN` 角色附加 | admin tab + observability tab | 学生权限 + `/admin/**` 全开(`/admin/preheat/trigger` + `/admin/feature-flag/**`) |

> demo 预设账号(内存 `UserDetailsServiceImpl`,见 `docs/api/api-reference.md` §1.1):
>
> | username | password | 角色 |
> |---|---|---|
> | `demo` | `demo` | STUDENT |
> | `admin` | `admin` | STUDENT + ADMIN |

### 4.2 招实习现场标准旅程(讲解 bullet 顺序)

```
1. /#/login            ← 鉴权(F-1 JWT)
   ↓
2. /#/main             ← 主路径:zone → cuisine → merchant
   ├─ 选 zone(F-2 会话槽位第 2 阶)
   ├─ 选 cuisine(F-2 第 3 阶)
   ├─ 看推荐(F-3 结构化输出 + hit_tier 显式标)
   └─ 点 like(F-5 幂等点赞)
   ↓
3. /#/main/merchant/:id ← 商户详情(F-4 缓存 L0/L1/L2 透明)
   ↓
4. /#/admin            ← Admin(折叠 3 sub-tab)
   ├─ /#/admin/preheat(F-4 离线预热)
   ├─ /#/admin/feature-flag(F-11 灰度开关)
   └─ /#/admin/rate-limit(F-7 限流触发 + 429 倒计时)
   ↓
5. /#/observability    ← 自绘 Recharts 指标页(F-9)
   └─ 4 业务 + 6 技术指标,招实习现场讲解可观测性 bullet
```

---

## 5. 设计原则

### 5.1 反 AI aesthetic(AGENTS.md §9 不变量第 6 条)

| 禁止 | 理由 |
|---|---|
| 紫色 / 靛蓝主色 | 模型默认色,所有 AI 生成 app 撞色 |
| 渐变 / 玻璃态 / 模糊背景 | 视觉噪声,反生产风 |
| 圆角无脑 (`rounded-2xl` everywhere) | 丢失视觉层级 |
| Hero 大图 + 居中文案 | 模板感,丢语境 |
| Lorem ipsum 文案 | 暴露长度 / overflow 问题 |
| 巨大 padding / margin | 浪费空间、毁层级 |
| 阴影叠加 `shadow-xl` 多层 | 与内容竞争、低端设备慢渲染 |
| Stock 卡片网格 | 套路化,丢信息优先级 |

### 5.2 校园主题暖色克制(Q3 锁定)

- **主色**:校园绿(低饱和 `#3D8B5F`)——区别于"互联网产品默认紫"
- **辅色**:暖橙 `#E0A458`、校园红 `#C75450`(克制使用)
- **背景**:暖白 `#FAF7F2` 而非冷白 `#FFFFFF`
- **字体**:Inter / system-ui;中文优先系统字体栈
- **圆角**:`0.5rem` 主体,`0.75rem` 卡片,`0.25rem` 标签,**不**用 `rounded-2xl`
- **阴影**:克制,最多 1 层 `shadow-sm`,**不**叠加

### 5.3 工程友好

- **Tailwind utility** 而非组件库(class 显式、可控 spacing scale)
- **CSS 变量** 在 `:root` 集中(色彩层声明的 token 与 Tailwind config 同源)
- **TypeScript strict**(`tsconfig.json` 必开 strict)
- **零运行时魔法**:不引 mitt / mittens / tiny-emitter,事件总线不引入
- **Pinia 切片** ≤ 8 个,每个聚合避免 **>200 行**

### 5.9 WCAG 2. AA(强制)

- 正文 4.5:1 对比度
- 大字 3:1 对比度
- 所有交互元素键盘可达(Tab / Shift+Tab / Enter / Space)
- 非纯色传达信息(配图标 / 文本 / 模式)
- 焦点环(`focus-visible:ring-2 ring-primary`)
- 屏幕阅读器友好(`role` / `aria-label` / `aria-busy`)

---

## 6. 文件清单

```
docs/
├── design/
│   └── frontend/
│       ├── overview.md                      ← 本文件(项目目标 / 范围 / 旅程 / 原则)
│       ├── visual-system.md                 ← 色板 / 字号 / 间距 / 圆角 / 阴影 / CSS 变量 / Tailwind 映射
│       ├── architecture.md                  ← 信息架构 / 路由树 / Pinia 切片 / 组件清单
│       ├── api-contract.md                  ← 9 类接口契约 / 鉴权流程 / 错误处理 / 429 UX / hit_tier
│       ├── observability-and-launch.md      ← 自绘 Recharts 指标 / 上线 checklist / demo 脚本 / 截图清单
│       └── mock/
│           └── login.html                   ← 单文件静态 HTML(招实习现场展示登录页基调)
└── adr/
    └── 0011-frontend-design-decisions.md    ← 9 项决策 + 每项"被推翻方案"
```

---

## 7. 与现有工单 / ADR 的关系

| 现有资产 | 关系 |
|---|---|
| `docs/api/api-reference.md`(F-17 done) | 接口契约唯一真相源,design.md 引用,**不复制字段定义** |
| `CONTEXT.md` §1-§14 术语 | design.md 不重抄术语,引用即可 |
| ADR-0010(接口文档定稿) | 本设计稿是其下游消费方,**接口字段定义不再次设计** |
| F-16 全栈 demo ticket | **保持 wontful / deferred**;本 design.md 是 F-16.1+ 子工单的契约 |
| ADR-0011(本轮产出) | 9 项决策 + "被推翻方案"段,作为未来 F-16.1+ 实施前的"为什么这么选"的回溯点 |
| `init.ps1` 6 stage | **新增 stage 7 frontend-design** 校验本设计稿存在 + 行数下限;后端 6 stage 不动 |

---

## 8. 完成定义(DoD)

本轮 design.md 完成的硬证据(`init.ps1` stage 7 校验):

| 项 | 标准 |
|---|---|
| 5 个 design.md 文件存在 | `overview.md` / `visual-system.md` / `architecture.md` / `api-contract.md` / `observability-and-launch.md` |
| `mock/login.html` 存在 | 单文件、纯静态、**无 `<script>` 标签** |
| `docs/adr/0011-frontend-design-decisions.md` 存在 | ≥ 9 项决策 |
| overview.md 行数 | ≥ 80 行 |
| visual-system.md 行数 | ≥ 150 行 |
| architecture.md 行数 | ≥ 150 行 |
| api-contract.md 行数 | ≥ 120 行 |
| observability-and-launch.md 行数 | ≥ 120 行 |
| visual-system.md 含 CSS 变量 | 至少包含 `--color-primary:` 与 `--space-4:` 两类 grep 关键字 |
| init.ps1 stage 7 退出码 | 0

`init.ps1` 仍 6 stage 通过(后端不退步),stage 7 是**新增**校验。

---

## 9. 下一步

> **不在本轮范围**:

1. F-16.1+ 子工单体系(骨架 + 路由 + 组件 + 联调)
2. `frontend/` 目录创建 + Vue3 + Vite 初始化
3. CI 在 `frontend/` 跑 `npm run build` / `vitest` / a11y 检查

> **未来启动信号**:用户开新一轮会话时若授权"开干前端",先开 ADR(候选名 ADR-0012 "全栈 demo 二次决议")走 grill-with-docs skill 重启设计访谈,**不直接按本 design.md 实施**——本设计稿是契约参考,但实施阶段的 scope / 边界 / 新技术栈引入**仍需独立决策**。