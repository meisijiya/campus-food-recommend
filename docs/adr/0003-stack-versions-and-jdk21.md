# ADR-0003:版本表与 JDK 21 升级

- **状态**: 已采纳
- **日期**: 2026-09-18
- **决策者**: 用户(meisijiya)
- **覆盖**: 部分替代 ADR-0002(JDK 17 → 21)

## 决策

| 维度 | 版本 | 来源 / 备注 |
|---|---|---|
| JDK | **21 LTS** | 本机已装 Oracle JDK 21.0.6；覆盖 ADR-0002 的 17 |
| Spring Boot | **3.5.16** | 2026-06-25 release；与 Alibaba Starter 强依赖 3.5.8 兼容 |
| Spring AI Alibaba DashScope | **1.1.2.2** | `com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope`；2026-03-01 |
| MySQL | **8.4 LTS** | docker 镜像 `mysql:8.4` |
| Redis | **7.4-alpine** | docker |
| RabbitMQ | **3.13-management-alpine** | docker，含管理界面 |
| Caffeine | **3.1.8** | Spring Boot 3.5 BOM 对齐 |
| JJWT | **0.12.6** | F-1 JWT 鉴权 |
| networknt json-schema-validator | **1.5.2** | F-3 JSON Schema 校验 |
| Testcontainers | **1.20.4** | F-4 / F-5 集成测试 |
| locust | **>=2.31** | Python 压测；uv 虚拟环境管理 |
| uv | **latest** | Python 包与虚拟环境管理 |

## 理由

1. **JDK 21**：本机已装 Oracle JDK 21.0.6；Spring Boot 3.5 与 Spring AI Alibaba 1.1.2.2 都兼容 17/21。再装 17 是浪费时间，且与本机环境不一致会带来"Docker 内 17、本地编译 21"的语义陷阱。
2. **Spring Boot 3.5.16**：Spring AI Alibaba 1.1.2.2 的 BOM 强制依赖 Spring Boot 3.5.8，因此 Spring Boot 必须锁在 3.5.x 系列（最新 3.5.16 = 2026-06-25）。
3. **中间件版本**：与简历技术栈 1:1 对齐（MySQL / Redis / RabbitMQ / Caffeine），无偏差。
4. **locust / uv**：本决策在 ADR-0004 单独立项，本表仅列版本。

## 后果

### 正面
- 本机零额外安装（JDK 21 / Maven 3.9.9 / Docker / docker compose 已就绪）。
- Spring AI Alibaba 1.1.2.2 提供阿里云百炼原生 starter，无需自写 OpenAI 兼容层。

### 负面 / 约束
- 仓库内 `pom.xml` 与 Dockerfile 的 JDK 版本声明需从 17 改为 21。
- ADR-0002 不删除（AGENTS.md §9：ADR 只增不删）。后续 ADR 可加"已废止"标记但保留正文。
- Spring Boot 锁 3.5.x 后，若 Alibaba Starter 升到 1.2+ 引入 Spring Boot 4.x 依赖，需新增 ADR-0005 跟进。

## 备选

| 备选 | 否决理由 |
|---|---|
| 退回 JDK 17 | 本机需重装；与 ADR-0002 字面一致但无实质收益 |
| Spring Boot 3.4.x | Alibaba Starter 1.1.2.2 强制要求 3.5.8+，3.4 系列不兼容 |
| OpenAI starter 替代 Alibaba starter | 阿里云百炼在国内更稳；且用户已在 Q2 确认用百炼 |

## 与 ADR-0002 的覆盖关系

| 字段 | ADR-0002（旧） | ADR-0003（新） |
|---|---|---|
| 语言 | Java 17 | **Java 21** |
| 构建工具 | Maven | Maven（未变）|
| 其他 | 未列版本 | 全部锁定版本 |