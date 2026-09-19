# AGENTS.md — 校园美食推荐平台 · Agent 工作约定

> 本文件是 AI 编码代理进入本仓库的唯一启动入口。
> 路径在第一次有效 git init 之前以"未跟踪"运行;一旦仓库初始化,下文"git 跟踪策略"即生效。

---

## 1. 项目一句话

校园美食推荐平台后端 demo。目标是**逐条复现简历中 5 条项目 bullet**,而不是做一个生产级外卖系统。
简历 5 条 bullet 直接作为开发 ticket 来源,任何不在 bullet 范围内的新需求都必须先开 ADR 才能写代码。

---

## 2. 仓库结构与产物落点

| 路径 | 用途 | 谁负责 |
|---|---|---|
| `AGENTS.md` | 本文件,启动路径与不变量 | 代理读 |
| `CONTEXT.md` | 领域语言(术语表) | 代理读,术语变更走 ADR |
| `docs/adr/` | 决策记录,**只增不删** | 决策前写草稿,通过后改名归档 |
| `init.sh` | 验证门禁,声称完成前必须跑 | 代理必跑 |
| `.gitignore` | Java/Maven 标准忽略 | 一次性 |
| `src/main/java/com/meisijiya/campusfood/` | Java 源码 | 业务模块 |
| `src/main/resources/` | 配置(application.yml / logback 等) | 业务模块 |
| `docker-compose.yml` | MySQL/Redis/RabbitMQ 一键起 | 后续 ticket 创建 |
| `pom.xml` | Maven 依赖与构建 | 业务模块 |
| `.scratch/` | 任务级暂存(调研、spec 草稿),完成即删 | 不进版本控制 |
| **Tracker 模式工单** | 状态与依赖的来源 | **上游 `matt` skill 负责,见下方 §6** |

---

## 3. 完成定义 (Definition of Done)

任何 ticket 在声称 `done` 之前必须**全部**满足:

1. 对应 Java 代码已落地,包路径 `com.meisijiya.campusfood.*`。
2. 关键路径有单元测试或集成测试,`./mvnw test` 通过。
3. `bash init.sh` 退出码 0(见 §5)。
4. 受影响文档(`CONTEXT.md` / ADR / 工单)已同步更新。
5. commit 信息含 ticket 编号前缀(如 `[F-1] 引入 JWT 鉴权无状态改造`)。
6. 简历原 bullet 中量化指标(5000+ QPS / 88% 合规率 / 60% 响应时间缩短 / P99 < 50ms / Token ↓35%)有可复现的压测或日志证据,否则在工单里标记 `unverified` 并写明验证方法。

---

## 4. 工作流(每条 ticket)

```
读取工单 → 在 .scratch/<ticket-id>-plan.md 写实现计划(可选)
         → 增量实现,每步跑 mvnw compile 保证可编译
         → 写测试,跑 mvnw test
         → 跑 bash init.sh
         → 更新工单状态 + 在工单 evidence 段写证据(命令 + 输出摘要)
         → commit
         → 删除 .scratch 暂存
```

**纪律**:
- 一次只推进一个 active ticket;切换前必须把当前工单 evidence 段写满。
- 不在工单外乱改代码;遇到 bullet 之外的"顺手优化"先开 ADR。
- 不删除注释、不"清理"无关代码,scope 严格收敛。

---

## 5. 验证门禁 `init.sh`

`init.sh` 是单一可执行约束,**所有"完成"声明的硬证据**。当前 ticket 阶段(脚手架未建)其行为是占位失败,见脚本顶部注释;一旦工程脚手架 ticket 完成,此脚本必须替换为真实门禁(预计组合: `mvnw -q -DskipTests package` + `mvnw test` + `docker compose config -q` 校验 compose 合法)。

任何"我自己测过 OK"不算证据。**只有 `bash init.sh` 退出 0 才算通过。**

---

## 6. Tracker 模式与 Issue Tracker 路由

本仓库采用 **Tracker 模式**,状态与依赖由 **Local Markdown** 工单系统承接。

- **Tracker 配置**: `docs/agents/issue-tracker.md`、`docs/agents/triage-labels.md`、`docs/agents/domain.md`(均由 `mattpocock-skills:setup-matt-pocock-skills` 生成)。
- **工单落点**: `.scratch/campus-food-recommend/issues/0N-FN-<slug>.md`(已建好 F-1 ~ F-5)。
- **spec 落点**: `.scratch/campus-food-recommend/spec.md`。
- **状态行**: 工单顶部 `Status:` 是 **triage role**(needs-triage / needs-info / ready-for-agent / ready-for-human / wontfix);工程进度写工单 body 内 `## 工程进度` 段。
- **不写** `feature_list.json` / `progress.md`(Tracker 模式不在仓内)。
- **.scratch/ 受 git 忽略**:见 §8 + `.gitignore`。工单与仓内 tracked 文件物理隔离。

---

## 7. 简历 bullet → ticket 映射

| ticket | bullet 一句话 | 工单路径 | triage status |
|---|---|---|---|
| F-1 | 面向扩缩容的无状态架构(JWT + Docker Compose + Nginx) | `.scratch/campus-food-recommend/issues/01-F1-stateless-jwt.md` | ready-for-agent |
| F-2 | 会话槽位约束的渐进式检索(Redis 会话状态 + Skill 模块) | `.scratch/campus-food-recommend/issues/02-F2-session-slot.md` | ready-for-agent |
| F-3 | 结构化输出与反思重试(JSON Schema + AI 自反思) | `.scratch/campus-food-recommend/issues/03-F3-structured-output.md` | ready-for-agent |
| F-4 | 离线数据加工与缓存预热(Spring Task + 层级 JSON + Redis 分片) | `.scratch/campus-food-recommend/issues/04-F4-offline-preheat.md` | ready-for-agent |
| F-5 | 缓存一致性与多级加速(Redis 原子 + RabbitMQ + Caffeine) | `.scratch/campus-food-recommend/issues/05-F5-cache-consistency.md` | ready-for-agent |

F-1 必须先做(其他 ticket 都依赖它建好的工程脚手架与鉴权骨架)。F-2 ~ F-5 之间互相解耦,可按任意顺序推进;F-5 强依赖 F-4 的缓存层。

---

## 8. git 跟踪策略(待 git init 后生效)

| 路径 | 跟踪 | 理由 |
|---|---|---|
| `AGENTS.md` `CONTEXT.md` `docs/adr/` | tracked | 长期资产 |
| `init.sh` | tracked | 门禁必须共享 |
| `.scratch/` | untracked | 任务级暂存,完成即删 |
| `target/` `*.class` `.idea/` `*.iml` `.vscode/` | untracked | 构建产物与编辑器配置 |
| `.env` `.env.*` `secrets/` | untracked | 密钥 |

具体 .gitignore 内容见仓库根 `.gitignore`。

---

## 9. 不变量(任何 ticket 都不得破坏)

- 包名前缀 `com.meisijiya.campusfood.*` 不变。
- Spring Boot 3.x + JDK 17+ 不变(除非新 ADR 显式覆盖)。
- 不引入简历技术栈之外的中间件(MySQL / Redis / RabbitMQ / Caffeine / Spring AI 是天花板)。
- ADR 只增不删;过时 ADR 加"已废止"标记但保留正文。
- `init.sh` 必须可独立运行,依赖在脚本内声明或写在 README,不靠外部环境魔法。

---

## 10. Startup Workflow(新会话第一件事)

1. `pwd` 确认在仓库根。
2. **必须先读** `AGENTS.md` 与 `CONTEXT.md`,然后才能动代码。
3. 检查 `docs/agents/` 是否存在 → 不存在提示用户跑 matt 初始化(否则状态来源缺失)。
4. 从 `docs/agents/` 选一个 `status: in-progress` 的工单继续;没有就选下一个 `pending`。
5. 不要在工单 evidence 段为空时声称完成。

## 11. One ticket at a time(强制)

**one ticket at a time** — 一次只允许一个 active ticket。在切换到下一个 ticket 之前,当前工单必须:
- evidence 段非空,记录最近一次 `bash init.sh` 的命令 + 结果摘要。
- 至少一次 commit。

越界新增工作必须在当前工单的"out of scope"段登记,完成后回滚或转新 ticket。**禁止多线并行**——多代理必须先在 AGENTS.md 加"## Multi-Agent Boundaries"小节定义所有权,目前未启用。

## 12. Session Wrap-up(会话结束必做)

每次会话结束前:
1. 把本次会话的"做了什么 / 下一步是什么 / 阻塞在哪"三段写到 **`.scratch/handoff/last-session.md`**(引用式,不复制 ADR / 工单正文)。
2. 受影响工单的 evidence 段更新(命令 + 输出摘要)。
3. 未提交的改动要么 commit、要么 stash、要么写进 handoff 明确说明。
4. `.scratch/` 下与本次会话无关的临时材料删掉。

## 13. Handoff Convention(交接到上游 matt)

- **仓内不创建 handoff 文档常驻**(反例黑名单第 2 条)。所有交接材料进 `.scratch/handoff/`,任务完成即删。
- **Tracker 模式工单 = 状态来源**:会话中断后的下一会话直接读 `docs/agents/F-*.md` 的 `status` 与 `evidence` 段恢复上下文,**不读聊天历史**。
- `.scratch/handoff/` 仅承载"当前工单 evidence 之外的临时上下文",完成工单后整体删除。

## 14. Clean Restart(干净重启路径)

任何新会话重启项目时,依次:
1. `git pull`(或首次 `git init`)。
2. `bash init.sh` — 必须退出非零(脚手架阶段),退出 0 才是有效完成态。
3. 读最新一个 `status: in-progress` 工单;无则在工单系统里选下一个 `pending` 工单转 `in-progress`。
4. 在 `.scratch/handoff/` 检查最近一次会话的 handoff(若存在)。

## 15. Agent skills(由 matt setup 注入)

本节由 `mattpocock-skills:setup-matt-pocock-skills` 在 2026-09-18 初始化写入。后续若切换工单系统或重启,在此更新。

### Issue tracker

Local Markdown: 工单文件在 `.scratch/campus-food-recommend/issues/`,spec 在 `.scratch/campus-food-recommend/spec.md`。详见 `docs/agents/issue-tracker.md`。

### Triage labels

默认五个: `needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`。详见 `docs/agents/triage-labels.md`。

### Domain docs

Single-context: 一份 `CONTEXT.md` + `docs/adr/`。详见 `docs/agents/domain.md`。

---

## 16. Stack & Harness updates(2026-09-18 to-spec 沉淀后)

> 本节是 to-spec / to-tickets 跑完后新增的约定,**与 §2-§9 不变量并列**;若冲突,以本节为准并回 §2-§9 修订。

### 16.1 实际采用的技术栈

| 维度 | 锁定值 | 决策来源 |
|---|---|---|
| JDK | **21 LTS** | ADR-0003(覆盖 ADR-0002 的 17) |
| Spring Boot | 3.5.16 | ADR-0003 |
| Spring AI Alibaba DashScope | 1.1.2.2 | ADR-0003 |
| 中间件 | MySQL 8.4 / Redis 7.4 / RabbitMQ 3.13-mgmt(docker) | ADR-0003 |
| 鉴权 | JJWT 0.12.6 | ADR-0002 |
| Schema 校验 | networknt 1.5.2 | ADR-0003 |
| 压测 | locust + uv venv | ADR-0004(替代 wrk) |

### 16.2 Spring AI 双 Profile(不可省)

| Profile | ChatModel | 启用方式 |
|---|---|---|
| `dev` / `test` / `it`(默认) | `MockChatModel`(`@Configuration @Profile("!bench & !smoke")`)| 不设 `SPRING_PROFILES_ACTIVE` 即可 |
| `bench` / `smoke` | `DashScopeChatModel`(Spring AI Alibaba Starter 1.1.2.2)| `SPRING_PROFILES_ACTIVE=bench ./mvnw spring-boot:run` |

**纪律**:百炼真实 API 仅在 `bench` / `smoke` profile 启用;`./mvnw test` 默认走 Mock,**禁止**在 CI / 本地测试阶段启用真实 API(避免金钱损失,用户在 2026-09-18 Q2 明确)。

### 16.3 Python 压测工具链(locust + uv)

- 仓根 `pyproject.toml` 锁 `locust>=2.31`;
- 仓根 `locustfile.py` 定义三场景(用户对应三个 tag): `auth-only` / `recommend` / `mix-like-detail`;
- `uv venv` 与 `uv sync` 装依赖;`.venv/` 入 `.gitignore`;
- 跑压测统一前缀:`uv run locust -f locustfile.py --headless ...`;
- 报告落 `evidence/<ticket>-<metric>.csv`,`.gitignore` 加 `evidence/*.csv`。

### 16.4 工单拆分(dependency order)

按 to-tickets 提议重拆,顺序即执行顺序;前 3 个 ticket 完成时停下 review:

```
F-1(无依赖)  →  F-2 / F-3 / F-4(互相独立)
                              ↓
                            F-5(依赖 F-1 + F-4)
```

### 16.5 spec 与 ticket 关系

- spec 是单一真相源: `.scratch/campus-food-recommend/spec.md`(2026-09-18 重写);
- 旧 5 ticket(`01-F1` ~ `05-F5`,在 to-spec 沉淀前占位创建)已删除;
- 新 5 ticket 按 to-tickets 提议重写,文件名沿用 `0N-F<N>-<slug>.md` 风格;
- spec 变更必须先改 spec.md,再调 ticket;ticket 变更必须先调 spec.md。

---

## 17. Active ADR 引用

| ADR | 主题 | 状态 |
|---|---|---|
| 0001 | Tracker 模式 | 已采纳 |
| 0002 | 技术栈与模块拆分（JDK 17 / 单 module）| **已被 0003 部分替代** |
| 0003 | 版本表与 JDK 21 升级 | 已采纳 |
| 0004 | locust 替代 wrk | 已采纳 |
| **0005** | F-1 evidence 用 JMeter（locust 在 Windows GIL 受限，5000+ QPS bullet 跨不过） | 已采纳 |
| **0006** | F-6 Demo Readiness 范围(招实习 demo 化 hardening) | 已采纳 |
| **0007** | F-7~F-12 多 ticket 路线图(限流/锁/可观测性/CI/灰度/简历包装) | 已采纳 |

任何后续 ADR 直接追加,编号 `0006` 起;**ADR 只增不删**(§9)。

---

## 18. Multi-Agent Boundaries(2026-09-20 多 worker 委派解锁)

> **状态**:启用(2026-09-20 F-7 / F-8 / F-9 阶段首次正式启用)
> **触发**:F-6 done 后进入 F-7~F-12 实战新 bullet 阶段(ADR-0007),3 张 ticket 依赖解耦但共享 LikeService / RecommendService / MerchantQueryService 等热点文件。

### 18.1 解锁条件

AGENTS.md §11 强制 "one ticket at a time",但 F-4 / F-5 已实际通过 3 worker 并行落地(详见 `05-F5-cache-consistency.md` 工程进度段)。本节正式补齐 §11 要求的多代理前置定义,把"实践已并行"提升为"约定已并行"。

### 18.2 启用场景

满足以下**全部**条件才允许多 worker 并行:

1. **依赖已闭环**:并行 ticket 全部 `Blocked by:` 段的 ticket 已 done(查工单顶部)。
2. **scope 解耦**:每个 worker 拥有独立的"主路径文件集",不在同一文件主区域编辑(只读允许)。
3. **冲突预案**:跨 ticket 共享文件(如 `LikeService.like()`)必须明确"由谁先动 + 谁后动"或"由 orchestrator 串行合并",不允许两个 worker 并发改同一行。
5. **用户授权**:本会话内用户已通过 ask_user 显式确认走多 worker 并行(本节记录在 2026-09-20 00:17 用户回复)。

### 18.3 并行模式

| 模式 | 适用 | 切分原则 |
|---|---|---|
| **单 ticket 三 worker(W1/W2/W3)** | 单 ticket 内多模块(如 F-4 数据层/装配层/查询层;F-5 Like/Caffeine/IT) | 按代码层次切,主路径文件不重叠 |
| **多 ticket 各 worker** | 多张 ticket 同时在依赖图中可启动 | 每 ticket 独立 worker 池,跨 ticket 共享文件由 orchestrator 串行合并 |
| **混合模式**(F-7~F-9 当前采用) | 多张 ticket 同时进入,且每张内可继续切 W1/W2/W3 | 9 worker 同时 dispatch,3 ticket × 3 worker,跨 ticket 冲突文件 orchestrator 收尾合并 |

### 18.4 文件所有权表(F-7~F-9 阶段)

> **唯一所有权**:同一文件的同一方法,只允许一个 worker 编辑。其他 worker 只读。

| 文件 | 主编辑 worker | 只读 worker | 备注 |
|---|---|---|---|
| `module/ratelimit/**` 全新增 | **F-7 W1** + **F-7 W2** | F-7 W3 | W1 = Lua+接口;W2 = Caffeine降级+Filter;W3 = 测试 |
| `config/SecurityConfig.java`(放行 /actuator/prometheus 等) | **F-9 W2** | F-7 W2 (Filter 顺序) | F-7 W2 与 F-9 W2 互不修改对方区域 |
| `module/like/LikeService.java` | **F-8 W2**(加 distributed lock)+ **F-9 W1**(加 Counter metric) | — | **冲突点**:同一文件两个方法,两个 worker 各自加一段。orchestrator 串行合并:先 F-8 加锁 → 后 F-9 加埋点。 |
| `module/preheat/HeatJobPreheater.java` | **F-8 W2** | — | 仅 F-8 W2 修改 |
| `module/recommend/RecommendService.java` | **F-9 W1**(Timer metric) | — | 仅 F-9 W1 |
| `module/catalog/MerchantQueryService.java` | **F-9 W1 / F-9 W2**(Gauge metric) | — | F-9 W1 加 Timer,Gauge 在 W2 |
| `module/catalog/session/SessionService.java` | **F-9 W2**(stage Gauge) | — | F-9 W2 |
| `docker/observability/**` + `docker-compose.yml`(新增 prometheus/grafana) | **F-9 W2** | — | 仅 F-9 W2 |
| `application.yml`(新增 management.prometheus.metrics.export 等) | **F-9 W2** | — | 仅 F-9 W2 |
| `application.yml`(新增 rate-limit 配置) | **F-7 W1** | — | 仅 F-7 W1 |
| `evidence/f7-*.csv` / `f8-*.csv` / `f9-*.csv` | 各 ticket 自身 worker | — | `.gitignore` 已排除 `evidence/*.csv` |

### 18.5 冲突解决顺序(orchestrator 串行收尾)

如出现主编辑重叠,按以下顺序合并:

1. **F-7 W1 → F-7 W2 → F-7 W3** (同 ticket 内串行,各 worker git add + commit 独立分支由 orchestrator rebase)
2. **F-8 W1 → F-8 W2 → F-8 W3**
3. **F-9 W1 → F-9 W2 → F-9 W3**
4. **跨 ticket 共享文件**(如 `LikeService`):**F-8 W2 先 → F-9 W1 后** —— 因为 F-8 的锁包裹整个方法体,F-9 的 Counter 应在锁内 try/finally 或锁外,前者更内聚;先做锁再做埋点。
5. **最终**:orchestrator 跑 `mvn -B verify` + `pwsh -File init.ps1` 6/6 stage + `git log --oneline` 审计每条 commit 含 `[F-N]` 前缀。

### 18.6 撤场条件

任一条件触发即回退到 §11 单 ticket 模式:

- 任一 worker 失败且修复成本 > 1 ticket 时间
- 跨 ticket 共享文件合并冲突 > 3 处需要人工决策
- orchestrator 串行收尾后 `mvn verify` 红

回退路径:删除 worker 提交,回到上一次 `mvn verify` 绿的状态,改串行做当前 ticket。

### 18.7 不变量(并行模式不得破坏)

- 包名前缀 `com.meisijiya.campusfood.*` 不变
- Spring Boot 3.x + JDK 21 不变
- 不引入简历技术栈之外的中间件(MySQL / Redis / RabbitMQ / Caffeine / Spring AI + Prometheus / Grafana,后者为本节显式授权)
- ADR 只增不删
- `init.sh` / `init.ps1` 仍为单一可执行约束
- **commit 信息必含 `[F-N]` 前缀**(F-7 / F-8 / F-9 各 worker commit 也含,如 `[F-7][W1]`)