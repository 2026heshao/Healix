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
                if re.search(r'\b(launch|withContext|async|runBlocking|suspendCoroutine)\b', body):
                    continue
                for sf in suspend_fns:
                    if sf == fn["name"]:
                        continue
                    if re.search(rf'(?<![\w.]){re.escape(sf)}\s*\(', body):
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

        seen: set[tuple[str, int]] = set()
        for name, lo, hi, host, decl_line in scopes:
            if name in file_level or name in imported:
                continue
            use_re = re.compile(rf'(?<![\w.])(?<!::){re.escape(name)}\b')
            for um in use_re.finditer(masked):
                p = um.start()
                if lo <= p <= hi:
                    continue
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
