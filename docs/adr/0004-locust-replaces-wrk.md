# ADR-0004:用 locust 替代 wrk 作为压测工具

- **状态**: 已采纳
- **日期**: 2026-09-18
- **决策者**: 用户(meisijiya)
- **覆盖**: 原 F-1 / F-5 DoD 段压测命令中的 wrk

## 决策

压测工具统一用 **locust**（Python + uv 虚拟环境管理），替代 5 个 ticket DoD 段里的 wrk 命令。

| 场景 | wrk（旧） | locust（新） |
|---|---|---|
| F-1 单节点 5000+ QPS | `wrk -t 8 -c 200 -d 30s http://localhost:8080/actuator/health` | `uv run locust -f locustfile.py --headless --host=http://localhost --tags auth-only -u 200 -r 50 -t 30s --csv=evidence/f1-qps` |
| F-5 混合流量 P99 < 50ms | `wrk -t 4 -c 50 -d 30s` | `uv run locust -f locustfile.py --headless --host=http://localhost --tags mix-like-detail -u 50 -r 25 -t 30s --csv=evidence/f5-p99` |

## 理由

1. **Windows 兼容性**：wrk 在 Windows 上编译需 OpenSSL + MSVC，失败概率高且耗时；locust 是 Python 包，`uv add locust` 即装即用。
2. **Python 生态一致性**：用户有 Python 基础；locust 用 Python 写负载脚本（`locustfile.py`）更顺手，后续可平滑扩展分布式 locust。
3. **F-5 P99 测量精度**：wrk 默认只给平均延迟与 stdev，要 P95/P99 得切 wrk2；locust 原生报告含 RPS / P50 / P95 / P99，正中 F-5 量化证据需求。
4. **uv 虚拟环境隔离**：Python 依赖不污染系统 Python，与 Maven 依赖隔离同构；`pyproject.toml` 与 `uv.lock` 进 git，便于复现。

## 后果

### 正面
- 压测工具链与 Java + uv 双语言一致，符合"最小化工具多样性"原则。
- F-5 P99 测量更直接，无需 wrk2 兼容层。

### 负面 / 约束
- 5 ticket 的 DoD 段压测命令都要从 wrk 改为 locust；工单 evidence 段引用本 ADR。
- 仓根新增 `pyproject.toml`（列 locust 依赖）+ `locustfile.py`（三场景负载定义）。文件放在仓根而非 `tools/`，与 `pom.xml` 同级，遵循"配置在根"约定。
- `.gitignore` 需新增 `.venv/` 与 `evidence/*.csv`（locust 报告产物不进 git）。

## 备选

| 备选 | 否决理由 |
|---|---|
| wrk2 | 同样需编译；与本机环境同样痛苦 |
| k6 | JavaScript 写脚本，与 Python 生态不一致；P99 支持完善但学习曲线与用户技能栈不匹配 |
| Apache JMeter | Java GUI + CLI 双模式，跨平台；但启动曲线重，CI 集成不如 locust 干净 |

## 落地动作

1. 仓根新增 `pyproject.toml`：
   ```toml
   [project]
   name = "campus-food-recommend-bench"
   version = "0.1.0"
   requires-python = ">=3.11"
   dependencies = ["locust>=2.31"]
   ```
2. 仓根新增 `locustfile.py`：定义 `AuthOnlyUser` / `MixAuthRecommendUser` / `MixLikeDetailUser` 三场景，分别打 `@tag auth-only` / `recommend` / `mix-like-detail`。
3. `.gitignore` 追加 `.venv/` 与 `evidence/*.csv`。
4. 5 ticket 的 DoD 段压测命令全部替换为 locust；本 ADR 编号在 DoD 段注明。