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

## 6. Tracker 模式依赖与上游协调

本仓库采用 **Tracker 模式**,状态与依赖由工单系统承接,不在仓内写 `feature_list.json` / `progress.md`。

- **当前状态**: 上游 `matt` skill 尚未初始化 → `docs/agents/` 工单目录还不存在。
- **本技能不创建工单文件**(`docs/agents/` 是 matt 上游的产物)。
- **简历 5 条 bullet → 5 个 ticket 编号已在本文件中预留**: F-1 ~ F-5(见 §7)。
- **下一步(用户执行)**: 在仓库根跑一次 matt 的初始化 skill(命令见后续回复),之后工单文件会被自动生成在 `docs/agents/`。在那之前,如需手动登记进度,只在 README 顶部加一段"待 matt 初始化"的临时状态。
- **matt 跑完后**:
  - `docs/agents/` 下生成 `F-1` ~ `F-5` 工单 md。
  - 工单文件 = 状态来源。状态变更、改 evidence、关 ticket 都改工单。
  - AGENTS.md 本节作为路由指引长期保留。

---

## 7. 简历 bullet → ticket 映射

| ticket | bullet 一句话 | 状态 |
|---|---|---|
| F-1 | 面向扩缩容的无状态架构(JWT + Docker Compose + Nginx) | 待启动 |
| F-2 | 会话槽位约束的渐进式检索(Redis 会话状态 + Skill 模块) | 待启动 |
| F-3 | 结构化输出与反思重试(JSON Schema + AI 自反思) | 待启动 |
| F-4 | 离线数据加工与缓存预热(Spring Task + 层级 JSON + Redis 分片) | 待启动 |
| F-5 | 缓存一致性与多级加速(Redis 原子 + RabbitMQ + Caffeine) | 待启动 |

F-1 必须先做(其他 ticket 都依赖它建好的工程脚手架与鉴权骨架)。F-2 ~ F-5 之间互相解耦,可按任意顺序推进。

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