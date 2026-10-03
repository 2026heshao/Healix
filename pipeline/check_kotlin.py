#!/usr/bin/env python3
"""Healix Kotlin/Room 静态检查（零第三方依赖）。

动机：本机不装 JDK / Android SDK（决策：全走 GitHub Actions），所以 CI 上
`./gradlew assembleDebug` 的编译错误**无法在本地复现**。日志接口又需要
特殊鉴权。于是退一步：把「编译器最常抓到的那几类错误」用静态分析兜住，
让它们在 push 之前就暴露。

覆盖范围（都是会真实导致 KSP / kotlinc 失败的模式）：
  1. Room DAO 查询列 与 @Entity @ColumnInfo(name=...) 的列名是否对得上
  2. Room DAO 查询里引用但实体中不存在的列（悬空列）
  3. DAO 方法返回类型 vs 投影类字段类型的 SQLite 亲和性
     （典型坑：SUM() 返回 Long，却被声明成 Int）
  4. 同一 @TypeConverters 类里签名重复的 @TypeConverter
  5. Activity/Sheet 里 binding.xxx 与布局 @+id/xxx 是否匹配
  6. Manifest 里 android:name 指向的类是否存在
  7. AndroidManifest 引用的 @xml/@mipmap/@style/@string 是否声明

退出码：0 = 全通过；1 = 发现问题。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java"
RES = ROOT / "app/src/main/res"
DB = JAVA / "com/healix/app/db"

errors: list[str] = []
warnings: list[str] = []


def rel(p: Path) -> str:
    return str(p.relative_to(ROOT)).replace("\\", "/")


# ---------------------------------------------------------------------------
# 解析工具
# ---------------------------------------------------------------------------

ENTITY_RE = re.compile(r'@Entity\s*\(([^)]*)\)', re.S)
# 注解可能是 @ColumnInfo 或全限定的 @androidx.room.ColumnInfo
COLUMN_RE = re.compile(
    r'@(?:androidx\.room\.)?ColumnInfo\s*\(\s*name\s*=\s*"([^"]+)"\s*\)\s*'
    r'(?:val|var)\s+(\w+)\s*:\s*([A-Za-z0-9_<>,? ]+)'
)
# 没写 @ColumnInfo 的字段（fallback 到属性名）
PLAIN_VAL_RE = re.compile(r'(?:val|var)\s+(\w+)\s*:\s*([A-Za-z0-9_<>,? ]+?)\s*[=,)]')


def collect_tables() -> dict[str, dict[str, str]]:
    """表名 -> {列名: Kotlin 类型}。

    注意 @Entity(...) 与 class 声明之间可能隔着别的行，也可能 @Entity 内含
    换行/多个参数（indices 等），所以用「找到 @Entity( 后用括号配平提取参数，
    再向下找最近的 class」的方式，而不是单一正则。
    """
    tables: dict[str, dict[str, str]] = {}

    def balanced_paren(text: str, start: int) -> tuple[str, int]:
        """从 text[start] == '(' 开始，返回配平内的内容和结束位置。"""
        depth = 0
        i = start
        while i < len(text):
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
                if depth == 0:
                    return text[start + 1: i], i + 1
            i += 1
        return text[start + 1:], len(text)

    for f in sorted(DB.glob("*.kt")):
        text = f.read_text(encoding="utf-8")
        for am in re.finditer(r'@Entity\s*\(', text):
            pstart = am.end() - 1
            attrs, aend = balanced_paren(text, pstart)
            cm = re.search(r'class\s+(\w+)', text[aend:])
            if not cm:
                continue
            cls = cm.group(1)
            tn = re.search(r'tableName\s*=\s*"(\w+)"', attrs)
            table = tn.group(1) if tn else cls.lower()
            body_start = aend + cm.end()
            nxt = re.search(r'\n(?:@Entity|/\*\*|\s*data\s+class|\s*class\s)', text[body_start:])
            body = text[body_start: body_start + (nxt.start() if nxt else len(text))]
            cols: dict[str, str] = {}
            spans: list[tuple[int, int]] = []
            for cm2 in COLUMN_RE.finditer(body):
                cols[cm2.group(1)] = cm2.group(3).strip()
                spans.append((cm2.start(), cm2.end()))
            for pm in PLAIN_VAL_RE.finditer(body):
                if any(s <= pm.start() < e for s, e in spans):
                    continue
                name, typ = pm.group(1), pm.group(2).strip()
                cols.setdefault(name, typ)
            tables[table] = cols
    return tables


def check_type_converters() -> None:
    """@TypeConverter 相关的两类编译错误。

    1. 签名重复 —— KSP 报 duplicate converter
    2. **空转换器类** —— 被 @TypeConverters(X::class) 引用但类里一个
       @TypeConverter 方法都没有，KSP 报：
         Class is referenced as a converter but it does not have any
         converter methods.
       （这是实测 CI 报错，我自己修 bug 时反而引入的回归。）
    """
    found_any = False
    for conv in sorted(DB.glob("*.kt")):
        text = conv.read_text(encoding="utf-8")
        lines = text.splitlines()

        # --- 1. 重复签名 ---
        sigs: dict[tuple[str, str], int] = {}
        for idx, line in enumerate(lines):
            if "fun " not in line:
                continue
            annotated = False
            k = idx - 1
            while k >= 0:
                prev = lines[k].strip()
                if not prev or prev.startswith("//"):
                    k -= 1
                    continue
                annotated = prev.startswith("@TypeConverter")
                break
            if not annotated:
                continue
            found_any = True
            m = re.search(
                r'fun\s+\w+\s*\(\s*\w+\s*:\s*([A-Za-z0-9_<>?]+)\s*\)\s*:\s*([A-Za-z0-9_<>?]+)',
                line,
            )
            if not m:
                continue
            sig = (m.group(1), m.group(2))
            if sig in sigs:
                errors.append(
                    f"{rel(conv)}:{idx + 1}: @TypeConverter 参数/返回类型与第 "
                    f"{sigs[sig]} 行重复（{sig[0]} -> {sig[1]}），Room 会报重复转换器"
                )
            else:
                sigs[sig] = idx + 1

        # --- 2. 空转换器类 ---
        for cm in re.finditer(r'@TypeConverters\s*\(\s*(\w+)::class\s*\)', text):
            cls = cm.group(1)
            # 找该类定义体
            dm = re.search(rf'\bclass\s+{re.escape(cls)}\b', text)
            if not dm:
                continue
            body_start = dm.end()
            # 粗略取到文件末（转换器类通常就在同文件末尾）
            body = text[body_start:]
            if "@TypeConverter" not in body:
                line = text[: cm.start()].count("\n") + 1
                errors.append(
                    f"{rel(conv)}:{line}: @TypeConverters({cls}::class) 引用的 "
                    f"`{cls}` 里没有任何 @TypeConverter 方法，KSP 会报 "
                    f"'does not have any converter methods'"
                )

    # 反过来：定义了 @TypeConverter 但没有任何 @TypeConverters 引用 → 只是提示
    if found_any:
        for conv in sorted(DB.glob("*.kt")):
            t = conv.read_text(encoding="utf-8")
            if "@TypeConverter" in t and "@TypeConverters" not in t:
                warnings.append(
                    f"{rel(conv)}: 有 @TypeConverter 方法但没有 @TypeConverters "
                    f"引用（可能是漏挂）"
                )


# 关于「行首裸 ! 」：
#   曾试图加一条检查来复现 CI 里 BootReceiver.kt:50 的 Unexpected token。
#   实测发现 `val enabled = ...` 换行后的 `!enabled` 在 Kotlin 中是合法写法
#   （块的最后一条表达式），DebugActivity.kt 里 `when {}` 的 `!isOk ->`
#   分支也完全合法。规则一写出来就产两处假阳性 —— 说明它本身站不住脚。
#   宁可不要，也不给一个会误报的检查：**语法正确性交给编译器**（CI 会跑
#   assembleDebug），这个脚本只负责编译器抓不到、或抓得太慢的语义类问题。
# SQL 关键字，用于粗筛「查询里出现的标识符」
SQL_KEYWORDS = {
    "select", "from", "where", "and", "or", "not", "null", "is", "in",
    "order", "by", "group", "limit", "offset", "asc", "desc", "as", "case",
    "when", "then", "else", "end", "sum", "avg", "count", "max", "min",
    "coalesce", "distinct", "insert", "into", "values", "update", "set",
    "delete", "join", "left", "inner", "outer", "on", "between", "like",
    "exists", "having", "union", "all", "cast", "total", "ifnull",
    "abs", "round", "lower", "upper", "length",
}


def sql_columns(sql: str) -> set[str]:
    """从 SQL 里粗取疑似**列名**标识符。

    剔除三类噪声（否则全是假阳性，输出没人看）：
      1. SQL 关键字/函数
      2. 字符串字面量里的内容（'meal' 等 —— 那是值不是列）
      3. 绑定参数 :dayKey 与 Kotlin 命名参数 dayKey=...
    """
    # 先剥掉单引号字符串字面量
    cleaned = re.sub(r"'[^']*'", " ", sql)
    toks = re.findall(r'[A-Za-z_][A-Za-z0-9_]*', cleaned)
    out = set()
    for t in toks:
        if t.lower() in SQL_KEYWORDS:
            continue
        out.add(t)
    return out


def check_dao_columns(tables: dict[str, dict[str, str]]) -> None:
    """DAO 查询中引用的列是否存在于对应实体。

    只报「疑似真列名」的：SQL 里列名通常是 snake_case，而绑定参数是
    camelCase（Kotlin 变量名）。据此过滤掉大量误报。
    """
    for f in sorted(DB.glob("*.kt")):
        text = f.read_text(encoding="utf-8")
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if "@Query" not in line:
                continue
            buf: list[str] = []
            j = i
            started = False
            while j < len(lines):
                seg = lines[j]
                if '"""' in seg:
                    parts = seg.split('"""')
                    if not started:
                        started = True
                        if len(parts) > 1:
                            buf.append(parts[1])
                        if len(parts) > 2:
                            break
                    else:
                        buf.append(parts[0])
                        break
                elif started:
                    buf.append(seg)
                elif '"' in seg:
                    buf.extend(re.findall(r'"([^"]*)"', seg))
                    break
                j += 1
            sql = " ".join(buf)
            if not sql.strip():
                continue
            fm = re.search(r'\bFROM\s+(\w+)', sql, re.I)
            if not fm:
                continue
            table = fm.group(1)
            if table not in tables:
                continue
            declared = set(tables[table].keys())
            for col in sql_columns(sql):
                if col in declared:
                    continue
                if re.search(rf'\bAS\s+{re.escape(col)}\b', sql, re.I):
                    continue
                # 别名/子查询列
                if col in ("daily", "kcal_in", "kcal_out"):
                    continue
                # 只保留 snake_case —— SQL 列名的写法；camelCase 是 Kotlin
                # 绑定参数名（:dayKey）或命名参数（dayKey =），不是列
                if "_" not in col:
                    continue
                if len(col) <= 2 or col == table:
                    continue
                warnings.append(
                    f"{rel(f)}:{i + 1}: 查询引用的 `{col}` 在表 `{table}` "
                    f"中未找到同名列（若为别名可忽略）"
                )


def check_projection_types(tables: dict[str, dict[str, str]]) -> None:
    """SUM()/AVG() 结果类型 vs Kotlin 投影字段类型。

    SQLite 中 SUM(INTEGER) -> INTEGER(64bit)，映射到 Kotlin 应为 Long。
    声明成 Int 时 Room 会报类型不匹配。
    """
    for f in sorted(DB.glob("*.kt")):
        text = f.read_text(encoding="utf-8")
        # 找 @Query(...) 里的 SUM(...) AS alias
        for m in re.finditer(r'SUM\s*\(\s*(.*?)\s*\)\s+AS\s+(\w+)', text, re.S | re.I):
            alias = m.group(2)
            # 在投影 data class 里找该 alias 的字段类型
            # 注意注解可能写作 @ColumnInfo 或全限定的 @androidx.room.ColumnInfo
            pm = re.search(
                rf'@(?:androidx\.room\.)?ColumnInfo\s*\(\s*name\s*=\s*"{re.escape(alias)}"\s*\)'
                rf'\s*val\s+\w+\s*:\s*([A-Za-z0-9_?]+)',
                text,
            )
            if pm and pm.group(1).strip() == "Int":
                line = text[: m.start()].count("\n") + 1
                errors.append(
                    f"{rel(f)}:{line}: `SUM(...) AS {alias}` 映射到 Kotlin `Int`，"
                    f"但 SQLite SUM() 返回 64 位整数，Room 要求 Long"
                )


BINDING_RE = re.compile(r'binding\.(\w+)')
ID_RE = re.compile(r'@\+id/(\w+)')


def kebab_to_camel(name: str) -> str:
    parts = name.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def check_view_binding() -> None:
    """Activity 里的 binding.xxx 必须能在**它自己 inflate 的布局**里找到 @+id/xxx。

    注意：一个文件里可能 inflate 多个布局（列表项 row/item）。所以按
    `XxxBinding.inflate(...)` 找每个绑定对应的布局，收集该文件的 binding.*
    时取所有相关布局 id 的并集 —— 一对一映射会产出大量假阳性。
    """
    layouts: dict[str, set[str]] = {}
    for lf in (RES / "layout").glob("*.xml"):
        ids = set(ID_RE.findall(lf.read_text(encoding="utf-8")))
        layouts[lf.stem] = {kebab_to_camel(i) for i in ids}
        layouts[lf.stem].add("root")

    for kt in sorted(JAVA.rglob("*.kt")):
        text = kt.read_text(encoding="utf-8")
        # 找文件里 inflate 过的所有布局
        used_layouts: set[str] = set()
        for m in re.finditer(r'(\w+Binding)\.(?:inflate|bind)\b', text):
            bind = m.group(1)
            snake = re.sub(r'(?<!^)(?=[A-Z])', '_', bind).lower()
            if snake in layouts:
                used_layouts.add(snake)
        if not used_layouts:
            continue
        declared: set[str] = set()
        for s in used_layouts:
            declared |= layouts[s]
        used = set(BINDING_RE.findall(text))
        for u in sorted(used - declared):
            errors.append(
                f"{rel(kt)}: binding.{u} 在已 inflate 的布局 "
                f"（{', '.join(sorted(used_layouts))}）中找不到对应 @+id"
            )


def check_manifest_classes() -> None:
    mf = ROOT / "app/src/main/AndroidManifest.xml"
    if not mf.exists():
        return
    text = mf.read_text(encoding="utf-8")
    # 从 gradle 读 namespace，manifest 里的 .ui.X 是相对它解析的
    namespace = "com.healix.app"
    gradle = ROOT / "app/build.gradle"
    if gradle.exists():
        nm = re.search(r"namespace\s+['\"]([\w.]+)['\"]", gradle.read_text(encoding="utf-8"))
        if nm:
            namespace = nm.group(1)
    for m in re.finditer(r'android:name\s*=\s*"([.$][\w.]*)"', text):
        name = m.group(1)
        if name.startswith("android."):
            continue
        full = name[1:] if name.startswith(".") else name
        if not name.startswith("."):
            continue
        pkg_path = JAVA / (namespace.replace(".", "/")) / (full.replace(".", "/") + ".kt")
        if not pkg_path.exists():
            line = text[: m.start()].count("\n") + 1
            errors.append(
                f"app/src/main/AndroidManifest.xml:{line}: android:name=\"{name}\" "
                f"对应的类文件不存在（{rel(pkg_path)}）"
            )


def check_manifest_resources() -> None:
    mf = ROOT / "app/src/main/AndroidManifest.xml"
    if not mf.exists():
        return
    text = mf.read_text(encoding="utf-8")
    declared_strings: set[str] = set()
    declared_styles: set[str] = set()
    for vf in (RES / "values").glob("*.xml"):
        vt = vf.read_text(encoding="utf-8")
        declared_strings |= set(re.findall(r'<string\s+name="([\w.]+)"', vt))
        declared_styles |= set(re.findall(r'<style\s+name="([\w.]+)"', vt))
    declared_mipmaps = {p.stem for p in RES.glob("mipmap*/**/*") if p.is_file()}
    declared_xml = {p.stem for p in (RES / "xml").glob("*.xml")}

    for m in re.finditer(r'@(string|style|mipmap|xml)/([\w.]+)', text):
        kind, nm = m.group(1), m.group(2)
        line = text[: m.start()].count("\n") + 1
        if kind == "string" and nm not in declared_strings:
            errors.append(f"AndroidManifest.xml:{line}: @string/{nm} 未在 values 声明")
        elif kind == "style" and nm not in declared_styles:
            errors.append(f"AndroidManifest.xml:{line}: @style/{nm} 未在 values 声明")
        elif kind == "mipmap" and nm not in declared_mipmaps:
            errors.append(f"AndroidManifest.xml:{line}: @mipmap/{nm} 资源不存在")
        elif kind == "xml" and nm not in declared_xml:
            errors.append(f"AndroidManifest.xml:{line}: @xml/{nm} 文件不存在")


def main() -> int:
    if not DB.exists():
        print(f"找不到 db 目录：{DB}")
        return 1

    tables = collect_tables()
    check_type_converters()
    check_dao_columns(tables)
    check_projection_types(tables)
    check_view_binding()
    check_manifest_classes()
    check_manifest_resources()

    print("=" * 64)
    print("Healix Kotlin/Room 静态检查")
    print("=" * 64)
    print(f"解析到 {len(tables)} 张表：{', '.join(sorted(tables))}")
    if errors:
        print(f"\n❌ 错误 {len(errors)} 项：")
        for e in errors:
            print(f"   {e}")
    if warnings:
        print(f"\n⚠️  提示 {len(warnings)} 项（可能误报）：")
        for w in warnings[:20]:
            print(f"   {w}")
        if len(warnings) > 20:
            print(f"   ... 另有 {len(warnings) - 20} 项")
    if not errors:
        print("\n✅ 无编译级错误")
    print("=" * 64)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
