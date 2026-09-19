"""locust rate-limit harness — F-7 evidence.

Why a separate file: locust's --tags filter excludes from the scheduled task list,
but ALL User classes declared in the file are still instantiated. Splitting per-tag into
separate files is the cleanest workaround; the master ``locustfile.py`` keeps
all three classes for backward-compat / doc references in tickets. Same pattern as
F-4 (``locustfile_recommend.py``) and F-5 (``locustfile_mix.py``).

Target endpoint
---------------
F-7 W2's ``RateLimitFilter.shouldNotFilter`` explicitly skips:

  - ``/api/auth/**`` — login/refresh must not be self-rate-limited (would lock
    out new users before they get a token)
  - ``/actuator/**`` — k8s probes would trigger pod kills if rate-limited
  - ``/error`` — Spring MVC internal error dispatch

Therefore the rate-limit target MUST be a non-auth, non-actuator endpoint. We use
``GET /api/merchant/M-NOODLE`` (F-4 seed merchant, also used by F-5 ``mix-like-detail``)
which:
  - goes through ``RateLimitFilter`` → 双层令牌桶 (api:GET:/api/merchant/M-NOODLE + user:demo)
  - is a real business endpoint (cache miss path hits MySQL via MerchantQueryService)
  - doesn't have heavy side effects (read-only)

User flow
---------
1. ``on_start`` POSTs ``/api/auth/login`` with demo/demo → gets ``accessToken``
   (``/api/auth/login`` is NOT rate-limited, so login itself never 429s)
2. ``task`` GETs ``/api/merchant/M-NOODLE`` with Bearer token — this is where
   rate limiting actually triggers

Per the F-7 ticket acceptance #8: locust should hammer this endpoint hard
enough to **observe token-bucket rejection** under contention. Under user
bucket defaults (burst=100, rate=10/s), you'd need ~110+ rapid requests from
a single user to trigger 429 — that's why the orchestrator runs
``-u 200 -r 50 -t 30s`` (200 users * >0.5 req/s each ≈ >100 req/s total).

Usage
-----
  uv run locust -f locustfile_ratelimit.py --headless --host=http://127.0.0.1:8080 \\
      --tags rate-limit -u 200 -r 50 -t 30s --csv=evidence/f7-rate-limit

CSV outputs (locust --csv prefix, written under ``evidence/`` which is
git-ignored — see root ``.gitignore`` ``evidence/*.csv`` rule):
  - evidence/f7-rate-limit_stats.csv          — per-endpoint aggregate stats
  - evidence/f7-rate-limit_stats_history.csv  — time-series (csv-full-history)
  - evidence/f7-rate-limit_failures.csv       — failed-request detail
  - evidence/f7-rate-limit_exceptions.csv     — exception detail

Evidence interpretation
-----------------------
``stats.csv`` columns of interest for the F-7 bullet ("单节点 1w+ QPS"):
  - ``Request Count`` — total requests issued (across all users, 30s window)
  - ``Requests/s``   — actual QPS achieved
  - ``Failure Count`` — 429 + 5xx count (200/404 are expected successes)
  - ``Median / 95% / 99%`` response time percentiles
  - per-endpoint row ``/api/auth/login`` should be ~0% failure
  - per-endpoint row ``/api/merchant/:id`` should show 429s IF 200 users
    * average > user-burst / window

See ADR-0005 for the limitation: on Windows GIL, locust tops out ~1-2k QPS;
JMeter (used for F-1 evidence) is what actually proves the 5000+ QPS bullet.
F-7's bullet is "token 桶不漏" — i.e., at any QPS the rate limiter does NOT
let through more than burst+rate*window. Locust's failure-rate curve is the
evidence; quantitative QPS target is validated separately by W2's
``RateLimitFilterTest`` plus the orchestrator's JMeter run.
"""
from locust import HttpUser, task, between, tag, events


class RateLimitUser(HttpUser):
    """F-7 场景:登录 → 走双层令牌桶 → 验证 HTTP 429 + Retry-After 行为。"""

    wait_time = between(0, 0.01)  # 极短间隔逼出 burst 耗尽

    def on_start(self):
        """登录拿 token — /api/auth/login 本身不受 RateLimitFilter 约束(shouldNotFilter 显式跳过 /api/auth/**)。"""
        r = self.client.post(
            "/api/auth/login",
            json={"username": "demo", "password": "demo"},
            name="/api/auth/login",
        )
        r.raise_for_status()
        # F-1 ApiResponse 统一包成 {code, message, data} — token 在 data.accessToken
        self.token = r.json()["data"]["accessToken"]
        self.headers = {"Authorization": f"Bearer {self.token}"}

    @tag("rate-limit")
    @task
    def merchant_detail(self):
        """GET /api/merchant/M-NOODLE — 走 RateLimitFilter 双层令牌桶。

        M-NOODLE 是 F-4 种子商户(F-5 evidence 也用),即使 cache miss 走 MySQL,
        单次请求 < 50ms,所以 200 用户 * ~100 req/s = 20k req/s 还能跑得动 GPU/CPU。
        """
        self.client.get(
            "/api/merchant/M-NOODLE",
            headers=self.headers,
            name="/api/merchant/:id",
        )


# 命令行入口 — `python -m locust -f locustfile_ratelimit.py` 也生效
if __name__ == "__main__":
    import locust.main
    locust.main.main()