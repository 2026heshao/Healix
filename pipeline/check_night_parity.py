#!/usr/bin/env python3
"""Healix 夜间资源对等检查（values-night/ ↔ values/，零第三方依赖）。

═══════════════════════════════════════════════════════════════════════════
为什么单独立一条检查器
═══════════════════════════════════════════════════════════════════════════
`res/values-night/` 下的 `colors.xml` / `themes.xml` / `button_colors.xml` 是
**整份重定义**：同名资源落在高限定符目录（`values-night`）时，系统**整份选用**
该目录那一份，**不与 `values/` 合并**。于是：

  * `<color>`：夜间那份整份替换白天那份 —— **白天有、夜间漏掉的颜色键，在夜间
    模式会静默回落到白天的值**（不是报错，是用错色）；
  * `<style>`：某个 style 一旦在 `values-night` 出现，它的 `<item>` **不再与白天
    那份合并** —— 夜间那份若漏登记某个 item，夜间模式就**静默丢掉**该 item。

两者都**不会编译报错**，只在夜间模式静默错。且本机无 JDK / Android SDK，
`assembleDebug` 跑不了，编译期同样发现不了 —— 与 `check_resources.py` /
`check_schema.py` 同一类「静默失效」问题，必须靠静态检查兜住。

本仓库真实踩过此类坑（见 `values/themes.xml` 里 `Theme.Healix` 的注释）：
弹层容器 `bottomSheetStyle` 漏在夜间登记 → 夜间弹层白底 bug 复现。

═══════════════════════════════════════════════════════════════════════════
检查内容
═══════════════════════════════════════════════════════════════════════════
对 `values/` 与 `values-night/` 中**同名**的 `.xml` 文件：

  1. 顶层条目键集合（`<color name>` / `<style name>` / `<dimen name>` / …）
     双向比较 → 报告「只在白天定义」/「只在夜间定义」的键；
  2. **两侧都存在**的同名 `<style>` 的 `<item name>` 集合 → 双向比较
     （第 2 条才真正覆盖「整份重定义漏 item」这一类 bug）。

确属**有意**的单边差异，在下面的白名单里逐条登记并写明理由。

═══════════════════════════════════════════════════════════════════════════
× 收集口径（勿重蹈 check_resources.py 的盲区）
═══════════════════════════════════════════════════════════════════════════
`check_resources.py` 早期只扫 `values*/` 却漏了 `res/color/*.xml`（color state
list 不在 values 目录），导致「引用不存在的 @color」误报。本脚本据此：
  * 直接对 `values/` 与 `values-night/` 两个目录各自 `glob("*.xml")`，按**文件名**
    求交集（`values-night` 下的文件**全部**参与收集，不做任何前缀过滤）；
  * 用 `xml.etree.ElementTree`（标准库）解析而非正则 —— 注释里举例写的
    `<color name="反例">` 不会被当成真实声明（ElementTree 天然忽略注释）；
  * 运行时会打印两侧**实际收集到的文件清单**，口径一目了然。

退出码：0 = 全通过；1 = 发现问题。
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
DAY_DIR = RES / "values"
NIGHT_DIR = RES / "values-night"

errors: list[str] = []
warnings: list[str] = []

# ---------------------------------------------------------------------------
# 白名单：确属**有意**的单边差异
# ---------------------------------------------------------------------------
# 键 = (文件名, 资源种类, 资源名)，表示「该键只允许出现在其中一侧」。
#
# 为什么 values/themes.xml 里这 9 个 style 只登记白天、不进 values-night：
#   它们是**只引用颜色令牌**（@color/…）的组件样式 —— 令牌本身由
#   values-night/colors.xml 整份覆盖，样式不需要（也不应该）再有夜间副本。
#   把同一份 style 重复登记到 values-night 反而与「style 不跨限定符合并」的
#   整份重定义语义打架（见 values/themes.xml 中 Widget.Healix.BottomSheet 的
#   注释：style 无颜色令牌 → 只在 values 定义一次，白天/夜间两份 Theme.Healix
#   共用）。真正需要夜间副本的只有 Theme.Healix（它引用了 windowBackground /
#   textColor* 等随模式变化的项）—— 那份两侧都有，其 item 集合由下方「同名
#   style 的 item 对等」逐项核对。
WHITELIST_DAY_ONLY: set[tuple[str, str, str]] = {
    ("themes.xml", "style", "Theme.Healix.Splash"),
    ("themes.xml", "style", "Widget.Healix.Button"),
    ("themes.xml", "style", "Widget.Healix.TextButton"),
    ("themes.xml", "style", "Widget.Healix.TextButton.Danger"),
    ("themes.xml", "style", "Widget.Healix.QuickInput"),
    ("themes.xml", "style", "Widget.Healix.BlockInput"),
    ("themes.xml", "style", "Widget.Healix.ListRow"),
    ("themes.xml", "style", "Widget.Healix.SettingRow"),
    ("themes.xml", "style", "Widget.Healix.BottomSheet"),
}

# 仅出现在夜间一侧的键白名单 (文件名, 种类, 名)。当前无。
WHITELIST_NIGHT_ONLY: set[tuple[str, str, str]] = set()

# 同名 style 的 item 单边白名单 (文件名, style 名, item 名)。当前无。
WHITELIST_STYLE_ITEM: set[tuple[str, str, str]] = set()


def rel(p: Path) -> str:
    return str(p.relative_to(ROOT)).replace("\\", "/")


def _local(tag: str) -> str:
    """去掉 ElementTree 展开的命名空间前缀，只留本地名。"""
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


def read_file(path: Path) -> tuple[set[tuple[str, str]], dict[str, set[str]]]:
    """解析一个 values 资源文件。

    @return (keys, style_items)
      - keys: ``{(kind, name)}`` —— 顶层声明的资源键（含 `<style name>`）；
      - style_items: ``{styleName: {itemName}}`` —— 每个 style 的 item 集合。
    """
    root = ET.parse(path).getroot()
    keys: set[tuple[str, str]] = set()
    style_items: dict[str, set[str]] = {}
    for child in root:
        kind = _local(child.tag)
        # 顶层 <item type="..." name="..."> 也是一种资源声明形式
        if kind == "item":
            t, n = child.get("type"), child.get("name")
            if t and n:
                keys.add((t, n))
            continue
        name = child.get("name")
        if name is None:
            continue
        keys.add((kind, name))
        if kind == "style":
            style_items.setdefault(name, set())
            for item in child:
                if _local(item.tag) == "item" and item.get("name"):
                    style_items[name].add(item.get("name"))
    return keys, style_items


def main() -> int:
    if not DAY_DIR.exists():
        print(f"找不到资源目录：{rel(DAY_DIR)}")
        return 1

    day_files = {p.name for p in DAY_DIR.glob("*.xml")}
    night_files = {p.name for p in NIGHT_DIR.glob("*.xml")} if NIGHT_DIR.exists() else set()
    shared = sorted(day_files & night_files)

    # 口径自证：打印两侧实际收集到的文件清单（防「漏收集」的静默盲区）
    print("=" * 64)
    print("Healix 夜间资源对等检查（values-night ↔ values）")
    print("=" * 64)
    print(f"values/        ：{sorted(day_files)}")
    print(f"values-night/  ：{sorted(night_files)}")
    print(f"同名文件（参与对等）：{shared or '（无）'}")

    if not night_files:
        warnings.append(
            "没有 values-night/（或为空）→ 无夜间资源可比。"
            "若确无深色模式需求可忽略；否则确认目录是否被漏收集。"
        )

    for fname in shared:
        try:
            day_keys, day_styles = read_file(DAY_DIR / fname)
            night_keys, night_styles = read_file(NIGHT_DIR / fname)
        except ET.ParseError as e:  # noqa: BLE001 —— 良构性失败要报出来而非崩栈
            errors.append(f"{fname} 无法解析为 XML：{e}（aapt2 也会拒绝该文件）")
            continue

        # ── 1. 顶层条目键双向比较 ──
        for kind, name in sorted(day_keys - night_keys):
            if (fname, kind, name) in WHITELIST_DAY_ONLY:
                continue
            errors.append(
                f"values/{fname}：{kind} `{name}` 只在白天定义，"
                f"values-night/{fname} 缺它 → 夜间模式**静默回落到白天值**（不报错、用错色/用错值）。"
                "请补进 values-night，或（确属有意）登记进本脚本的白名单并写明理由。"
            )
        for kind, name in sorted(night_keys - day_keys):
            if (fname, kind, name) in WHITELIST_NIGHT_ONLY:
                continue
            errors.append(
                f"values-night/{fname}：{kind} `{name}` 只在夜间定义，"
                f"values/{fname} 缺它 → 白天模式引用会失败（夜间那份是整份重定义，不会回落到白天）。"
            )

        # ── 2. 两侧都存在的同名 style，逐 item 比较 ──
        for sname in sorted(set(day_styles) & set(night_styles)):
            for item in sorted(day_styles[sname] - night_styles[sname]):
                if (fname, sname, item) in WHITELIST_STYLE_ITEM:
                    continue
                errors.append(
                    f"values-night/{fname}：style `{sname}` 缺 item `{item}`"
                    "（白天有）→ style 不跨限定符合并，夜间该 item **静默丢失**。"
                )
            for item in sorted(night_styles[sname] - day_styles[sname]):
                if (fname, sname, item) in WHITELIST_STYLE_ITEM:
                    continue
                errors.append(
                    f"values/{fname}：style `{sname}` 缺 item `{item}`（夜间有）"
                    "→ 白天该 style 缺此 item。"
                )

    if errors:
        print(f"\n❌ 错误 {len(errors)} 项：")
        for e in errors:
            print(f"   - {e}")
    if warnings:
        print(f"\n⚠️  提示 {len(warnings)} 项（可能误报）：")
        for w in warnings:
            print(f"   - {w}")
    if not errors and not warnings:
        print("\n✅ 全部通过（同名文件键集合 + 同名 style 的 item 集合两侧对等）")
    print("=" * 64)

    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
