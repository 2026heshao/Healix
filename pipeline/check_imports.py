#!/usr/bin/env python3
r"""Healix 跨包 import 漏抄检查（零第三方依赖，纯静态）。

═══════════════════════════════════════════════════════════════════════════
为什么单独立一条检查器
═══════════════════════════════════════════════════════════════════════════
本机无 JDK / Android SDK，Kotlin 的**符号解析**（`Unresolved reference`）
只能靠 CI 编译暴露；而 `check_kotlin.py` 是正则型检查器、不做符号表，
对「代码从 A 文件迁到 B 文件、import 段没跟着走」这一类错误**零覆盖**。

实证（CI run #71，2026-10-07）：
  EventAdapter 从 `ui/RecordFragment.kt` 迁入 `ui/RecordListAdapters.kt` 时，
  整个 import 段没跟着走 —— 新文件仍 3 处调用 `com.healix.app.notify.EventText`，
  但文件内无对应 import。本地三条静态检查器**全绿**，CI 才炸：
    e: RecordListAdapters.kt:202:32 Unresolved reference 'EventText'.
    e: RecordListAdapters.kt:206:38 Unresolved reference 'EventText'.
    e: RecordListAdapters.kt:212:27 Unresolved reference 'EventText'.
  这正是「无本地编译器」场景下的头号盲区（代码迁移漏抄 import）。

═══════════════════════════════════════════════════════════════════════════
检查口径
═══════════════════════════════════════════════════════════════════════════
1. 扫全仓 `*.kt`，收集**顶层声明**名 → 所在包（class / object / interface /
   fun / val / var / typealias，仅取大写开头者）；
2. 逐个文件收集其 `import`（含 `.*` 通配）与 `package`；
3. 在**剥掉注释与 import 段**后的正文里，找「大写开头标识符」的使用；
   若该名字是本仓某顶层声明、且**声明包 ≠ 本文件包**、且**未被 import 覆盖**
   → 报错。
4. 单列一条 [scan_generated]：`R` / `BuildConfig` 是 AGP **生成**的类、不在
   源码里，1–3 步看不见它们（只能进 STOP 跳过）→ 该路线另行精确判定，
   判据见函数 docstring。**2026-10-08 补**：`ui/PagePrewarm.kt` 漏
   `import com.healix.app.R` 时四道检查器全绿、只有 CI 编译才炸，正是此盲区。

为什么不误报（已实测 0 误报，全仓 90 个 .kt）：
  * 同包声明直接放行（Kotlin 同包无需 import）；
  * 通配 import（`import a.b.*`）与其等价形式放行；
  * 只认可被识别的**顶层**声明名 —— 局部变量 / 参数 / 嵌套成员同名时，
    若其名字恰好也是某个顶层声明名，仍可能命中（已知理论误报面，实测未出现）；
  * 注释与 import 段先剥离，避免注释里举例写的类名被当成真代码。

═══════════════════════════════════════════════════════════════════════════
坏例自证（改本文件后必须重跑，SOP 见 ci-compile-guard §2.2）
═══════════════════════════════════════════════════════════════════════════
  1. 基线             → ✅ 0 误报
  2. 删掉 `import com.healix.app.notify.EventText` → 必须精确报出该文件 + EventText
  3. 还原             → 回到 ✅ 0 误报
  4. 删掉 `PagePrewarm.kt` 的 `import com.healix.app.R`
     → 必须精确报出该文件 + `R`（走 [scan_generated]）；还原 → 0 误报
（注：本检查器首版有 bug —— `sorted(set(out))` 的元素含 list → TypeError，
  第 2 步表现为「退出码 1」而非真检出。**只看退出码会误判防线有效**，
  必须检查输出内容里是否精确出现了坏例。）

已知未修的休眠缺陷（当前无影响，需要时再动）：
  * 通配 import 的捕获与放行其实都是坏的：`^import\s+([\w.]+)` 不匹配 `*`，
    捕获结果以 `.` 结尾 → `wild` 恒为空集；放行判据 `f"{w}.{name}" in imports`
    语义也不对（应为「该名字的声明包 ∈ wild」）。全仓当前 **0 个星号 import**，
    所以是死代码 —— 一旦有人写 `import a.b.*` 会立刻误报。

退出码：0 = 全通过；1 = 发现问题。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java"

DECL_RE = re.compile(
    r'^(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+'
    r'|data\s+|enum\s+|annotation\s+|value\s+)*'
    r'(?:object|class|interface|fun|val|var|typealias)\s+([A-Z]\w*)',
    re.M,
)

# R / BuildConfig 由 AGP 生成，不在源码里 → 上面的「顶层声明」表看不见它们，
# 通用检查只能整个跳过。但它们在**固定包**下生成，因此可以精确判定（见
# [scan_generated]，2026-10-08 补）。
STOP = {"R", "BuildConfig"}

# AGP 生成类的唯一来源包 = 模块 namespace（`app/build.gradle` 的 namespace）。
GENERATED_PKG = "com.healix.app"
GENERATED = ("R", "BuildConfig")

errors: list[str] = []


def strip_comments(text: str) -> str:
    """剥掉块注释与行注释（注释里常举例写类名，不剥会假阳性）。"""
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'//[^\n]*', '', text)


def mask_keep_lines(text: str) -> str:
    """把注释内容抹掉，但**保留换行数** → 行号仍与原文件对齐。

    既有 [strip_comments]（用 `re.S` 一次性删块注释）会把注释里的换行也删掉，
    行号当场漂移，只能用于「有没有」的判定，不能用于定位。
    """
    out: list[str] = []
    i, n, depth = 0, len(text), 0
    while i < n:
        if depth > 0:                        # 块注释内：Kotlin 允许嵌套
            if text.startswith('/*', i):
                depth += 1
                i += 2
            elif text.startswith('*/', i):
                depth -= 1
                i += 2
            else:
                out.append('\n' if text[i] == '\n' else '')
                i += 1
            continue
        if text.startswith('/*', i):
            depth = 1
            i += 2
            continue
        if text.startswith('//', i):
            j = text.find('\n', i)
            i = n if j < 0 else j
            continue
        out.append(text[i])
        i += 1
    return ''.join(out)


def scan_generated() -> list[tuple[str, str, str, int]]:
    """`R` / `BuildConfig` 的裸用检查 —— 通用检查看不到的两个类。

    为什么必须单列（实证，2026-08-10…2026-10-08 两次踩同一个坑）：
      `R` 与 `BuildConfig` 由 AGP **生成**，不在 `app/src/main/java` 里 →
      [collect_declarations] 收集不到 → 只能进 [STOP] 被整个跳过 →
      「新建文件忘了 `import com.healix.app.R`」这类编译错误在本检查器里**全盲**。
      最近一次：`ui/PagePrewarm.kt` 新建时漏掉该 import，本地四道检查器全绿，
      直到 `assembleDebug` 才炸出 6 行 `Unresolved reference 'R'`。

    判据（**Kotlin 没有「子包自动可见父包」规则** —— 这是与 Java 的关键差异：
    `com.healix.app.ui` 里写裸 `R` **不会**自动解析到 `com.healix.app.R`）：
      正文里出现「裸 `R.`」或「裸 `BuildConfig.`」（`.` 之前既不是词字符也不是 `.`，
      于是 `android.R.layout` 这种全限定写法天然不入选），且**四条豁免全不成立**：
        ① 本文件包 == namespace（同包无需 import）；
        ② 有 `import com.healix.app.R` 精确 import；
        ③ `simple_imports` 里已有同名（如 `import android.R`，合法用法）；
        ④ 有 `import com.healix.app.*` 星号 import。
      任一豁免成立即放行，否则报错。

    返回 (相对路径, 本文件包, 名字, 行号)。
    """
    hits: list[tuple[str, str, str, int]] = []
    if not SRC.exists():
        return hits

    for path in SRC.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        pkg_m = re.search(r'^package\s+([\w.]+)', text, re.M)
        pkg = pkg_m.group(1) if pkg_m else ""
        if pkg == GENERATED_PKG:
            continue

        raw_imports = re.findall(r'^import\s+([\w.]+)(?:\.\*)?', text, re.M)
        simple_imports = {i.split(".")[-1] for i in raw_imports}
        stars = set(re.findall(r'^import\s+([\w.]+)\.\*\s*$', text, re.M))

        for name in GENERATED:
            if name in simple_imports:
                continue
            if GENERATED_PKG in stars:
                continue
            use_re = re.compile(rf'(?<![\w.]){name}\s*\.')
            if not use_re.search(mask_keep_lines(text)):
                continue
            for lineno, line in enumerate(mask_keep_lines(text).splitlines(), 1):
                if use_re.search(line):
                    hits.append(
                        (str(path.relative_to(ROOT)).replace("\\", "/"),
                         pkg, name, lineno)
                    )
                    break

    return sorted(set(hits))


def collect_declarations() -> dict[str, set[str]]:
    """全仓顶层 `大写开头` 声明名 → 所在包集合。"""
    decl: dict[str, set[str]] = {}
    for path in SRC.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        pkg = re.search(r'^package\s+([\w.]+)', text, re.M)
        pkg_name = pkg.group(1) if pkg else ""
        for m in DECL_RE.finditer(strip_comments(text)):
            decl.setdefault(m.group(1), set()).add(pkg_name)
    return decl


def scan() -> list[tuple[str, str, str, tuple[str, ...]]]:
    if not SRC.exists():
        errors.append(f"找不到源码目录：{SRC}")
        return []

    decl = collect_declarations()
    found: list[tuple[str, str, str, tuple[str, ...]]] = []

    for path in SRC.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        pkg_m = re.search(r'^package\s+([\w.]+)', text, re.M)
        pkg = pkg_m.group(1) if pkg_m else ""

        raw_imports = re.findall(r'^import\s+([\w.]+)', text, re.M)
        imports = set(raw_imports)
        simple_imports = {i.split(".")[-1] for i in raw_imports}
        wild = {i[:-2] for i in raw_imports if i.endswith(".*")}

        body = re.sub(r'^import[^\n]*', '', strip_comments(text), flags=re.M)

        for name in set(re.findall(r'(?<![\w.])([A-Z]\w+)', body)):
            if name in STOP or name not in decl:
                continue
            if pkg in decl[name]:
                continue                      # 同包无需 import
            if f"{pkg}.{name}" in imports:
                continue
            if name in simple_imports:
                continue
            if any(f"{w}.{name}" in imports for w in wild):
                continue
            found.append(
                (str(path.relative_to(ROOT)).replace("\\", "/"), pkg, name,
                 tuple(sorted(decl[name])))
            )

    return sorted(set(found))


def main() -> int:
    print("=" * 64)
    print("Healix 跨包 import 漏抄检查")
    print("=" * 64)
    print(f"扫描源码：{SRC.relative_to(ROOT)}")

    bad = scan()
    bad_gen = scan_generated()

    if errors:
        for e in errors:
            print(f"❌ {e}")
        return 1

    if not bad and not bad_gen:
        print("\n✅ 全部通过（无跨包裸用未 import 的项目类型，"
              "含 AGP 生成的 R / BuildConfig）")
        print("=" * 64)
        return 0

    if bad:
        print(f"\n❌ 发现 {len(bad)} 处疑似漏抄 import（编译期会报 Unresolved reference）：")
        for path, pkg, name, pkgs in bad:
            print(f"   {path}")
            print(f"      [{pkg}] 裸用 {name}，但它在 {', '.join(pkgs)}")

    if bad_gen:
        print(f"\n❌ 发现 {len(bad_gen)} 处裸用 AGP 生成类（R / BuildConfig）未 import：")
        for path, pkg, name, lineno in bad_gen:
            print(f"   {path}:{lineno}")
            print(f"      [{pkg}] 裸用 {name}，需 `import {GENERATED_PKG}.{name}`"
                  f"（Kotlin 不会自动向上解析父包）")

    print("=" * 64)
    return 1


if __name__ == "__main__":
    sys.exit(main())
