"""Provider 抽象层 + OpenAI 兼容实现（S1 的 Python 侧先行验证版）。

设计对齐 `功能补充与套壳选型.md` 9.1：
- 一个接口 + 一个实现，覆盖智谱 / DeepSeek / OpenRouter / SiliconFlow 等
  OpenAI 兼容端点（差异只有 baseUrl / model / key 三个字符串）
- baseUrl 与 model **零硬编码**，必须由调用方传入
- 显式超时（C4）、退避重试、失败返回结构化 Err 而不抛异常

为什么 Python 侧先做：S1 的 Kotlin 实现与这里逻辑一一对应，先用 20 条用例
验证"选什么 provider、配什么重试参数"，再翻译成 Kotlin，避免在 Android 上
反复试错（本机不装工具链，编译一次要等远端 CI）。

仅用标准库 urllib —— 不引入 requests，保证零安装即可跑回归。
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Final

PROMPT_VER_RETRY_DEFAULT: Final[int] = 5
RETRY_BASE_SECONDS_DEFAULT: Final[float] = 1.5
TIMEOUT_MS_DEFAULT: Final[int] = 15_000


class ErrKind(str, Enum):
    """异常分类（C4：可重试 / 需人工 / 终止）。"""

    HTTP = "http"  # 含 429/5xx，可重试
    TIMEOUT = "timeout"  # 可重试
    PARSE = "parse"  # 模型返回非法 JSON，可重试（但重试价值低）
    AUTH = "auth"  # key 错/欠费，重试无意义 → 终止
    RATE_LIMIT = "rate_limit"  # 明确限流，回退后重试
    NETWORK = "network"  # DNS / 连接失败，可重试


# 可重试的错误类型（可重试 = 退避后值得再试一次）
RETRYABLE: Final[frozenset[ErrKind]] = frozenset(
    {ErrKind.HTTP, ErrKind.TIMEOUT, ErrKind.PARSE, ErrKind.RATE_LIMIT, ErrKind.NETWORK}
)

# 这些 HTTP 码不重试（重试也是白等）
FATAL_HTTP_CODES: Final[frozenset[int]] = frozenset({400, 401, 403, 404, 422})


@dataclass
class ChatMessage:
    role: str  # system | user | assistant | tool
    content: str
    tool_calls: list[dict[str, Any]] | None = None
    tool_call_id: str | None = None


@dataclass
class Usage:
    input_tokens: int = 0
    output_tokens: int = 0


@dataclass
class ChatRequest:
    messages: list[ChatMessage]
    tools: list[dict[str, Any]] | None = None
    temperature: float = 0.3
    timeout_ms: int = TIMEOUT_MS_DEFAULT
    max_retries: int = PROMPT_VER_RETRY_DEFAULT
    retry_base_seconds: float = RETRY_BASE_SECONDS_DEFAULT
    exponential_backoff: bool = True


@dataclass
class Ok:
    content: str
    tool_calls: list[dict[str, Any]] = field(default_factory=list)
    usage: Usage = field(default_factory=Usage)


@dataclass
class Err:
    kind: ErrKind
    message: str
    http_code: int | None = None
    attempts: int = 0


ChatResult = Ok | Err


@dataclass
class ProviderConfig:
    """三个字符串定义一切。全部来自设置页，禁止硬编码。"""

    base_url: str
    model: str
    api_key: str
    provider_name: str = "custom"

    def endpoint(self) -> str:
        return f"{self.base_url.rstrip('/')}/chat/completions"


# 设置页预设（这三组值必须对着官方文档核实后再填入，代码里不猜）
PROVIDER_PRESETS: Final[dict[str, dict[str, str]]] = {
    "zhipu": {
        "name": "智谱 GLM",
        "base_url": "[待核实: 智谱开放平台 > API 文档 > base_url]",
        "model": "[待核实: 智谱开放平台 > 模型列表 > 当前可用 flash 模型名]",
    },
    "deepseek": {
        "name": "DeepSeek",
        "base_url": "[待核实: DeepSeek 开放平台 > API 文档 > base_url]",
        "model": "[待核实: DeepSeek 开放平台 > 模型列表]",
    },
    "openrouter": {
        "name": "OpenRouter",
        "base_url": "[待核实: OpenRouter > Docs > API Reference > base URL]",
        "model": "[待核实: OpenRouter > Models]",
    },
    "siliconflow": {
        "name": "SiliconFlow",
        "base_url": "[待核实: 硅基流动 > 文档 > API 参考]",
        "model": "[待核实: 硅基流动 > 模型广场]",
    },
}


class OpenAiCompatProvider:
    """OpenAI 兼容端点客户端。无状态，可复用。"""

    def __init__(self, config: ProviderConfig) -> None:
        self.config = config

    def chat(self, request: ChatRequest) -> ChatResult:
        """单次对话。绝不抛异常 —— 失败一律返回 Err。"""
        payload: dict[str, Any] = {
            "model": self.config.model,
            "messages": [self._message_to_wire(m) for m in request.messages],
            "temperature": request.temperature,
        }
        if request.tools:
            payload["tools"] = request.tools
            payload["tool_choice"] = "auto"

        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        last_err: Err | None = None

        for attempt in range(request.max_retries + 1):
            if attempt > 0:
                wait = self._backoff_seconds(attempt, request)
                time.sleep(wait)

            result = self._single_call(body, request.timeout_ms, attempt)

            if isinstance(result, Ok):
                return result

            last_err = result
            last_err.attempts = attempt + 1

            # 终止类错误不重试
            if result.kind == ErrKind.AUTH or (
                result.http_code is not None and result.http_code in FATAL_HTTP_CODES
            ):
                return result

            if result.kind not in RETRYABLE:
                return result

        assert last_err is not None
        return last_err

    @staticmethod
    def _backoff_seconds(attempt: int, request: ChatRequest) -> float:
        """退避：1.5 × 2^(n-1)，指数退避可关（不同平台限流策略不同，9.1）。"""
        if not request.exponential_backoff:
            return request.retry_base_seconds
        return request.retry_base_seconds * (2 ** (attempt - 1))

    def _single_call(self, body: bytes, timeout_ms: int, attempt: int) -> ChatResult:
        req = urllib.request.Request(
            self.config.endpoint(),
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.config.api_key}",
                "Accept": "application/json",
            },
        )

        try:
            with urllib.request.urlopen(req, timeout=timeout_ms / 1000.0) as resp:
                raw = resp.read().decode("utf-8", errors="replace")
                return self._parse_response(raw, resp.status)

        except urllib.error.HTTPError as e:
            raw = ""
            try:
                raw = e.read().decode("utf-8", errors="replace")
            except Exception:  # noqa: BLE001 — 读 body 失败不影响主判断
                pass
            code = int(e.code)
            kind = ErrKind.AUTH if code in (401, 403) else (
                ErrKind.RATE_LIMIT if code == 429 else ErrKind.HTTP
            )
            return Err(kind=kind, message=self._sanitize(raw) or f"HTTP {code}", http_code=code)

        except TimeoutError:
            return Err(kind=ErrKind.TIMEOUT, message=f"请求超时（{timeout_ms}ms）")

        except urllib.error.URLError as e:
            reason = str(getattr(e, "reason", e))
            if "timed out" in reason.lower():
                return Err(kind=ErrKind.TIMEOUT, message=f"请求超时（{timeout_ms}ms）")
            return Err(kind=ErrKind.NETWORK, message=self._sanitize(reason))

        except Exception as e:  # noqa: BLE001 — 兜底，绝不向上抛
            return Err(kind=ErrKind.NETWORK, message=self._sanitize(f"{type(e).__name__}: {e}"))

    def _parse_response(self, raw: str, status: int) -> ChatResult:
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            return Err(kind=ErrKind.PARSE, message=self._sanitize(raw), http_code=status)

        if not isinstance(data, dict):
            return Err(kind=ErrKind.PARSE, message="响应顶层不是对象", http_code=status)

        # 有些平台在 200 里塞业务错误码（智谱历史上用 code 字段）
        biz_code = data.get("code")
        if biz_code not in (None, 0, "0", 200):
            return Err(
                kind=ErrKind.RATE_LIMIT if str(biz_code) == "1305" else ErrKind.HTTP,
                message=self._sanitize(str(data.get("message") or data.get("msg") or biz_code)),
                http_code=status,
            )

        choices = data.get("choices")
        if not isinstance(choices, list) or not choices:
            return Err(kind=ErrKind.PARSE, message="响应缺少 choices", http_code=status)

        message = (choices[0] or {}).get("message") or {}
        content = message.get("content")
        if content is None:
            content = ""

        tool_calls = message.get("tool_calls") or []
        if not isinstance(tool_calls, list):
            tool_calls = []

        # 有 tool_calls 但 content 为空 = 合法（模型决定调工具）
        if not content and not tool_calls:
            return Err(kind=ErrKind.PARSE, message="响应既无 content 也无 tool_calls", http_code=status)

        usage_raw = data.get("usage") or {}
        usage = Usage(
            input_tokens=int(usage_raw.get("prompt_tokens") or 0),
            output_tokens=int(usage_raw.get("completion_tokens") or 0),
        )

        return Ok(content=str(content), tool_calls=tool_calls, usage=usage)

    @staticmethod
    def _message_to_wire(m: ChatMessage) -> dict[str, Any]:
        wire: dict[str, Any] = {"role": m.role, "content": m.content}
        if m.tool_calls:
            wire["tool_calls"] = m.tool_calls
        if m.tool_call_id:
            wire["tool_call_id"] = m.tool_call_id
        return wire

    @staticmethod
    def _sanitize(text: str) -> str:
        """脱敏：绝不把 key 写进日志或错误信息（C6）。

        保守做法 —— 任何形如 sk-xxx / 长 base64 的片段一律抹掉。
        """
        import re

        if not text:
            return ""
        out = re.sub(r"sk-[A-Za-z0-9_\-]{8,}", "sk-***", text)
        out = re.sub(r"(?i)(api[_\-]?key\"?\s*[:=]\s*\"?)[^\"\s,}]{8,}", r"\1***", out)
        out = re.sub(r"(?i)(authorization\"?\s*[:=]\s*\"?bearer\s+)[^\"\s,}]{8,}", r"\1***", out)
        return out[:200]


__all__ = [
    "ErrKind",
    "RETRYABLE",
    "FATAL_HTTP_CODES",
    "ChatMessage",
    "Usage",
    "ChatRequest",
    "Ok",
    "Err",
    "ChatResult",
    "ProviderConfig",
    "PROVIDER_PRESETS",
    "OpenAiCompatProvider",
]
