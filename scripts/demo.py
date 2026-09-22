#!/usr/bin/env python3
"""scripts/demo.py — 端到端演示 5 条简历 bullet(跨平台,Python 3.8+)

前提:cfr-app 已起(默认 127.0.0.1:8080),MySQL/Redis/RabbitMQ 已就绪。
注意:默认走 127.0.0.1 而非 localhost — Windows 上 localhost 会先解析 IPv6(::1)
      等 10s timeout 才回退 IPv4,严重影响演示体验。
用法:
    python scripts/demo.py                              # 直连 app
    python scripts/demo.py --base http://127.0.0.1      # 同上(显式)
    python scripts/demo.py --base http://localhost      # 经 Nginx 80(注意 IPv6)
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import urllib.error
import urllib.request
from typing import Any

# 强制 UTF-8 stdout:Windows cmd 默认 GBK 会让中文乱码
if hasattr(sys.stdout, "buffer"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "buffer"):
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

PRESET_USER = {"username": "demo", "password": "demo"}
DEFAULT_BASE = "http://127.0.0.1:8080"


def http_request(
    method: str,
    url: str,
    token: str | None = None,
    body: dict | None = None,
    timeout: float = 10.0,
) -> tuple[int, dict | None]:
    """返回 (status_code, parsed_json_or_None)。非 2xx 也返回,便于演示流程不被中断。"""
    data = None
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            try:
                return (resp.status, json.loads(raw))
            except json.JSONDecodeError:
                return (resp.status, None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return (e.code, json.loads(raw))
        except json.JSONDecodeError:
            return (e.code, None)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        print(f"  [ERR] {method} {url}: {e}", file=sys.stderr)
        return (0, None)


def section(title: str) -> None:
    print()
    print(f"==== {title} ====")


def green(msg: str) -> None:
    print(f"  \033[32m{msg}\033[0m")


def yellow(msg: str) -> None:
    print(f"  \033[33m{msg}\033[0m")


def red(msg: str) -> None:
    print(f"  \033[31m{msg}\033[0m")


def field(obj: dict | None, *keys: str) -> Any:
    if obj is None:
        return None
    cur: Any = obj
    for k in keys:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(k)
        if cur is None:
            return None
    return cur


def main() -> int:
    ap = argparse.ArgumentParser(description="端到端演示 5 条简历 bullet")
    ap.add_argument("--base", default=DEFAULT_BASE, help=f"API base URL(default {DEFAULT_BASE})")
    args = ap.parse_args()
    base = args.base.rstrip("/")

    # 0. 健康检查
    section("0. 健康检查")
    status, body = http_request("GET", f"{base}/actuator/health")
    if status == 200 and body:
        green(f"/actuator/health         → {field(body, 'status')}")
        groups = field(body, "groups")
        if groups:
            print(f"  groups: {', '.join(groups)}")
    else:
        yellow(f"/actuator/health FAIL status={status}")

    # 1. 登录
    section("1. F-1 无状态架构 / JWT 鉴权")
    status, body = http_request("POST", f"{base}/api/auth/login", body=PRESET_USER)
    if status != 200 or not body:
        red(f"LOGIN FAILED status={status}")
        return 1
    token = field(body, "data", "accessToken")
    if not token:
        red(f"LOGIN RESPONSE 无 accessToken: {body}")
        return 1
    green(f"login → 拿到 accessToken(长度 {len(token)})")

    # 2. 会话槽位(SlotRequest body 字段是 {value},不是 {zoneId}/{cuisineId}/{merchantId})
    #    F-4 预热数据里 zone/cuisine/merchant ID 均为字符串(Z-1 / C-1 / M-NOODLE 等)
    section("2. F-2 会话槽位约束 / 渐进检索")
    steps = [
        ("INIT",     "/api/session/init",     None),
        ("ZONE",     "/api/session/zone",     {"value": "Z-1"}),
        ("CUISINE",  "/api/session/cuisine",  {"value": "C-1"}),
        ("MERCHANT", "/api/session/merchant", {"value": "M-NOODLE"}),
    ]
    for name, path, payload in steps:
        status, body = http_request("POST", f"{base}{path}", token=token, body=payload)
        if status == 200:
            stage = field(body, "data", "stage")
            green(f"{name:<9} → {stage}")
        else:
            yellow(f"{name} → FAIL status={status} body={body}")

    # 3. 推荐 — controller 不读 body,从 JWT sub 拿 sid 读会话上下文
    #    响应字段:content(JSON string)/ stage / promptTokens / completionTokens
    section("3. F-3 结构化输出 / 反思重试")
    status, body = http_request("POST", f"{base}/api/recommend", token=token)
    if status == 200 and body:
        d = field(body, "data") or {}
        content = d.get("content") or ""
        try:
            content_json = json.loads(content)
            mids = content_json.get("merchantId") or []
        except (json.JSONDecodeError, TypeError):
            mids = []
        green(f"recommendations: {len(mids)} 条 (mids={mids})")
        print(f"  stage           : {d.get('stage')}")
        print(f"  promptTokens    : {d.get('promptTokens')}")
        print(f"  completionTokens: {d.get('completionTokens')}")
    else:
        yellow(f"/api/recommend FAIL status={status} body={body}")

    # 4. 点赞幂等 — 60s 内重复只生效一次,演示会因前次 Redis 残留命中 False
    #     (这是真实幂等行为,不是 bug;文档说明 60s 窗口或换 sid 重跑)
    section("4. F-5 点赞幂等 / Redis NX 60s")
    _, b1 = http_request("POST", f"{base}/api/like/M-NOODLE", token=token)
    _, b2 = http_request("POST", f"{base}/api/like/M-NOODLE", token=token)
    l1 = field(b1, "data", "liked")
    l2 = field(b2, "data", "liked")
    green(f"第 1 次 → liked={l1}")
    green(f"第 2 次 → liked={l2} (60s 内重复拦截,若 l1=False 说明 60s 窗口未过期)")

    # 5. 商户详情(F-4 预热后 L0/L1/L2 应命中;id 用槽位写过的 M-NOODLE)
    section("5. F-4 预热 / F-5 L0→L1→L2 三级降级")
    _, body = http_request("GET", f"{base}/api/merchant/M-NOODLE", token=token)
    if body:
        d = field(body, "data") or {}
        mid = d.get("id")
        name = d.get("name")
        heat = d.get("heatScore")
        zone = d.get("zoneId")
        cuisine = d.get("cuisineId")
        green(f"merchant #{mid} ({name}) zone={zone} cuisine={cuisine} heat={heat}")
    else:
        yellow("/api/merchant/M-NOODLE FAIL")

    # 6. 预热
    section("6. F-4 离线预热(手动触发)")
    _, body = http_request("POST", f"{base}/admin/preheat/trigger", token=token)
    if body:
        d = field(body, "data") or {}
        green(f"merchantCount : {d.get('merchantCount')}")
        print(f"  zoneCount     : {d.get('zoneCount')}")
        print(f"  hotCount      : {d.get('hotCount')}")
        print(f"  elapsedMs     : {d.get('elapsedMs')}")

    print()
    print("==== 演示完成 ====")
    print("如需压测 F-1 5000+ QPS:    pwsh -File init.ps1  (stage 5)")
    print("如需压测 F-5 P99<100ms:    pwsh -File init.ps1  (stage 6,burst=100000 覆盖)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
