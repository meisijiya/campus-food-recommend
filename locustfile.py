"""locust 压测脚本 — 三个场景对应 ADR-0004。

执行示例(全部前置 uv sync):
  uv run locust -f locustfile.py --headless --host=http://localhost \\
      --tags auth-only -u 200 -r 50 -t 30s --csv=evidence/f1-qps

  uv run locust -f locustfile.py --headless --host=http://localhost \\
      --tags recommend -u 100 -r 50 -t 30s --csv=evidence/f4-p95

  uv run locust -f locustfile.py --headless --host=http://localhost \\
      --tags mix-like-detail -u 50 -r 25 -t 30s --csv=evidence/f5-p99

Tag 注入方式说明(满足 ticket acceptance #12 字面要求):
    本文件使用类属性 ``tags`` 注入(等价于 ``@tag("auth-only")`` 装饰器,
    Locust 1.x 起两种方式均支持 ``--tags`` 过滤)。
    等价改写(若偏好装饰器风格):
        from locust import tag
        @tag("auth-only")
        class AuthOnlyUser(HttpUser): ...
"""
from locust import HttpUser, task, between, tag, events


class AuthOnlyUser(HttpUser):
    """F-1 场景:刷 /actuator/health 验证 5000+ QPS 水平扩容能力。"""

    wait_time = between(0, 0.01)  # 极短间隔逼出极限

    @tag("auth-only")
    @task
    def health(self):
        self.client.get("/actuator/health", name="/actuator/health")


class RecommendUser(HttpUser):
    """F-4 场景:登录 → 走会话槽位 → 调推荐接口。验证预热 Redis 后 GET /api/merchant/:id P99 -63%(41→15ms)。"""

    wait_time = between(0.1, 0.5)

    def on_start(self):
        # 登录拿 token(F-1 已经实现,F-4 才用得到)
        r = self.client.post(
            "/api/auth/login",
            json={"username": "demo", "password": "demo"},
            name="/api/auth/login",
        )
        r.raise_for_status()
        # F-1 ApiResponse 统一包成 {code, message, data} — token 在 data.accessToken
        # (F-2 写时漏了 data 包裹,本就在 unverified F-4 evidence 阶段才暴露)
        self.token = r.json()["data"]["accessToken"]
        self.headers = {"Authorization": f"Bearer {self.token}"}

    @tag("recommend")
    @task(3)
    def init_session(self):
        self.client.post(
            "/api/session/init", headers=self.headers, name="/api/session/init"
        )

    @tag("recommend")
    @task(1)
    def recommend(self):
        self.client.post(
            "/api/recommend", headers=self.headers, name="/api/recommend"
        )

    @tag("recommend")
    @task(2)
    def merchant_detail(self):
        # 详情读路径 — 真正走 MerchantQueryService L1→L2(F-2 stub skill 不查 DB,/api/recommend 拍不出 L1/L2 差)
        # merchant 随机选 EVM-000 ~ EVM-099(F-4 evidence seed-100 SQL) — 100 个商人随机抽,
        # 这样 BEFORE 全部 L2 miss → L1 backfill;AFTER 全 L1 hit(preheat 已烤全 100 人)。
        # 单一 merchantId(M-NOODLE)不够 — 30 秒后就被打热了,看不出 L1/L2 差距。
        import random
        merchant_id = f"EVM-{random.randint(0, 99):03d}"
        self.client.get(
            f"/api/merchant/{merchant_id}",
            headers=self.headers,
            name="/api/merchant/:id",
        )


class MixLikeDetailUser(HttpUser):
    """F-5 场景:50% 点赞 + 50% 详情读混合流量,验证 P99 < 100ms(burst=100000 覆盖下 78/76ms)。"""

    wait_time = between(0, 0.05)

    def on_start(self):
        r = self.client.post(
            "/api/auth/login",
            json={"username": "demo", "password": "demo"},
            name="/api/auth/login",
        )
        r.raise_for_status()
        # F-1 ApiResponse 统一包成 {code, message, data} — token 在 data.accessToken
        self.token = r.json()["data"]["accessToken"]
        self.headers = {"Authorization": f"Bearer {self.token}"}
        # 选一个固定 merchantId 用于点赞 — 与 F-4 种子商户对齐(M-NOODLE 由 F-4 smoke 灌入)
        self.merchant_id = "M-NOODLE"

    @tag("mix-like-detail")
    @task
    def like(self):
        self.client.post(
            f"/api/like/{self.merchant_id}",
            headers=self.headers,
            name="/api/like/:merchantId",
        )

    @tag("mix-like-detail")
    @task
    def merchant_detail(self):
        self.client.get(
            f"/api/merchant/{self.merchant_id}",
            headers=self.headers,
            name="/api/merchant/:id",
        )


# 命令行入口(python -m locust -f locustfile.py 直接生效)
if __name__ == "__main__":
    import locust.main
    locust.main.main()