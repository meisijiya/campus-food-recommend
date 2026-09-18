# ADR-0001:采用 Tracker 模式管理本仓库状态

- **状态**: 已采纳
- **日期**: 2026-09-18
- **决策者**: 用户(meisijiya)

## 背景

需要为"校园美食推荐平台"Java demo 仓库建立 AI 编码代理工作框架。harness-creator skill 给出两种状态治理模式:

1. **Registry 模式**: 状态写在仓内 `feature_list.json` + `progress.md`,无外部工单依赖。
2. **Tracker 模式**: 状态由外部工单系统(matt)承接,仓内只留 `CONTEXT.md` + ADR。

## 决策

采用 **Tracker 模式**。

## 理由

1. 项目本身有 5 条天然的"已完成定义可量化"的简历 bullet,本身就是 ticket 友好的结构,工单粒度更细于简单的 feature 列表。
2. 用户后续若引入 `matt` skill 做长期管理,Tracker 模式天然兼容,无需迁移。
3. Tracker 模式强制"状态唯一来源"原则,避免双写漂移(反例黑名单第 1 条)。

## 后果

### 正面
- 状态由 matt 工单文件承载,与代码 commit、ADR 各司其职,无冲突。
- 工单 evidence 段天然承接简历 bullet 的"量化指标验证"。

### 负面 / 依赖
- **必须先跑 matt 上游初始化**,否则 `docs/agents/` 不存在,状态来源缺失。
- 在 matt 跑通之前,简历 5 条 bullet 的"工单形态"无法落地;只能靠 README / 本 ADR 临时登记。

## 备选

- **Registry 模式**: 实现门槛低,但与后续引入 matt 时会产生双写期。需要时再升级。

## 上游依赖

- matt skill: `mattpocock-skills:setup-matt-pocock-skills`(用户在仓库根手动执行一次)。
- 跑通后:`docs/agents/` 目录将出现 `F-1` ~ `F-5` 五个工单 md,每个工单的 `status` / `evidence` 字段是状态来源。