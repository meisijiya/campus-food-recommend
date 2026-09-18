# Project Skills Index

本仓库 `.claude/skills/` 下项目级安装了 18 个 skill（来自 6 个 GitHub 仓库，
通过 `git clone --depth=1 --filter=blob:none --sparse` 拉取后复制）。

装入日期：2026-09-18
装入脚本：`.scratch/install-skills-shallow.ps1`（任务级暂存，使用后删除）

## Skill 清单（按 F-ticket 分组）

### 通用基底（覆盖所有 ticket）
| skill | 仓库 | 用途 |
|---|---|---|
| `java-springboot` | github/awesome-copilot | Spring Boot 通用最佳实践 |
| `java-junit` | github/awesome-copilot | JUnit 5 测试规范 |
| `create-spring-boot-java-project` | github/awesome-copilot | Spring Boot 项目骨架 |
| `java-coding-standards` | affaan-m/ecc | Java 编码规范（命名/Optional/异常/CDI） |
| `spring-boot-engineer` | jeffallan/claude-skills | Spring Boot 工程视角 |

### F-1 · 无状态 JWT 鉴权
| skill | 仓库 | 用途 |
|---|---|---|
| `springboot-security` | affaan-m/ecc | Spring Security 范式 |
| `springboot-patterns` | affaan-m/ecc | Spring Boot 项目模式 |
| `springboot-tdd` | affaan-m/ecc | TDD 实践 |
| `spring-boot-security-jwt` | giuseppe-trisciuoglio/developer-kit | JWT 鉴权专攻 |

### F-2 · 会话槽位 + Redis 状态
| skill | 仓库 | 用途 |
|---|---|---|
| `redis-patterns` | affaan-m/ecc | Redis 用法范式 |
| `redis-development` | redis/agent-skills（plugin） | Redis 官方 plugin（含 8 个子 skill） |
| `redis-core` | redis/agent-skills | Redis 数据结构 / 命令 |
| `redis-clustering` | redis/agent-skills | Redis 分片场景 |

### F-3 · Spring AI DashScope
| skill | 仓库 | 用途 |
|---|---|---|
| `spring-ai-mcp-server-patterns` | giuseppe-trisciuoglio/developer-kit | Spring AI + MCP |

### F-4 · 离线加工 + 缓存预热
| skill | 仓库 | 用途 |
|---|---|---|
| `spring-boot-cache` | giuseppe-trisciuoglio/developer-kit | 缓存范式 |

### F-5 · RabbitMQ + Caffeine + 多级缓存
| skill | 仓库 | 用途 |
|---|---|---|
| `spring-boot-event-driven-patterns` | giuseppe-trisciuoglio/developer-kit | 事件驱动 → AMQP |
| `rabbitmq-development` | mindrally/skills | RabbitMQ 专用 |
| `redis-best-practices` | mindrally/skills | Redis 最佳实践（备选） |

## 注意事项

1. **skill 不替代库文档**——遇到 JJWT / networknt JSON Schema / Spring AI Alibaba
   DashScope 具体 API 还是要去读官方 doc。
2. **不要一次激活太多**——skill 会注入每次 agent 调用的上下文，建议按当前
   active ticket 选 3-5 条切换。
3. **更新方法**——运行 `npx skills update -p` 检查新版本；或重新跑
   `install-skills-shallow.ps1`（脚本以幂等方式覆盖 `.claude/skills/`）。

## 排除的 skill

| skill | 排除原因 |
|---|---|
| `docker-compose-orchestration` (manutej) | 3 个月未 push，仓库陈旧 |
| `pluginagentmarketplace/custom-plugin-java` | 8 个月未 push，陈旧 |