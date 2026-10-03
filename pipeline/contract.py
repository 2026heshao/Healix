"""Healix 抽取链契约层（S0）。

本模块是整个项目的**唯一契约来源**：
- Python 竖切片（P1）直接 import 本模块
- Kotlin 侧 `SchemaValidator.kt` 必须与本模块行为逐条对齐（见 docs/contract.md）

设计原则：
1. 无网络依赖、无第三方库，只依赖标准库 —— 保证可以在任何环境跑回归
2. 所有规则以**纯函数**形式暴露，便于 20 条用例逐条验证
3. 异常值一律收敛为占位值，绝不抛异常（模型输出永远不可信）
"""

from __future__ import annotations

import json
import re
from datetime import datetime, timedelta, timezone
from typing import Any, Final

# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------

PROMPT_VER: Final[str] = "v1"  # ★ 每次改 prompt 必须递增，并写入 llm_calls.prompt_ver

VALID_TYPES: Final[tuple[str, ...]] = (
    "meal",
    "exercise",
    "body",
    "sleep",
    "illness",
    "other",
)

DEFAULT_DAY_START_HOUR: Final[int] = 4  # 日界线默认 4:00

# 模型会把"规则文字"当值填进来的字面量（总方案 1.2 实测：time 填 "空串"）
# 全部收敛为 ""，绝不入库
SENTINEL_LITERALS: Final[frozenset[str]] = frozenset(
    {
        "空串",
        "空",
        "无",
        "暂无",
        "没有",
        "null",
        "NULL",
        "None",
        "none",
        "N/A",
        "n/a",
        "NA",
        "-",
        "--",
        "—",
        "无。",
        "无信息",
        "未提及",
        "未提供",
        "不适用",
        "unknown",
    }
)

# 字段 → 兜底默认值（第三节"字段兜底"）
FIELD_DEFAULTS: Final[dict[str, Any]] = {
    "type": "other",
    "time_hint": "",
    "foods": [],
    "exercise": "",
    "amount": "",
    "kcal": 0,
    "symptom": "",
    "weight_kg": 0.0,
    "sleep_h": 0.0,
}

# 数值字段的合理区间（异常值清洗）。超出即视为模型胡说，回落默认值。
KCAL_MIN: Final[int] = 0
KCAL_MAX: Final[int] = 5000  # 单条记录上限，一顿饭吃 5000 kcal 不现实
WEIGHT_MIN: Final[float] = 20.0
WEIGHT_MAX: Final[float] = 300.0
SLEEP_MIN: Final[float] = 0.0
SLEEP_MAX: Final[float] = 24.0

# 从中文文本里榨数字用
_NUM_RE = re.compile(r"-?\d+(?:\.\d+)?")


# ---------------------------------------------------------------------------
# 后处理原语（每一步都是纯函数）
# ---------------------------------------------------------------------------


def clean_literal(value: Any) -> str:
    """第 4 步「异常值清洗」：字面量占位词 → ""。

    只处理字符串。非字符串先转成字符串再判断 —— 模型可能返回 null / 0。
    """
    if value is None:
        return ""
    if not isinstance(value, str):
        value = str(value)
    text = value.strip()
    if text in SENTINEL_LITERALS:
        return ""
    return text


def to_int(value: Any, default: int = 0, lo: int | None = None, hi: int | None = None) -> int:
    """第 2 步「类型强转」：任意值 → int，失败给默认值。

    实测模型会返回 "300"（字符串）、300.0（浮点）、"约300"（带噪音）。
    带 lo/hi 时做区间钳制，超界回落默认值而非钳到边界 —— 钳到边界会造出假数据。
    """
    num: float | None = None

    if isinstance(value, bool):  # bool 是 int 子类，必须先拦掉
        num = None
    elif isinstance(value, (int, float)):
        num = float(value)
    elif isinstance(value, str):
        text = clean_literal(value)
        if text:
            # 直接能转就转，否则从文本里抓第一个数字（"约300 kcal" → 300）
            try:
                num = float(text)
            except ValueError:
                match = _NUM_RE.search(text)
                if match:
                    num = float(match.group())

    if num is None:
        return default

    result = int(round(num))
    if lo is not None and result < lo:
        return default
    if hi is not None and result > hi:
        return default
    return result


def to_float(value: Any, default: float = 0.0, lo: float | None = None, hi: float | None = None) -> float:
    """第 2 步「类型强转」：任意值 → float，语义同 to_int。"""
    num: float | None = None

    if isinstance(value, bool):
        num = None
    elif isinstance(value, (int, float)):
        num = float(value)
    elif isinstance(value, str):
        text = clean_literal(value)
        if text:
            try:
                num = float(text)
            except ValueError:
                match = _NUM_RE.search(text)
                if match:
                    num = float(match.group())

    if num is None:
        return default

    result = round(num, 2)
    if lo is not None and result < lo:
        return default
    if hi is not None and result > hi:
        return default
    return result


def to_str_list(value: Any) -> list[str]:
    """第 2 步「类型强转」：任意值 → List[str]。

    实测模型会把 foods 返回成 "牛肉面加鸡蛋"（整串）或 "空串"（占位词）
    或 {"牛肉面": "1碗"}（对象）—— 三种都要能吃下去。
    """
    if value is None:
        return []

    items: list[Any]

    if isinstance(value, list):
        items = value
    elif isinstance(value, dict):
        # {"牛肉面": "1碗"} → ["牛肉面"]，只取键（值是数量，属于 amount 语义）
        items = list(value.keys())
    elif isinstance(value, str):
        text = clean_literal(value)
        if not text:
            return []
        # 常见分隔符全切成多段："牛肉面、鸡蛋" / "牛肉面, 鸡蛋" / "牛肉面加鸡蛋"
        parts = re.split(r"[、,，;；/|\n]+|\s+和\s*|\s*加\s*", text)
        items = [p for p in parts if p.strip()]
    else:
        items = [value]

    result: list[str] = []
    for item in items:
        cleaned = clean_literal(item)
        if cleaned:
            result.append(cleaned)

    # 去重但保持顺序
    seen: set[str] = set()
    deduped: list[str] = []
    for item in result:
        if item not in seen:
            seen.add(item)
            deduped.append(item)
    return deduped


def to_type(value: Any) -> str:
    """第 2 步：type 必须是枚举内值，否则 → other。"""
    text = clean_literal(value).lower()
    if text in VALID_TYPES:
        return text
    # 宽容一点：模型可能返回 "meal（饮食）" 这类带注释的
    for candidate in VALID_TYPES:
        if candidate in text:
            return candidate
    return "other"


# ---------------------------------------------------------------------------
# 单条事件后处理
# ---------------------------------------------------------------------------


def normalize_event(raw: Any) -> dict[str, Any]:
    """第 2–4 步：把模型返回的一个元素规整为**保证可用**的事件 dict。

    输入可以是 dict / str / 其他任何东西 —— 永远返回完整 dict，绝不抛异常。
    这是"单条坏不影响其他条"的实现（功能补充 1.5）。
    """
    if not isinstance(raw, dict):
        raw = {}

    event: dict[str, Any] = {
        "type": to_type(raw.get("type")),
        "time_hint": clean_literal(raw.get("time") or raw.get("time_hint")),
        "foods": to_str_list(raw.get("foods")),
        "exercise": clean_literal(raw.get("exercise")),
        "amount": clean_literal(raw.get("amount")),
        "kcal": to_int(raw.get("kcal"), default=0, lo=KCAL_MIN, hi=KCAL_MAX),
        "symptom": clean_literal(raw.get("symptom")),
        "weight_kg": to_float(raw.get("weight_kg"), default=0.0, lo=WEIGHT_MIN, hi=WEIGHT_MAX),
        "sleep_h": to_float(raw.get("sleep_h"), default=0.0, lo=SLEEP_MIN, hi=SLEEP_MAX),
    }

    # 补默认值（第 3 步字段兜底）—— 上面的 dict 已保证每个键都存在，
    # 这里只处理"键存在但值为空、且类型语义要求有值"的情况
    if not event["foods"] and event["type"] == "meal":
        event["foods"] = []  # meal 空 foods 是合法的（模型漏抽），不编造内容

    # 非饮食类型不该带 foods
    if event["type"] != "meal" and event["foods"]:
        event["foods"] = []

    return event


# ---------------------------------------------------------------------------
# 顶层响应解析（第 1 层：把模型输出变成 events 列表）
# ---------------------------------------------------------------------------


def extract_events_from_response(content: str) -> list[dict[str, Any]]:
    """把模型返回的原始字符串解析为**规整后的事件列表**。

    兼容四种真实形态（实测 + 防御性）：
      1. {"events": [ {...}, {...} ]}          ← 目标形态（功能补充 1.5）
      2. { ...单条字段... }                     ← 旧 prompt / 模型退化的形态
      3. [ {...}, {...} ]                       ← 模型直接给数组
      4. { "answer": {...} } / {"data": {...}}  ← 被包了一层（总方案第六节明令禁止但会发生）

    解析失败的兜底：返回空列表，由调用方决定标 failed 还是走本地估算。
    """
    if not content or not isinstance(content, str):
        return []

    payload = _loads_lenient(content)
    if payload is None:
        return []

    return _collect_events(payload)


def _loads_lenient(content: str) -> Any:
    """尽力解析 JSON：先直解，失败则剥掉 ```json 围栏 / 抓最外层大括号。"""
    text = content.strip()

    # 剥 markdown 代码围栏
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
        text = text.strip()

    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    # 抓第一个 { 到最后一个 }（模型前后加了解释文字）
    start, end = text.find("{"), text.rfind("}")
    if start != -1 and end > start:
        try:
            return json.loads(text[start : end + 1])
        except json.JSONDecodeError:
            pass

    start, end = text.find("["), text.rfind("]")
    if start != -1 and end > start:
        try:
            return json.loads(text[start : end + 1])
        except json.JSONDecodeError:
            pass

    return None


# 已知的"包裹键"（总方案第六节：明确不要，但模型不听话时要能救）
_WRAPPER_KEYS: Final[tuple[str, ...]] = ("events", "answer", "result", "data", "items", "records")

# 单条事件的字段名（用于形态 2 判定）
_EVENT_FIELD_KEYS: Final[frozenset[str]] = frozenset(
    {"type", "time", "foods", "exercise", "amount", "kcal", "symptom", "weight_kg", "sleep_h"}
)

_MAX_EVENTS: Final[int] = 10  # 一句话最多拆 10 条，防模型刷屏


def _collect_events(payload: Any, _depth: int = 0) -> list[dict[str, Any]]:
    """从任意 JSON 结构里挖出事件列表。"""
    if _depth > 4:  # 防循环引用 / 嵌套过深
        return []

    # 形态 3：直接是数组
    if isinstance(payload, list):
        events: list[dict[str, Any]] = []
        for item in payload[:_MAX_EVENTS]:
            events.append(normalize_event(item))
        return events

    if not isinstance(payload, dict):
        return []

    # 形态 1 / 4：有包裹键
    for key in _WRAPPER_KEYS:
        if key in payload:
            inner = payload[key]
            if isinstance(inner, list):
                return [normalize_event(x) for x in inner[:_MAX_EVENTS]]
            if isinstance(inner, dict):
                # {"events": {...}} 单条
                return [normalize_event(inner)]

    # 形态 2：就是一个单条事件对象
    if _EVENT_FIELD_KEYS & set(payload.keys()):
        return [normalize_event(payload)]

    return []


# ---------------------------------------------------------------------------
# day_key（功能补充 1.4：日界线 4:00）
# ---------------------------------------------------------------------------


def day_key_of(ts_ms: int, day_start_hour: int = DEFAULT_DAY_START_HOUR) -> str:
    """把时间戳折算为"记录日"，按自定义日界线。

    实现：本地时间整体减 day_start_hour 小时后取日期 —— 无需任何边界分支。
    验证：凌晨 1:00 夜宵 → 减 4h = 前一天 21:00 → 归前一天
          早上 6:00     → 减 4h = 当天 2:00   → 归当天
          中午 12:00    → 减 4h = 当天 8:00   → 归当天
    """
    dt = datetime.fromtimestamp(ts_ms / 1000.0, tz=timezone.utc).astimezone()
    shifted = dt - timedelta(hours=day_start_hour)
    return shifted.strftime("%Y-%m-%d")


# ---------------------------------------------------------------------------
# Prompt（最终版，含 Region pack 段）
# ---------------------------------------------------------------------------

PROMPT_EXTRACT: Final[str] = """你是一个健康记录助手。把用户的口语记录抽取为结构化 JSON。

字段规则：
- type: meal=吃喝, exercise=运动训练, body=体重体脂等身体指标, sleep=睡眠, illness=生病不适, other=其他
- time: 只填时间词本身（如 早上/中午/晚上/下午/睡前/昨天/三点）。没有就填 ""
- foods: 只填食物和饮品名称，不填地点、不填数量。非饮食填 []
- exercise: 运动项目名称（如 跑步/卧推/深蹲/羽毛球），非运动填 ""
- amount: 数量、重量、时长、组数等量化描述，没有填 ""
- kcal: 摄入热量估算值（整数千卡）。必须估算，按常见分量给出合理数字，不得填 0
- symptom: 症状描述原文，非生病填 ""
- weight_kg: 体重（公斤，小数）。没提体重就填 0
- sleep_h: 睡眠时长（小时，小数）。没提睡眠就填 0

分量默认参考（中国北方日常）：
- 一碗牛肉面 ~500g（面 200g + 汤 250g + 配料 50g）→ 约 550-650 kcal
- 一份盖浇饭（米饭 300g + 菜）→ 约 650-800 kcal
- 一个肉包子 ~100g → 约 230 kcal
- 二两米饭 = 100g 熟米 → 约 116 kcal

kcal 估算优先按「当地常见分量」而不是「标准 100g」。

拆分规则：
- 一句话里包含多件事时，必须拆成多条（例如"吃了牛肉面又称了体重"是 2 条）
- 纯饮食记录不算运动，纯体重记录不算饮食

只输出 JSON，不要任何解释文字。
输出格式固定为：{"events": [ {...}, {...} ]}
每个元素就是一个事件对象，字段名就是上面这些。
不要包裹在 answer / result / data 等任何其他键里。若只有一件事，events 数组里也放 1 个元素。
"""


def build_messages(raw_text: str) -> list[dict[str, str]]:
    """组装抽取链的两条消息。"""
    return [
        {"role": "system", "content": PROMPT_EXTRACT},
        {"role": "user", "content": raw_text},
    ]


__all__ = [
    "PROMPT_VER",
    "VALID_TYPES",
    "DEFAULT_DAY_START_HOUR",
    "PROMPT_EXTRACT",
    "clean_literal",
    "to_int",
    "to_float",
    "to_str_list",
    "to_type",
    "normalize_event",
    "extract_events_from_response",
    "day_key_of",
    "build_messages",
]
