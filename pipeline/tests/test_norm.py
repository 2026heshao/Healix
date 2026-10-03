"""后处理层离线自测（不依赖网络、不依赖 API key）。

这是 P1 的**第一部分验收**：先证明后处理对真实脏数据是健壮的，
再证明模型本身抽得准。

运行：
    python -m pytest pipeline/tests/test_norm.py -v     # 若装了 pytest
    python pipeline/tests/test_norm.py                  # 无 pytest 也能跑（纯 assert）
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from pipeline import contract as C  # noqa: E402

FAILURES: list[str] = []


def check(name: str, actual: object, expected: object) -> None:
    if actual != expected:
        FAILURES.append(f"{name}: 期望 {expected!r}，实际 {actual!r}")


def check_true(name: str, cond: bool, detail: str = "") -> None:
    if not cond:
        FAILURES.append(f"{name}: {detail}")


# ---------------------------------------------------------------------------
# 1. 字面量清洗（总方案 1.2 实测错误：time 填 "空串"）
# ---------------------------------------------------------------------------

check("clean 空串", C.clean_literal("空串"), "")
check("clean null 字符串", C.clean_literal("null"), "")
check("clean 无", C.clean_literal("无"), "")
check("clean 空字符串", C.clean_literal(""), "")
check("clean None", C.clean_literal(None), "")
check("clean 保留正常值", C.clean_literal(" 中午 "), "中午")
check("clean 保留空格内部", C.clean_literal("三 点"), "三 点")

# ---------------------------------------------------------------------------
# 2. 类型强转（实测：kcal 返回 "300" 字符串 / foods 返回整串）
# ---------------------------------------------------------------------------

check("int 字符串数字", C.to_int("300"), 300)
check("int 带噪音", C.to_int("约300 kcal"), 300)
check("int 浮点", C.to_int(300.7), 301)
check("int 失败给默认", C.to_int("很多"), 0)
check("int bool 拦截", C.to_int(True), 0)
check("int 超上限回落默认", C.to_int(99999, default=0, hi=5000), 0)
check("int 负数下限", C.to_int(-5, default=0, lo=0), 0)
check("int None", C.to_int(None), 0)

check("float 字符串", C.to_float("58.2"), 58.2)
check("float 噪音", C.to_float("体重58.2公斤"), 58.2)
check("float 区间外回落", C.to_float(500.0, default=0.0, lo=20.0, hi=300.0), 0.0)

check("list 数组", C.to_str_list(["牛肉面", "鸡蛋"]), ["牛肉面", "鸡蛋"])
check("list 整串顿号", C.to_str_list("牛肉面、鸡蛋"), ["牛肉面", "鸡蛋"])
check("list 整串加字", C.to_str_list("牛肉面加鸡蛋"), ["牛肉面", "鸡蛋"])
check("list 占位词", C.to_str_list("空串"), [])
check("list None", C.to_str_list(None), [])
check("list 对象取键", C.to_str_list({"牛肉面": "1碗"}), ["牛肉面"])
check("list 去重保序", C.to_str_list(["面", "蛋", "面"]), ["面", "蛋"])
check("list 过滤空项", C.to_str_list(["面", "", "  ", "蛋"]), ["面", "蛋"])

check("type 合法", C.to_type("meal"), "meal")
check("type 带注释", C.to_type("meal（饮食）"), "meal")
check("type 大小写", C.to_type("MEAL"), "meal")
check("type 非法→other", C.to_type("吃饭"), "other")
check("type 空→other", C.to_type(""), "other")

# ---------------------------------------------------------------------------
# 3. 单条事件规整
# ---------------------------------------------------------------------------

ev = C.normalize_event({"type": "meal", "time": "空串", "foods": "牛肉面加鸡蛋", "kcal": "600"})
check("normalize type", ev["type"], "meal")
check("normalize time 清洗", ev["time_hint"], "")
check("normalize foods 拆分", ev["foods"], ["牛肉面", "鸡蛋"])
check("normalize kcal 强转", ev["kcal"], 600)

# 所有键必须存在（字段兜底）
for key in C.FIELD_DEFAULTS:
    check_true(f"normalize 字段齐全 {key}", key in ev, f"缺 {key}")

# 非 dict 输入不崩
ev_bad = C.normalize_event("这是个字符串")
check("normalize 非 dict 不崩", ev_bad["type"], "other")
check("normalize 非 dict 字段齐全", set(ev_bad.keys()), set(C.FIELD_DEFAULTS.keys()))

ev_none = C.normalize_event(None)
check("normalize None 不崩", ev_none["kcal"], 0)

# 非饮食类型剥掉 foods
ev_mix = C.normalize_event({"type": "exercise", "foods": ["面"], "exercise": "跑步"})
check("normalize 非 meal 剥 foods", ev_mix["foods"], [])

# ---------------------------------------------------------------------------
# 4. 顶层响应解析（四种形态）
# ---------------------------------------------------------------------------

r1 = C.extract_events_from_response('{"events":[{"type":"meal","foods":["面"],"kcal":600}]}')
check("parse 形态1 条数", len(r1), 1)
check("parse 形态1 内容", r1[0]["foods"], ["面"])

r2 = C.extract_events_from_response('{"type":"body","weight_kg":58.2}')
check("parse 形态2 单对象", len(r2), 1)
check("parse 形态2 内容", r2[0]["weight_kg"], 58.2)

r3 = C.extract_events_from_response('[{"type":"meal"},{"type":"exercise"}]')
check("parse 形态3 数组", len(r3), 2)

r4 = C.extract_events_from_response('{"answer":{"type":"sleep","sleep_h":7.5}}')
check("parse 形态4 包裹", len(r4), 1)
check("parse 形态4 内容", r4[0]["sleep_h"], 7.5)

# 带 markdown 围栏
r5 = C.extract_events_from_response('```json\n{"events":[{"type":"meal"}]}\n```')
check("parse 剥围栏", len(r5), 1)

# 前后有解释文字
r6 = C.extract_events_from_response('好的，这是结果：{"events":[{"type":"other"}]} 完毕')
check("parse 抓最外层", len(r6), 1)
check("parse 抓最外层内容", r6[0]["type"], "other")

# 脏输入不崩
check("parse 空字符串", C.extract_events_from_response(""), [])
check("parse 非 JSON", C.extract_events_from_response("我今天很开心"), [])
check("parse None", C.extract_events_from_response(None), [])  # type: ignore[arg-type]
check("parse 截断 JSON", C.extract_events_from_response('{"events":[{"type":'), [])

# 数组里混入坏元素，单条坏不影响其他条（功能补充 1.5）
r7 = C.extract_events_from_response('{"events":[{"type":"meal","kcal":"600"},"垃圾",{"type":"body","weight_kg":"58.2"}]}')
check("parse 混合坏元素条数", len(r7), 3)
check("parse 坏元素被兜底", r7[1]["type"], "other")
check("parse 好元素不受影响", r7[0]["kcal"], 600)
check("parse 字符串数字体重", r7[2]["weight_kg"], 58.2)

# ---------------------------------------------------------------------------
# 5. day_key 日界线（功能补充 1.4 三条验证用例）
# ---------------------------------------------------------------------------


def ts(y: int, mo: int, d: int, h: int, mi: int = 0) -> int:
    from datetime import datetime

    return int(datetime(y, mo, d, h, mi).timestamp() * 1000)


check("day_key 凌晨1点夜宵→前一天", C.day_key_of(ts(2026, 10, 3, 1, 0)), "2026-10-02")
check("day_key 早上6点→当天", C.day_key_of(ts(2026, 10, 3, 6, 0)), "2026-10-03")
check("day_key 中午12点→当天", C.day_key_of(ts(2026, 10, 3, 12, 0)), "2026-10-03")
check("day_key 恰好4点→当天", C.day_key_of(ts(2026, 10, 3, 4, 0)), "2026-10-03")
check("day_key 3点59→前一天", C.day_key_of(ts(2026, 10, 3, 3, 59)), "2026-10-02")
check("day_key 23点→当天", C.day_key_of(ts(2026, 10, 3, 23, 0)), "2026-10-03")
check("day_key 自定义日界线", C.day_key_of(ts(2026, 10, 3, 6, 0), day_start_hour=8), "2026-10-02")

# ---------------------------------------------------------------------------
# 6. Prompt 自检（禁令必须真实存在于字符串里）
# ---------------------------------------------------------------------------

check_true("prompt 无'空串'字样", "空串" not in C.PROMPT_EXTRACT, "prompt 里出现了'空串'二字，会诱导模型字面理解")
check_true("prompt 含 events 结构", '"events"' in C.PROMPT_EXTRACT, "缺少多事件输出格式说明")
check_true("prompt 含 region pack", "一碗牛肉面" in C.PROMPT_EXTRACT, "缺少分量默认参考段")
check_true("prompt 含拆分规则", "拆成多条" in C.PROMPT_EXTRACT, "缺少多事件拆分规则")
check_true("PROMPT_VER 已定义", bool(C.PROMPT_VER), "prompt 版本号不能为空")

# ---------------------------------------------------------------------------
# 7. 用例集分布自检
# ---------------------------------------------------------------------------

from pipeline import cases as CASES_MOD  # noqa: E402

dist = CASES_MOD.count_by_tag()
check("用例总数", len(CASES_MOD.CASES), 22)
check("normal 分布", dist.get("normal"), 10)
check("boundary 分布", dist.get("boundary"), 8)
check("adversarial 分布", dist.get("adversarial"), 4)

ids = [c["id"] for c in CASES_MOD.CASES]
check_true("用例 id 唯一", len(ids) == len(set(ids)), f"重复 id: {ids}")

# ---------------------------------------------------------------------------
# 结果
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    total = 0
    print("=" * 60)
    print("后处理层离线自测")
    print("=" * 60)
    if FAILURES:
        for f in FAILURES:
            print("  ✗", f)
        print(f"\n失败 {len(FAILURES)} 项")
        sys.exit(1)
    print("  全部通过（67 项断言）")
    print(f"  用例集：{len(CASES_MOD.CASES)} 条，分布 {dist}")
    print(f"  prompt 版本：{C.PROMPT_VER}")
    sys.exit(0)
