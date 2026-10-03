#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
dump_manifest.py —— 反编译 APK 内二进制 AndroidManifest.xml 为可读文本。

用途：本机无 Android SDK（无 aapt2 / apktool），需要静态核对
      "APK 里最终落地的清单" 与 "源码清单" 是否一致时使用。

⚠️ 重要背景（踩坑记录）：
  - 源码 `app/src/main/AndroidManifest.xml` 会被 AGP **合并**并注入大量库组件
    （WorkManager / ProfileInstaller / Room / Emoji2 等），
    所以"源码写了什么"≠"APK 里是什么"，必须直接读 APK。
  - AXML 字符串池的 `stringsStart` 字段是**相对 chunk 起点**（不是相对 offset 数组起点），
    正确公式是 `data_base = chunk_offset + stringsStart`。
    误当成 `chunk_offset + headerSize + stringsStart` 会让每条字符串**偏移 28 字节**，
    读出 `'ame\\x00\\npermission\\x00...'` 这种"看起来像拼接"的假象 —— 极易误判为
    "多处字符串重叠"，实际是解析器算错了 base。
  - 用**索引长度**（UTF-16 码元数）读，不要用字节长度读，否则字符串被截断。
  - 属性名常量池含命名空间前缀（形如 `http://...:name`），需按 ':' 取裸名。

用法：
  python pipeline/dump_manifest.py app-debug.apk            # 打印完整元素树
  python pipeline/dump_manifest.py app-debug.apk summary    # 只列组件与 intent-filter
  python pipeline/dump_manifest.py app-debug.apk strings    # 打印字符串池
  python pipeline/dump_manifest.py app-debug.apk browser    # 检查是否存在浏览器型入口
"""
import struct
import sys
import zipfile

CHUNK_STRING_POOL = 0x0001
CHUNK_XML = 0x0180
CHUNK_START_NS = 0x0100
CHUNK_END_NS = 0x0101
CHUNK_START_ELEM = 0x0102
CHUNK_END_ELEM = 0x0103
CHUNK_CDATA = 0x0104

TYPE_NULL = 0x00
TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12

COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")


def parse_string_pool(raw, sp):
    """解析 AXML 字符串池。返回 (strings, chunk_end)。

    ⚠️ 关键：`stringsStart` 是相对 **chunk 起点 sp** 的偏移，不是相对 offset 数组起点。
       写成 `sp + header_size + strings_start` 会整体偏移 28 字节（本 APK 实测），
       导致读出的字符串看起来像跨条拼接。见模块 docstring。
    """
    header_size = struct.unpack_from("<H", raw, sp + 2)[0]
    chunk_size = struct.unpack_from("<I", raw, sp + 4)[0]
    string_count = struct.unpack_from("<I", raw, sp + 8)[0]
    flags, strings_start, _styles_start = struct.unpack_from("<III", raw, sp + 16)
    utf8 = bool(flags & (1 << 8))

    index_base = sp + header_size
    data_base = sp + strings_start

    out = []
    for i in range(string_count):
        off = struct.unpack_from("<I", raw, index_base + 4 * i)[0]
        p = data_base + off
        if utf8:
            n = raw[p]
            p += 2 if (n & 0x80) else 1
            n2 = raw[p]
            p += 2 if (n2 & 0x80) else 1
            out.append(raw[p:p + n2].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", raw, p)[0]
            p += 2
            out.append(raw[p:p + n * 2].decode("utf-16-le", "replace"))
    return out, sp + chunk_size


def walk_chunks(raw, start):
    pos = start
    total = len(raw)
    while pos + 8 <= total:
        ctype, _header_size, size = struct.unpack_from("<HHI", raw, pos)
        if size < 8 or pos + size > total:
            break
        yield pos, ctype, size
        pos += size


def bare_name(key):
    """属性名去掉命名空间前缀（常量池里存的是 `uri:name` 形式）。"""
    return key.split(":")[-1]


def decode_attr(raw, strings, pos):
    """解码一个 XML 属性。

    ResXMLTree_attribute 布局（20 字节）：
        ns       uint32   命名空间字符串索引
        name     uint32   属性名字符串索引
        rawValue uint32   Res_value 的原始值（若 == 0xffffffff 表示无原始值）
        size     uint16   Res_value 数据长度
        res0     uint8
        dataType uint8
        data     uint32

    注意：`rawValue` 是**单独的字段**，不能从它取 dataType/data；
    dataType 在 +14 字节处，data 在 +16 字节处。
    """
    _ns, name_idx, raw_value = struct.unpack_from("<iii", raw, pos)
    data_type = raw[pos + 15]
    data = struct.unpack_from("<I", raw, pos + 16)[0]
    return bare_name(strings[name_idx] if 0 <= name_idx < len(strings) else "?"), \
        decode_value(strings, data_type, data, raw_value)


def decode_value(strings, data_type, data, raw_value=0xFFFFFFFF):
    """把 Res_value 的 (dataType, data) 还原成可读值。"""
    if data_type == TYPE_STRING:
        return strings[data] if 0 <= data < len(strings) else "?str%d" % data
    if data_type == TYPE_INT_DEC:
        return str(data)
    if data_type == TYPE_INT_HEX:
        return "0x%x" % data
    if data_type == TYPE_INT_BOOLEAN:
        return "true" if data else "false"
    if data_type == TYPE_REFERENCE:
        return "@0x%08x" % data
    if data_type == TYPE_NULL:
        return "null"
    return "t0x%x:0x%x" % (data_type, data)


def parse(apk_path, entry="AndroidManifest.xml"):
    """返回 (strings, events)；events 为统一后的 [(kind, depth, tag, attrs)]。"""
    with zipfile.ZipFile(apk_path) as z:
        raw = z.read(entry)

    chunks = list(walk_chunks(raw, 8))
    if not chunks or chunks[0][1] != CHUNK_STRING_POOL:
        raise SystemExit("不是合法 AXML：首个 chunk 不是 STRING_POOL")
    strings, _ = parse_string_pool(raw, chunks[0][0])

    def s(idx):
        return strings[idx] if 0 <= idx < len(strings) else "?%d" % idx

    events = []
    depth = 0
    for pos, ctype, _size in chunks:
        if ctype == CHUNK_START_ELEM:
            _ns, name_idx = struct.unpack_from("<ii", raw, pos + 16)
            attr_start, _asz, attr_count = struct.unpack_from("<HHH", raw, pos + 24)
            attrs = {}
            # 属性区起点 = 元素头起算；元素头固定 16 字节（chunk8 + ns4 + name4）
            ap = pos + 16 + attr_start
            for _ in range(attr_count):
                key, val = decode_attr(raw, strings, ap)
                attrs[key] = val
                ap += 20
            events.append(("start", depth, s(name_idx), attrs))
            depth += 1
        elif ctype == CHUNK_END_ELEM:
            depth -= 1
            events.append(("end", depth, None, None))
    return strings, events


def dump_tree(apk_path):
    _strings, events = parse(apk_path)
    lines = []
    for kind, depth, tag, attrs in events:
        if kind != "start":
            continue
        body = ", ".join("%s=%s" % (k, v) for k, v in attrs.items())
        lines.append("%s<%s%s>" % ("  " * depth, tag, " " + body if body else ""))
    return "\n".join(lines)


def dump_summary(apk_path):
    """只列组件及其 intent-filter 内容 —— 回答"这个 APK 注册了哪些入口"最直接。"""
    _strings, events = parse(apk_path)
    out = []
    cur = None
    in_filter = False
    stack = []
    for kind, depth, tag, attrs in events:
        if kind == "start":
            stack.append(tag)
            if tag in COMPONENT_TAGS:
                cur = {"tag": tag, "attrs": attrs, "actions": [], "cats": [],
                       "data": []}
                out.append(cur)
                in_filter = False
            elif tag == "intent-filter" and cur is not None:
                in_filter = True
            elif tag == "action" and in_filter and cur is not None:
                cur["actions"].append(attrs.get("name", "?"))
            elif tag == "category" and in_filter and cur is not None:
                cur["cats"].append(attrs.get("name", "?"))
            elif tag == "data" and in_filter and cur is not None:
                cur["data"].append(attrs)
        else:
            if stack:
                popped = stack.pop()
                if popped == "intent-filter":
                    in_filter = False
    lines = []
    for c in out:
        a = c["attrs"]
        exported = a.get("exported", "-")
        lines.append("<%s name=%s exported=%s%s>" % (
            c["tag"], a.get("name", "?"), exported,
            (" process=%s" % a["process"]) if "process" in a else ""))
        for x in c["actions"]:
            lines.append("      action:   %s" % x)
        for x in c["cats"]:
            lines.append("      category: %s" % x)
        for d in c["data"]:
            lines.append("      data:     %s" % d)
    return "\n".join(lines)


def dump_strings(apk_path):
    with zipfile.ZipFile(apk_path) as z:
        raw = z.read("AndroidManifest.xml")
    strings, _ = parse_string_pool(raw, 8)
    return "\n".join("%3d %r" % (i, s) for i, s in enumerate(strings))


def check_browser_entry(apk_path):
    """断言：清单里是否存在浏览器型 intent-filter（VIEW + BROWSABLE + http/https）。

    这个方法用来回答"能否被选为浏览器打开 APK/网页"——Healix 不需要这个能力，
    因此预期是"没有"。返回 (是否存在, 说明)。
    """
    _strings, events = parse(apk_path)
    cur = None
    in_filter = False
    stack = []
    for kind, depth, tag, attrs in events:
        if kind == "start":
            stack.append(tag)
            if tag in COMPONENT_TAGS:
                cur = {"tag": tag, "attrs": attrs, "actions": set(),
                       "cats": set(), "schemes": set()}
            elif tag == "intent-filter" and cur is not None:
                in_filter = True
            elif tag == "action" and in_filter and cur is not None:
                cur["actions"].add(attrs.get("name", ""))
            elif tag == "category" and in_filter and cur is not None:
                cur["cats"].add(attrs.get("name", ""))
            elif tag == "data" and in_filter and cur is not None:
                if "scheme" in attrs:
                    cur["schemes"].add(attrs["scheme"])
            if tag in COMPONENT_TAGS and cur is not None:
                pass
        else:
            if stack:
                popped = stack.pop()
                if popped == "intent-filter":
                    in_filter = False
                if popped in COMPONENT_TAGS and cur is not None:
                    missing = {"android.intent.action.VIEW"} - cur["actions"]
                    missing_cat = {"android.intent.category.BROWSABLE"} - cur["cats"]
                    http = cur["schemes"] & {"http", "https"}
                    if not missing and not missing_cat and http:
                        return True, "存在浏览器型 intent-filter: %s" % cur["attrs"].get("name")
                    cur = None
    return False, "没有任何组件声明 VIEW + BROWSABLE + http/https → 不会被系统当作浏览器候选"


if __name__ == "__main__":
    apk = sys.argv[1] if len(sys.argv) > 1 else "app-debug.apk"
    mode = sys.argv[2] if len(sys.argv) > 2 else "summary"
    if mode == "tree":
        print(dump_tree(apk))
    elif mode == "strings":
        print(dump_strings(apk))
    elif mode == "browser":
        exists, why = check_browser_entry(apk)
        print("浏览器型入口存在:", exists)
        print(why)
    else:
        print(dump_summary(apk))
