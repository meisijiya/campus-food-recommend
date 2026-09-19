"""F-8 locust 压测 — 点赞严格幂等(分布式锁场景)。

Scenario:
  100 并发用户同时对同一对 (sid, merchantId) 发 POST /api/like/{merchantId}。
  验证目标 — 在分布式锁 + 现有 NX 60s 双重保护下,所有响应里
  ``data.liked == true`` 的计数恰好 == 1(只允许 1 次真点赞,其余全部幂等命中)。

为什么需要这个场景(对比 F-5 现有 mix-like-detail):
  F-5 已有 ``MixLikeDetailUser`` 测 P99 延迟,但 50/50 混合 + 50 用户规模,无法
  触发"100 并发争抢同一把锁"的真实压力。本脚本做的是 ``like-strict`` 严格模式:
  同一 (sid, merchantId) 的纯点赞压测,用于:
    1. 验证 RedisLock 在高并发下 tryLock 唯一性(只能一个 owner 拿到锁);
    2. 验证锁释放 + NX 60s 协同后的 liked:true 严格 == 1;
    3. 当后续 W2(F-8 W2)把锁接进 LikeService.like() 时,本脚本是 acceptance
       evidence 的执行入口。

为什么独立一个文件(参考 locustfile_mix.py 的设计说明):
  locust 的 ``--tags`` 过滤**仍然会实例化文件里所有 User 类**;若把
  ``LikeStrictUser`` 放进 ``locustfile.py``,``--tags like-strict`` 会顺带触发
  ``AuthOnlyUser`` / ``RecommendUser`` / ``MixLikeDetailUser`` 的 import 副作用
  (读环境变量 / 调 /api/auth/login)。拆文件是干净做法。

前置:
  应用必须已启动(``docker compose up -d`` + ``./mvnw spring-boot:run``),且
  RedisLock 已被 LikeService.like() 接入(F-8 W2 完成)。

执行:
  uv run locust -f locustfile_lock.py --headless \\
      --host=http://127.0.0.1:8080 \\
      --tags like-strict -u 100 -r 50 -t 30s \\
      --csv=evidence/f8-lock

输出:
  evidence/f8-lock_stats.csv  — 每个请求端点的中位 / P95 / P99 / max
  evidence/f8-lock_failures.csv — 失败请求
  evidence/f8-lock_stats_history.csv — 时间序列
  evidence/f8-lock_distribution.csv — 响应时间分布

结果判定(人工/脚本):
  - POST /api/like/:merchantId 的请求全部返回 200(无 401 / 5xx);
  - ``data.liked == true`` 的响应**全局只出现 1 次**(脚本通过 gevent event
    在 ``on_start`` 后,统计 ``self.client`` 的所有响应体,见
    :func:`LikeStrictUser.teardown` 钩子 — locust 不直接暴露 per-request body,
    我们用 gevent.queue 收集);
  - 锁竞争证据:同一 (sid, merchantId) 下,Redis 端 ``lock:like:<sid>:<mid>``
    key 续期间隔内只能存在 1 个 ownerToken。
"""
import threading

from locust import HttpUser, between, events, tag, task


# ---------- 全局统计器 ----------
# locust 的 HttpUser 各自独立,跨 user 共享计数要走模块级变量 + 锁
# (gevent 协程下 GIL 仍保证 += 原子,但读后写需要锁保序)。
_LIKED_TRUE_COUNT = 0
_LIKED_TRUE_LOCK = threading.Lock()


def _inc_liked_true() -> int:
    """原子地把 ``data.liked == true`` 的响应数 +1,返回新值。"""
    global _LIKED_TRUE_COUNT
    with _LIKED_TRUE_LOCK:
        _LIKED_TRUE_COUNT += 1
        return _LIKED_TRUE_COUNT


class LikeStrictUser(HttpUser):
    """F-8 like-strict 场景:100 并发同 (sid, mid) 点赞,验证 liked:true 全局 == 1。

    与 F-5 ``MixLikeDetailUser`` 的关键差异:
      - 任务只有 1 个(纯点赞,无详情读 / 无随机);
      - wait_time=0(极限并发,逼出锁竞争);
      - 所有 user 共享**同一对** ``sid`` / ``merchantId``(F-5 是 user 自己随机选);
      - 启动后用 ``_inc_liked_true()`` 统计 liked:true 响应数,见
        :func:`teardown`。
    """

    wait_time = between(0, 0)  # 极限并发,0 间隔

    def on_start(self):
        """登录拿 JWT — 与 F-5 ``MixLikeDetailUser.on_start`` 同款,保持一致行为。"""
        r = self.client.post(
            "/api/auth/login",
            json={"username": "demo", "password": "demo"},
            name="/api/auth/login",
        )
        r.raise_for_status()
        # F-1 ApiResponse 统一包成 {code, message, data}
        self.token = r.json()["data"]["accessToken"]
        self.headers = {"Authorization": f"Bearer {self.token}"}
        # 锁定唯一 (sid, merchantId);F-8 evidence 要求"100 并发同 sid+mid"
        self.merchant_id = "M-LOCK-EVIDENCE"

    @tag("like-strict")
    @task
    def like(self):
        """并发打同一把锁 — 每次都期望:第一个 liked:true,其余 liked:false。"""
        with self.client.post(
            f"/api/like/{self.merchant_id}",
            headers=self.headers,
            name="/api/like/:merchantId",
            catch_response=True,
        ) as resp:
            if resp.status_code != 200:
                # 非 200 不算"点赞成功 / 幂等命中"二选一,记失败但不计入 liked:true
                resp.failure(f"unexpected HTTP {resp.status_code}: {resp.text[:120]}")
                return
            try:
                body = resp.json()
            except ValueError:
                resp.failure("response body is not valid JSON")
                return
            # ApiResponse 结构: {code: 0, message, data: {liked: true|false}}
            data = body.get("data") or {}
            if "liked" not in data:
                resp.failure(f"response missing data.liked: {body}")
                return
            if data["liked"] is True:
                _inc_liked_true()


@events.test_stop.add_listener
def _on_test_stop(environment, **kwargs):
    """测试结束时打印 liked:true 计数 + 判定结论。

    工单 acceptance:"100 并发同 sid+mid,验证 Redis 计数 == 1"。
    在 F-8 W2 把锁接进 LikeService 之前,基线(NX 60s 单独)就已能达成 liked:true == 1;
    本脚本同时作为 F-8 W2 接入后的 acceptance runner。统计输出供 orchestrator 抓取。
    """
    expected = 1
    actual = _LIKED_TRUE_COUNT
    verdict = "PASS" if actual == expected else f"FAIL(expected={expected}, actual={actual})"
    # 用 stdout 让 locust 把这行写进 evidence 目录的 stdout.log(或直接转发)
    print(f"[F-8 like-strict verdict] liked:true count = {actual} (expected {expected}) → {verdict}")


if __name__ == "__main__":
    import locust.main

    locust.main.main()