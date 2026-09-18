# ADR-0005:F-1 5000+ QPS evidence 改用 JMeter(locust 留作 F-4/F-5 默认)

- **状态**: 已采纳
- **日期**: 2026-09-19
- **决策者**: 用户(meisijiya)
- **覆盖**: F-1 ticket `01-F1-stateless-jwt.md` evidence 段的 locust 压测命令;**不覆盖** F-4 / F-5 的 locust 命令(ADR-0004 保持)
- **关联**: 续 ADR-0004 (locust 替代 wrk)

## 决策

F-1 的"B-1 单节点 5000+ QPS"压测 evidence 改用 **Apache JMeter 5.6.3 (CLI non-GUI 模式)**,locust 在 F-4 / F-5 ticket 中保留(实战流量场景)。

| 场景 | 工具 | 配置 |
|---|---|---|
| **F-1 单节点 5000+ QPS** | **JMeter CLI** | `evidence/f1-jmeter.jmx` 500 threads / 10s ramp / 60s duration |
| F-4 推荐流量 P95 | locust | (保留 ADR-0004) `--tags recommend -u 100 -r 50 -t 30s` |
| F-5 混合流量 P99 | locust | (保留 ADR-0004) `--tags mix-like-detail -u 50 -r 25 -t 30s` |

## 理由

1. **Locust 在 Windows 的 GIL 限制**:本机 (Windows 11) 实测 locust 2.46.6 单进程上限约 **1100-1200 RPS**(具体数字 `evidence/f1-qps_*.csv` + summary line 1217 RPS);locust 文档明示 `--processes 4` 不支持 Windows(only Linux/WSL2)。
2. **JMeter 是 Java 多线程原生**:无 GIL 限制,本机实测跨过 5000+ QPS:
   ```
   summary = 304147 in 00:01:00 = 5052.0/s  Avg: 90ms  Err: 0 (0.00%)
   window peaks: 5091 / 5046 / 5196 RPS
   ```
3. **F-1 仅压一个 endpoint**:locust 在 F-1 场景没有发挥多用户场景协议模拟的优势;F-4 / F-5 多 endpoint 多业务的实战流量,locust 的 `HttpUser` + `@task` 编程模型才能体现价值。
4. **本机已装 JMeter 5.6.3**(用户口径):无新增依赖。

## 后果

### 正面
- F-1 的 5000+ QPS bullet 有了真实可复现的实测 evidence,与简历口径一致。
- locust 保留在 F-4 / F-5,实战场景不浪费现有 locustfile.py 与 3 个 user class。
- 验证过程暴露了一个**真实的 F-1 service-side 调优点**:`server.tomcat.threads.max` 默认 200 不够并发;Spring Boot 拉到 800 才能稳定跨 5000+。此调参在 `application.yml` 提交,f-2+ 受益。

### 负面 / 约束
- F-1 ticket evidence 段命令不再是 `uv run locust ...`,改成 `jmeter -n -t evidence\f1-jmeter.jmx ...`
- 仓根新增 `evidence/f1-jmeter.jmx`(进 git)+ 多个 `evidence/f1-*.jtl`(locust debug + jmeter 主结果,均进 git,因 `.gitignore evidence/*.csv` 不匹配 `*.jtl`)
- ADR-0004 的"备选"段需要 review:其原文否掉了 JMeter(理由"启动曲线重,CI 集成不如 locust 干净"),本 ADR 把这条否定**部分撤销**(仅适用 F-4/F-5)
- F-1 ticket 的 `Evidence(命令 + 结果摘要)` 段需要重写;不在 ADR-0004 范围内做 ADR-0004 amend,而是写 ADR-0005 替代

## 备选

| 备选 | 否决理由 |
|---|---|
| Locust 在 Linux/WSL2 跑 `--processes 4` | 需要用户在 WSL ↔ Docker Desktop 之间配端口;调试链长 |
| Apache Bench (ab.exe) | 输出简陋;与 ADR-0004 "原生 P95/P99 报告"诉求背离 |
| wrk2 | (历史否决项,ADR-0004 已记录)Windows 编译链痛 |
| hey | Go 单二进制;输出 HTML 报告偏弱;本机未装 |

## 落地动作

1. F-1 ticket 的 evidence 段改写,引用 `evidence/f1-jmeter-500t-60s.jtl` 作为权威 evidence。
2. `application.yml` 加 `server.tomcat.threads.max:800` (此调参同时是必要的 F-1 service-side fix):
   ```yaml
   spring:
     tomcat:
       threads:
         max: 800
         min-spare: 50
       accept-count: 500
       connection-timeout: 5s
   ```
3. `evidence/f1-jmeter.jmx` 进 git(测试计划可复现)。
4. `evidence/f1-*.jtl` 进 git(`.gitignore evidence/*.csv` 不覆盖 .jtl,可保留作为历史 attempts)。
5. locust 的 `evidence/f1-qps_*.csv` 不进 git(.gitignore 已覆盖),但 locust 命令在 F-1 evidence 中保留作为"尝试过但 Windows 受限"的注解。

## 与 ADR-0004 的关系

ADR-0004 在"备选"段说过:
> Apache JMeter | Java GUI + CI 双模式,跨平台;但启动曲线重,CI 集成不如 locust 干净 | 否决

**本 ADR 把这条否定部分撤销**:JMeter 在 F-1 场景被重新启用,理由是 F-1 的特定约束(单 endpoint + Windows + 量化指标要求)使 locust 不达标。ADR-0004 的核心决策(locust 是默认 + 多用户场景协议建模更优)在 F-4/F-5 不变。
