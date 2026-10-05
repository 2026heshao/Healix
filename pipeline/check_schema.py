#!/usr/bin/env python3
"""Healix Room schema 完整性检查（零第三方依赖）。

动机（2026-10-05，v8 T07）：
  `AppDatabase` 早已 `exportSchema = true` 且 `version = 3`，但仓库里
  **只有 1.json / 2.json，缺 3.json** —— 也就是 v2→v3 那次升级的 schema
  从未入库。后果不是"少个文件"，而是：

    1. `MigrationTestHelper` 拿不到 3.json，迁移**无法被自动校验**；
    2. 一旦有人改动实体却忘了升 version，Room 在真机上抛
       `IllegalStateException: Room cannot verify the data integrity`
       —— 而本地无 JDK/Android SDK，`assembleDebug` 跑不了，**编译期也发现不了**。

  于是退一步：把「schema 与代码是否自洽」用静态分析兜住。本机 python3 就能跑。

═══════════════════════════════════════════════════════════════════════════
为什么单独一个脚本，且**必须放在 CI 的编译步骤之后**
═══════════════════════════════════════════════════════════════════════════
`3.json` 这类文件是 Room 在 **KSP 编译期**生成的，本机没有 JDK 跑不出来，
唯一来源是 CI 跑完 `assembleDebug` 后上传的 artifact（见 `pipeline/pull_schemas.py`）。
若把这个检查放在编译**之前**（像 check_kotlin.py 那样），就会死锁：

  升 version → 检查报"缺 N.json" → CI 在这一步失败 → 后续 gradle 不执行
  → N.json 从未被生成 → artifact 里没有 → 永远拉不回来。

所以它单独成脚本、挂在 ci.yml 的**产物上传之后**（`if: always()`）：
编译照跑、artifact 照传，检查红着，开发者拉回 N.json 提交即转绿。
注意本地手动跑时它同样是"事后检查"，这个顺序是刻意的。

覆盖范围：
  1. `@Database(version = N)` 的 N 必须有一个已入库的 `<N>.json`
     ——「已入库」以 `git ls-files` 为准（CI 编译会就地生成未入库的文件，
       只看工作区会导致 CI 自己把自己的疏漏"治愈"，检查形同虚设）；
  2. `<v>.json` 的 `database.version` 必须等于 v（文件放错版本号是常见手误）；
  3. 当前版本 `<N>.json` 的实体表集合 与 `@Database(entities = [...])` **双向一致**
     —— 加实体忘了升 version、或改了 entities 没重新导 schema，都会在这里暴露；
  4. v1..vN 连续，且每相邻两版都有对应的 `Migration(a, b)` 对象；
  5. 每个版本**新增**的表 / 索引，其 DDL 必须**逐字**出现在
     `Migration(v-1, v)` 的块体里 —— 迁移里写错一个列名/漏一个 UNIQUE 索引，
     真机升级时才会炸，且炸在用户数据上。这是本检查器最有价值的一条；
  6. 禁止 `fallbackToDestructiveMigration`（硬约束：数据只有本机一份，宁可崩不静默删库）。

退出码：0 = 全通过；1 = 发现问题。
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DB_DIR = ROOT / "app/src/main/java/com/healix/app/db"
DB_FILE = DB_DIR / "AppDatabase.kt"
SCHEMA_ROOT = ROOT / "app/schemas"

errors: list[str] = []
warnings: list[str] = []


def rel(p: Path) -> str:
    return str(p.relative_to(ROOT)).replace("\\", "/")


# ---------------------------------------------------------------------------
# 解析工具
# ---------------------------------------------------------------------------

def balanced(text: str, start: int, opener: str = "(", closer: str = ")") -> tuple[str, int]:
    """从 text[start] == opener 开始，返回配平内的内容与结束位置。"""
    depth = 0
    i = start
    while i < len(text):
        if text[i] == opener:
            depth += 1
        elif text[i] == closer:
            depth -= 1
            if depth == 0:
                return text[start + 1: i], i + 1
        i += 1
    return text[start + 1:], len(text)


def mask_noncode(text: str) -> str:
    """把字符串字面量 / 行注释 / 块注释的内容替换成等长空白。

    只用于**括号配平**（`Migration(a, b) { ... }` 的块体边界）——
    注释里出现 `{`、SQL 字符串里出现 `)` 都会让朴素计数跑偏。
    保持长度不变，索引可直接沿用。
    """
    out = list(text)
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                out[k] = " "
            i = j
        elif c == '"':
            # 跳过长字符串（""" ... """）与普通字符串；转义 \" 不结束字符串
            if text.startswith('"""', i):
                j = text.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n:
                    if text[j] == "\\":
                        j += 2
                        continue
                    if text[j] == '"' or text[j] == "\n":
                        j += 1
                        break
                    j += 1
            for k in range(i, min(j, n)):
                out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)


def normalize_sql(s: str) -> str:
    """空白折叠 —— Kotlin 源码里 DDL 可能折成多行，JSON 里是单行。"""
    return re.sub(r"\s+", " ", s).strip()


# ---------------------------------------------------------------------------
# 1. 从 Kotlin 源里读「实体类名 -> 表名」
# ---------------------------------------------------------------------------

def entity_class_to_table() -> dict[str, str]:
    out: dict[str, str] = {}
    for f in sorted(DB_DIR.glob("*.kt")):
        text = f.read_text(encoding="utf-8")
        # 位置在 mask 后的文本里找（注释里写 `@Entity(` 不该被当成声明），
        # 内容仍从原文取（`tableName = "x"` 里的字符串在 mask 后是空白）。
        # mask 保持长度不变，索引可直接沿用。
        masked = mask_noncode(text)
        for am in re.finditer(r"@Entity\s*\(", masked):
            attrs, aend = balanced(text, am.end() - 1)
            cm = re.search(r"class\s+(\w+)", masked[aend:])
            if not cm:
                continue
            cls = cm.group(1)
            tn = re.search(r'tableName\s*=\s*"(\w+)"', attrs)
            out[cls] = tn.group(1) if tn else cls.lower()
    return out


# ---------------------------------------------------------------------------
# 2. 从 AppDatabase.kt 读 @Database 注解 / Migration 块
# ---------------------------------------------------------------------------

def read_database_annotation(text: str) -> tuple[str, int, list[str]]:
    """返回 (全限定库名, version, 实体类名列表)。

    ⚠️ 一律在 `mask_noncode` 之后匹配：本仓库的 KDoc 里**会写** `@Database(version = ...)`
    这样的示例（说明为什么注解里保留字面量），不 mask 的话正则会命中注释里的示例，
    然后把 `version = DB_VERSION` 当注解体解析 → 报"找不到 version = N"。
    """
    masked = mask_noncode(text)
    pkg_m = re.search(r"^package\s+([\w.]+)", masked, re.M)
    cls_m = re.search(r"class\s+(\w+)\s*:\s*RoomDatabase", masked)
    ann_m = re.search(r"@Database\s*\(", masked)
    if not (pkg_m and cls_m and ann_m):
        raise ValueError("AppDatabase.kt 里找不到 package / @Database / class ... : RoomDatabase")
    attrs, _ = balanced(masked, ann_m.end() - 1)
    ver_m = re.search(r"version\s*=\s*(\d+)", attrs)
    if not ver_m:
        raise ValueError("@Database 注解里找不到 version = N")
    ent_m = re.search(r"entities\s*=\s*\[(.*?)\]", attrs, re.S)
    classes = re.findall(r"(\w+)::class", ent_m.group(1)) if ent_m else []
    return f"{pkg_m.group(1)}.{cls_m.group(1)}", int(ver_m.group(1)), classes


def migration_blocks(text: str) -> dict[tuple[int, int], str]:
    """`object : Migration(a, b) { ... }` -> {(a, b): 块体源码}。"""
    masked = mask_noncode(text)
    out: dict[tuple[int, int], str] = {}
    for m in re.finditer(r":\s*Migration\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*\{", masked):
        body, _ = balanced(masked, m.end() - 1, "{", "}")
        # 用 mask 后的文本定位边界，再用原文取内容（保留 SQL 字面量）
        out[(int(m.group(1)), int(m.group(2)))] = text[m.end(): m.end() + len(body)]
    return out


# ---------------------------------------------------------------------------
# 3. 读 schema JSON
# ---------------------------------------------------------------------------

def load_schema(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def table_map(schema: dict) -> dict[str, dict]:
    return {e["tableName"]: e for e in schema["database"].get("entities", [])}


def index_names(entity: dict) -> set[str]:
    return {i["name"] for i in entity.get("indices", [])}


def index_create_sql(entity: dict) -> dict[str, str]:
    return {i["name"]: i["createSql"] for i in entity.get("indices", [])}


# ---------------------------------------------------------------------------
# 4. 「已入库」判据
# ---------------------------------------------------------------------------

def tracked_schema_files() -> set[str] | None:
    """`git ls-files app/schemas` 的结果（相对仓库根的 posix 路径）。

    非 git 环境（比如别人把仓库整包拷走）返回 None → 降级为只看工作区，
    并给一条提示，而不是直接判死。
    """
    try:
        p = subprocess.run(
            ["git", "-C", str(ROOT), "ls-files", "--", "app/schemas"],
            capture_output=True, text=True, timeout=30,
        )
        if p.returncode != 0:
            return None
        return {ln.strip().replace("\\", "/") for ln in p.stdout.splitlines() if ln.strip()}
    except Exception:
        return None


# ---------------------------------------------------------------------------
# 主检查
# ---------------------------------------------------------------------------

def main() -> int:
    if not DB_FILE.exists():
        print(f"找不到 {rel(DB_FILE)}")
        return 1

    text = DB_FILE.read_text(encoding="utf-8")

    try:
        db_fqn, version, entity_classes = read_database_annotation(text)
    except ValueError as e:
        print(f"❌ {e}")
        return 1

    schema_dir = SCHEMA_ROOT / db_fqn
    c2t = entity_class_to_table()
    expected_tables = {c2t.get(c, c.lower()) for c in entity_classes}

    # ── 0. 顶层 DB_VERSION 常量必须与 @Database 的字面量一致 ──
    # 注解里保留字面量是刻意的（Kotlin 注解引用顶层 const 在 KSP/Room 侧不受保证），
    # 代价就是"两处写成一样"要靠检查器兜住 —— DbSnapshot 用的正是 DB_VERSION，
    # 一旦它与注解漂移，快照就会对着错误的版本号判断"是否要迁移"。
    dv = re.search(r"\bconst\s+val\s+DB_VERSION\s*(?::\s*Int\s*)?=\s*(\d+)", mask_noncode(text))
    if dv and int(dv.group(1)) != version:
        errors.append(
            f"{rel(DB_FILE)}: 顶层 `const val DB_VERSION = {dv.group(1)}` 与 "
            f"`@Database(version = {version})` 不一致。两者必须相等："
            "DB_VERSION 是 DbSnapshot 判定“本次是否会迁移”的依据，"
            "漂移会让快照在错误的时机触发（或该存的时候不存）。"
        )

    # ── 6. 禁止破坏性迁移（先查，这条与 schema 文件无关） ──
    if "fallbackToDestructiveMigration" in mask_noncode(text):
        errors.append(
            f"{rel(DB_FILE)}: 出现 `fallbackToDestructiveMigration()`。"
            "这是硬约束禁止项：数据只有手机本地一份副本，缺 Migration 时静默删库 = 数据全灭。"
            "宁可崩溃报错（IllegalStateException 可定位），也不要静默重建。"
        )

    # ── 1. v1..vN 的 schema 文件必须齐、且必须已入库 ──
    tracked = tracked_schema_files()
    if tracked is None:
        warnings.append(
            "不是 git 仓库（或 git 不可用）→ 本次只校验工作区里的 schema 文件，"
            "无法确认「是否已入库」。CI 上会按 git ls-files 严格判。"
        )

    schemas: dict[int, dict] = {}
    for v in range(1, version + 1):
        path = schema_dir / f"{v}.json"
        if not path.exists():
            errors.append(
                f"缺 {rel(path)}（@Database 已声明 version = {version}）。"
                "迁移历史的唯一凭据，必须入库 —— 用 pipeline/pull_schemas.py <PAT> "
                "从 CI 产物拉回（CI 编译期由 Room 生成，本机无 JDK 跑不出来）。"
            )
            continue
        if tracked is not None and rel(path) not in tracked:
            errors.append(
                f"{rel(path)} 存在但**未入库**（git 未跟踪）。"
                "CI 编译期会在工作区就地生成这个文件，只看工作区的话 CI 会把自己的疏漏"
                "“治愈”，检查形同虚设 —— 所以判据是 git ls-files。"
                "请 git add app/schemas 并提交（迁移历史必须入库）。"
            )
        try:
            schema = load_schema(path)
        except Exception as e:  # noqa: BLE001 —— 良构性失败要报出来而不是崩栈
            errors.append(f"{rel(path)} 不是合法 JSON：{e}")
            continue
        got = schema.get("database", {}).get("version")
        if got != v:
            errors.append(
                f"{rel(path)} 里 database.version = {got}，与文件名 {v} 不符"
                "（放错版本号会让 Room 的迁移校验直接失败）。"
            )
        schemas[v] = schema

    # ── 4. 版本连续 + 每步都有 Migration ──
    # 放在"缺 schema 文件就提前 return"之前：升 version 时最常见的两个疏漏
    # （忘加 Migration、忘导 schema）应当**一次都报出来**，而不是修一个再等下一轮。
    blocks = migration_blocks(text)
    for v in range(1, version):
        if (v, v + 1) not in blocks:
            errors.append(
                f"缺 `Migration({v}, {v + 1})` 对象（@Database version = {version}，"
                f"而 MIGRATIONS 数组里没有这一步）。缺一步 = 真机从 v{v} 升级直接抛异常。"
            )

    if version not in schemas:
        _report()
        return 1 if errors else 0

    # ── 3. 当前版本的实体集合双向一致 ──
    got_tables = set(table_map(schemas[version]))
    for t in sorted(expected_tables - got_tables):
        errors.append(
            f"{db_fqn}/{version}.json 里没有表 `{t}`，但 @Database(entities = [...]) 声明了它。"
            "两种情况：① 加了实体忘了把 version +1（真机升级会抛 data integrity 异常）；"
            "② 升了 version 但没重新导 schema。"
        )
    for t in sorted(got_tables - expected_tables):
        errors.append(
            f"{db_fqn}/{version}.json 里有表 `{t}`，但 @Database(entities = [...]) 没声明它。"
            "多半是从 @Database 删了实体却把旧 schema 文件一起改错了 —— "
            "schema JSON 是 Room 生成的产物，**不要手改**，重新导一份。"
        )

    # ── 5. 新增表 / 索引的 DDL 必须逐字出现在对应的 Migration 块里 ──
    for v in range(2, version + 1):
        prev, cur = schemas.get(v - 1), schemas.get(v)
        if prev is None or cur is None:
            continue
        block = blocks.get((v - 1, v))
        if block is None:
            continue  # 上一条已经报过"缺 Migration"
        haystack = normalize_sql(block)
        prev_t = table_map(prev)
        cur_t = table_map(cur)

        for name, entity in cur_t.items():
            if name not in prev_t:
                needle = normalize_sql(entity["createSql"].replace("${TABLE_NAME}", name))
                if needle not in haystack:
                    errors.append(
                        f"表 `{name}` 是 v{v} 新增的，但 `Migration({v - 1}, {v})` 的块体里"
                        "找不到与 schema 逐字一致的建表语句。\n"
                        f"        期望（来自 {db_fqn}/{v}.json）：{needle}"
                    )
        # 索引：只看"新出现的索引名"（v 版里存在、v-1 版里没有）
        prev_idx: dict[str, str] = {}
        for e in prev_t.values():
            prev_idx.update(index_create_sql(e))
        for tname, entity in cur_t.items():
            for iname, isql in index_create_sql(entity).items():
                if iname in prev_idx:
                    continue
                needle = normalize_sql(isql.replace("${TABLE_NAME}", tname))
                if needle not in haystack:
                    errors.append(
                        f"索引 `{iname}` 是 v{v} 新增的，但 `Migration({v - 1}, {v})` 的块体里"
                        "找不到与 schema 逐字一致的建索引语句（漏 UNIQUE 索引 = "
                        "去重 / 幂等链路在真机上失效）。\n"
                        f"        期望：{needle}"
                    )

        # 警告：既有表的建表语句发生变化 → 该版本的 Migration 里必须有 ALTER / 重建
        for name, entity in cur_t.items():
            old = prev_t.get(name)
            if old is None:
                continue
            if normalize_sql(old["createSql"]) != normalize_sql(entity["createSql"]):
                if re.search(rf"\b{re.escape(name)}\b", haystack) and "ALTER TABLE" not in haystack.upper():
                    warnings.append(
                        f"表 `{name}` 的建表语句在 v{v} 变了，但 `Migration({v - 1}, {v})` 里"
                        "没有 ALTER TABLE —— 若确实用「建新表 + 搬数据 + 改名」重建，"
                        "请人工确认，这条可忽略。"
                    )

    _report()
    return 1 if errors else 0


def _report() -> None:
    print("=" * 64)
    print("Healix Room schema 完整性检查")
    print("=" * 64)
    if errors:
        print(f"\n❌ 错误 {len(errors)} 项：")
        for e in errors:
            print(f"   - {e}")
    if warnings:
        print(f"\n⚠️  提示 {len(warnings)} 项（可能误报）：")
        for w in warnings:
            print(f"   - {w}")
    if not errors:
        print("\n✅ schema 与代码自洽")
    print("=" * 64)


if __name__ == "__main__":
    sys.exit(main())
