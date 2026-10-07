#!/usr/bin/env python3
"""Healix 跨包 import 漏抄检查（零第三方依赖，纯静态）。

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
（注：本检查器首版有 bug —— `sorted(set(out))` 的元素含 list → TypeError，
  第 2 步表现为「退出码 1」而非真检出。**只看退出码会误判防线有效**，
  必须检查输出内容里是否精确出现了坏例。）

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

# R / BuildConfig 由 AGP 生成，不参与本检查
STOP = {"R", "BuildConfig"}

errors: list[str] = []


def strip_comments(text: str) -> str:
    """剥掉块注释与行注释（注释里常举例写类名，不剥会假阳性）。"""
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'//[^\n]*', '', text)


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

    if errors:
        for e in errors:
            print(f"❌ {e}")
        return 1

    if not bad:
        print("\n✅ 全部通过（无跨包裸用未 import 的项目类型）")
        print("=" * 64)
        return 0

    print(f"\n❌ 发现 {len(bad)} 处疑似漏抄 import（编译期会报 Unresolved reference）：")
    for path, pkg, name, pkgs in bad:
        print(f"   {path}")
        print(f"      [{pkg}] 裸用 {name}，但它在 {', '.join(pkgs)}")
    print("=" * 64)
    return 1


if __name__ == "__main__":
    sys.exit(main())
