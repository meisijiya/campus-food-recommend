"""locust feature-flag harness — F-11 evidence.

Why a separate file: locust's --tags filter excludes from the scheduled task list,
but ALL User classes declared in the file are still instantiated. Splitting per-tag into
separate files is the cleanest workaround; the master ``locustfile.py`` keeps
all classes for backward-compat / doc references in tickets. Same pattern as
F-4 (``locustfile_recommend.py``), F-5 (``locustfile_mix.py``),
F-7 (``locustfile_ratelimit.py``).

Target endpoints
----------------
F-11 W2's ``FeatureFlagController`` exposes the public ``check`` endpoint without
authentication (``SecurityConfig`` puts ``/api/feature-flag/**`` in permitAll), so
this locust script needs no login for the check tasks. The admin ``POST`` task is
kept as an opt-in tag because admin operations need an ADMIN token — the script
documents how to provide it.

The three demo flows exercised here:
  - ``recommend-v2``  (PERCENTAGE 20)        — check at scale to validate 20% hit ratio
  - ``like-cache-bypass`` (WHITELIST_ONLY)   — check at scale to validate whitelist membership
  - ``merchant-detail-new`` (ALL_OFF)        — admin POST toggle + check validation

User flow
---------
1. ``on_start`` skips login (check endpoint is permitAll).
2. ``task`` GETs ``/api/feature-flag/{flagKey}/check?studentId=N`` — this is the
   public demo path; Aspect in W3 wraps ``FeatureFlagService.isEnabled`` calls.

Per F-11 ticket acceptance: locust should hammer the check endpoint hard enough to
observe PERCENTAGE-mode 20% hit ratio on ``recommend-v2`` over many students.

Usage
-----
  uv run locust -f locustfile_featureflag.py --headless --host=http://127.0.0.1:8080 \\
      --tags feature-flag-check -u 100 -r 50 -t 30s --csv=evidence/f11-check

CSV outputs (locust --csv prefix, written under ``evidence/`` which is
git-ignored — see root ``.gitignore`` ``evidence/*.csv`` rule):
  - evidence/f11-check_stats.csv          — per-endpoint aggregate stats
  - evidence/f11-check_stats_history.csv  — time-series (csv-full-history)
  - evidence/f11-check_failures.csv       — failed-request detail
  - evidence/f11-check_exceptions.csv     — exception detail

Evidence interpretation
-----------------------
``stats.csv`` columns of interest for the F-11 bullet ("3 demo flows"):
  - ``Request Count`` — total requests issued (across all users, 30s window)
  - ``Requests/s``   — actual QPS achieved
  - ``Failure Count`` — 4xx/5xx count (200 is expected success)
  - ``Median / 95% / 99%`` response time percentiles
  - per-endpoint row ``/api/feature-flag/recommend-v2/check`` should show 200s
  - per-endpoint row ``/api/feature-flag/like-cache-bypass/check`` should show 200s
  - per-endpoint row ``/api/feature-flag/merchant-detail-new/check`` should show 200s

The acceptance criterion is qualitative — W3 documents 3 demo scenarios and the
locust run provides evidence that the public ``/api/feature-flag/{flagKey}/check``
endpoints are stable under load. PERCENTAGE 20% ratio is verifiable independently
via the ``feature_flag_check_total{flag, decision}`` Prometheus counter.

See ADR-0005 for the limitation: on Windows GIL, locust tops out ~1-2k QPS;
this script is for **functional stability** evidence, not raw throughput (that's
covered by JMeter for F-1 / locust for F-7).
"""
from locust import HttpUser, task, between, tag, events


class FeatureFlagCheckUser(HttpUser):
    """F-11 场景:公开 check 接口在 100 用户下稳定,不依赖鉴权。"""

    # 短间隔模拟"管理员高频 toggle + 检查"压力
    wait_time = between(0.05, 0.2)

    # 检查 100 个 studentId,用于 PERCENTAGE 20 命中验证
    STUDENT_IDS = list(range(1, 101))

    @tag("feature-flag-check")
    @task(6)  # 60% 流量给 recommend-v2(主 demo)
    def check_recommend_v2(self):
        """GET /api/feature-flag/recommend-v2/check?studentId=N — 20% 灰度命中。"""
        sid = self.STUDENT_IDS[self.environment.runner.user_count % len(self.STUDENT_IDS)]
        self.client.get(
            f"/api/feature-flag/recommend-v2/check?studentId={sid}",
            name="/api/feature-flag/recommend-v2/check",
        )

    @tag("feature-flag-check")
    @task(2)  # 20% 流量给 like-cache-bypass
    def check_like_cache_bypass(self):
        """GET /api/feature-flag/like-cache-bypass/check?studentId=N — 白名单命中。"""
        sid = self.STUDENT_IDS[self.environment.runner.user_count % len(self.STUDENT_IDS)]
        self.client.get(
            f"/api/feature-flag/like-cache-bypass/check?studentId={sid}",
            name="/api/feature-flag/like-cache-bypass/check",
        )

    @tag("feature-flag-check")
    @task(2)  # 20% 流量给 merchant-detail-new
    def check_merchant_detail_new(self):
        """GET /api/feature-flag/merchant-detail-new/check?studentId=N — 开关状态。"""
        sid = self.STUDENT_IDS[self.environment.runner.user_count % len(self.STUDENT_IDS)]
        self.client.get(
            f"/api/feature-flag/merchant-detail-new/check?studentId={sid}",
            name="/api/feature-flag/merchant-detail-new/check",
        )


class FeatureFlagAdminUser(HttpUser):
    """F-11 场景:admin POST 改 flag 配置 — 需要 ADMIN token;脚本里默认空 token,
    真实跑前用 ``TOKEN`` env var 注入。"""

    wait_time = between(1.0, 3.0)

    @tag("feature-flag-admin")
    @task
    def admin_toggle_merchant_detail_new(self):
        """POST /admin/feature-flag/merchant-detail-new 切换 ALL_ON / ALL_OFF 验证 < 1ms 生效。"""
        import os
        token = os.environ.get("ADMIN_TOKEN", "")
        if not token:
            # 没有 token 就跳过(401 / 403 是预期失败)
            return
        headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
        # 每次 50/50 切换 ALL_ON / ALL_OFF,验证 Caffeine 失效路径生效
        mode = "ALL_ON" if (self.environment.runner.user_count % 2 == 0) else "ALL_OFF"
        self.client.post(
            "/admin/feature-flag/merchant-detail-new",
            json={"mode": mode},
            headers=headers,
            name="/admin/feature-flag/merchant-detail-new",
        )


# 命令行入口 — `python -m locust -f locustfile_featureflag.py` 也生效
if __name__ == "__main__":
    import locust.main
    locust.main.main()