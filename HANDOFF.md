# Healix 中断交接文档

> 更新时间：2026-10-03 18:20
> 用途：让下一个会话能**无上下文**接手。
> 一律只写客观事实，不做推测性美化。

---

## 一、当前状态（一句话）

**CI 连续 4 轮全绿，项目首次构建成功并已产出可安装 APK（18,645,124 B / 17.78 MB）。**
Provider 配置已全部核实填入；Room schema v1 已入库；用户新需求「我的情况」背景项已实现并通过编译。

| 项 | 值 |
|---|---|
| 本地 HEAD | `899347a`（另有 schema 提交 `db3bda6`） |
| 远程 main | `ee53c7e392c7` |
| 远程 tree == 本地 tree | ✅ 是 |
| 仓库 | https://github.com/2026heshao/Healix （Public） |
| 最近 CI | **#12 `ee53c7e3` ✅ success** |
| 本地预检 | 三连全绿 |

---

## 二、凭据从哪里拿（不用再问用户）

**GitHub PAT 存在知识库**：`D:\桌面\AI Port\my_kb\github\账号与仓库.md` 第 22 行。
直接读该文件，不要向用户重复索要。

> ⚠️ 该 PAT 出现在知识库文档中，属长期凭据。用完不要写入任何代码或配置文件。

---

## 三、本轮（run#8 → run#12）完成的事

### 3.1 run#8 根因修复（关键）

| 项 | 内容 |
|---|---|
| 报错 | `SettingsActivity.kt:241:14 None of the following candidates is applicable` + `241:34/37 Cannot infer type` + `242:14 Unresolved reference 'setNegativeButton'` |
| 根因 | `vm.providerNames()` 返回 `List<String>`，直接传给 `AlertDialog.Builder.setItems()` —— **该 API 只接受 `Array<CharSequence>`**。候选全不适用 → `setItems` 返回类型无法确定 → 链式后 `setNegativeButton` 连带解析失败。**是单条根因，不是两个问题。** |
| 对照 | 同文件 `chooseActivity()` 传 `getStringArray(...)`（是 Array）→ 正常 |
| 修复 | `.toTypedArray()` |

### 3.2 新增静态检查第 8 类：`check_set_items_argument()`

⚠️ **关键教训**：`providerNames` 定义在 `SettingsViewModel.kt`，
规则**必须跨文件**收集「返回 List<String> 的函数名」。
初版只在文件内收集 → 零命中（假阴性），自查时才发现。

识别三种坏形态：直接传调用结果 / listOf 中转变量 / 内联 listOf。
并识别 `.toTypedArray()` 赋值以消除假阳性。
**自证 5 步**：坏例 A/B/C 全部报出 → 还原 → 无误报。

### 3.3 Provider 8 项 `[待核实]` → 真实值

全部取自官方文档原文（非记忆），Python/Kotlin 两侧脚本比对 8/8 相等：

| Provider | baseUrl | model |
|---|---|---|
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| DeepSeek | `https://api.deepseek.com` | `deepseek-chat` |
| OpenRouter | `https://openrouter.ai/api/v1` | `deepseek/deepseek-chat-v3.1:free` |
| 硅基流动 | `https://api.siliconflow.cn/v1` | `Qwen/Qwen3-8B` |

**模型选型依据**：智谱取 `glm-4-flash`（回归实测该族 21/22 通过、无 429）。
⚠️ 官方文档已标注 **`GLM-4.5 / GLM-4.5-X 即将下线`**，新旗舰 GLM-4.7 ——
故**未**选 `glm-4.5-flash`。

### 3.4 Room schema v1 入库

本机无 JDK 跑不出 → CI 编译期生成 artifact → 新增
`pipeline/pull_schemas.py` 拉回。已入库
`app/schemas/com.healix.app.db.AppDatabase/1.json`（1852 B，7 表，
identityHash `7410531927b5aa61859d6711508fdef9`）。

⚠️ **artifact 下载端点与 job logs 是同一个坑**：302 到 Azure Blob，
带 Authorization 跟随会 401，必须剥掉该头。

### 3.5 新功能：设置页「我的情况」背景项

用户原始需求：「让用户预先录入自己的状况，让 AI 的计划和一些决策基于这些背景」。

**用户拍板的 4 项**：
1. 一个空白多行输入框（不限具体字段）
2. 只进**计划/对话** prompt（不动抽取链）
3. 存 `settings` 表
4. 软上限 2000 字

**关键设计**（理由都写进了 commit `899347a` 的 message）：
- **`PROMPT_EXTRACT` 零改动、`PROMPT_VER` 保持 v2** → 回归基线 21/22 继续有效
- 拼在 `ChatEngine.systemPrompt()` **最前面**
- 存 settings 表 key = `user_background` → **无需 Room Migration**
- 常驻多行框而非弹窗；新增 `Widget.Healix.BlockInput` 样式
  （`SettingInput` 有固定行高，不适合多行）
- 保存 = 失焦 + `onPause` 兜底；不做 TextWatcher 实时写库
- **截断用 `InputFilter.LengthFilter` 在输入时做**，不在保存时做 ——
  保存时才截断属静默丢数据
- 回填加 `!hasFocus()` 条件，否则 `vm.reload()` 会覆写用户正在敲的字
- 背景为空时 prompt 整段省略，不留「（空）」噪声

---

## 四、CI 战绩（本轮）

| run | sha | 结果 |
|---|---|---|
| #8 | b57a638b | ❌ failure（setItems） |
| **#9** | 417bd1a7 | ✅ **首次构建成功** |
| **#10** | 4aceb0e7 | ✅ 新增 schema 产物 |
| **#11** | 87e26220 | ✅ schema 入库 |
| **#12** | ee53c7e3 | ✅ 背景项 |

---

## 五、本地开发环境（不变）

**用户明确决策：本机不装 JDK / Android SDK，全部依赖 GitHub Actions。**

唯一可用的 Python（managed，隔离环境）：
```
C:\Users\LENOVO\.workbuddy\binaries\python\versions\3.13.12\python.exe
```
> 不要用裸 `python`（PATH 里没有）；Git Bash 里也没有 `java`。

### 本地预检三连（每次推送前必跑）

```bash
PY="C:/Users/LENOVO/.workbuddy/binaries/python/versions/3.13.12/python.exe"
cd "D:/桌面/AI Port/Healix"
$PY pipeline/check_resources.py   # 资源静态检查
$PY pipeline/check_kotlin.py      # Kotlin/Room 静态检查（现 8 类）
$PY pipeline/tests/test_norm.py   # Python 合约自测（67 断言）
```

### 推送代码的方法（git push 走不通）

```bash
$PY pipeline/push_via_api.py <github_token>
$PY pipeline/push_via_api.py <github_token> --dry-run   # 先看变更范围
```

### 拉回 Room schema

```bash
$PY pipeline/pull_schemas.py <github_token>              # 自动取最近成功的 run
$PY pipeline/pull_schemas.py <github_token> --run-id <id>
```

### 下载 APK

`/tmp/healix_apk.py` 的思路（artifact 下载 + 无鉴权重定向），
或直接改 run id 复用脚本。

---

## 六、两个必须遵守的实现纪律（血的教训）

1. **检查器必须先剥注释再扫**（`strip_comments` / `code_lines`）。
   扫描注释 = 自己给自己造假阳性。
2. **检查器必须自证**：每个新规则都注入已知坏例 → 确认报出 → 还原 → 确认无误报。
   **宁可不要，也不留会误报的检查。**
   （本轮主动**没加**死字符串检查：实测 19 个未引用 string 中 18 个是
   为 Agent 化 S3/真机适配预留的，加了必然噪声。）

---

## 七、待办（下会话接手）

### 立即
1. **S1 真机验收**：装 `app-debug.apk` 到 Magic6 Pro
   → 设置页选「智谱 GLM」→ 填 API Key → 点「测试连通性」
   → 应显示「连通 · 1.2s」
2. **验收背景项**：设置页「我的情况」填一段（如「乳糖不耐受，不吃香菜」）
   → 去对话页问饮食建议 → 看 AI 是否遵守该约束

### 未决项（原 16 项，现降至 8 项）
3. **1 项版本号**：`androidx.security:security-crypto` 当前钉 `1.1.0-alpha06`，
   需确认是否有正式版（`app/build.gradle` 约 103 行）
4. **4 项真机行为**（MagicOS）：`specialUse` 前台服务处理 / 自启动拦截 /
   后台启动限制 / RemoteInput 是否重复投递。见 `docs/待核实清单.md` 第三节
5. **3 项功能完整度**：通知文案日界线一致性 / 多事件拆分后的撤销语义 /
   `.5` 平局的舍入差异（均已在文档中标注为「MVP 接受」）

### 技术债
6. **n06 用例波动**：多事件拆分含体重时模型输出不稳定
   （v1 ✓ / v2 ✓ / v2b ✗，`58.2` 被吞成 `0.0`）。属模型不确定性非规则缺陷。
   建议多跑 3 次取多数，或报告加稳定性指标
7. **Agent 化（L2/L3）**：地基缺口补完后才动。见 `功能补充与套壳选型.md` 第八章

### ⚠️ 安全
8. 智谱 GLM API Key（`871dbf24...`）曾出现在对话记录中 —— **建议重置**。
   GitHub PAT 存于知识库，属长期凭据，注意不要外泄。

---

## 八、关键设计约束（改代码前必读）

1. **Prompt 契约唯一来源**：`pipeline/contract.py` ↔ `SchemaValidator.kt`，
   `PROMPT_EXTRACT` 必须**逐字一致**（当前 v2 / 1535 字符 / 52 行）
2. **改 `PROMPT_EXTRACT` 才需递增 `PROMPT_VER`**。
   背景项**只改 `ChatEngine.systemPrompt`，故 PROMPT_VER 不动** ——
   这是刻意的边界，不要误以为漏了
3. **Room Migration 纪律**：`exportSchema=true` + 显式 Migration +
   **禁用 `fallbackToDestructiveMigration()`**（数据只有手机本地一份副本）
4. **区间钳制规则**：超界**回落默认值**，不钳边界（避免造假数据）
5. **幂等键** `client_event_id`：UNIQUE + `OnConflictStrategy.IGNORE`
6. **日界线 `day_key`**：本地时间减 4 小时后取日期
7. **C1 反幻觉**：不确定值一律写 `[待核实: 核实方式]` 占位符
8. **C6 安全**：API key 只存 EncryptedSharedPreferences，**绝不打印**
9. **设计规范 v3**：1 强调色 / 无卡片 / 无阴影 / 无 emoji / 圆角仅 8dp /
   字重仅 400·500
10. **换行符**：`gradlew` / `*.sh` 必须 LF（`.gitattributes` 已规定）
11. **基础设施失败 ≠ 链路失败**：限流/超时/鉴权/网络不算解析缺陷

---

## 九、文件速查

```
D:\桌面\AI Port\Healix\
├── pipeline\
│   ├── contract.py          # ★ 契约唯一来源（PROMPT_EXTRACT / PROMPT_VER）
│   ├── provider.py          # ★ 预设（已核实真实值）
│   ├── check_resources.py   # 资源静态检查（5 类）
│   ├── check_kotlin.py      # Kotlin/Room 静态检查（8 类）
│   ├── push_via_api.py      # ★ Git Data API 推送
│   ├── pull_schemas.py      # ★ 拉回 Room schema（新）
│   ├── run_regression.py    # 回归脚本
│   └── tests\test_norm.py   # 67 项断言
├── app\src\main\java\com\healix\app\
│   ├── db\                  # Room（7 entity / 6 DAO）
│   ├── net\LlmProvider.kt   # ★ Provider 抽象 + 预设（已核实）
│   ├── parse\SchemaValidator.kt  # ★ 与 contract.py 同步
│   ├── repo\EventRepository.kt   # 串联 net → parse → db
│   └── ui\
│       ├── ChatEngine.kt    # ★ systemPrompt 拼背景（本轮改）
│       └── SettingsActivity.kt   # ★ 背景输入框（本轮改）
├── app\schemas\             # ★ Room schema v1（已入库）
├── app-debug.apk            # ★ 可安装 APK（18,645,124 B，同 桌面/Healix-v0.1-测试版.apk）
├── .github\workflows\
│   ├── ci.yml               # 编译 + 单测 + 静态检查 + schema 产物
│   └── release.yml
├── docs\待核实清单.md        # 第二节已从「阻塞」改为「已核实」
├── .workbuddy\memory\2026-10-03.md   # 详细工作日志
└── HANDOFF.md               # 本文件
```

---

## 十、给下一个会话的提醒

1. **不要本机装工具链**（用户明确决策，已问过）
2. **不要用裸 `python`**，用 managed 路径（见第五节）
3. **推送一律走 `push_via_api.py`**，不要试 `git push`（会挂在网络）
4. **改动后必跑三连预检**，再推
5. **新增静态检查规则必须自证**（注入坏例 → 报出 → 还原 → 无误报）
6. **新增检查必须先剥注释**
7. **PAT 从知识库读**（第二节），不要重复问用户
8. 用户偏好：**细节详尽、中文、每个结论给依据**；
   改 prompt 要说明变更点；提交信息要写「问题/根因/修复/验证」结构
9. **有不明白的地方一定问用户，不可擅自做主**；
   需要决策的地方要询问；下载/安装任何东西需先经同意
