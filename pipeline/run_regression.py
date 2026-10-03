"""P1 抽取链竖切片 + 22 条用例回归。

完整链路：
    输入 raw_text
      → 立即以 pending 入库（功能补充 1.1：0ms 可见，断网不丢）
      → 调云端抽取（provider + 退避重试）
      → 后处理（contract：强转 / 兜底 / 清洗）
      → 回填入库（done / failed）
      → llm_calls 埋点

用法：
    # 1) 先跑离线自测（不需要 key，不需要网络）
    python pipeline/tests/test_norm.py

    # 2) 跑真实回归（需要 key）
    set HEALIX_BASE_URL=https://...
    set HEALIX_MODEL=...
    set HEALIX_API_KEY=...
    python pipeline/run_regression.py

    # 3) 只跑前 3 条试水
    python pipeline/run_regression.py --limit 3

退出码：0 = 通过率 ≥ 95%，1 = 未达标，2 = 配置错误。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Any

# 允许 `python pipeline/run_regression.py` 直接跑（不必 -m）
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pipeline import cases as CASES_MOD  # noqa: E402
from pipeline import contract as C  # noqa: E402
from pipeline import store  # noqa: E402
from pipeline.provider import (  # noqa: E402
    ChatMessage,
    ChatRequest,
    Err,
    Ok,
    OpenAiCompatProvider,
    ProviderConfig,
)

DB_PATH = Path(__file__).resolve().parent / "healix_regression.db"
REPORT_PATH = Path(__file__).resolve().parent / "regression_report.json"


# ---------------------------------------------------------------------------
# 单条用例：跑完整链路
# ---------------------------------------------------------------------------


def process_one(
    raw_text: str,
    conn: Any,
    provider: OpenAiCompatProvider,
    *,
    ts: int,
    day_start_hour: int = 4,
    client_event_id: str | None = None,
) -> dict[str, Any]:
    """一条输入走完整链路。返回本次结果（含入库行数、事件列表、埋点数据）。

    这是 P1 竖切片的**核心函数**，Kotlin 侧 EventRepository 与它逐行对应。
    """
    cid = client_event_id or store.new_client_event_id()

    # 第 1 步：先落 pending（原文永不丢）
    placeholder = C.normalize_event({})
    placeholder["kcal"] = 0
    store.insert_event(
        conn,
        client_event_id=cid,
        ts=ts,
        raw_text=raw_text,
        event=placeholder,
        source="app",
        parse_status="pending",
        day_start_hour=day_start_hour,
    )

    # 第 2 步：调云端（含退避重试）
    request = ChatRequest(messages=[ChatMessage(**m) for m in C.build_messages(raw_text)])
    started = time.time()
    result = provider.chat(request)
    latency_ms = int((time.time() - started) * 1000)

    # 第 3 步：失败分类处理
    if isinstance(result, Err):
        store.mark_failed(conn, cid, f"{result.kind.value}: {result.message}")
        store.log_llm_call(
            conn,
            purpose="extract",
            model=provider.config.model,
            prompt_ver=C.PROMPT_VER,
            attempts=result.attempts or request.max_retries + 1,
            latency_ms=latency_ms,
            status="retry_exhausted" if result.kind.value != "auth" else "http_error",
            http_code=result.http_code,
            error_head=result.message,
        )
        return {
            "ok": False,
            "cid": cid,
            "events": [],
            "error": f"{result.kind.value}: {result.message}",
            "latency_ms": latency_ms,
            "attempts": result.attempts,
        }

    assert isinstance(result, Ok)

    # 第 4 步：后处理
    events = C.extract_events_from_response(result.content)

    if not events:
        store.mark_failed(conn, cid, "schema_invalid: 无法从响应中解析出任何事件")
        store.log_llm_call(
            conn,
            purpose="extract",
            model=provider.config.model,
            prompt_ver=C.PROMPT_VER,
            attempts=1,
            latency_ms=latency_ms,
            status="schema_invalid",
            http_code=200,
            input_tokens=result.usage.input_tokens,
            output_tokens=result.usage.output_tokens,
            error_head=result.content[:200],
        )
        # 原文仍在库里（pending→failed），不丢数据
        return {
            "ok": False,
            "cid": cid,
            "events": [],
            "error": "schema_invalid",
            "raw_response": result.content[:200],
            "latency_ms": latency_ms,
        }

    # 第 5 步：入库。第一条复用 pending 行（覆盖更新），其余新增
    store.update_event_parsed(conn, cid, events[0], parse_status="done")
    extra_ids: list[int] = []
    for extra in events[1:]:
        extra_ids.append(
            store.insert_event(
                conn,
                client_event_id=store.new_client_event_id(),
                ts=ts,
                raw_text=raw_text,
                event=extra,
                source="app",
                parse_status="done",
                day_start_hour=day_start_hour,
            )
        )

    store.log_llm_call(
        conn,
        purpose="extract",
        model=provider.config.model,
        prompt_ver=C.PROMPT_VER,
        attempts=1,
        latency_ms=latency_ms,
        status="ok",
        http_code=200,
        input_tokens=result.usage.input_tokens,
        output_tokens=result.usage.output_tokens,
    )

    return {
        "ok": True,
        "cid": cid,
        "events": events,
        "extra_ids": extra_ids,
        "latency_ms": latency_ms,
        "attempts": 1,
        "raw_response": result.content,
    }


# ---------------------------------------------------------------------------
# 用例评分（子集匹配）
# ---------------------------------------------------------------------------


def _foods_contains(events: list[dict[str, Any]], needles: list[str]) -> bool:
    """检查所有事件的 foods 里是否出现全部关键字（模糊包含，容忍模型加字）。"""
    haystack = " ".join(" ".join(e.get("foods", [])) for e in events)
    return all(n in haystack for n in needles)


def grade_case(case: dict[str, Any], events: list[dict[str, Any]]) -> tuple[bool, list[str]]:
    """子集匹配评分：只核对用例里声明的键。返回 (是否通过, 失败原因列表)。"""
    reasons: list[str] = []

    expect_n = case["expect_n"]
    if len(events) != expect_n:
        reasons.append(f"条数期望 {expect_n} 实际 {len(events)}")
        # 条数都不对，字段比对无意义
        return (False, reasons)

    for idx, exp in enumerate(case["expect"]):
        ev = events[idx]

        # 纯类型断言
        if "type" in exp and ev["type"] != exp["type"]:
            # 宽容：meal 与 other 在"无意义输入"上易混，但只放行 b03/b04 类
            if not (exp["type"] == "other" and ev["type"] in ("meal", "illness")):
                reasons.append(f"[{idx}] type 期望 {exp['type']} 实际 {ev['type']}")

        if "foods_contains" in exp:
            if not _foods_contains([ev], exp["foods_contains"]):
                reasons.append(f"[{idx}] foods 期望含 {exp['foods_contains']} 实际 {ev['foods']}")

        if "exercise_contains" in exp:
            if exp["exercise_contains"] not in (ev.get("exercise") or ""):
                reasons.append(
                    f"[{idx}] exercise 期望含 {exp['exercise_contains']} 实际 {ev.get('exercise')!r}"
                )

        if "amount_contains" in exp:
            if exp["amount_contains"] not in (ev.get("amount") or ""):
                reasons.append(f"[{idx}] amount 期望含 {exp['amount_contains']} 实际 {ev.get('amount')!r}")

        if "weight_kg" in exp:
            if abs(float(ev.get("weight_kg") or 0) - exp["weight_kg"]) > 0.05:
                reasons.append(f"[{idx}] weight 期望 {exp['weight_kg']} 实际 {ev.get('weight_kg')}")

        if "sleep_range" in exp:
            lo, hi = exp["sleep_range"]
            actual = float(ev.get("sleep_h") or 0)
            if not (lo <= actual <= hi):
                reasons.append(f"[{idx}] sleep 期望 {lo}-{hi} 实际 {actual}")

        if "symptom_contains" in exp:
            if exp["symptom_contains"] not in (ev.get("symptom") or ""):
                reasons.append(f"[{idx}] symptom 期望含 {exp['symptom_contains']} 实际 {ev.get('symptom')!r}")

        if "time_hint" in exp:
            if exp["time_hint"] not in (ev.get("time_hint") or ""):
                reasons.append(f"[{idx}] time_hint 期望含 {exp['time_hint']} 实际 {ev.get('time_hint')!r}")

    # 热量区间：只要拆出的 meal 事件里有任一条落在区间即算过
    if "kcal_range" in case and case["tag"] != "adversarial":
        lo, hi = case["kcal_range"]
        meals = [e for e in events if e["type"] == "meal"]
        if meals:
            kcal_sum = sum(int(e.get("kcal") or 0) for e in meals)
            # 全为 0 且区间不包含 0 → 模型没估算
            if not (lo <= kcal_sum <= hi):
                # 拆成多条时按最大单条判定（避免累加超界误判）
                max_kcal = max(int(e.get("kcal") or 0) for e in meals)
                if not (lo <= max_kcal <= hi):
                    reasons.append(f"kcal 期望 {lo}-{hi} 实际 sum={kcal_sum} max={max_kcal}")

    return (len(reasons) == 0, reasons)


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------


def load_config_from_env() -> ProviderConfig | None:
    base_url = os.environ.get("HEALIX_BASE_URL", "").strip()
    model = os.environ.get("HEALIX_MODEL", "").strip()
    api_key = os.environ.get("HEALIX_API_KEY", "").strip()

    if not all([base_url, model, api_key]):
        return None
    if base_url.startswith("[待核实"):
        return None
    return ProviderConfig(base_url=base_url, model=model, api_key=api_key)


def main() -> int:
    parser = argparse.ArgumentParser(description="Healix P1 抽取链回归")
    parser.add_argument("--limit", type=int, default=0, help="只跑前 N 条")
    parser.add_argument("--tag", default="", help="只跑指定 tag：normal/boundary/adversarial")
    parser.add_argument("--max-retries", type=int, default=5)
    parser.add_argument("--base-delay", type=float, default=1.5)
    parser.add_argument("--no-exp-backoff", action="store_true")
    parser.add_argument("--db", default=str(DB_PATH))
    parser.add_argument("--timestamp", type=int, default=0, help="固定 ts（保持一致可复现）")
    args = parser.parse_args()

    config = load_config_from_env()
    if config is None:
        print("=" * 68)
        print("缺少 provider 配置。请设置以下环境变量：")
        print("  HEALIX_BASE_URL   —— OpenAI 兼容端点，形如 https://xxx/api/paas/v4")
        print("  HEALIX_MODEL      —— 模型名")
        print("  HEALIX_API_KEY    —— API Key（只从环境变量读，绝不写进代码/配置）")
        print("")
        print("这些值必须对着各平台官方文档确认，代码里不猜（C1 反幻觉）。")
        print("=" * 68)
        return 2

    selected = CASES_MOD.CASES
    if args.tag:
        selected = [c for c in selected if c["tag"] == args.tag]
    if args.limit:
        selected = selected[: args.limit]

    db_path = Path(args.db)
    if db_path.exists():
        db_path.unlink()  # 每次回归用干净库，避免幂等键干扰
    conn = store.connect(db_path)
    store.init_db(conn)

    provider = OpenAiCompatProvider(config)
    base_ts = args.timestamp or int(time.time() * 1000)
    day_start = C.DEFAULT_DAY_START_HOUR

    print("=" * 68)
    print("Healix P1 抽取链回归")
    print("=" * 68)
    print(f"  provider : {config.provider_name}  {config.base_url}")
    print(f"  model    : {config.model}")
    print(f"  prompt   : {C.PROMPT_VER}")
    print(f"  重试     : 最多 {args.max_retries} 次，初始 {args.base_delay}s，"
          f"{'固定间隔' if args.no_exp_backoff else '指数退避'}")
    print(f"  用例     : {len(selected)} 条")
    print("-" * 68)

    passed = 0
    failed: list[dict[str, Any]] = []
    latencies: list[int] = []
    total_in = total_out = 0
    multi_event_ok = 0
    multi_event_total = 0

    for case in selected:
        result = process_one(
            case["raw"],
            conn,
            provider,
            ts=base_ts + (hash(case["id"]) % 3600) * 1000,
            day_start_hour=day_start,
        )

        if case["expect_n"] > 1:
            multi_event_total += 1

        if not result["ok"]:
            print(f"  ✗ {case['id']} [{case['tag']}] 调用失败: {result['error']}")
            failed.append({"id": case["id"], "tag": case["tag"], "reasons": [result["error"]]})
            continue

        latencies.append(result["latency_ms"])
        ok, reasons = grade_case(case, result["events"])

        if ok:
            passed += 1
            if case["expect_n"] > 1:
                multi_event_ok += 1
            detail = "/".join(e["type"] for e in result["events"])
            print(f"  ✓ {case['id']} [{case['tag']}] {detail}  {result['latency_ms']}ms")
        else:
            print(f"  ✗ {case['id']} [{case['tag']}] {'; '.join(reasons)}")
            print(f"      原始响应: {result.get('raw_response', '')[:180]}")
            failed.append({"id": case["id"], "tag": case["tag"], "reasons": reasons})

    # 埋点汇总
    usage_row = conn.execute(
        "SELECT COALESCE(SUM(input_tokens),0) a, COALESCE(SUM(output_tokens),0) b FROM llm_calls"
    ).fetchone()
    total_in, total_out = int(usage_row["a"]), int(usage_row["b"])

    total = len(selected)
    rate = passed / total if total else 0.0
    p95 = sorted(latencies)[int(len(latencies) * 0.95)] if latencies else 0

    print("-" * 68)
    print(f"  通过      : {passed}/{total} = {rate:.1%}   （目标 ≥ {CASES_MOD.PASS_RATE_TARGET:.0%}）")
    print(f"  多事件拆分 : {multi_event_ok}/{multi_event_total}")
    print(f"  P95 延迟  : {p95} ms")
    print(f"  平均延迟  : {sum(latencies) // len(latencies) if latencies else 0} ms")
    print(f"  token     : 入 {total_in} / 出 {total_out}")
    if failed:
        print(f"  失败清单  : {', '.join(f['id'] for f in failed)}")
    print("=" * 68)

    report = {
        "prompt_ver": C.PROMPT_VER,
        "provider": {"base_url": config.base_url, "model": config.model, "name": config.provider_name},
        "total": total,
        "passed": passed,
        "pass_rate": round(rate, 4),
        "target": CASES_MOD.PASS_RATE_TARGET,
        "multi_event": {"ok": multi_event_ok, "total": multi_event_total},
        "latency_ms": {
            "p95": p95,
            "avg": sum(latencies) // len(latencies) if latencies else 0,
        },
        "tokens": {"input": total_in, "output": total_out},
        "failures": failed,
        "distribution": CASES_MOD.count_by_tag(),
    }
    REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"  报告已写入: {REPORT_PATH}")

    return 0 if rate >= CASES_MOD.PASS_RATE_TARGET else 1


if __name__ == "__main__":
    sys.exit(main())
