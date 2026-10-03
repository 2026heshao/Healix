#!/usr/bin/env python3
"""Healix 资源静态检查。

CI 首跑时 mergeDebugResources 报 `Found item String/type_meal more than one time`，
而此前的手工校验用 `sort -u` 去重，恰好掩盖了重复定义 —— 教训：**校验脚本自身
必须能捕捉被检查的那类问题**，去重后才比对等于自废武功。

本脚本覆盖几类「编译期才会暴露、但可以静态发现」的错误：
  1. 同一 values/*.xml 内重复定义同名资源（Resource 合并会直接失败）
  2. 布局引用的 @dimen/@color/@style/@string/@drawable 是否存在
  3. Kotlin 引用的 R.xxx 是否存在
  4. values-night 覆盖项是否都在 values 里有对应定义（否则是死代码）

退出码：0 = 全通过；1 = 发现问题。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
JAVA = ROOT / "app/src/main/java"

errors: list[str] = []
warnings: list[str] = []

# ---------------------------------------------------------------------------
# 1. 同一文件内重复定义
# ---------------------------------------------------------------------------

RESOURCE_RE = re.compile(
    r'<(string|color|dimen|style|integer|bool|string-array|array|attr)\s+name="([A-Za-z0-9._]+)"'
)


def check_duplicates() -> None:
    for xml in sorted(RES.glob("values*/*.xml")):
        seen: dict[tuple[str, str], int] = {}
        for lineno, line in enumerate(xml.read_text(encoding="utf-8").splitlines(), 1):
            for kind, name in RESOURCE_RE.findall(line):
                key = (kind, name)
                if key in seen:
                    errors.append(
                        f"{xml.relative_to(ROOT)}:{lineno}: 重复定义 "
                        f"{kind}/{name}（首次出现在第 {seen[key]} 行）"
                    )
                else:
                    seen[key] = lineno


# ---------------------------------------------------------------------------
# 2/3. 引用存在性
# ---------------------------------------------------------------------------


def collect_declared(kind_pattern: str, dirs: list[Path]) -> set[str]:
    """收集已声明的资源名。"""
    names: set[str] = set()
    pat = re.compile(kind_pattern)
    for d in dirs:
        for xml in d.glob("*.xml"):
            names.update(pat.findall(xml.read_text(encoding="utf-8")))
    return names


def check_references() -> None:
    values_dirs = [RES / "values", RES / "values-night"]

    declared = {
        "string": collect_declared(r'<string\s+name="([A-Za-z0-9._]+)"', values_dirs),
        "color": collect_declared(r'<color\s+name="([A-Za-z0-9._]+)"', values_dirs),
        "dimen": collect_declared(r'<dimen\s+name="([A-Za-z0-9._]+)"', values_dirs),
        "style": collect_declared(r'<style\s+name="([A-Za-z0-9._]+)"', values_dirs),
        "array": collect_declared(
            r'<(?:string-array|integer-array|array)\s+name="([A-Za-z0-9._]+)"', values_dirs
        ),
    }
    declared["drawable"] = {p.stem for p in (RES / "drawable").glob("*.xml")}
    declared["layout"] = {p.stem for p in (RES / "layout").glob("*.xml")}
    declared["color_ref"] = declared["color"]

    # --- 布局引用 ---
    for xml in sorted((RES / "layout").glob("*.xml")):
        text = xml.read_text(encoding="utf-8")
        for kind in ("dimen", "color", "style", "string", "drawable"):
            for ref in set(re.findall(rf'@(?:android:)?{kind}/([A-Za-z0-9._]+)', text)):
                if kind == "style":
                    # @style/ 可引用框架样式（Theme.AppCompat 等），跳过带点的
                    if "." in ref and ref not in declared["style"]:
                        continue
                if ref not in declared.get(kind, set()):
                    errors.append(f"{xml.relative_to(ROOT)}: 引用不存在的 @{kind}/{ref}")

    # --- Kotlin 引用 ---
    for kt in sorted(JAVA.rglob("*.kt")):
        text = kt.read_text(encoding="utf-8")
        for kind in ("string", "color", "dimen", "layout", "drawable", "array"):
            for ref in set(re.findall(rf"R\.{kind}\.([A-Za-z0-9_]+)", text)):
                if ref not in declared.get(kind, set()):
                    errors.append(f"{kt.relative_to(ROOT)}: 引用不存在的 R.{kind}.{ref}")


# ---------------------------------------------------------------------------
# 4. values-night 覆盖项应在 values 有对应
# ---------------------------------------------------------------------------


def check_night_overrides() -> None:
    base = collect_declared(r'<color\s+name="([A-Za-z0-9._]+)"', [RES / "values"])
    for xml in sorted((RES / "values-night").glob("*.xml")):
        night = collect_declared(r'<color\s+name="([A-Za-z0-9._]+)"', [xml.parent])
        # values-night 允许直接覆盖同名 color，但若 values 里没有同名项，
        # 则这个定义只在深色模式存在 —— 浅色模式下引用会失败
        for name in sorted(night):
            if name not in base:
                warnings.append(
                    f"{xml.relative_to(ROOT)}: color/{name} 在深色模式定义了，"
                    f"但浅色 values/ 没有同名项（浅色模式引用会失败）"
                )


def main() -> int:
    if not RES.exists():
        print(f"找不到资源目录：{RES}")
        return 1

    check_duplicates()
    check_references()
    check_night_overrides()

    print("=" * 64)
    print("Healix 资源静态检查")
    print("=" * 64)
    if errors:
        print(f"\n❌ 错误 {len(errors)} 项：")
        for e in errors:
            print(f"   {e}")
    if warnings:
        print(f"\n⚠️  警告 {len(warnings)} 项：")
        for w in warnings:
            print(f"   {w}")
    if not errors and not warnings:
        print("\n✅ 全部通过（无重复定义、无悬空引用）")
    print("=" * 64)

    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
