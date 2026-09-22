# ADR-0013: bc 之间 · 全栈前端企业级化扩展路线图

> **状态**:已采纳
> **日期**:2026-09-22
> **决策者**:用户 + orchestrator,grill-with-docs skill(2 轮 4 题 + playground 1 个)
> **触发**:用户在 F-16.1 playground(`docs/design/frontend/playground.html`)完整体验 5 view 占位与视觉 token 调节后,显式要求"**从简单 demo 到企业级可用 demo,全面覆盖后端接口 + 企业级基本的全栈业务逻辑**"。F-16.1~F-16.5 现有 scope(wontful → ready-for-agent 的"招实习 demo"目标)不够支撑"bc 之间"目标态,需开本 ADR 重定位。

---

## 决策一览(基于 grill 答复)

| # | 决策 | 答复 | 来源 |
|---|---|---|---|
| D1 | 目标态具体定义 | **bc 之间**:内部产品形态(b)+ 后端已落地的 c 能力复用 | Q1 |
| D2 | 后端接口覆盖范围 | **全部 9 类接口 1:1 接入前端**,不动后端 Session | Q2 = b |
| D3 | 实施路径 | **F-16.2 优先扩"企业级",其余新开 F-22+** | Q3 = c |
| D4 | F-16.2 "企业级"扩展范围 | **中等企业级**:真联调 + 完整错误 UX + Loading + 乐观更新 + 边界场景 + Vitest + Playwright e2e + bundle 预算 + 路由懒加载 | Q4 = b |

---

## D1 — 目标态 = bc 之间

- **被推翻方案**:
  - a(招实习演示升级版):b 太薄就是现有 F-16.x scope,扩展即可
  - c(真生产可部署):实施周期 4-6 周,与"招实习 demo"原意冲突
- **理由**:**后端已经完成了 c 的相当一部分核心能力**——无状态架构(F-1)/ 限流(F-7)/ 分布式锁(F-8)/ 可观测性(F-9)/ CI/CD(F-10)/ 灰度(F-11)全部已 evidence。前端只需要"接入 + 完整错误 UX + 测试护栏",既不重复造后端的轮子,也不为生产部署投入 4-6 周。
- **后端已具备的 c 能力清单**(用户确认):

| c 维度 | 已落地 ticket | 关键能力 |
|---|---|---|
| 无状态架构 | F-1 | JWT + Docker Compose + Nginx |
| 缓存一致性 | F-4 / F-5 | Caffeine L0 + Redis L1 + MySQL L2 三级缓存 + 离线预热 |
| 限流 | F-7 | 双层令牌桶 + Caffeine 降级 + 429 + Retry-After |
| 分布式锁 | F-8 | SETNX + 看门狗 + ownerToken |
| 可观测性 | F-9 | Micrometer 业务指标 + Prometheus 导出 + Grafana 面板 |
| CI/CD | F-10 | GitHub Actions + init.ps1 多 stage 门禁 |
| 灰度 | F-11 | FeatureFlag Redis hash + 3 种灰度模式(ALL_ON / WHITELIST_ONLY / PERCENTAGE) |
| 测试覆盖 | 多 | 单元 + 集成 + JMeter + locust |

---

## D2 — 全部 9 类接口 1:1 接入前端(不动后端 Session)

按 `docs/api/api-reference.md` 现有 9 类接口(实际 17 个 endpoint):

| 类别 | endpoint | F-16.x 归属 |
|---|---|---|
| Auth | `/api/auth/login` / `/api/auth/refresh` | F-16.2 |
| Session | `/api/session/init` / `cuisine` / `merchant` / `zone` | F-16.2 |
| Recommend | `/api/recommend` | F-16.2 |
| Like | `/api/like/{merchantId}` | F-16.2 |
| Merchant | `/api/merchant/{id}` / `/api/merchant?zoneId=` | F-16.2 |
| Admin Preheat | `/admin/preheat/trigger` | F-16.4 |
| Admin FeatureFlag | `/admin/feature-flag` / `{key}` GET / POST | F-16.4 |
| Public FeatureFlag Check | `/api/feature-flag/{key}/check` | F-16.2 |
| Actuator Prometheus | `/actuator/prometheus` | F-16.3 |

- **被推翻方案**:
  - c(全部 9 类 + 重构 Session):Session 重构是**后端**工作,不在前端 scope
- **理由**:现有 9 类接口是后端真实能力,前端 1:1 接入 + ADR-0011 §F-16.2 已锁的 hit_tier 推断即可覆盖

**关于"重构 Session 为企业级语义"**(澄清用户在 Q2 选项 c 中提到的疑问):

| 当前后端 Session 设计 | "企业级"重构方向(后端开 ADR,不在前端 scope) |
|---|---|
| 1 个 sid 1 个会话(Redis 30 分钟 TTL) | 多会话:web + 平板 + 手机并行登录,各自独立槽位 |
| 无主动刷新(只能超时) | 主动刷新:用户点"延长会话"延长 TTL |
| 无主动撤销 | 主动撤销:用户登出 / 撤销其他设备会话 |
| 无会话列表查询 | 会话列表:"我目前在哪几个设备上登录" |

如果将来要做,开 ADR(候选 ADR-0014 "Session 企业级语义重构")+ 新工单 F-26+。**本轮前端不碰 Session 重构**。

---

## D3 — 实施路径:F-16.2 优先扩,其余新开 F-22+

- **被推翻方案**:
  - a(扩现有 F-16.x scope):节奏快但 scope creep,违反 AGENTS.md §11
  - b(新增 F-22+ 体系):节奏稳但工单多
- **理由**:F-16.2 是用户接触最深的页面(登录 + 主路径 + 点赞 + 商家详情),企业级闭环先在这里体现(完整错误处理 / loading / 乐观更新 / 真实 API 联调);F-16.3~F-16.5 余下 scope 适度扩展,**走 F-22+ 单独工单**

### F-22+ 工单体系(从 Q4 选项 c 拆出)

| ticket | 内容 | 状态 | 优先级 |
|---|---|---|---|
| **F-22** 性能监控 + 错误上报 | Web Vitals 埋点(LCP/FCP/INP/CLS)+ 轻量错误上报 SDK(`window.onerror` → `/api/log/error`) | ready-for-agent | 中 |
| **F-23** 灰度 SDK | 客户端 SDK(基于 `/api/feature-flag/check`)+ `useFlagStore` 增强 + 主路径条件渲染(`recommend-v2` 等 flag 现场演示) | ready-for-agent | 中 |
| **F-24** i18n + dark mode | vue-i18n 接入(中文为主,英文可选)+ 深色模式 token 切换 + 系统主题检测(`prefers-color-scheme`)+ 用户偏好覆盖 | ready-for-agent | 低 |
| **F-25** 主路径扩展 | 主路径细节增强:搜索 / 收藏 / 历史 / 多语言 / 多校区切换(后端需配合) | ready-for-agent(后端 blocked) | 低 |
| **F-26** Session 企业级语义重构(后端) | 多会话 / 主动刷新 / 主动撤销 / 会话列表 | blocked by 后端 ADR | 后端优先 |

> F-25 需要后端新增接口(搜索/收藏/历史),F-26 是后端单独工单,都不在前端阻塞 F-16.x 推进的关键路径上。

---

## D4 — F-16.2 完工标准(中等企业级)

| 项 | 范围 |
|---|---|
| 真联调 | 去掉 mock,9 类接口全部接后端真实数据 |
| 完整错误 UX | toast(业务错误)+ 表单内联ErrorState(参数错误)+ 401 自动 refresh + 跳登录 |
| Loading 骨架 | 列表 5 行 / 卡片 3 行 / 表单提交按钮 |
| 乐观更新 | 点赞 + 槽位切换(立即翻 UI,失败回滚) |
| 边界场景 | 网络失败 / 401 / 429(CountdownButton)/ 404 / 500 |
| Vitest 单元测试 | `useAuthStore` / `useSessionStore` / `useRecommendStore` / `useLikeStore` / `useMerchantStore` 关键 reducer 覆盖率 ≥ 80% |
| Playwright e2e | 招实习现场完整旅程:登录 → 主路径 → 点赞 → 商家详情 → 退出 |
| bundle size 预算 | gzipped ≤ 250KB(不含 Recharts,F-16.3 引入)|
| 路由懒加载 | 已配(架构.md §8.1) |

---

## 排除范围(不做)

- **不做真生产部署**(bc 之间不需要)
- **不做 Sentry 等外部 APM**(F-22 轻量 `window.onerror` 已够)
- **不做支付 / 配送 / 订单**(CONTEXT.md §8)
- **不做真 ML 推荐**(CONTEXT.md §8)
- **不做后端 Session 重构**(开 ADR-0014 + F-26,不在前端 scope)
- **不做多校区 / 多租户**(CONTEXT.md §8)
- **不引 Element Plus / Naive UI / Ant Design Vue**(ADR-0011 决策 1)
- **不引 iframe Grafana**(ADR-0011 决策 7)

---

## 实施切片

| ticket | 内容 | 状态 |
|---|---|---|
| **ADR-0013**(本 ADR) | bc 之间路线图 | ready-for-agent → done(本会话 commit)|
| F-16.2 扩展 | 中等企业级扩展(Q4=b)| scope 已扩,等用户拍 active |
| F-22 / F-23 / F-24 草稿 | F-22+ 工单体系创建 | ready-for-agent(本会话创建 .scratch 工单)|
| F-25 / F-26 | 后端 blocked,留作未来扩展 | pending |

---

## 文件变更(tracked)

| 路径 | 类型 | 说明 |
|---|---|---|
| `docs/adr/0013-bc-between-fullstack-roadmap.md` | A | 本 ADR |
| `.scratch/campus-food-recommend/issues/22-F16.2-login-main-path.md` | M | scope 扩 b 范围 |
| `.scratch/campus-food-recommend/issues/26-F22-monitor-errors.md` | A | F-22 草稿 |
| `.scratch/campus-food-recommend/issues/27-F23-feature-flag-sdk.md` | A | F-23 草稿 |
| `.scratch/campus-food-recommend/issues/28-F24-i18n-dark-mode.md` | A | F-24 草稿 |

---

## 副作用

- 完全 doc-only + 4 `.scratch/` 工单文件(.scratch/ 受 git 忽略)
- 不引入任何新后端中间件
- F-16.1 + ADR-0011 / ADR-0012 不动
- F-16.2 scope 扩 b 是"bc 之间"目标态的最低门槛

---

## 反例(被显式排除)

- 不在 F-16.2 引入 i18n / dark mode(留 F-24)
- 不在 F-16.2 引入 Web Vitals 埋点(留 F-22)
- 不在 F-16.2 引入 FeatureFlag SDK(留 F-23)
- 不在 F-16.2 引入后端 Session 重构(留 F-26)
- 不做真生产部署
- 真的做"bc 之间",既不是 a 也不是 c(避免 scope creep 到 c 或浅薄到 a)

---

## Comments

> 2026-09-22 root:用户提"bc 之间"是关键信号——既不抛弃招实习 demo 原意(b 内部产品形态),也不简单重做后端(c 已有相当一部分完成)。F-16.1 playground 是触发器,让用户看清现状才能精确提新目标。Round 1 grill 4 题沉淀 4 决策;F-22+ 工单体系已建。下一步:用户拍 F-16.2 active 或先 polish F-16.1。