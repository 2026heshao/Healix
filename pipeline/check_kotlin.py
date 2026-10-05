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
  8. `AlertDialog.Builder.setItems()` 首参传 List<String>（只接受 Array）
  9. settings 键名不得写裸字符串，必须引用 SettingsKeys
 10. 游戏化词汇（streak / 打卡 / 归零 / 勋章 …）
 11. pipeline/contract.py ↔ Kotlin 的 prompt 必须**字节一致**
 12. `object` / `companion object` 成员的作用域 —— 出了宿主就必须限定引用
     （2026-10-03 新增：CI #21 的 4 个错误里 3 个是这条，而前 11 条一条没报，
      因为它们全是「单行正则」，不做作用域分析）
 13. 同名同值的 `const val` 跨文件重复定义（提示收敛）

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


# ⚠️ 两个负向后顾都是被假阳性逼出来的（2026-10-03 本检查首次真正生效时暴露）：
#   (?<![\w.]) 排除 `import com.healix.app.databinding.XxxBinding` ——
#              包名里的 "databinding." 让 `binding.XxxBinding` 成了子串，每个 import
#              都被当成一次属性访问，于是所有文件狂报。
#   (?<!::)    排除 Kotlin 委托的标准写法 `if (::binding.isInitialized)` ——
#              "isInitialized" 是语言关键字不是布局 id。
BINDING_RE = re.compile(r'(?<![\w.])(?<!::)binding\.(\w+)')
ID_RE = re.compile(r'@\+id/(\w+)')


def kebab_to_camel(name: str) -> str:
    parts = name.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def check_view_binding() -> None:
    """Activity 里的 binding.xxx 必须能在**它自己 inflate 的布局**里找到 @+id/xxx。

    注意：一个文件里可能 inflate 多个布局（列表项 row/item）。所以按
    `XxxBinding.inflate(...)` 找每个绑定对应的布局，收集该文件的 binding.*
    时取所有相关布局 id 的并集 —— 一对一映射会产出大量假阳性。

    ⚠️ 2026-10-03 修复：本检查此前**从未生效过**。原因是把绑定类名
    `ActivityMainBinding` 直接转 snake 得到 `activity_main_binding`，
    而布局文件名是 `activity_main` —— 两者永不相等，于是每个文件都在
    `if not used_layouts: continue` 处被静默跳过，注入坏例（`binding.zzz…`）
    也照样报「无编译级错误」。检查器静默失效比不检查更糟：它给人「有防线」的错觉。
    修法是先去掉 `Binding` 后缀再转 snake。
    """
    layouts: dict[str, set[str]] = {}
    for lf in (RES / "layout").glob("*.xml"):
        ids = set(ID_RE.findall(lf.read_text(encoding="utf-8")))
        layouts[lf.stem] = {kebab_to_camel(i) for i in ids}
        layouts[lf.stem].add("root")

    for kt in sorted(JAVA.rglob("*.kt")):
        text = kt.read_text(encoding="utf-8")
        # ⚠️ 用去注释后的文本，否则注释里举例写的 binding.xxx 会被当成真代码
        #    （strip_comments 的由来就是这么被逼出来的，见其 docstring）。
        code = strip_comments(text)
        # 找文件里 inflate / bind 过的所有布局，以及作为参数类型声明的绑定
        # （adapter 的 `class X(private val binding: ItemEventBinding)` 没有
        #  inflate 调用，只认 inflate 会漏掉它手里的 binding.xxx）。
        used_layouts: set[str] = set()
        for m in re.finditer(
            r'(\w+Binding)\.(?:inflate|bind)\b|:\s*(\w+Binding)\b', code
        ):
            bind = m.group(1) or m.group(2)
            stem = bind[: -len("Binding")] if bind.endswith("Binding") else bind
            snake = re.sub(r'(?<!^)(?=[A-Z])', '_', stem).lower()
            if snake in layouts:
                used_layouts.add(snake)
        if not used_layouts:
            continue
        declared: set[str] = set()
        for s in used_layouts:
            declared |= layouts[s]
        used = set(BINDING_RE.findall(code))
        for u in sorted(used - declared):
            errors.append(
                f"{rel(kt)}: binding.{u} 在已 inflate 的布局 "
                f"（{', '.join(sorted(used_layouts))}）中找不到对应 @+id"
            )


def strip_comments(text: str) -> str:
    """去掉 // 行注释与 /* */ 块注释，避免把注释里的示例代码当成真代码。

    ⚠️ 这条函数是被假阳性逼出来的：我在 EventText.kt 的注释里写了
    「千万不要写 `val R = ...`」来说明坑，结果检查器把注释也扫了，
    于是「已经修好的文件」继续报错。检查器扫注释 = 自己骗自己。
    """
    # 块注释
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    # 行注释（避免误伤字符串里的 //，这里简单处理：只匹配行首或空白后的 //）
    out = []
    for line in text.splitlines():
        # 找不在字符串里的 //：粗略做法 —— 先按引号切分
        in_str = False
        cut = None
        i = 0
        while i < len(line):
            ch = line[i]
            if ch == '"':
                in_str = not in_str
            elif not in_str and ch == '/' and i + 1 < len(line) and line[i + 1] == '/':
                cut = i
                break
            i += 1
        out.append(line if cut is None else line[:cut])
    return "\n".join(out)


def code_lines(text: str) -> list[str]:
    """先去注释再分行 —— 所有基于行的检查都应使用这个。"""
    return strip_comments(text).splitlines()


def check_shadowed_R() -> None:
    """禁止 `val R = com.healix.app.R` 这类遮蔽。

    实测 CI 报错：
      Classifier 'class R : Any' does not have a companion object,
      so it cannot be used as an expression
      Unresolved reference 'string'
    根因就是局部 val 名叫 R，把生成的 R 类遮蔽了。R 是保留名，谁都不许当变量。
    """
    pattern = re.compile(r'\b(?:val|var)\s+R\s*=')
    for kt in sorted(JAVA.rglob("*.kt")):
        for lineno, line in enumerate(code_lines(kt.read_text(encoding="utf-8")), 1):
            if pattern.search(line):
                errors.append(
                    f"{rel(kt)}:{lineno}: 用 `R` 当局部变量名，会遮蔽生成的 R 类，"
                    f"导致 `R.string.xxx` 解析失败。改用其它名字"
                )


def check_set_items_argument() -> None:
    """`AlertDialog.Builder.setItems(...)` 只接受 **Array<CharSequence>**，
    不接受 List<String>。

    实测 CI 报错（run#8）：
      SettingsActivity.kt:241:14 None of the following candidates is applicable:
      fun setItems(p0: Int, p1: DialogInterface.OnClickListener!)
      fun setItems(p0: (Array<CharSequence!>..Array<out CharSequence!>?),
                   p1: DialogInterface.OnClickListener!)
      SettingsActivity.kt:241:34 Cannot infer type for this parameter.
    `providerNames()` 返回 List<String> 直接传给 setItems → 候选全不适用；
    链式调用后 `setNegativeButton` 也随之解析失败（返回类型未定）。

    判据（**跨文件**收集，因为 `vm.providerNames()` 定义在 ViewModel 里）：
      a) `.setItems( 标识符 )` 且该标识符在**全工程**由 `fun x(): List<String>` 定义
      b) `.setItems( listOf(...) )`
    另识别 `val x = vm.y()` 这种「先落变量再传入」的中转形态。
    """
    ret_list_re = re.compile(r'fun\s+(\w+)\s*\([^)]*\)\s*:\s*List<\s*String\s*>')
    # 本地变量 = 某个返回 List<String> 的调用；`listOf(...)` 本身即 List
    alias_re = re.compile(r'\bval\s+(\w+)\s*=\s*[\w.]*?(\w+)\s*\(')
    # 已转成数组的变量（.toTypedArray() / .toArray(...)）—— 不再算 List
    to_array_re = re.compile(r'\bval\s+(\w+)\s*=[^=]*\.to(?:Typed)?Array\s*\(')

    list_funcs: set[str] = set()
    for kt in sorted(JAVA.rglob("*.kt")):
        list_funcs |= set(ret_list_re.findall(kt.read_text(encoding="utf-8")))
    # 无参调用才会整体是 List；带参调用（如 put(k,v)）不算，除非是已知函数名。
    # `listOf` 自身即构造 List，恒算。
    list_funcs |= {"listOf", "mutableListOf", "listOfNotNull"}

    call_re = re.compile(r'\.setItems\s*\(\s*([A-Za-z_]\w*|listOf\s*\()')

    for kt in sorted(JAVA.rglob("*.kt")):
        text = kt.read_text(encoding="utf-8")
        # 本文件里「值为 List<String>」的局部变量名：来自已知函数、或 listOf 且无参
        list_vars: set[str] = set()
        for var, fn in alias_re.findall(text):
            if fn in list_funcs:
                # 判断是调用是否带参：listOf( 恒算；其它需 0 参
                if fn in ("listOf", "mutableListOf", "listOfNotNull"):
                    list_vars.add(var)
                elif re.search(re.escape(fn) + r'\s*\(\s*\)', text):
                    list_vars.add(var)
        # 已经 toTypedArray() 的变量从嫌疑名单里剔除（同一名字被重新赋值为数组）
        list_vars -= set(to_array_re.findall(text))
        for lineno, line in enumerate(code_lines(text), 1):
            m = call_re.search(line)
            if not m:
                continue
            arg = m.group(1).strip()
            bad = False
            reason = ""
            if arg.startswith("listOf"):
                bad, reason = True, "`listOf(...)` 返回 List"
            elif arg.endswith("()"):
                fname = arg[:-2].split(".")[-1]
                if fname in list_funcs:
                    bad, reason = True, f"`{fname}()` 返回 List<String>"
            elif arg in list_vars:
                bad, reason = True, f"局部变量 `{arg}` 来自返回 List<String> 的调用"
            if bad:
                errors.append(
                    f"{rel(kt)}:{lineno}: setItems() 首参传了 List<String>"
                    f"（{reason}），它只接受 Array<CharSequence>。"
                    f"改用 `.toTypedArray()`；否则会产生 "
                    f"「None of the following candidates is applicable」"
                    f"并连带使后续 setNegativeButton 解析失败"
                )


# 识别 `fun xxx(` / `suspend fun xxx(` 定义
FUN_DEF_RE = re.compile(r'^\s*(?:@\w+\s+)*(?:internal\s+|private\s+|public\s+|protected\s+)?'
                        r'(suspend\s+)?fun\s+(?:<[^>]*>\s*)?(\w+)\s*\(')


def collect_suspend_functions() -> set[str]:
    """全工程里 suspend 函数名 —— 但**排除**任何地方有非 suspend 同名定义的。

    ⚠️ 假阳性教训：最初只收集「叫这个名字的 suspend 函数」，结果
    `JSONObject.put()`、`PlanReviewViewModel.reload()`（本身是普通函数，
    只是内部 launch 了协程）都被误报。原因是**同名不同签名的函数存在**。
    折中：只要该名字在工程里出现过**非 suspend** 定义，就不作为判据。
    """
    suspend_names: set[str] = set()
    plain_names: set[str] = set()
    for kt in JAVA.rglob("*.kt"):
        for line in code_lines(kt.read_text(encoding="utf-8")):
            m = FUN_DEF_RE.match(line)
            if not m:
                continue
            if m.group(1):
                suspend_names.add(m.group(2))
            else:
                plain_names.add(m.group(2))
    return suspend_names - plain_names


# 接收者指向「挂起源」的判据：DAO / Repository / 数据库句柄。
_SUSPEND_RECEIVER_RE = re.compile(r'(?:[Dd]ao|Repository|repository|database|\bdb\b)')


def _calls_suspend(body: str, name: str) -> bool:
    r"""`body` 里是否有对挂起函数 `name` 的调用。两种合法形态，缺一不可：

    (A) **无接收者的裸调用** `name(` —— 同文件 / 顶层 / 成员挂起函数；
    (B) **带接收者且接收者指向挂起源**的调用 `receiver.name(` ——
        `db.eventDao().listInRange(...)`、`container.eventRepository.latestByType(...)`。

    ⚠️ 为什么 (B) 必须限定接收者：名字能进 `suspend_fns`，只说明**全工程只有
       suspend 版本**（`collect_suspend_functions` 已排除任何非 suspend 同名定义）；
       但**标准库同名**（`list.find{}` / `map.remove(k)` / `LocalDate.parse(s)`）
       在 body 里以裸名出现，接收者却是 list / map / LocalDate。若放宽到「任意
       接收者」，实测会一次生成 **15 条误报**，把真信号淹掉 —— 所以按接收者收敛。

    ⚠️ 为什么 (A) 的后顾是 `(?<![\w.])`（**排除** `.` 前缀）：无接收者的裸调用
       其前一个字符不该是 `.`（那是 (B) 的形态），也不该是单词字符
       （避免 `alistInRange(` 这类粘连）。

    ⚠️ 历史教训（静默失效）：本函数的前身把带 `.` 的调用**整体排除**，导致对
       真实 CI 报错的同一段代码（`settingsDao().get` / `eventDao().listInRange`）
       完全静默 —— 检查器存在却零覆盖，比没有更糟。
    """
    if re.search(rf'(?<![\w.]){re.escape(name)}\s*\(', body):
        return True
    for m in re.finditer(rf'([A-Za-z_][A-Za-z0-9_.()]*?)\.{re.escape(name)}\s*\(', body):
        if _SUSPEND_RECEIVER_RE.search(m.group(1)):
            return True
    return False


def check_suspend_calls() -> None:
    """在**非 suspend 函数体**里直接调用 suspend 函数 = 编译错误。

    实测 CI 报错：
      Suspend function 'suspend fun raw(key: String): String?' should be
      called only from a coroutine or another suspend function
    做法：逐文件、逐函数块地判断「当前函数是否 suspend」，
    若否，则检查块内是否出现已知 suspend 函数名的调用。
    这是启发式，对同名函数可能误报，因此放宽到「仅当函数名唯一且确实是
    suspend 时才报」。
    """
    suspend_fns = collect_suspend_functions()
    if not suspend_fns:
        return

    for kt in sorted(JAVA.rglob("*.kt")):
        lines = code_lines(kt.read_text(encoding="utf-8"))
        # 建立「每个函数体的行区间 + 是否 suspend」
        stack: list[dict] = []
        for idx, line in enumerate(lines):
            m = FUN_DEF_RE.match(line)
            if m:
                # ⚠️ 只处理**有函数体**的函数（含 `{`）。
                #    interface 里的 `fun x(): Y` 是声明，没有体 ——
                #    之前把 DAO 接口的声明也当成函数体，导致 obverseSession
                #    这类完全无关的相邻方法被误判为「调用了 suspend」。
                if "{" not in line:
                    continue
                stack.append({
                    "name": m.group(2),
                    "suspend": bool(m.group(1)),
                    "start": idx,
                    "depth": line.count("{") - line.count("}"),
                })
                continue
            if not stack:
                continue
            stripped = line.strip()
            stack[-1]["depth"] += stripped.count("{") - stripped.count("}")
            if stack[-1]["depth"] <= 0:
                fn = stack.pop()
                if fn["suspend"]:
                    continue
                body = "\n".join(lines[fn["start"]: idx + 1])
                # 函数体内若已有协程作用域，调用可能是安全的 → 跳过
                # ⚠️ 必须含 `runBlockingSafe`（项目自定义的作用域包装，见
                #    ui/runBlockingSafe.kt）：否则 `buildJson = runBlockingSafe { ... }`
                #    被误判成"非挂起函数直调 suspend"（实测 ExportWriter 报 3 条误报）。
                #    注意 `\brunBlocking\b` **不覆盖** `runBlockingSafe`
                #    （后者的 `runBlocking` 后紧跟 `S`，不构成词边界）。
                if re.search(
                    r'\b(launch|withContext|async|runBlockingSafe|runBlocking'
                    r'|suspendCoroutine)\b',
                    body,
                ):
                    continue
                for sf in suspend_fns:
                    if sf == fn["name"]:
                        continue
                    if _calls_suspend(body, sf):
                        errors.append(
                            f"{rel(kt)}:{fn['start'] + 1}: 非 suspend 函数 "
                            f"`{fn['name']}` 里调用了 suspend 函数 `{sf}()`，"
                            f"编译会报 should be called only from a coroutine"
                        )


def check_undefined_self_calls() -> None:
    """类内调用了「本类里并不存在、也不是已知外部符号」的方法名。

    专治实测踩到的那类错：写了 `recordCall(...)` 但类里定义的是 `logCall`。
    只检查**无接收者**的调用 `foo(`（没有 `.` 前缀），这类必然是本类/
    同文件的函数，最容易因为改名漏改而断链。
    """
    # 收集全工程已知的顶层/成员函数名（粗粒度，宁可少报）
    known: set[str] = set()
    for kt in JAVA.rglob("*.kt"):
        text = kt.read_text(encoding="utf-8")
        known |= set(re.findall(r'\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(', text))
    # Kotlin 标准库 / 常见内置，避免误报
    builtins = {
        "listOf", "mapOf", "setOf", "arrayOf", "buildList", "buildString",
        "mutableListOf", "mutableMapOf", "mutableSetOf", "emptyList", "emptyMap",
        "require", "check", "requireNotNull", "checkNotNull", "error",
        "println", "print", "TODO", "lazy", "let", "run", "with", "apply", "also",
        "synchronized", "runCatching", "repeat", "if", "for", "while", "when",
        "super", "this", "return", "throw", "catch", "fun", "val", "var",
        "getOrNull", "getValue", "getOrDefault", "toString", "hashCode",
        "equals", "copy", "take", "takeLast", "drop", "filter", "map", "forEach",
        "count", "first", "firstOrNull", "last", "lastOrNull", "any", "all",
        "none", "isNotEmpty", "isEmpty", "isNotBlank", "isBlank", "trim",
        "split", "joinToString", "sorted", "sortedBy", "toSet", "toList",
        "toMutableList", "add", "addAll", "put", "remove", "insert", "update",
        "find", "indexOf", "substring", "format", "toLong", "toDouble", "toInt",
        "toFloat", "orEmpty", "uppercase", "lowercase", "capitalize", "contains",
        "startsWith", "endsWith", "replace", "also", "apply", "let", "also",
        "withContext", "launch", "async", "await", "delay", "suspendCoroutine",
        "getString", "getColor", "setContentView", "findViewById", "setText",
        "setOnClickListener", "setPadding", "setSelection", "inflate", "build",
        "create", "start", "stop", "reload", "show", "dismiss", "log",
        "startForeground", "stopSelf", "round", "abs", "max", "min", "mutableMapOf",
        "onRetry", "notify", "cancel", "buildString", "getSystemService",
        # 适配器回调属性名（构造参数 lambda，非本文件 fun）。与 onRetry 同源：
        # 一旦同文件出现 onDestroyView / onRetry 等，前缀启发式会误报。
        "onDelete", "onEdit",
        # Fragment / Activity / Context 的框架方法（继承自基类，非本文件 fun）。
        # 与 onRetry 同源：同文件一旦出现 `requestFocusInput`（前缀 requ…），
        # `requireContext()` / `requireActivity()` 就会被前缀启发式误报成"改名漏改"。
        "requireContext", "requireActivity", "requireView", "requireParentFragment",
    }
    for kt in sorted(JAVA.rglob("*.kt")):
        text = strip_comments(kt.read_text(encoding="utf-8"))
        local = set(re.findall(r'\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(', text))
        for m in re.finditer(r'(?<![\w.])([a-z]\w{3,})\s*\(', text):
            name = m.group(1)
            if name in local or name in known or name in builtins:
                continue
            # 只报「看起来像本类方法」的：同一文件里已定义了同前缀的其它方法
            if not any(f.startswith(name[:4]) for f in local):
                continue
            line = text[: m.start()].count("\n") + 1
            warnings.append(
                f"{rel(kt)}:{line}: 调用了 `{name}()`，但本文件内未定义该方法"
                f"（可能改过名但漏改调用点）"
            )


def check_no_gamification() -> None:
    """禁止把"连续"变成**可累积、可失去的机制**（PRD 硬规则 `R5` / 规范 §9.12）。

    ══════════════════════════════════════════════════════════════════════════
    为什么必须针对"机制"而不是字面词
    ══════════════════════════════════════════════════════════════════════════
    「连续 3 天睡不到 6 小时」**是允许的** —— 它只是描述数据本身，
    是健康提示；被禁止的是 streak 那一套：可累积、会归零、给奖励、能排行。

    因此这里**不能简单 grep "连续"** —— 那会把文案与规则条件全部误报成违规，
    检查器一有误报就没人看了。只拦下面这些**只有游戏化机制才会出现的标识符**：

      机制名：streak / consecutiveDays / 连续天数 / 连续打卡 / 打卡天数 / 打卡日历
      惩罚项：归零 / reset streak
      奖励项：勋章 / 成就 / 积分 / 等级 / 评分环 / 热力图 / 排行 / 排行榜

    依据（PRD §10.2）：UCL 研究显示 streak 断掉后用户连行为一起放弃；
    二元思维者首次失败后放弃率 3.2 倍。"MyFitnessPal 连续 100 天，错过一天就归零"
    是用户原话里最典型的流失原因。
    """
    # ⚠️ 只放"只有游戏化才会用"的词。像 `level`（body_signals 的 info/notice/alert
    #    也叫 level）、`score`（调试页有得分语义风险）这类通用词**故意不放**，
    #    否则必然误报 —— 宁可少拦，也不留会误报的检查。
    forbidden = [
        "streak",
        "Streak",
        "consecutiveDay",
        "打卡",
        "归零",
        "勋章",
        "成就",
        "积分",
        "评分环",
        "热力图",
        "排行榜",
        "achievement",
        "Achievement",
    ]

    for kt in sorted(JAVA.rglob("*.kt")):
        for lineno, line in enumerate(code_lines(kt.read_text(encoding="utf-8")), 1):
            for word in forbidden:
                if word in line:
                    errors.append(
                        f"{rel(kt)}:{lineno}: 出现 `{word}` —— 命中 PRD R5 禁用机制"
                        f"（连续打卡 / 归零 / 勋章 / 成就 / 积分 / 排行）。"
                        f"注意：「连续 3 天睡不到 6 小时」这类**描述数据**的文案是允许的，"
                        f"禁止的是可累积、可失去的机制"
                    )


def check_settings_keys() -> None:
    """settings 表键名的一致性检查。

    ══════════════════════════════════════════════════════════════════════════
    为什么必须有这条检查（2026-10-03 真实事故）
    ══════════════════════════════════════════════════════════════════════════
    设置页（写端）用 `base_url` / `model` / `retry_max` / `daily_quota`，
    而事件仓库（读端）用 `provider_base_url` / `provider_model` /
    `retry_max_retries` / `quota_daily_call_limit`。

    两边都是**合法字符串字面量** —— kotlinc 不报错、KSP 不报错、单测覆盖不到。
    唯一的表现是：设置页填好配置、点「测试连通性」也能过（因为测试走的是
    写端自己的内存值），但用户真正「记一笔」时读端拿到 null，
    直接 markFailed，**一次 HTTP 请求都没发出去**。

    这类 bug 的排查成本极高（"测试能过、实际不能用"），
    因此必须在静态检查层拦死。

    检查策略：
      1. 收集所有形如 `const val KEY_XXX = "some_key"` 的定义
      2. 若同一个字面量值被多个 KEY_XXX 名字引用，且名字看起来是"同一语义的
         不同拼法"（如 KEY_BASE_URL 与 KEY_PROVIDER_BASE_URL），报警
      3. 更直接地：检查 `SettingsKeys` 是否是唯一赋值来源 ——
         若某文件里出现裸字符串键名（不在 SettingsKeys.kt 内），报警

    第 3 条是主检查，因为它能精确拦住"新写了一处裸字符串"。
    """
    settings_keys = JAVA / "com/healix/app/db/SettingsKeys.kt"
    if not settings_keys.exists():
        errors.append(
            "找不到 app/src/main/java/com/healix/app/db/SettingsKeys.kt —— "
            "settings 键名的唯一事实来源必须存在"
        )
        return

    # SettingsKeys.kt 里声明的所有键名字面量（允许裸字符串）
    sk_text = settings_keys.read_text(encoding="utf-8")
    declared: dict[str, str] = {}  # 字面量 -> 常量名
    for m in re.finditer(r'const val (\w+)\s*=\s*"([^"]+)"', sk_text):
        declared[m.group(2)] = m.group(1)

    # 反向索引：常量名 -> 字面量。b2 分支要用"常量名"比对，
    # 而 declared 是以字面量为键的，必须另建一份，否则会查不到。
    by_name: dict[str, str] = {v: k for k, v in declared.items()}

    # 扫描所有其它 .kt，找出 settings 相关的裸字符串键名 / 第二套命名空间
    known_keys = set(declared.keys())

    # 「这个文件在操作 settings 表」的判定信号
    touches_settings = re.compile(
        r'settingsDao\(\)|SettingsDao|\bsettings\s*[:=]|settings\.(?:get|put|remove)'
    )

    for kt in sorted(JAVA.rglob("*.kt")):
        if kt == settings_keys:
            continue
        text = kt.read_text(encoding="utf-8")

        # (a) 直接以裸字符串访问 settings
        for m in re.finditer(
            r'(?:settingsDao\(\)\.(?:get|observe)|settings\.(?:get|put|remove))\s*\(\s*"([^"]+)"',
            text,
        ):
            literal = m.group(1)
            line = text[: m.start()].count("\n") + 1
            if literal in declared:
                errors.append(
                    f"{rel(kt)}:{line}: settings 键名 \"{literal}\" 应改用 "
                    f"SettingsKeys.{declared[literal]}，不要写裸字符串"
                )
            else:
                errors.append(
                    f"{rel(kt)}:{line}: settings 键名 \"{literal}\" 未在 SettingsKeys 中声明"
                )

        # (b) 声明了 KEY_* 常量，其值"看起来是 settings 键名"但与 SettingsKeys
        #     的命名不一致 —— 这是**原始事故的精确形态**：
        #     EventRepository 里 `const val KEY_BASE_URL = "provider_base_url"`，
        #     而 SettingsKeys 里 `BASE_URL = "base_url"`。
        #
        #     判定条件（三选一即报警）：
        #       b1. 字面量已是 SettingsKeys 声明的键名 → 明显是别名，报警
        #       b2. 该文件确实在操作 settings 表，且常量名（去掉 KEY_ 前缀后）
        #           与某个 SettingsKeys 常量名相同/高度相似，但字面量不同
        #           → 同一语义被写成了两个不同的键名
        #     只对"确实碰 settings 的文件"做 b2，避免误伤
        #     RemoteInput key / EditText 字段名等无关的 KEY_* 常量。
        for m in re.finditer(r'const val (KEY_\w+)\s*=\s*"([^"]+)"', text):
            name, literal = m.group(1), m.group(2)
            line = text[: m.start()].count("\n") + 1

            if literal in known_keys:
                if declared.get(literal) == name:
                    continue  # 允许同名常量（如 SettingsActivity 的 KEY_BASE_URL 直接转发）
                errors.append(
                    f"{rel(kt)}:{line}: 定义了 settings 键常量 {name} = \"{literal}\"，"
                    f"与 SettingsKeys.{declared[literal]} 指向同一键名但用了不同常量名"
                    f"（键名分裂 bug 的典型形态，应写 `const val {name} = "
                    f"SettingsKeys.{declared[literal]}`）"
                )
                continue

            # b2：只在文件确实操作 settings 时才比对"语义相似名"
            if not touches_settings.search(text):
                continue

            bare = name[len("KEY_"):]  # BASE_URL
            sk_literal = by_name.get(bare)
            if sk_literal is not None and sk_literal != literal:
                errors.append(
                    f"{rel(kt)}:{line}: 定义了 {name} = \"{literal}\"，"
                    f"但 SettingsKeys.{bare} = \"{sk_literal}\" —— "
                    f"同一语义有两个不同键名（读写两端会各读各的，"
                    f"这是 2026-10-03 事故的根因）。应改为 "
                    f"`const val {name} = SettingsKeys.{bare}`"
                )


def check_settings_keys_consistency() -> None:
    """读写两端引用的 SettingsKeys 常量是否指向同一字面量。

    纯逻辑推导，不依赖字符串扫描的完整性 —— 与 check_settings_keys 互补：
    前者查"有没有绕过 SettingsKeys"，后者查"SettingsKeys 内部有没有重名冲突"。
    """
    settings_keys = JAVA / "com/healix/app/db/SettingsKeys.kt"
    if not settings_keys.exists():
        return
    text = settings_keys.read_text(encoding="utf-8")

    seen: dict[str, str] = {}
    for m in re.finditer(r'const val (\w+)\s*=\s*"([^"]+)"', text):
        name, literal = m.group(1), m.group(2)
        if literal in seen:
            errors.append(
                f"SettingsKeys.kt: 键名字面量 \"{literal}\" 被 {seen[literal]} "
                f"与 {name} 重复使用（同一值两个常量名，极易写混）"
            )
        else:
            seen[literal] = name


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


# ── prompt 双处一致性 ────────────────────────────────────────────────────
# Python 侧（pipeline/contract.py）与 Kotlin 侧各有一份 prompt 副本。
# 这是刻意的：Python 侧用于离线自测与后处理层，Kotlin 侧是真正发给模型的字符串。
# 但两份**必须逐字节相同** —— 否则「本地自测通过」与「App 实际行为」会悄悄分叉，
# 而这种分叉不会报错，只会让模型表现变差，极难定位。
PROMPT_PARITY: list[tuple[str, str]] = [
    ("PROMPT_EXTRACT", "com/healix/app/parse/SchemaValidator.kt"),
    ("PROMPT_TRAINING", "com/healix/app/ui/TrainingPlanner.kt"),
]


def check_prompt_parity() -> None:
    """contract.py 里的 prompt 必须与 Kotlin 侧对应常量**逐字节一致**。

    2026-10-03 补：此前这个约束只写在文档与 memory 里，**没有任何自动检查**。
    本轮新增 PROMPT_TRAINING 时才发现 —— 手工比对过才知道是一致的。
    这类"文档里有、机器不验"的契约最容易腐坏，所以固化成检查。
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    try:
        import contract  # noqa: PLC0415
    except Exception as e:  # 防止 contract.py 自身语法错误把整个检查搞挂
        errors.append(f"无法导入 pipeline/contract.py（prompt 一致性检查跳过）：{e}")
        return

    for ver in ("PROMPT_VER", "PROMPT_VER_TRAINING"):
        if not hasattr(contract, ver):
            errors.append(f"pipeline/contract.py 缺少版本号常量 {ver}")

    for name, rel_path in PROMPT_PARITY:
        py_side = getattr(contract, name, None)
        kt_file = JAVA / rel_path
        if py_side is None:
            errors.append(f"pipeline/contract.py 缺少 {name}")
            continue
        if not kt_file.exists():
            errors.append(f"{rel_path} 不存在，但 contract.py 里有 {name}")
            continue
        text = kt_file.read_text(encoding="utf-8")
        m = re.search(
            rf'const val {name}\s*:\s*String\s*=\s*"""(.*?)"""', text, re.S
        )
        if not m:
            errors.append(
                f"{rel_path}: 找不到 `const val {name}: String = \"\"\"…\"\"\"`"
            )
            continue
        kt_side = m.group(1)
        if kt_side.encode("utf-8") != py_side.encode("utf-8"):
            errors.append(
                f"{rel_path}: {name} 与 pipeline/contract.py **不一致** "
                f"（Python {len(py_side.encode('utf-8'))} 字节 / "
                f"Kotlin {len(kt_side.encode('utf-8'))} 字节）—— "
                f"两处必须逐字相同，否则离线自测与 App 实际行为会静默分叉"
            )


# ── object / companion 作用域 ────────────────────────────────────────────
# ══════════════════════════════════════════════════════════════════════════
# 为什么需要「作用域分析」这一类检查（2026-10-03，CI #21 实况）
# ══════════════════════════════════════════════════════════════════════════
# CI #21 报的 4 个错误里有 3 个是同一条：
#   StatusDetailViewModel.kt:27 Unresolved reference 'DEFAULT_SESSIONS_PER_WEEK'
#
# `ExerciseSection` / `SleepSection` 是文件**开头**的顶层 data class，
# 默认参数里非限定引用了 `DEFAULT_SESSIONS_PER_WEEK`，而该常量定义在文件**末尾**
# 的 `StatusDetailViewModel.companion` 里。顶层声明的解析域里没有那个 companion
# → 编译不过。
#
# 本脚本当时已有 12 条检查，**一条都没报** —— 因为它们全是「单行正则」，
# 不做作用域判断。这类错误编译器一抓一个准，而我们本地没有编译器
# （无 JDK/SDK），所以必须自己补上。
#
# 规则：`object` 成员的**简单名**只在其宿主作用域内可非限定使用：
#   - `companion object` → 宿主 = 它所属的那个 class 体（含其中的嵌套类）
#   - 具名 `object X`    → 宿主 = 它自己的体
# 出了宿主作用域必须写 `X.member` / `Owner.member`，否则 Unresolved reference。
#
# 已知**不覆盖**的情形（刻意，避免误报）：
#   - 跨文件：`B.kt` 里非限定写 `A.kt` 中 object 的成员。要做对必须知道全工程
#     所有标识符（局部变量、参数、lambda 形参…），代价与假阳性都不划算。
#     这种错误编译器必报，且现有 check_undefined_self_calls 已覆盖一部分。
# ══════════════════════════════════════════════════════════════════════════

VAL_DECL_RE = re.compile(r'\b(?:const\s+)?val\s+(\w+)')
VAR_DECL_RE = re.compile(r'\bvar\s+(\w+)')
FUN_DECL_RE = re.compile(r'\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(')
TYPE_DECL_RE = re.compile(
    r'\b(?:data\s+|sealed\s+|enum\s+|annotation\s+|value\s+)?'
    r'(?:class|interface|object)\s+(\w+)'
)
OBJ_DECL_RE = re.compile(r'\b(companion\s+object|object)\s*(\w+)?')
IMPORT_RE = re.compile(r'^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$', re.M)
# 使用点前面若是声明关键字，说明这是**声明**而不是引用，跳过
DECL_BEFORE_RE = re.compile(r'(?:val|var|fun|class|object|interface|typealias)\s+$')


def _iter_code_chars(text: str):
    """产出 (下标, 字符)，**跳过**字符串字面量与注释内部。

    ⚠️ 不能复用 strip_comments：`PROMPT_EXTRACT` 是**多行原始字符串**，
    里面含 JSON 示例的 `{` `}` 与可能的 `//`。直接对原文做括号配平，
    字符串里的花括号会把结构算歪；而 strip_comments 又会误删原始字符串里的 `//`。
    这里统一把「非代码字符」涂白，配平与正则只看代码。
    """
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        ch = text[i]
        if ch in ('"', "'"):
            i += 1
            while i < n and text[i] != ch:
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue
        yield i, ch
        i += 1


def mask_noncode(text: str) -> str:
    """把字符串/注释内容换成空格（保留换行与下标），只留代码。"""
    out = ["\n" if c == "\n" else " " for c in text]
    for i, ch in _iter_code_chars(text):
        out[i] = ch
    return "".join(out)


def _depths_and_pairs(masked: str) -> tuple[list[int], dict[int, int]]:
    """返回 (每个下标的括号深度, {open 下标: close 下标})。

    深度的定义：某下标处**已打开但未闭合**的 `{` 个数。
    于是「某个 `{` 的直接内容」深度 = 该 `{` 处的深度 + 1。
    """
    dep = [0] * (len(masked) + 1)
    pairs: dict[int, int] = {}
    stack: list[int] = []
    d = 0
    for i, ch in enumerate(masked):
        if ch == "{":
            dep[i] = d
            stack.append(i)
            d += 1
        elif ch == "}":
            d -= 1
            dep[i] = d
            if stack:
                pairs[stack.pop()] = i
        else:
            dep[i] = d
    dep[len(masked)] = d
    return dep, pairs


def check_object_scope() -> None:
    """非限定引用 `object` / `companion object` 的成员 = Unresolved reference。"""
    for kt in sorted(JAVA.rglob("*.kt")):
        text = kt.read_text(encoding="utf-8")
        masked = mask_noncode(text)
        dep, pairs = _depths_and_pairs(masked)

        imports = IMPORT_RE.findall(masked)
        if any(sym == "*" for sym, _alias in imports):
            # 通配 import 下无法判断某个简单名从哪来 → 整文件跳过（宁少报）
            continue
        imported = set()
        for sym, alias in imports:
            imported.add(alias or sym.rsplit(".", 1)[-1])

        # 文件级（深度 0）声明 —— 顶层声明的简单名在整个文件都可见
        file_level: set[str] = set()
        for rx in (VAL_DECL_RE, VAR_DECL_RE, FUN_DECL_RE, TYPE_DECL_RE):
            for m in rx.finditer(masked):
                if dep[m.start()] == 0:
                    file_level.add(m.group(1))

        # 收集 (成员名, 允许非限定使用的作用域区间, 宿主类别, 声明行号)
        scopes: list[tuple[str, int, int, str, int]] = []
        for m in OBJ_DECL_RE.finditer(masked):
            is_companion = m.group(1).startswith("companion")
            obj_name = m.group(2)
            if not is_companion and not obj_name:
                continue  # 匿名对象 `object : Runnable { }` —— 跳过
            # ⚠️ `{` 必须与声明头**同一行**。
            #    否则「无体」的声明会越界抢括号 —— 实测误报：
            #    `object Idle : NetworkStatus`（无体）抢到了几十行后
            #    `sealed interface HomeStatus {` 的 `{`，于是 HomeStatus 的
            #    `data class Summary(val text: String)` 被当成 Idle 的成员，
            #    再在全文件报「非限定引用 text」3 处假阳性。
            #    无体的 object 本来就没有成员，跳过它不损失覆盖率。
            nl = masked.find("\n", m.end())
            b = masked.find("{", m.end())
            if b < 0 or (nl >= 0 and b > nl):
                continue
            if b not in pairs:
                continue
            body_close = pairs[b]
            body_depth = dep[b] + 1

            if is_companion:
                # 宿主 = 最近一个「在上一级深度打开」的 `{`，即所属 class 的体
                owner = None
                for o in sorted(pairs):
                    if o < b and dep[o] == dep[b] - 1 and pairs[o] > b:
                        owner = o
                if owner is None:
                    continue
                lo, hi = owner, pairs[owner]
                host = f"{obj_name or ''}companion object"
            else:
                lo, hi = b, body_close
                host = f"object {obj_name}"

            # 只取**体直接一层**的声明（函数体内的局部变量不算成员）
            for rx in (VAL_DECL_RE, VAR_DECL_RE, FUN_DECL_RE):
                for mm in rx.finditer(masked, b + 1, body_close):
                    if dep[mm.start()] == body_depth:
                        scopes.append(
                            (mm.group(1), lo, hi, host,
                             masked[:mm.start()].count("\n") + 1)
                        )

        if not scopes:
            continue

        # ── 同名成员消歧（2026-10-05）──────────────────────────────
        # 若**使用点所在的对象体**里也声明了同名成员，Kotlin 会解析到该对象的成员
        # （隐式接收者 `this` 的成员优先级高于外层/顶层声明）→ 这是合法引用，
        # **不是** Unresolved reference。
        # 典型误报：`object GoalSlots { val PRIMARY = ...; val ALL = listOf(PRIMARY) }`
        # 与 `object GoalMetrics { const val PRIMARY = "primary" }` 同名 →
        # 旧逻辑按"出 GoalMetrics 宿主作用域"报了 3 处假阳性。
        # 只跳过"使用点位于**声明了同名成员**的宿主体内"这一种情况：
        # 若使用点所在对象**没有**同名成员（真正的漏写 `Owner.`），仍照常报出。
        host_members: dict[tuple[int, int], set[str]] = {}
        for nm, hlo, hhi, _host, _ln in scopes:
            host_members.setdefault((hlo, hhi), set()).add(nm)

        def shadowed_by_enclosing(p: int, nm: str) -> bool:
            return any(
                hlo <= p <= hhi and nm in names
                for (hlo, hhi), names in host_members.items()
            )

        seen: set[tuple[str, int]] = set()
        for name, lo, hi, host, decl_line in scopes:
            if name in file_level or name in imported:
                continue
            use_re = re.compile(rf'(?<![\w.])(?<!::){re.escape(name)}\b')
            for um in use_re.finditer(masked):
                p = um.start()
                if lo <= p <= hi:
                    continue
                if shadowed_by_enclosing(p, name):
                    continue  # 使用点所在对象声明了同名成员 → 合法解析，跳过
                if DECL_BEFORE_RE.search(masked[max(0, p - 12): p]):
                    continue  # 这是同名声明本身，不是引用
                line = masked[:p].count("\n") + 1
                if (name, line) in seen:
                    continue
                seen.add((name, line))
                errors.append(
                    f"{rel(kt)}:{line}: 非限定引用了 `{name}`，但它声明在 "
                    f"{host}（第 {decl_line} 行）里，而此处已出宿主作用域 —— "
                    f"必须写 `Owner.{name}` / `X.{name}`，否则编译报 "
                    f"Unresolved reference（CI #21 的 3 个错误就是这一条）"
                )


def check_duplicate_constants() -> None:
    """**同名且同值**的 `const val` 在多个文件重复定义 → 提示收敛到一处。

    2026-10-03：`DEFAULT_SLEEP_H = 7.5` 同时存在于 SettingsViewModel 与
    StatusDetailViewModel；`DEFAULT_TARGET_KCAL = 2500` 甚至有 4 份
    （HealthAggregator / MainViewModel / TodaySummary / 设置页）。这类重复是
    「改了 A 忘了改 B」的温床 —— 本轮它就是**以编译错误的形式**连本带利还回来的。

    判据**刻意收紧**为「同名 **且** 同值」：
      - 只是同名（各类的 `TAG`）不算问题 —— 那些本来就该各自不同
      - 值不是字面量（如 `const val KEY_X = SettingsKeys.X`）不算问题 ——
        那是**故意**的转发别名，指向同一个来源
    """
    lit_re = re.compile(
        r'\bconst val (\w+)\s*(?::\s*[\w<>?.\s]+)?=\s*'
        r'("(?:[^"\\]|\\.)*"|[-+]?\d[\w.\s*+\-/]*?)\s*(?://[^\n]*)?$',
        re.M,
    )
    found: dict[tuple[str, str], list[str]] = {}
    for kt in sorted(JAVA.rglob("*.kt")):
        for name, val in lit_re.findall(kt.read_text(encoding="utf-8")):
            found.setdefault((name, val.strip()), []).append(rel(kt))
    for (name, val), files in sorted(found.items()):
        if len(files) > 1:
            warnings.append(
                f"`const val {name} = {val}` 在 {len(files)} 个文件重复定义"
                f"（{', '.join(files)}）—— 建议收敛到唯一来源，"
                f"否则改一处漏一处（本轮 CI 失败的同类根因）"
            )


# 协程 API 的「符号 → 必需 import」表。
# ⚠️ 只收录**能零误报判定**的形态：正则必须能区分"协程调用"与"同名方法调用"。
COROUTINE_APIS: dict[str, tuple[str, "re.Pattern[str]"]] = {
    # `pickPdf.launch(arrayOf(...))` 是 ActivityResultLauncher.launch，不是协程；
    # 协程调用一律写作 `launch {`（后面直接跟块）—— 这一条足以零误报地区分两者。
    "launch": ("kotlinx.coroutines.launch", re.compile(r"(?<![\w.])launch\s*\{")),
    "withContext": ("kotlinx.coroutines.withContext", re.compile(r"(?<![\w.])withContext\s*\(")),
    "Dispatchers": ("kotlinx.coroutines.Dispatchers", re.compile(r"(?<![\w.])Dispatchers\.")),
}


def check_missing_coroutine_imports() -> None:
    """用了协程 API 却**没 import** → 编译错误（本机无 JDK，只有 CI 才暴露）。

    2026-10-05：`KnowledgeBaseFragment` 由 Activity 迁为 Fragment 时漏抄
    `import kotlinx.coroutines.launch`；静态检查器**全绿**，CI 一编译就是
    6 条 `Unresolved reference 'launch'` + 一串
    `should be called only from a coroutine or another suspend function`。
    这是本地防线此前**唯一没覆盖**的一类：跨文件符号解析（编译器一查就出，
    正则却查不到）。本规则补上这个洞。

    判据刻意收紧到零误报：
      - 星号导入 `kotlinx.coroutines.*` 视为已覆盖；
      - 只在文件**确实用了**该符号时才要求 import（不凭空索取）；
      - `launch` 只认 `launch {`：「ActivityResultLauncher.launch(...)」不匹配。
    """
    for kt in sorted(JAVA.rglob("*.kt")):
        code = strip_comments(kt.read_text(encoding="utf-8"))
        imports = {
            ln.strip()[len("import "):].strip()
            for ln in code.splitlines()
            if ln.strip().startswith("import ")
        }
        if "kotlinx.coroutines.*" in imports:
            continue
        for sym, (imp, pat) in COROUTINE_APIS.items():
            if pat.search(code) and imp not in imports:
                errors.append(
                    f"{rel(kt)}: 用了协程 `{sym}` 但没有 `import {imp}` —— "
                    f"本地无 JDK，这类**跨文件符号解析**错误只在 CI 暴露"
                )


def check_backup_parity() -> None:
    """导出字段 ↔ 导入字段必须一一对应（v8 T07 需求 8 数据继承）。

    ══════════════════════════════════════════════════════════════════════════
    为什么这条最值得查
    ══════════════════════════════════════════════════════════════════════════
    `ExportWriter` 把实体写成一堆 `put("字段名", 值)`，`ImportReader` 再从
    JSON 里 `optString("字段名")` 取回来。两侧的"字段名"是**字符串字面量**：

        put("weight_kg", e.weightKg)      // 导出
        optDouble("weightKg", 0.0)        // 导入 —— 少了下划线

    kotlinc 不报错、KSP 不报错、单测（如果没有真备份文件）也覆盖不到。
    表现是：用户换了手机，导入提示"已导入 128 条"，**然后体重全变成 0**。
    这类静默丢数据是本项目最不能接受的一类 bug —— 而且它只在真机换机时才发作。

    与 `check_prompt_parity` 同一思路：把"两侧必须逐字一致"的事实交给机器守。
    这里比 prompt 那条更简单 —— 没有字节级要求，只要求**集合相等**。

    判据：
      - 导出侧按 `root.put("<表名>", ...)` 分节，节内所有 `put("字段", ...)`
        的字面量即该表的字段集；
      - 导入侧按 `each(root.optJSONArray(JSON_X)) { ... }` 分节（块体用括号配平取），
        块内所有 `optString/optInt/...("字段")` 的字面量即该表的字段集；
      - 两张表必须相等，多一个少一个都报。
      - `settings` 两侧都是**动态键**（`s.key` / `for (key in settingsObj.keys())`），
        没有固定字段集 → 跳过。

    ⚠️ **解析失败必须报错，不能静默通过**：本函数开头会检查"至少解析出若干张表"，
    一个都没解析出来就直接报错（2026-10-05 的教训：静默跳过的检查器比没有更糟）。
    """
    export = JAVA / "com/healix/app/ui/ExportWriter.kt"
    reader = JAVA / "com/healix/app/ui/ImportReader.kt"
    if not export.exists() or not reader.exists():
        errors.append(
            "找不到 ExportWriter.kt / ImportReader.kt —— 备份的写侧与读侧必须成对存在，"
            "只有一侧等于没有迁移能力"
        )
        return

    e_text = export.read_text(encoding="utf-8")

    # ── 导出侧：表名 → 字段集合 ──
    # 节边界 = `root.put("<表名>", <数组>)`；节内 `put("字段", ...)` 归属该表。
    # 字段 put 是 `JSONObject().apply { put("x", ...) }` 里的**无接收者**调用，
    # 所以第二分支匹配不带点的 `put(`；用 (?<![\w.]) 把 `root.put(` / `x.put(` 排掉。
    out_tables: dict[str, set[str]] = {}
    cur: set[str] = set()
    for m in re.finditer(
        r'(root\.put\(\s*"(\w+)"\s*,)|((?<![\w.])put\(\s*"(\w+)"\s*,)', e_text
    ):
        if m.group(1):
            out_tables.setdefault(m.group(2), set()).update(cur)
            cur = set()
        else:
            cur.add(m.group(4))

    # ── 导入侧：表名 → 字段集合 ──
    r_text = reader.read_text(encoding="utf-8")
    masked = mask_noncode(r_text)
    # 常量 → 字面量：JSON_X = "表名"，以及 FIELD_WEIGHT_KG = "weight_kg" 这类字段名常量
    # （字段名用常量是为了避开 check_settings_keys 的裸字符串启发式）。
    consts: dict[str, str] = dict(re.findall(r'const\s+val\s+(\w+)\s*=\s*"([^"]+)"', r_text))

    KEY_TAIL = (
        r'(?:optString|optInt|optLong|optDouble|optBoolean|textOrNull'
        r'|optionalDouble|optionalLong|isNull)'
    )
    # 字段读取有两种写法，都要收：
    #   显式接收者（each 块 / 块体 helper）：`o.optString("x")`
    #   隐式接收者（表达式体 helper）      ：`= optString("source").takeIf { ... }`
    # 前者要求有 `.`，后者没有 —— 于是前缀写成「可选的一个 `标识符.`」，
    # 并用 (?<![\w.]) 保证不是从某个链式调用的中段开始匹配。
    KEY_RE = re.compile(
        r'(?<![\w.])(?:[A-Za-z_]\w*\.)?' + KEY_TAIL + r'\(\s*(?:"(\w+)"|(\w+))'
    )

    def keys_in(code: str, *, helper_body: bool = False) -> set[str]:
        """收「这段代码读了哪些字段名」。

        `helper_body=True` 用于 **helper 函数体**：那里出现的裸标识符多半是
        **形参名**（`private fun JSONObject.textOrNull(key)` 的 body 里是
        `optString(key)`），跟进来会凭空多出假字段 `"key"`、把检查器搞成
        "永远报一堆假错"最后被无视。所以 helper body 里只认字面量，以及能在
        本文件里查到 `const val` 声明的常量名 —— 形参名两种情况都不满足。
        """
        found: set[str] = set()
        for km in KEY_RE.finditer(code):
            if km.group(1):
                found.add(km.group(1))
                continue
            name = km.group(2)
            if name in consts:
                found.add(consts[name])
            elif not helper_body:
                found.add(name)
        return found

    # helper 函数：each 块（或其调用的 helper）里读的字段也算该表的字段。
    #
    # 为什么必须跟这一层：单行 event 的解析整个搬进了 `eventOf(o, dayStart)`，
    # 字段名在那里读 —— 不跟的话 "events 的字段集"是空的，检查直接失效。
    #
    # 为什么要连**带接收者的扩展函数**一起收：`private fun JSONObject.sourceOf()`
    # 从备份里读 `source`；只收无接收者的函数就会漏掉它，把 `daily_plans` /
    # `training_plans` 的 `source` 误报成"导入侧从不读它"（真实发生过的误报）。
    #
    # 两种体都要能取：块体（`... { ... }`）与表达式体（`... = expr`）——
    # `sourceOf` / `textOrNull` 正是表达式体，只认 `{` 会整天漏掉。
    FUN_DECL = re.compile(
        r'(?:private|internal)\s+'
        r'(?:(?:inline|suspend|operator|infix|tailrec|external)\s+)*'
        r'fun\s+'
        r'(?:[A-Za-z_]\w*\s*\.\s*)?'   # 可选接收者：`JSONObject.`
        r'(\w+)\s*\([^)]*\)'
    )
    helpers: dict[str, str] = {}
    for hm in FUN_DECL.finditer(masked):
        body = _fun_body(r_text, masked, hm.end())
        if body:
            helpers[hm.group(1)] = body

    in_tables: dict[str, set[str]] = {}
    for m in re.finditer(r'each\(\s*root\.optJSONArray\((\w+)\)\s*\)\s*\{', masked):
        name = consts.get(m.group(1))
        if name is None:
            errors.append(
                f"{rel(reader)}: `each(root.optJSONArray({m.group(1)}))` 里的常量没有在"
                f"文件内声明 —— 字段一致性检查无法确定它对应备份里的哪张表"
            )
            continue
        body = _block_body(r_text, masked, m.end() - 1)
        keys = keys_in(body)
        for hname, hbody in helpers.items():
            if re.search(rf'\b{re.escape(hname)}\s*\(', body):
                keys |= keys_in(hbody, helper_body=True)
        in_tables[name] = keys

    # ── 防静默失效：两侧都必须真的解析出东西 ──
    if not out_tables or not in_tables:
        errors.append(
            "备份字段一致性检查器**自身解析失败**（导出侧 "
            f"{len(out_tables)} 张表 / 导入侧 {len(in_tables)} 张表）—— "
            "多半是 ExportWriter / ImportReader 的写法变了导致正则对不上。"
            "这类检查器一旦静默通过就比没有更糟，所以这里直接报错。"
        )
        return

    # 头部键不是"表"，settings 两侧都是动态键 → 都不参与比对。
    HEADER_KEYS = {"format", "version", "schema_version", "exported_at", "exported_date"}
    skip = HEADER_KEYS | {"settings"}

    for table, out_keys in sorted(out_tables.items()):
        if table in skip:
            continue
        in_keys = in_tables.get(table)
        if in_keys is None:
            errors.append(
                f"备份里的 `{table}` 会被导出，但 ImportReader 没有对应的 each() 分支 "
                f"→ 换机后这张表**静默丢失**（用户只看到「已导入 N 条」，"
                f"以为全搬过来了 —— 静默丢数据是本项目最不能接受的一类 bug）"
            )
            continue
        for k in sorted(out_keys - in_keys):
            errors.append(
                f"备份字段不一致：`{table}` 导出时写了 `{k}`，导入侧从不读它 "
                f"→ 该字段在换机 / 重装后丢失（ExportWriter 与 ImportReader 必须同步改）"
            )
        for k in sorted(in_keys - out_keys):
            errors.append(
                f"备份字段不一致：`{table}` 导入侧读 `{k}`，导出侧从不写它 "
                f"→ 永远读到兜底值（八成是字段名拼错了）"
            )

    for table in sorted(set(in_tables) - set(out_tables) - {"settings"}):
        errors.append(
            f"ImportReader 读备份里的 `{table}`，但 ExportWriter 从不导出它 "
            f"→ 这个分支永远拿到 null，是一段死代码"
        )


_DECL_LINE = re.compile(
    r'[ \t]*(?:@|private\b|internal\b|public\b|protected\b|fun\b|object\b|val\b|var\b|\})'
)


def _fun_body(raw: str, masked: str, after_params: int) -> str:
    """取「参数表之后」的函数体：块体 `{...}` 或表达式体 `= ...`。

    起点判据统一：「参数表之后第一个 `{` 或 `=`」（`==` 不算）。
    两种体都要能取，因为 `private fun JSONObject.sourceOf(): String =` 是
    **表达式体** —— 只认 `{` 的写法会整天漏掉它（于是把 `source` 误报成
    "导入侧从不读它"）。

    表达式体的**结束**判据是「括号配平回到 0 之后，下一行是同级声明行」。
    不能只看「下一行以 `}` 开头」：`sourceOf()` 里的续行
    `} ?: TrainingPlanner.SOURCE_FALLBACK` 恰好就是以 `}` 开头的一行 ——
    那样会在续行处提前截断。截断本身只会漏键（报假错，可见），不会静默，
    但仍应当避免。
    """
    n = len(masked)
    i = after_params
    start = -1
    is_block = False
    while i < n:
        c = masked[i]
        if c == "\n":
            return ""
        if c == "{":
            start, is_block = i, True
            break
        if c == "=" and masked[i:i + 2] != "==":
            start, is_block = i + 1, False
            break
        i += 1
    if start < 0:
        return ""
    if is_block:
        return _block_body(raw, masked, start)

    depth = 0
    j = start
    while j < n:
        c = masked[j]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "\n" and depth <= 0:
            nl = masked.find("\n", j + 1)
            nxt = masked[j + 1: n if nl < 0 else nl]
            if _DECL_LINE.match(nxt):
                return raw[start:j]
        j += 1
    return raw[start:]


def _block_body(raw: str, masked: str, brace_pos: int) -> str:
    """取 `masked[brace_pos] == '{'` 起的配平块体（内容从 raw 里切，保留字符串）。

    `masked` 与 `raw` 等长（`mask_noncode` 保证），所以索引可直接沿用。
    """
    depth = 0
    i = brace_pos
    while i < len(masked):
        if masked[i] == "{":
            depth += 1
        elif masked[i] == "}":
            depth -= 1
            if depth == 0:
                return raw[brace_pos + 1: i]
        i += 1
    return raw[brace_pos + 1:]


def main() -> int:
    if not DB.exists():
        print(f"找不到 db 目录：{DB}")
        return 1

    tables = collect_tables()
    check_type_converters()
    check_dao_columns(tables)
    check_projection_types(tables)
    check_view_binding()
    check_shadowed_R()
    check_set_items_argument()
    check_suspend_calls()
    check_undefined_self_calls()
    check_manifest_classes()
    check_manifest_resources()
    check_settings_keys()
    check_settings_keys_consistency()
    check_no_gamification()
    check_prompt_parity()
    check_object_scope()
    check_duplicate_constants()
    check_missing_coroutine_imports()
    check_backup_parity()

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
