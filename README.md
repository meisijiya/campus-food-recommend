# 校园美食推荐平台(campus-food-recommend)

校园跑浪团队孵化的美食推荐平台后端 demo,目标:逐条复现简历"项目描述"段的 5 条带量化指标的 bullet。

## 技术栈(ADR-0003)

| 维度 | 版本 |
|---|---|
| JDK | 21 LTS |
| Spring Boot | 3.5.16 |
| Spring AI Alibaba DashScope | 1.1.2.2 |
| MySQL / Redis / RabbitMQ | 8.4 / 7.4 / 3.13-management(docker)|
| 鉴权 | JJWT 0.12.6 无状态 |
| 压测 | locust + uv(ADR-0004 替代 wrk)|

## 启动顺序

### 1. 一键起依赖(docker compose)

```bash
# 第一次启动前:确保本机 3306 端口空闲(停掉本机 MySQL service,避免冲突)
net stop MySQL

docker compose up -d
# 等待 healthcheck 全绿:mysql / redis / rabbitmq / app / nginx
docker compose ps
```

应用监听 `http://localhost`(nginx 80 端口)。

### 2. 本地开发模式(不走 docker)

```bash
# 仅启动应用(H2 内存数据库,无 Redis / RabbitMQ)
./mvnw spring-boot:run
```

应用监听 `http://localhost:8080`。

### 3. Bench profile(切真实百炼 API)

```bash
SPRING_PROFILES_ACTIVE=bench DASHSCOPE_API_KEY=sk-xxx ./mvnw spring-boot:run
```

⚠️ **仅在 F-3 量化证据采集阶段使用;`./mvnw test` 默认走 dev profile + MockChatModel**。

## 端点

### 鉴权(F-1)

| Method | Path | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 用户名密码登录 → `{accessToken, refreshToken, expiresIn}` |
| POST | `/api/auth/refresh` | refresh token 换新 access + refresh |
| GET | `/actuator/health` | 健康检查(无鉴权)|

### 预置账号(F-1 demo,内存用户)

| 用户名 | 密码 | 角色 |
|---|---|---|
| `demo` | `demo` | STUDENT |
| `admin` | `admin` | STUDENT + ADMIN |

## 测试

```bash
# 单元 + 集成测试(Mock profile,不产生 LLM 计费)
./mvnw test

# 压测 F-1 5000+ QPS(需先启动应用)
uv run locust -f locustfile.py --headless --host=http://localhost \
    --tags auth-only -u 200 -r 50 -t 30s --csv=evidence/f1-qps
```

## 项目结构

```
com.meisijiya.campusfood
├── CampusFoodApplication.java
├── common/                  // ApiResponse / GlobalExceptionHandler
├── config/                  // SecurityConfig / JwtAuthenticationFilter / MockChatModelConfig
└── module/
    ├── auth/                // F-1: JwtService / AuthController / AuthService / UserDetailsServiceImpl
    ├── catalog/             // F-2/F-4/F-5(待实现)
    ├── recommend/           // F-3(待实现)+ MockChatModel(F-1 占位)
    ├── preheat/             // F-4(待实现)
    └── like/                // F-5(待实现)
```

## 验证门禁

```bash
bash init.sh
```

5 ticket 全 done 后此脚本才会返 0;当前阶段会主动返 1(预期行为)。

## 文档

- `AGENTS.md` — Agent 工作约定
- `CONTEXT.md` — 领域语言
- `docs/adr/` — 决策记录(只增不删)
- `.scratch/campus-food-recommend/spec.md` — 项目 spec(单一真相源)
- `.scratch/campus-food-recommend/issues/` — 5 个工单