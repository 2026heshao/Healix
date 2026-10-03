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
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
JAVA = ROOT / "app/src/main/java"

errors: list[str] = []
warnings: list[str] = []


def strip_kotlin_comments(text: str) -> str:
    """去掉 Kotlin 的 // 行注释与 /* */ 块注释。

    注释是给人看的说明，里面常常出现示例代码（包括故意写错的反例）。
    检查器若不剥注释，就会把说明文字当成真实代码 —— 这正是
    「R.string.xxx」假阳性的来源。
    """
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    out = []
    for line in text.splitlines():
        in_str = False
        cut = None
        for i, ch in enumerate(line):
            if ch == '"':
                in_str = not in_str
            elif not in_str and ch == '/' and i + 1 < len(line) and line[i + 1] == '/':
                cut = i
                break
        out.append(line if cut is None else line[:cut])
    return "\n".join(out)

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
        # ⚠️ 必须先剥掉注释再扫。踩过的坑：在代码注释里举例写了
        #    「于是 `R.string.xxx` 解析失败」，结果检查器把注释里的
        #    `R.string.xxx` 当成真实引用，报「引用不存在的 R.string.xxx」。
        #    检查器扫描注释 = 自己给自己造假阳性。
        text = strip_kotlin_comments(kt.read_text(encoding="utf-8"))
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


def check_shell_line_endings() -> None:
    """gradlew / *.sh 必须是 LF。

    Linux runner 上 `./gradlew` 若带 CRLF，内核会报
    `bad interpreter: /bin/sh^M` —— 这是纯换行符问题，却要等 CI 跑起来
    才炸。静态查一遍，1 秒内定位。

    ⚠️ 这里**必须读 git blob 而不是工作区文件**。本机 core.autocrlf=true，
    工作区里所有文件都是 CRLF，读工作区会把正常文件全判成错误（假阳性）。
    git 存储的内容才是 CI 实际拿到的东西。
    """
    if not (ROOT / ".git").exists():
        return  # 非 git 环境（如打包后的源码）跳过
    targets = ["gradlew", *[p.name for p in ROOT.glob("*.sh")]]
    for name in targets:
        f = ROOT / name
        if not f.exists():
            continue
        try:
            data = subprocess.run(
                ["git", "cat-file", "-p", f"HEAD:{name}"],
                cwd=ROOT, capture_output=True, check=True,
            ).stdout
        except (subprocess.CalledProcessError, FileNotFoundError):
            data = f.read_bytes()
        if b"\r\n" in data:
            n = data.count(b"\r\n")
            errors.append(
                f"{name}: 在 git 中是 CRLF（{n} 处）—— Linux 上会报 "
                f"'bad interpreter: /bin/sh^M'，应为 LF"
            )


def check_signal_copy() -> None:
    """规范 §9.8：预警「双套文案」的硬约束。

    首页状态行只有 1 行、状态页「身体」段只给 2 行，所以：
      - `signal_short_*`（首页）≤ **24 汉字**
      - `signal_full_*`（状态页）≤ **52 汉字**

    同时 PRD §5.7 / §9.2 的禁用清单也要在文案层守住（Kotlin 侧
    `check_no_gamification` 只扫代码，扫不到 strings.xml）：
      - 疾病名 / 诊断 —— App 不做疾病推断
      - 概率数字（"风险 30%"）—— 假装有统计依据
      - "建议咨询医生"套话 —— 就医阈值文案除外（就医引导必须保留）
      - 游戏化词汇

    ⚠️ 只数字符串里的**汉字**，`%1$s` 占位符与数字标点不计入 —— 否则
    带占位符的 T1/T2/T3 会被误判超长。
    """
    strings_xml = RES / "values/strings.xml"
    if not strings_xml.exists():
        return
    text = strings_xml.read_text(encoding="utf-8")
    pairs = re.findall(
        r'<string name="(signal_(?:short|full)_\w+)">(.*?)</string>', text, re.S
    )
    if not pairs:
        errors.append("strings.xml: 找不到任何 signal_short_* / signal_full_* 文案")
        return

    cjk = re.compile(r"[\u4e00-\u9fff]")
    banned = {
        "疾病名/诊断": ["糖尿病", "高血压", "抑郁症", "贫血", "甲亢", "综合征", "确诊"],
        "咨询医生套话": ["建议咨询医生", "请咨询医生", "咨询专业医生"],
        "游戏化": ["连续打卡", "streak", "归零", "勋章", "成就", "积分", "排行"],
    }

    for name, raw in pairs:
        body = raw.strip()
        # 去掉格式化占位符再数汉字
        plain = re.sub(r"%[0-9]+\$[sd]", "", body)
        n = len(cjk.findall(plain))
        limit = 24 if "short" in name else 52
        if n > limit:
            errors.append(
                f"strings.xml: {name} 有 {n} 个汉字，超过规范 §9.8 的 {limit} 上限 → {body}"
            )
        for category, words in banned.items():
            for w in words:
                if w in body:
                    errors.append(
                        f"strings.xml: {name} 命中禁用词[{category}]「{w}」→ {body}"
                    )
        # 概率数字：形如 `30%`（`%1$d` 占位符不会命中，因为 % 在前）
        if re.search(r"\d\s*%", plain):
            errors.append(f"strings.xml: {name} 含概率数字 → {body}")


def main() -> int:
    if not RES.exists():
        print(f"找不到资源目录：{RES}")
        return 1

    check_duplicates()
    check_references()
    check_night_overrides()
    check_shell_line_endings()
    check_signal_copy()

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
