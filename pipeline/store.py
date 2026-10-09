"""SQLite 存储层（P1 竖切片的落库端）。

**定稿 schema** 以 `docs/archive/功能补充与套壳选型.md` 第六章为准 —— 这是 App 侧 Room
实体的镜像，两边字段必须一字不差。此文件同时也是 Kotlin 侧的对照物。

Migration 纪律（借 FairTrack 实践）：本文件即 v1，无历史包袱。
Kotlin 侧从 v1 起就必须 `exportSchema = true` + 显式 Migration，禁
`fallbackToDestructiveMigration()`。
"""

from __future__ import annotations

import json
import sqlite3
import uuid
from pathlib import Path
from typing import Any, Iterable

from .contract import day_key_of

SCHEMA_VERSION = 1

SCHEMA_SQL = """
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS events (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  client_event_id TEXT    NOT NULL UNIQUE,
  ts              INTEGER NOT NULL,
  day_key         TEXT    NOT NULL,
  raw_text        TEXT    NOT NULL,
  type            TEXT    NOT NULL,
  time_hint       TEXT,
  foods           TEXT,
  exercise        TEXT,
  amount          TEXT,
  kcal            INTEGER DEFAULT 0,
  symptom         TEXT,
  weight_kg       REAL    DEFAULT 0,
  sleep_h         REAL    DEFAULT 0,
  source          TEXT,
  parse_status    TEXT    NOT NULL DEFAULT 'pending',
  retry_count     INTEGER NOT NULL DEFAULT 0,
  last_error      TEXT,
  origin          TEXT,
  created_at      INTEGER,
  updated_at      INTEGER,
  deleted_at      INTEGER
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_events_client_id ON events(client_event_id);
CREATE INDEX IF NOT EXISTS idx_events_day   ON events(day_key);
CREATE INDEX IF NOT EXISTS idx_events_ts    ON events(ts);
CREATE INDEX IF NOT EXISTS idx_events_type  ON events(type);
CREATE INDEX IF NOT EXISTS idx_events_parse ON events(parse_status);

CREATE TABLE IF NOT EXISTS presets (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  name         TEXT    NOT NULL,
  foods_json   TEXT,
  kcal         INTEGER DEFAULT 0,
  use_count    INTEGER NOT NULL DEFAULT 0,
  last_used_at INTEGER,
  created_at   INTEGER
);

CREATE TABLE IF NOT EXISTS llm_calls (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  ts            INTEGER,
  purpose       TEXT,
  event_id      INTEGER,
  model         TEXT,
  prompt_ver    TEXT,
  attempts      INTEGER,
  latency_ms    INTEGER,
  status        TEXT,
  http_code     INTEGER,
  input_tokens  INTEGER,
  output_tokens INTEGER,
  error_head    TEXT
);
CREATE INDEX IF NOT EXISTS idx_llm_calls_ts ON llm_calls(ts);

CREATE TABLE IF NOT EXISTS chat_messages (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  session_date  TEXT    NOT NULL,
  role          TEXT    NOT NULL,
  content       TEXT,
  tool_name     TEXT,
  created_at    INTEGER
);
CREATE INDEX IF NOT EXISTS idx_chat_session ON chat_messages(session_date, created_at);

CREATE TABLE IF NOT EXISTS daily_plans (
  date          TEXT PRIMARY KEY,
  target_kcal   INTEGER,
  plan_json     TEXT,
  content       TEXT,
  generated_at  INTEGER,
  source        TEXT
);

CREATE TABLE IF NOT EXISTS daily_reviews (
  date         TEXT PRIMARY KEY,
  content      TEXT,
  model        TEXT,
  generated_at INTEGER
);

CREATE TABLE IF NOT EXISTS settings (
  key   TEXT PRIMARY KEY,
  value TEXT
);
"""


def connect(db_path: str | Path) -> sqlite3.Connection:
    """打开（必要时创建）数据库。"""
    path = Path(db_path)
    if path.parent and str(path.parent) not in ("", "."):
        path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA_SQL)
    conn.execute(
        "INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)",
        ("schema_version", str(SCHEMA_VERSION)),
    )
    conn.commit()


def new_client_event_id() -> str:
    """幂等键：录入瞬间生成一次（功能补充 1.3）。

    通知栏 RemoteInput 会重复投递，靠这个 UNIQUE 约束 + IGNORE 插入去重。
    """
    return str(uuid.uuid4())


def insert_event(
    conn: sqlite3.Connection,
    *,
    client_event_id: str,
    ts: int,
    raw_text: str,
    event: dict[str, Any],
    source: str = "app",
    parse_status: str = "pending",
    retry_count: int = 0,
    last_error: str | None = None,
    origin: str = "user",
    day_start_hour: int = 4,
) -> int:
    """插入一条事件。返回 rowid，重复（幂等键冲突）返回 -1。

    第 5 步「保留原文」：raw_text 是 NOT NULL，永远落库。
    """
    now = int(__import__("time").time() * 1000)

    cur = conn.execute(
        """
        INSERT OR IGNORE INTO events (
            client_event_id, ts, day_key, raw_text, type, time_hint, foods,
            exercise, amount, kcal, symptom, weight_kg, sleep_h,
            source, parse_status, retry_count, last_error, origin,
            created_at, updated_at, deleted_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)
        """,
        (
            client_event_id,
            ts,
            day_key_of(ts, day_start_hour),
            raw_text,
            event.get("type", "other"),
            event.get("time_hint", ""),
            json.dumps(event.get("foods", []), ensure_ascii=False),
            event.get("exercise", ""),
            event.get("amount", ""),
            int(event.get("kcal", 0) or 0),
            event.get("symptom", ""),
            float(event.get("weight_kg", 0.0) or 0.0),
            float(event.get("sleep_h", 0.0) or 0.0),
            source,
            parse_status,
            retry_count,
            last_error,
            origin,
            now,
            now,
        ),
    )
    conn.commit()
    if cur.rowcount <= 0:
        return -1  # 幂等命中：已存在，不新增行
    return int(cur.lastrowid or -1)


def update_event_parsed(
    conn: sqlite3.Connection,
    client_event_id: str,
    event: dict[str, Any],
    *,
    parse_status: str = "done",
    last_error: str | None = None,
) -> None:
    """AI 回来后回填字段（功能补充 1.1 第 2 步）。

    用 client_event_id 定位而不是 rowid —— 幂等键的另一个好处就是覆盖更新。
    """
    now = int(__import__("time").time() * 1000)
    conn.execute(
        """
        UPDATE events SET
            type=?, time_hint=?, foods=?, exercise=?, amount=?, kcal=?,
            symptom=?, weight_kg=?, sleep_h=?,
            parse_status=?, last_error=?, updated_at=?
        WHERE client_event_id=?
        """,
        (
            event.get("type", "other"),
            event.get("time_hint", ""),
            json.dumps(event.get("foods", []), ensure_ascii=False),
            event.get("exercise", ""),
            event.get("amount", ""),
            int(event.get("kcal", 0) or 0),
            event.get("symptom", ""),
            float(event.get("weight_kg", 0.0) or 0.0),
            float(event.get("sleep_h", 0.0) or 0.0),
            parse_status,
            last_error,
            now,
            client_event_id,
        ),
    )
    conn.commit()


def mark_failed(conn: sqlite3.Connection, client_event_id: str, error: str) -> None:
    """重试耗尽 → 置 failed（功能补充 1.1 第 4 步：不重试也能继续用）。"""
    now = int(__import__("time").time() * 1000)
    conn.execute(
        """
        UPDATE events SET parse_status='failed', retry_count=retry_count+1,
                          last_error=?, updated_at=?
        WHERE client_event_id=?
        """,
        (error[:200], now, client_event_id),
    )
    conn.commit()


def log_llm_call(
    conn: sqlite3.Connection,
    *,
    purpose: str,
    model: str,
    prompt_ver: str,
    attempts: int,
    latency_ms: int,
    status: str,
    http_code: int | None = None,
    input_tokens: int | None = None,
    output_tokens: int | None = None,
    error_head: str | None = None,
    event_id: int | None = None,
) -> None:
    """可观测性埋点（功能补充第三章）。prompt_ver 是最关键的一列。"""
    now = int(__import__("time").time() * 1000)
    conn.execute(
        """
        INSERT INTO llm_calls (
            ts, purpose, event_id, model, prompt_ver, attempts, latency_ms,
            status, http_code, input_tokens, output_tokens, error_head
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        (
            now,
            purpose,
            event_id,
            model,
            prompt_ver,
            attempts,
            latency_ms,
            status,
            http_code,
            input_tokens,
            output_tokens,
            (error_head or "")[:200] or None,
        ),
    )
    conn.commit()


def day_summary(conn: sqlite3.Connection, day_key: str) -> dict[str, Any]:
    """当日汇总（本地算术，不调 AI —— UI 设计方案 8.1）。"""
    row = conn.execute(
        """
        SELECT
          COALESCE(SUM(CASE WHEN type='meal' THEN kcal ELSE 0 END), 0) AS kcal_in,
          COALESCE(SUM(CASE WHEN type='exercise' THEN kcal ELSE 0 END), 0) AS kcal_out,
          COUNT(*) AS n_total,
          COALESCE(SUM(CASE WHEN type='meal' THEN 1 ELSE 0 END), 0) AS n_meal,
          COALESCE(SUM(CASE WHEN type='other' THEN 1 ELSE 0 END), 0) AS n_other
        FROM events
        WHERE day_key=? AND deleted_at IS NULL
        """,
        (day_key,),
    ).fetchone()

    # 体重取当天最后一条非 0 值
    weight_row = conn.execute(
        """
        SELECT weight_kg FROM events
        WHERE day_key=? AND deleted_at IS NULL AND type='body' AND weight_kg > 0
        ORDER BY ts DESC LIMIT 1
        """,
        (day_key,),
    ).fetchone()

    sleep_row = conn.execute(
        """
        SELECT sleep_h FROM events
        WHERE day_key=? AND deleted_at IS NULL AND type='sleep' AND sleep_h > 0
        ORDER BY ts DESC LIMIT 1
        """,
        (day_key,),
    ).fetchone()

    return {
        "day_key": day_key,
        "kcal_in": int(row["kcal_in"]),
        "kcal_out": int(row["kcal_out"]),
        "count": int(row["n_total"]),
        "weight_kg": float(weight_row["weight_kg"]) if weight_row else 0.0,
        "sleep_h": float(sleep_row["sleep_h"]) if sleep_row else 0.0,
    }


def fetch_events(conn: sqlite3.Connection, day_key: str | None = None, limit: int = 100) -> list[sqlite3.Row]:
    if day_key:
        return list(
            conn.execute(
                "SELECT * FROM events WHERE day_key=? AND deleted_at IS NULL ORDER BY ts DESC LIMIT ?",
                (day_key, limit),
            )
        )
    return list(
        conn.execute(
            "SELECT * FROM events WHERE deleted_at IS NULL ORDER BY ts DESC LIMIT ?", (limit,)
        )
    )


def insert_events_batch(
    conn: sqlite3.Connection, rows: Iterable[dict[str, Any]]
) -> list[int]:
    """批量插入（功能补充 1.5：一条输入 → 多条事件）。"""
    ids: list[int] = []
    for row in rows:
        ids.append(
            insert_event(
                conn,
                client_event_id=new_client_event_id(),
                ts=int(row["ts"]),
                raw_text=str(row["raw_text"]),
                event=row["event"],
                source=str(row.get("source", "app")),
                parse_status=str(row.get("parse_status", "pending")),
            )
        )
    return ids


__all__ = [
    "SCHEMA_VERSION",
    "SCHEMA_SQL",
    "connect",
    "init_db",
    "new_client_event_id",
    "insert_event",
    "insert_events_batch",
    "update_event_parsed",
    "mark_failed",
    "log_llm_call",
    "day_summary",
    "fetch_events",
]
