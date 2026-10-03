#!/usr/bin/env python3
"""通过 GitHub Git Data API 推送本地提交（绕开被阻断的 git-over-HTTPS）。

背景：本机网络下 `https://github.com` 返回 502（CONNECT tunnel failed），
git push 走不通；但 `https://api.github.com` 正常（200）。
因此改用 REST API 完成等价推送 —— 不是降级，而是换一条可用通道。

做法（标准 Git Data API 流程）：
  1. 读远程 main 的 commit SHA + 其 tree SHA
  2. 用本地 HEAD 的 tree（git ls-tree 递归），为有变更的路径创建 blob
  3. 新建 tree（基于远程 tree，覆盖本地有变更的路径，删除本地已删的）
  4. 建 commit（parent = 远程 main SHA，message 取自本地 HEAD）
  5. 更新 refs/heads/main 指向新 commit

这样推送后**远程 HEAD 的 tree 与本地 HEAD 的 tree 完全一致**，
提交信息也保留本地的内容。

用法：
  python pipeline/push_via_api.py <token> [--dry-run]
"""
from __future__ import annotations

import base64
import json
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OWNER = "2026heshao"
REPO = "Healix"
BRANCH = "main"
API = f"https://api.github.com/repos/{OWNER}/{REPO}"


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, check=True
    ).stdout.strip()


def api(method: str, url: str, token: str, payload: dict | None = None) -> dict:
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        raise RuntimeError(f"HTTP {e.code} on {method} {url}\n{body[:600]}") from e


def main() -> int:
    if len(sys.argv) < 2:
        print("用法: python pipeline/push_via_api.py <token> [--dry-run]")
        return 2
    token = sys.argv[1]
    dry = "--dry-run" in sys.argv

    local_head = git("rev-parse", "HEAD")
    local_tree = git("rev-parse", "HEAD^{tree}")
    msg = git("log", "-1", "--pretty=%B")

    print(f"本地 HEAD : {local_head[:12]}")
    print(f"本地 tree : {local_tree[:12]}")

    remote_ref = api("GET", f"{API}/git/ref/heads/{BRANCH}", token)
    remote_sha = remote_ref["object"]["sha"]
    remote_commit = api("GET", f"{API}/git/commits/{remote_sha}", token)
    remote_tree_sha = remote_commit["tree"]["sha"]
    print(f"远程 HEAD : {remote_sha[:12]}")
    print(f"远程 tree : {remote_tree_sha[:12]}")

    if remote_tree_sha == local_tree:
        print("\n✅ 远程与本地 tree 已一致，无需推送。")
        return 0

    # 用本地 tree 递归列文件（相对路径 -> mode）
    raw = git("ls-tree", "-r", "-z", "HEAD")
    local_files: dict[str, str] = {}
    for entry in raw.split("\0"):
        if not entry.strip():
            continue
        meta, path = entry.split("\t", 1)
        mode, _type, _sha = meta.split()
        local_files[path] = mode

    # 远程 tree 递归列文件
    remote_tree = api("GET", f"{API}/git/trees/{remote_tree_sha}?recursive=1", token)
    remote_files: dict[str, str] = {}
    for item in remote_tree.get("tree", []):
        if item["type"] == "blob":
            remote_files[item["path"]] = item["sha"]

    print(f"本地文件数: {len(local_files)} | 远程文件数: {len(remote_files)}")

    # 前置体检：工作区文件与 git blob 不一致的，逐个报出来。
    #
    # 这不是自己吓自己 —— core.autocrlf=true 的 Windows 上，工作区带 CRLF、
    # git 存 LF，两者天然不同。它本身无害（git 提交时已归一化），但如果
    # 我们误读了工作区来上传，就会把 CRLF 版本推上去（正是之前那个 bug）。
    # 这里主动暴露，让"读错源"变成可见信号而不是静默错误。
    dirty = []
    for path in sorted(local_files):
        wt = ROOT / path
        if not wt.exists():
            continue
        blob_bytes = subprocess.run(
            ["git", "cat-file", "-p", f"HEAD:{path}"],
            cwd=ROOT, capture_output=True, check=True,
        ).stdout
        if wt.read_bytes() != blob_bytes:
            dirty.append(path)
    if dirty:
        print(f"⚠️  工作区与 git blob 不一致（{len(dirty)} 个，读取时将使用 git blob）：")
        for p in dirty[:10]:
            print(f"     {p}")
        if len(dirty) > 10:
            print(f"     ... 另有 {len(dirty) - 10} 个")

    tree_entries: list[dict] = []
    added = modified = 0
    for path, mode in sorted(local_files.items()):
        blob_sha = git("rev-parse", f"HEAD:{path}")
        is_new = path not in remote_files
        changed = is_new or remote_files.get(path) != blob_sha
        if is_new:
            added += 1
        elif changed:
            modified += 1
        else:
            continue
        # 内容必须取 **git 归一化后的 blob**，而不是工作区原始文件。
        #
        # ⚠️ 踩坑记录（真实 bug，已修）：
        #    之前这里写的是 (ROOT / path).read_bytes()，即直接读工作区文件。
        #    本机 core.autocrlf=true，Windows 工作区文件带 CRLF，而 git 存储
        #    的是 LF。于是推上去的 blob 与本地 HEAD 的 blob 内容不一致：
        #      strings.xml  blob=8796B(LF)  worktree=8980B(CRLF)，差 184B
        #    导致远程 tree sha ≠ 本地 tree sha（脚本最后一行校验报 ❌）。
        #    更严重的是，这会污染 .gitattributes 设定的换行符纪律：
        #    gradlew 若被推成 CRLF，Linux runner 会报 bad interpreter: /bin/sh^M。
        #
        #    改用 git cat-file 读 HEAD 里的 blob，保证推上去的就是 git 认为
        #    的规范内容，与本地 tree 逐字节一致。
        content = subprocess.run(
            ["git", "cat-file", "-p", f"HEAD:{path}"],
            cwd=ROOT, capture_output=True, check=True,
        ).stdout
        blob = api("POST", f"{API}/git/blobs", token, {
            "content": base64.b64encode(content).decode(),
            "encoding": "base64",
        })
        tree_entries.append({
            "path": path,
            "mode": "100755" if mode == "100755" else "100644",
            "type": "blob",
            "sha": blob["sha"],
        })

    deleted = sorted(set(remote_files) - set(local_files))
    for path in deleted:
        tree_entries.append({
            "path": path, "mode": "100644", "type": "blob", "sha": None,
        })

    print(f"变更: 新增 {added} / 修改 {modified} / 删除 {len(deleted)}")
    for p in deleted:
        print(f"   删除 {p}")

    if not tree_entries:
        print("无变更需要提交")
        return 0

    if dry:
        print("\n[--dry-run] 将提交以下路径：")
        for e in tree_entries:
            action = "删除" if e["sha"] is None else "写入"
            print(f"   {action} {e['path']}")
        return 0

    new_tree = api("POST", f"{API}/git/trees", token, {
        "base_tree": remote_tree_sha,
        "tree": tree_entries,
    })
    print(f"新 tree: {new_tree['sha'][:12]}")

    new_commit = api("POST", f"{API}/git/commits", token, {
        "message": msg,
        "tree": new_tree["sha"],
        "parents": [remote_sha],
    })
    print(f"新 commit: {new_commit['sha'][:12]}")

    api("PATCH", f"{API}/git/refs/heads/{BRANCH}", token, {
        "sha": new_commit["sha"], "force": False,
    })
    print(f"\n✅ 已推送 {BRANCH} -> {new_commit['sha'][:12]}")

    # 校验远程 tree 与本地一致
    check = api("GET", f"{API}/git/commits/{new_commit['sha']}", token)
    same = check["tree"]["sha"] == local_tree
    print(f"   远程 tree == 本地 tree ? {'✅ 是' if same else '❌ 否'}")
    return 0 if same else 1


if __name__ == "__main__":
    sys.exit(main())
