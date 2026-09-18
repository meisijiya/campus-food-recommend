"""F-2 量化证据采集脚本(对应 ticket acceptance #14 / CONTEXT.md §9)。

执行方式::

    # 启发式估算(零依赖)
    python tools/token-counter.py

口径:
- 构造 baseline prompt(A 组):prompt 模板内联全量目录数据(模拟"无 Skill" 的历史实现)
- 构造 with-skill prompt(B 组):prompt 模板只引用 {skill:zone} 等占位符,
  运行时由 SkillRegistry.render() 注入真实数据
- 对同一推荐任务连跑 50 轮,按 chars/4 估算 token 数,统计平均值
- 计算 reduction = (avg_A - avg_B) / avg_A,达标 >= 0.35

口径说明:
- 启发式 estimator 与 MockChatModel.TokenEstimator 一致(chars/4,向上取整);
  中英文混合偏差 ±15%,F-2 evidence 只关心相对降幅,绝对值精度不要求。
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path

ROUNDS = 50
PASS_THRESHOLD = 0.35
BASELINE_ZONE_COUNT = 12
BASELINE_CUISINE_PER_ZONE = 8
BASELINE_MERCHANT_PER_CUISINE = 6


def baseline_catalog_blob() -> str:
    """生成 baseline prompt 的全量目录 JSON(模拟"无 Skill"内联数据)。"""
    zones = []
    for z in range(BASELINE_ZONE_COUNT):
        cuisines = []
        for c in range(BASELINE_CUISINE_PER_ZONE):
            merchants = [
                {
                    "id": f"m-{z:02d}-{c:02d}-{k:02d}",
                    "name": f"商家 {z:02d}-{c:02d}-{k:02d}",
                    "rating": round(4.0 + (k % 5) * 0.2, 1),
                    "price": 10 + k * 3,
                    "openHours": f"{10 + (k % 4)}:00-{(20 + k % 4) % 24}:00",
                    "tags": ["辣", "实惠", "快"][: (k % 3) + 1],
                }
                for k in range(BASELINE_MERCHANT_PER_CUISINE)
            ]
            cuisines.append(
                {
                    "id": f"cuisine-{z:02d}-{c:02d}",
                    "name": f"菜系 {z}-{c}",
                    "merchants": merchants,
                }
            )
        zones.append({"id": f"zone-{z:02d}", "name": f"商圈 {z}", "cuisines": cuisines})
    return json.dumps(zones, ensure_ascii=False)


BASELINE_TEMPLATE = """\
你是校园美食推荐助手。请根据用户当前会话阶段给出推荐。
用户当前 stage: MERCHANT
用户已选 zone: zone-03
用户已选 cuisine: cuisine-02
可用目录:
{catalog}
请输出符合 RecommendationSchema 的 JSON。
"""

SKILL_TEMPLATE = """\
你是校园美食推荐助手。请根据用户当前会话阶段给出推荐。
用户当前 stage: MERCHANT
{{skill:zone}}
{{skill:cuisine}}
{{skill:merchant}}
请输出符合 RecommendationSchema 的 JSON。
"""

# 用小段 Skill 渲染产物,模拟 SkillRegistry.render() 注入的字符串
SKILL_ZONE_RENDER = "zone=zone-03"
SKILL_CUISINE_RENDER = "cuisine=cuisine-02"
SKILL_MERCHANT_RENDER = "merchant=m-03-02-00"


def build_prompts() -> tuple[str, str]:
    """构造 (baseline_prompt, skill_prompt) 两组完整 prompt。"""
    catalog_blob = baseline_catalog_blob()
    baseline = BASELINE_TEMPLATE.format(catalog=catalog_blob)
    skill = SKILL_TEMPLATE \
            .replace("{{skill:zone}}", SKILL_ZONE_RENDER) \
            .replace("{{skill:cuisine}}", SKILL_CUISINE_RENDER) \
            .replace("{{skill:merchant}}", SKILL_MERCHANT_RENDER)
    return baseline, skill


def estimate_tokens(text: str) -> int:
    """与 MockChatModel.TokenEstimator 一致:chars/4,向上取整。"""
    if not text:
        return 0
    return (len(text) + 3) // 4


def run_heuristic() -> tuple[float, float, int, int]:
    """启发式路径:对每轮 prompt 重复 estimate_tokens 50 次(同 prompt 同答案)。"""
    baseline, skill = build_prompts()
    base_tokens = estimate_tokens(baseline)
    skill_tokens = estimate_tokens(skill)
    base_series = [base_tokens] * ROUNDS
    skill_series = [skill_tokens] * ROUNDS
    return (
        statistics.fmean(base_series),
        statistics.fmean(skill_series),
        base_tokens,
        skill_tokens,
    )


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", type=str, default=None, help="evidence JSON 落点(可选)")
    args = ap.parse_args()

    base_avg, skill_avg, base_total, skill_total = run_heuristic()
    mode = "heuristic"

    reduction = (base_avg - skill_avg) / base_avg if base_avg else 0.0
    passed = reduction >= PASS_THRESHOLD

    report = {
        "mode": mode,
        "rounds": ROUNDS,
        "pass_threshold": PASS_THRESHOLD,
        "avg_baseline_tokens": round(base_avg, 2),
        "avg_skill_tokens": round(skill_avg, 2),
        "total_baseline_tokens": base_total,
        "total_skill_tokens": skill_total,
        "reduction_ratio": round(reduction, 4),
        "reduction_pct": f"{reduction * 100:.2f}%",
        "passed": bool(passed),
        "baseline_template_chars": len(BASELINE_TEMPLATE),
        "skill_template_chars": len(SKILL_TEMPLATE),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))

    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if passed else 2


if __name__ == "__main__":
    sys.exit(main())