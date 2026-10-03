# Healix 中断交接文档

> 更新时间：2026-10-03 19:40
> 用途：让下一个会话能**无上下文**接手。
> 一律只写客观事实，不做推测性美化。

---

## 一、当前状态（一句话）

**CI 连续 10 轮全绿，已产出可安装 APK（18,676,819 B / 17.81 MB）。**
本轮修复了用户报告的三项 bug，其中「配置不生效」与「记一笔无法联网」是**同一个根因**
（settings 键名读写分裂，见 3.9）；同时补全了接入按钮、灰阶气泡、离线状态。
**当前交付 APK = run#19 产物**，签名 CN=Android Debug，**首次安装前请卸载旧版**（见 3.6）。

| 项 | 值 |
|---|---|
| 本地 HEAD | `0fee928` |
| 远程 main | `38be9d7421cc` |
| 远程 tree == 本地 tree | ✅ 是（`e1dabf14b152`） |
| 仓库 | https://github.com/2026heshao/Healix （Public） |
| 最近 CI | **#19 `38be9d74` ✅ success** |
| 本地预检 | 三连全绿（资源 / Kotlin **10 类** / 67 断言） |
| APK | `app-debug.apk` / 桌面 `Healix-v0.1-测试版.apk`，MD5 `34a69af802450058254948206ee50e4c` |
| APK 签名指纹 | SHA-256 `98:04:E1:F5:05:15:E9:79:96:2E:39:80:CA:42:1D:D2:D8:5F:65:83:2C:94:A1:9B:FF:B3:87:3C:AF:E3:A1:5C` |

---

## 二、凭据从哪里拿（不用再问用户）

**GitHub PAT 存在知识库**：`D:\桌面\AI Port\my_kb\github\账号与仓库.md` 第 22 行。
直接读该文件，不要向用户重复索要。

> ⚠️ 该 PAT 出现在知识库文档中，属长期凭据。用完不要写入任何代码或配置文件。

---

## 三、本轮（run#8 → run#15）完成的事

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

### 3.6 APK 交付与签名（run#14 复查结论）

**artifact 是两层包裹**：CI 产物 `healix-debug-apk`（7,007,019 B）是 ZIP 外壳，
里面才是真正的 `app-debug.apk`（18,645,124 B / 903 条目 / 9 个 dex）。
**必须解包后再装**，直接装外壳会失败。

**签名是 debug 自签**（不是 CI 复用固定 keystore）：

| 项 | 值 |
|---|---|
| 签名方案 | APK Signature Scheme **v2**（无 v3、无 v1/JAR 签名） |
| 证书主体 | `CN=Android Debug, O=Android, C=US` |
| 有效期 | 2026-10-03 → 2056-09-25 |

⚠️ **每次 CI 都重新生成一次自签证书**，因此每轮 APK 签名指纹都不同
（run#12 证书 sha256 `0eb9536c…`，run#14 `3009b624…`）。
后果：**覆盖安装会报「签名不一致」，必须先卸载旧版再装**。
若要后续支持覆盖升级，需在 CI 侧固定 keystore（当前未做，属待办）。

### 3.7 换行符漂移修复（CRLF → LF）

`push_via_api.py` 每轮都告警「工作区与 git blob 不一致（5 个文件）」。
根因：`core.autocrlf=true` 在 checkout 时写 CRLF，而 `.gitattributes`
声明 `* text=auto eol=lf` 入库为 LF —— 两侧口径不一致，且推送脚本在
读取时会**回退用 git blob**，存在部署到旧字节的隐患。
修复：把磁盘侧 6 个含 CRLF 的文件（5 个源文件 + `regression_report.json`）
统一改回 LF。验证：逐字节比对全部 OK，`git diff --numstat` 为空，三连全绿。

### 3.8 新增 `pipeline/dump_manifest.py`（无 SDK 反编译清单）

本机无 aapt2 / apktool，无法核对「APK 里最终落地的清单」。
源码清单会被 AGP **合并**并注入 WorkManager / ProfileInstaller / Room / Emoji2
等库组件，**两侧并不等价** —— 必须直接读 APK。

纯标准库 AXML 解析器，4 种模式：`summary`（默认）/ `tree` / `strings` / `browser`。

```bash
$PY pipeline/dump_manifest.py app-debug.apk summary   # 组件 + intent-filter
$PY pipeline/dump_manifest.py app-debug.apk browser   # 是否有浏览器型入口
```

**实测结论（回答用户"微信里没有浏览器选项"）**：
APK 内 **0 个**组件声明 `VIEW + BROWSABLE + http/https` → 无浏览器入口，这是**正确行为**。
全 APK 的 intent-filter 只有 3 处属于项目自身：`MainActivity`(MAIN+LAUNCHER)、
`BootReceiver`(BOOT_COMPLETED / MY_PACKAGE_REPLACED)；
其余全是库自带（WorkManager 约束代理、profileinstaller）。

⚠️ 解析踩坑（5 轮才对，改这个工具前先读）：
1. `strings_start` 是**相对 chunk 起点**，不是相对 offset 数组起点。
   正确 `data_base = sp + strings_start`。误加 `header_size` 会整体偏移 28 字节，
   读出 `'ame\x00permission\x00...'` 这种跨条拼接假象 → 极易误判成"字符串池损坏"。
2. `ResXMLTree_attribute` 20 字节 = `ns(4) name(4) rawValue(4) size(2) res0(1) dataType(1) data(4)`。
   `rawValue` 是独立字段；`dataType` 在 +15、`data` 在 +16。从 rawValue 取会全变 `0xff:0xffffff`。

---

### 3.9 三项用户报告的 Bug 修复（run#19）★

用户报告原文：
```
1、AI模型没有真实的接入按钮，配置无法生效
2、AI对话框要做左右气泡
3、记一笔无法正常联网，要保证wife和移动数据都可正常接入
```

#### 3.9.1 ★ 根因：① 与 ③ 是**同一个 bug** —— settings 键名读写分裂

**写端（SettingsActivity）与读端（EventRepository / QuotaGuard）用了两套不同键名：**

| 设置项 | 写入键名 | 读取键名 | 状态 |
|---|---|---|---|
| 接口地址 | `base_url` | `provider_base_url` | ❌ |
| 模型名 | `model` | `provider_model` | ❌ |
| 重试次数 | `retry_max` | `retry_max_retries` | ❌ |
| 服务商 | `provider` | `provider_name` | ❌ |
| 每日调用上限 | `daily_quota` | `quota_daily_call_limit` | ❌ |
| 每日对话上限 | `chat_quota` | `quota_daily_chat_limit` | ❌ |
| 退避初始间隔 | `retry_base_seconds` | `retry_base_seconds` | ✅ |
| 日界线 | `day_start_hour` | `day_start_hour` | ✅ |

**后果链**：设置页写入 `base_url`/`model` → `loadProviderConfig()` 读 `provider_base_url`
→ null → `submit()` 走 `markFailed("provider_not_configured")`
→ **一次 HTTP 请求都没发出去**。

⚠️ **最强误导线**：点「测试连通性」**能过** —— 它走 `testConnectivity()` 读的是
`SettingsViewModel._values`（与写端同名），发的是真请求。
「测试能过、实际不能用」使排查成本极高。

**修复**：新增 `db/SettingsKeys.kt` 作为键名**唯一事实来源**，
读写两端全部改为引用它（保留 `KEY_*` 公开名不动调用点）。
选短名（`base_url`）为正式名，因为已被用户设备写进 SQLite —— 换长名等于让存量配置失效。

#### 3.9.2 ① 接入按钮（心智模型补全）

键名修好只是"配置真能生效"；用户说"没有真实的接入按钮"还包含**心智模型**问题：
填完四个框后没有任何"确认采用"的动作与反馈。

- 布局新增 `btnApply`（主按钮「接入并启用」）+ `applyStatus`（**常驻**状态条）
- 原 `btnTest` 降级为**文字按钮**（避免两个同权重主按钮）
- `SettingsViewModel.applyProvider()`：逐项校验（**缺哪项点名哪项**）→ 落库
  → 发一次真实请求 → 状态条变「已接入 · 服务商 · 模型名」
- `_applied` 在 `reload()` 里**纯本地**计算（不发请求）—— 状态条是常驻 UI，不能每次进页都发请求

#### 3.9.3 ② 灰阶左右气泡

原实现是「无气泡，只靠对齐 + 灰度」（类注释写着"核心决策：不用彩色气泡"）。
用户要求明确气泡 → 已覆盖该决策。**但未破坏设计规范 v3**：只用了既有中性色。

| 角色 | 背景 | 文字 | 对齐 |
|---|---|---|---|
| 用户 | `text_1` (#1A1918) 实底 | 白 (`btn_primary_text`) | 靠右 |
| 助理 | `surface` (#FFFFFF) 底 + 1dp `line` 描边 | `text_1` | 靠左 |

- 新增 `drawable/bg_bubble_user.xml` / `bg_bubble_assistant.xml`（圆角 8dp，无阴影）
- `ChatAdapter` 重写：外层 `LinearLayout` 管对齐 + 78% 最大宽度，内层 `TextView` 带背景
  - 外层/内层分工的原因：`TextView` 直接带 background + wrap_content 时，
    padding 会算进宽度，导致长短文本视觉边距不一致
- 间距：同角色 6dp / 不同角色 16dp

#### 3.9.4 ③ 离线状态与网络可靠性

**关键概念纠正**：`needsConfiguration` 曾被映射成 `MainUiState.Offline` —— **语义错配**。
两者用户动作完全不同（填 key vs 检查网络）。且因键名 bug，每次提交都返回
`needsConfiguration=true`，于是界面上**一直显示"离线"**。

- 新增 `net/NetworkStatus.kt`：`isOnline()` 用 `INTERNET + VALIDATED` **双判据**
  - ⚠️ **不区分传输类型**（不用 `TRANSPORT_WIFI` 过滤）→ Wi-Fi 与移动数据天然都覆盖。
    这是"两种网络都能用"的**唯一关键点** —— 本 App 从未把请求绑定到特定网络
    （无 `bindProcessToNetwork`），Android 默认就交给系统当前默认网络。
  - 用 `VALIDATED` 而非仅 `INTERNET`：排除"连上 Wi-Fi 但没网"（captive portal / 假热点）
- `MainUiState` 新增 `NotConfigured`，与 `Offline` 拆开
- `stateOf()`：`needsConfiguration` → NotConfigured；`network:`/`timeout:` 前缀 → Offline
- `MainViewModel.submit()`：**先落库再判网络** —— 用户目标是"记下来"不是"发 HTTP"，
  先判网络就 return 会丢文字
- `MainViewModel.retryFailedPending()`：批量重试
  - ⚠️ 用 `events` Flow 过滤，**不能用 `listPending()`** ——
    后者 SQL 只筛 `parse_status='pending'`，会**漏掉全部 failed 记录**，
    而 failed 恰恰是用户最想重试的
- `MainActivity`：`offlineBar` 可点，按 `lastUiState` 分流（离线→重试 / 未配置→设置页）
- `ChatViewModel.send()`：加断网前置判断，避免白等 15 秒超时

#### 3.9.5 新增静态检查第 9·10 类（防复发）★

`pipeline/check_kotlin.py` 新增 `check_settings_keys()` + `check_settings_keys_consistency()`：

1. **裸字符串访问 settings** → 报错
2. **第二套 `KEY_*` 常量**（字面量已知 but 常量名不同）→ 直接报错
3. **语义相似名**（`KEY_BASE_URL` vs `SettingsKeys.BASE_URL` 字面量不同）→ 报错
4. `SettingsKeys` 内部字面量重名 → 报错

**已验证有效**：把 `SettingsKeys.BASE_URL` 换回 `"provider_base_url"`，
CI 立即报「同一语义有两个不同键名」并阻断（exit 1）。

⚠️ **验证检查有效性时的踩坑**：不要写 `注入 && 跑检查; git checkout --` 这种链式命令 ——
`git checkout` 会在检查前还原文件，造成"检查没生效"的假象（我因此误判了两轮）。
正确做法：**先注入并 `grep` 确认文件已变，再单独跑检查**。

⚠️ **设计取舍**：b2 分支只对"确实操作 settings 表的文件"生效 ——
项目里还有大量**非 settings** 的 `KEY_*` 常量（RemoteInput key、EditText 字段名、
EncryptedSharedPreferences 存储键）。第一版无差别拦截，误报 11 项，已收窄。

---

## 四、CI 战绩（本轮）

| run | sha | 结果 |
|---|---|---|
| #8 | b57a638b | ❌ failure（setItems） |
| **#9** | 417bd1a7 | ✅ **首次构建成功** |
| **#10** | 4aceb0e7 | ✅ 新增 schema 产物 |
| **#11** | 87e26220 | ✅ schema 入库 |
| **#12** | ee53c7e3 | ✅ 背景项 |
| **#13** | bcbeb7c3 | ✅ HANDOFF 更新 |
| **#14** | a48abc01 | ✅ 背景项 APK |
| **#15** | a21bb63b | ✅ HANDOFF 同步 |
| **#17** | ec3a649f | ✅ 新增 dump_manifest.py |
| **#18** | 7ba26a91 | ✅ HANDOFF 补 3.8 |
| **#19** | 38be9d74 | ✅ **当前交付 APK（三项 bug 修复）** |

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
⚠️ 两个坑：① artifact 端点 302 到 Azure Blob，**带 Authorization 跟随会 401**，
必须剥掉该头（`NoAuthRedirect` 模式）；② 下载到的 ZIP 里才是真 APK，**必须解包**。

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
1. **S1 真机验收**：装桌面 `Healix-v0.1-测试版.apk`（MD5 `34a69af8…`）到 Magic6 Pro
   ⚠️ **首次安装 / 换轮次重装前先卸载旧版**（每轮签名证书不同，覆盖装必报签名冲突）
   → 设置页选「智谱 GLM」→ 填 API Key → 点 **「接入并启用」**（新按钮）
   → 应显示「接入成功」且状态条变「已接入 · 智谱 GLM · glm-4-flash」
   → 再点「测试连通性」应显示「连通 · 1.2s」
2. **验收本轮三项修复**（重点）：
   - **① 配置生效**：按上面接入成功后，回主界面「记一笔」输入「午饭吃了牛肉面」
     → 应能在记录列表看到类型/热量被填上（**修 bug 前这里必然失败**）
   - **② 气泡**：进对话页发一句话 → 自己的消息应靠右的深色气泡，AI 回复靠左的白色描边气泡
   - **③ 两种网络**：分别在 **Wi-Fi** 与 **关掉 Wi-Fi 用移动数据** 下各「记一笔」
     → 都应成功。断网时应显示「无网络，已先记下，联网后自动补全」且**提示条可点**（点了批量重试）
3. **验收背景项**：设置页「我的情况」填一段（如「乳糖不耐受，不吃香菜」）
   → 去对话页问饮食建议 → 看 AI 是否遵守该约束

### 未决项（原 16 项，现降至 8 项）
3. **1 项版本号**：`androidx.security:security-crypto` 当前钉 `1.1.0-alpha06`，
   需确认是否有正式版（`app/build.gradle` 约 103 行）
4. **4 项真机行为**（MagicOS）：`specialUse` 前台服务处理 / 自启动拦截 /
   后台启动限制 / RemoteInput 是否重复投递。见 `docs/待核实清单.md` 第三节
5. **3 项功能完整度**：通知文案日界线一致性 / 多事件拆分后的撤销语义 /
   `.5` 平局的舍入差异（均已在文档中标注为「MVP 接受」）
6. **CI 签名未固定**：debug 构建每轮生成新自签证书 → 无法覆盖升级。
   若要长期发测试包，需把 keystore 用 GitHub Secrets 注入
   （`signingConfigs` 引用 `System.getenv`），当前**刻意未做**（避免凭据进 CI）

### 技术债
7. **n06 用例波动**：多事件拆分含体重时模型输出不稳定
   （v1 ✓ / v2 ✓ / v2b ✗，`58.2` 被吞成 `0.0`）。属模型不确定性非规则缺陷。
   建议多跑 3 次取多数，或报告加稳定性指标
8. **Agent 化（L2/L3）**：地基缺口补完后才动。见 `功能补充与套壳选型.md` 第八章

### ⚠️ 安全
9. 智谱 GLM API Key（`871dbf24...`）曾出现在对话记录中 —— **建议重置**。
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
   ⚠️ **2026-10-03 变更**：对话页由「无气泡」改为**灰阶左右气泡**（用户明确要求）。
   气泡只用既有中性色（`text_1` / `surface` / `line`），**未引入新颜色 / 阴影**，
   规范其余部分仍然有效。详见 3.9.3。
10. **换行符**：`gradlew` / `*.sh` 必须 LF（`.gitattributes` 已规定）
11. **基础设施失败 ≠ 链路失败**：限流/超时/鉴权/网络不算解析缺陷
12. **★ settings 键名纪律**（2026-10-03 事故后新增）：
    任何 settings 键名必须只在 `db/SettingsKeys.kt` 定义，
    其它文件**禁止**写裸字符串或另起 `const val KEY_X = "字面量"`。
    `pipeline/check_kotlin.py` 的 `check_settings_keys()` 会在 CI 拦死。
    违反的后果是「测试能过、实际不能用」—— 极难排查。

---

## 九、文件速查

```
D:\桌面\AI Port\Healix\
├── pipeline\
│   ├── contract.py          # ★ 契约唯一来源（PROMPT_EXTRACT / PROMPT_VER）
│   ├── provider.py          # ★ 预设（已核实真实值）
│   ├── check_resources.py   # 资源静态检查（5 类）
│   ├── check_kotlin.py      # Kotlin/Room 静态检查（**10 类**，本轮 +2）
│   ├── push_via_api.py      # ★ Git Data API 推送
│   ├── pull_schemas.py      # ★ 拉回 Room schema
│   ├── dump_manifest.py     # ★ 无 SDK 反编译 APK 内 AXML 清单
│   ├── run_regression.py    # 回归脚本
│   └── tests\test_norm.py   # 67 项断言
├── app\src\main\java\com\healix\app\
│   ├── db\
│   │   ├── SettingsKeys.kt  # ★★ settings 键名唯一事实来源（本轮新增）
│   │   └── (7 entity / 6 DAO)
│   ├── net\
│   │   ├── LlmProvider.kt   # ★ Provider 抽象 + 预设（已核实）
│   │   └── NetworkStatus.kt # ★ Wi-Fi/移动数据可达性判断（本轮新增）
│   ├── parse\SchemaValidator.kt  # ★ 与 contract.py 同步
│   ├── repo\
│   │   ├── EventRepository.kt   # 串联 net → parse → db（本轮改键名）
│   │   └── QuotaGuard.kt        # 配额护栏（本轮改键名）
│   └── ui\
│       ├── ChatEngine.kt    # ★ systemPrompt 拼背景
│       ├── ChatActivity.kt  # ★ 左右气泡（本轮重写适配器）
│       ├── MainViewModel.kt # ★ Offline/NotConfigured 拆分（本轮）
│       └── SettingsActivity.kt  # ★ 接入按钮 + 背景输入框
├── app\src\main\res\drawable\
│   ├── bg_bubble_user.xml       # ★ 用户气泡（本轮新增）
│   └── bg_bubble_assistant.xml  # ★ 助理气泡（本轮新增）
├── app\schemas\             # ★ Room schema v1（已入库）
├── app-debug.apk            # ★ 可安装 APK（18,676,819 B，同 桌面/Healix-v0.1-测试版.apk）
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
