#!/usr/bin/env python3
"""从 GitHub Actions 产物里拉回 Room 导出的 schema JSON。

为什么需要这个脚本：
  `exportSchema = true` 会让 Room 在编译期生成
  `app/schemas/<全限定库名>/<version>.json`。这些文件是**迁移历史的唯一凭据**，
  必须入库（`.gitignore` 里已显式 `!app/schemas/`）。
  但本机不装 JDK/Android SDK（用户明确决策），跑不出这些文件 ——
  只能让 CI 编译时生成、上传为 artifact，再从这里拉回来提交。

用法：
    python pipeline/pull_schemas.py <github_token> [--run-id <id>]

不带 --run-id 时自动取最近一次成功的 run。

⚠️ 关键坑（实测踩过）：
  Actions 的 artifacts 下载端点带 Authorization 请求时会 **302 重定向到
  Azure Blob Storage**。直接跟随会 401，因为 Authorization 头不该带给存储后端。
  必须用自定义 HTTPRedirectHandler，跟随重定向时**不带** Authorization。
  这与 `/actions/jobs/{id}/logs` 是同一个坑。
"""
from __future__ import annotations

import io
import json
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OWNER_REPO = "2026heshao/Healix"
API = f"https://api.github.com/repos/{OWNER_REPO}"
SCHEMA_DIR = ROOT / "app" / "schemas"


def make_opener() -> urllib.request.OpenerDirector:
    """跟随重定向时**剥掉** Authorization 头（否则 S3/Azure 返回 401）。"""

    class NoAuthRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            new = urllib.request.Request(newurl)
            new.add_header("User-Agent", "healix-pull-schemas")
            return new

    return urllib.request.build_opener(NoAuthRedirect)


def api_get(url: str, token: str) -> dict:
    req = urllib.request.Request(url)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "healix-pull-schemas")
    with urllib.request.urlopen(req, timeout=90) as resp:
        return json.loads(resp.read().decode() or "{}")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    token = sys.argv[1]
    run_id = None
    if "--run-id" in sys.argv:
        run_id = sys.argv[sys.argv.index("--run-id") + 1]

    if run_id is None:
        runs = api_get(f"{API}/actions/runs?per_page=20", token)
        for r in runs.get("workflow_runs", []):
            if r["conclusion"] == "success":
                run_id = r["id"]
                print(f"取最近成功的 run: #{r['run_number']} {r['head_sha'][:8]} id={run_id}")
                break
        if run_id is None:
            print("❌ 找不到成功的 run")
            return 1

    arts = api_get(f"{API}/actions/runs/{run_id}/artifacts", token)
    target = next(
        (a for a in arts.get("artifacts", []) if a["name"] == "healix-room-schema"),
        None,
    )
    if target is None:
        print("❌ 该 run 没有 healix-room-schema 产物。")
        print("   可能原因：run 早于 CI 加入上传步骤；或 exportSchema 未生效。")
        print(f"   现有产物：{[a['name'] for a in arts.get('artifacts', [])]}")
        return 1

    print(f"找到产物 id={target['id']} size={target['size_in_bytes']}B")

    opener = make_opener()
    req = urllib.request.Request(
        f"{API}/actions/artifacts/{target['id']}/zip"
    )
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    data = opener.open(req, timeout=180).read()
    print(f"下载 {len(data)} 字节")

    SCHEMA_DIR.mkdir(parents=True, exist_ok=True)
    written = []
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        for name in zf.namelist():
            if not name.lower().endswith(".json"):
                continue
            # 去掉产物打包时的顶层 app/schemas/ 前缀，落到本仓库同路径
            rel = name
            for prefix in ("app/schemas/", "schemas/"):
                if rel.startswith(prefix):
                    rel = rel[len(prefix):]
                    break
            dest = SCHEMA_DIR / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(zf.read(name))
            written.append(str(dest.relative_to(ROOT)))

    if not written:
        print("❌ 产物里没有 .json 文件")
        return 1

    print(f"\n✅ 写入 {len(written)} 个文件：")
    for w in written:
        print(f"   {w}")
    print("\n下一步：git add app/schemas && 提交（迁移历史必须入库）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
