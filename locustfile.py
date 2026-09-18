"""locust 压测脚本 — 三个场景对应 ADR-0004。

执行示例(全部前置 uv sync):
  uv run locust -f locustfile.py --headless --host=http://localhost \\
      --tags auth-only -u 200 -r 50 -t 30s --csv=evidence/f1-qps

  uv run locust -f locustfile.py --headless --host=http://localhost \\
      --tags recommend -u 100 -r 50 -t 30s --csv=evidence/f4-p95

  uv run locust -f locustfile.py --headless --host=http://localhost \\
      --tags mix-like-detail -u 50 -r 25 -t 30s --csv=evidence/f5-p99
"""
from locust import HttpUser, task, between, events


class AuthOnlyUser(HttpUser):
    """F-1 场景:刷 /actuator/health 验证 5000+ QPS 水平扩容能力。"""

    wait_time = between(0, 0.01)  # 极短间隔逼出极限

    @task
    def health(self):
        self.client.get("/actuator/health", name="/actuator/health")


class RecommendUser(HttpUser):
    """F-4 场景:登录 → 走会话槽位 → 调推荐接口。验证预热 Redis 后 P95 ↓60%。"""

    wait_time = between(0.1, 0.5)

    def on_start(self):
        # 登录拿 token(F-1 已经实现,F-4 才用得到)
        r = self.client.post(
            "/api/auth/login",
            json={"username": "demo", "password": "demo"},
            name="/api/auth/login",
        )
        r.raise_for_status()
        self.token = r.json()["accessToken"]
        self.headers = {"Authorization": f"Bearer {self.token}"}

    @task(3)
    def init_session(self):
        self.client.post(
            "/api/session/init", headers=self.headers, name="/api/session/init"
        )

    @task(1)
    def recommend(self):
        self.client.post(
            "/api/recommend", headers=self.headers, name="/api/recommend"
        )


class MixLikeDetailUser(HttpUser):
    """F-5 场景:50% 点赞 + 50% 详情读混合流量,验证 P99 < 50ms。"""

    wait_time = between(0, 0.05)

    def on_start(self):
        r = self.client.post(
            "/api/auth/login",
            json={"username": "demo", "password": "demo"},
            name="/api/auth/login",
        )
        r.raise_for_status()
        self.token = r.json()["accessToken"]
        self.headers = {"Authorization": f"Bearer {self.token}"}
        # 选一个固定 merchantId 用于点赞(F-5 才用得到,F-1 阶段可能 404)
        self.merchant_id = "m-001"

    @task
    def like(self):
        self.client.post(
            f"/api/like/{self.merchant_id}",
            headers=self.headers,
            name="/api/like/:merchantId",
        )

    @task
    def merchant_detail(self):
        self.client.get(
            f"/api/merchant/{self.merchant_id}",
            headers=self.headers,
            name="/api/merchant/:id",
        )


# tag 绑定(供 --tags 过滤)
AuthOnlyUser.tasks = [health for health in AuthOnlyUser.tasks]
RecommendUser.tasks = RecommendUser.tasks
MixLikeDetailUser.tasks = MixLikeDetailUser.tasks


# 让每个 User 类可以被打对应 tag
for cls, tag in [
    (AuthOnlyUser, "auth-only"),
    (RecommendUser, "recommend"),
    (MixLikeDetailUser, "mix-like-detail"),
]:
    existing = list(cls.tasks) if cls.tasks else []
    cls.tasks = existing  # tasks 已包含 @task 标注的方法
    setattr(cls, "tags", [tag])


# 命令行入口(uv run locust -f locustfile.py 直接生效)
if __name__ == "__main__":
    import locust.main
    locust.main.main()