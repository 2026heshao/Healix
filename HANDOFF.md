# Healix 中断交接文档

> 更新时间：2026-10-03 22:55
> 用途：让下一个会话能**无上下文**接手。
> 一律只写客观事实，不做推测性美化。

---

## 一、当前状态（一句话）

**P0 + P1 全部落地，CI #22 / #23 连续两绿；远程 main == 本地 HEAD = `5d8ae3dd`。**
App 从「增重热量记录器」升级为「目标驱动的多维度状态管理」：
DB v2（11 表）、规则层（H1–H8 / T1–T3，0 次 AI 调用）、
状态详情页、计划页训练 Tab、设置页三组（目标 / 提醒 / 隐私）全部可用。

| 项 | 值 |
|---|---|
| 本地 HEAD | `5d8ae3dd` |
| 远程 main | `5d8ae3ddde4fb023cefd4338c6196e5a069bfa06` |
| 远程 tree == 本地 tree | ✅ 是（`ff7a790c…`） |
| 仓库 | https://github.com/2026heshao/Healix （Public） |
| 最近 CI | **#23 `5d8ae3dd` ✅ success**（#22 `d2b120c` ✅，含 P0+P1 主体） |
| 本地预检 | 三连全绿（资源 / Kotlin **14 类** / 67 断言） |
| Room schema | v1（7 表）+ **v2（11 表）均已入库** |
| APK | CI #23 产物 `healix-debug-apk`（ZIP 外壳 7,168,052 B，内含 `app-debug.apk`）。⚠️ artifact 下载**必须带 PAT**（有效来源见第二节）；签名仍是每轮自签 → **覆盖装前先卸载旧版** |

---

## 二、凭据从哪里拿（不用再问用户）

**GitHub PAT 有效来源：`D:\桌面\AI Port\my_kb\github\账号与仓库.md` 第 22 行（93 字符）。**
2026-10-03 23:05 实测 `GET /user` 返回 200（账号 `2026heshao`）—— 与本轮用户在对话中
提供的是**同一把**。需要推送 / 拉 APK 时直接读该文件，不必问用户。

> ⚠️ **别与另一条混淆**：`~/.workbuddy/changes-detail/` 里那条 **40 字符**的历史 PAT
> 才是失效的（401）—— 两者长度不同，曾险些拿错。
>
> 凭据纪律：临时拷贝**用完即删**；不写入任何代码、配置、文档。
> 本轮曾发现 Temp 目录残留 5 个内嵌明文 PAT 的一次性脚本，已全部清除。

---

## 三、本轮（run#8 → run#23）完成的事

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

### 3.10 ★ P0 + P1 交付（run#20 → #23，2026-10-03）

`功能扩展设计方案.md` 的 P0 + P1 全部落地。三个提交，共 **32 个文件 / 约 5,160 行新增**：

| 提交 | 内容 | 规模 |
|---|---|---|
| `cfe4504` | **DB 升 v2**：`goals` / `training_plans` / `body_signals` / `reminders` 4 张表 + Migration + DAO | 5 文件 +290 |
| `387429e` | **规则层**：聚合与求值分离，**0 次 AI 调用** | 4 文件 +622 |
| `e7cab46` | **UI 层**：状态行 / 状态详情页 / 训练 Tab / 设置页三组 | 23 文件 +4,253 |

**架构要点（改代码前先读）**：

| 结构 | 位置 | 为什么 |
|---|---|---|
| 聚合与求值分离 | `rules/HealthAggregator.kt`（唯一碰 DB）→ `rules/HealthRules.kt`（纯函数） | 规则可离线单测、可复用；混在一起就没法测 |
| 8 条习惯红线 H1–H8 + 3 条趋势 T1–T3 | `rules/HealthRules.kt`（`evaluate()`） | 优先级表 `priorityOf()` 在 80–85 行 |
| 预警去重 | `body_signals` 的 `UNIQUE(rule_id, day_key)` | 防「每日唠叨」 |
| 多维状态行**两态互斥** | `MainViewModel.kt:75–85` `sealed interface HomeStatus { Summary / Signal }` | 规范 §9.13，用户已拍板 |
| 隐私开关三处同步 | `SettingsKeys.HIDE_KCAL / HIDE_WEIGHT` → 首页 / 状态页 / 对话系统提示 | 隐藏时**整块不显示**，不是 `***`（PRD §14.3） |
| 目标兜底值唯一来源 | `db/GoalEntities.kt:102` `object GoalDefaults` | 见 3.10.2 |
| 训练 prompt 契约 | `pipeline/contract.py` `PROMPT_TRAINING` ↔ `ui/TrainingPlanner.kt` **逐字节一致** | `check_prompt_parity()` 会拦 |

**提交顺序即依赖顺序**：DB → 规则 → UI。

#### 3.10.1 CI #21 failure（4 个编译错误，2 条根因）

| 错误 | 根因 |
|---|---|
| `HealthAggregator.kt:111` Unresolved `minusDays` | `last7From` 是 **String**（`today.minusDays(6).toString()` 的结果），`String` 上没有 `minusDays`。日期减法必须在 `toString()` **之前**做 |
| `StatusDetailViewModel.kt:27/29/38` Unresolved 三个 `DEFAULT_*` | **顶层 data class 的默认参数非限定引用了同文件 `companion` 的常量** —— 顶层声明的解析域不含那个 companion。这是"单行正则"型静态检查器**原理上抓不到**的作用域错误 |

#### 3.10.2 目标兜底值收敛（顺带挖出）

同一组「膳食指南推荐量」此前散落 **5 处、名字还各不相同**（`DEFAULT_TRAIN_SESSIONS` /
`DEFAULT_SESSIONS_PER_WEEK` / `DEFAULT_GOAL_SESSIONS` / `DEFAULT_SESSIONS` / …）。
已收敛到 `db/GoalEntities.kt` 的 **`object GoalDefaults`**（3 次/150 分/7.5h/1700ml/2500kcal），
7 个文件改走它。
⚠️ `HealthRules` 的 H7（<150 分钟/周）**刻意不复用** —— 临床阈值不随用户改目标而变。

#### 3.10.3 新增检查器第 11–14 类（现共 14 类）

| 类 | 作用 | 教训 |
|---|---|---|
| 11 `check_prompt_parity` | prompt 双处**逐字节**一致（此前只写在文档里，机器不验） | 契约不进 CI = 迟早分叉 |
| 12 `check_signal_copy` | 预警双套文案长度（short ≤24 / full ≤52 汉字）+ 禁用词（疾病名/概率数字/游戏化） | 只数**汉字**，占位符不计入 |
| 13 `check_object_scope` | object / companion 成员出宿主必须限定引用 | 首版被**无体** `object X : Y` 骗到（`find("{")` 越界抢了别的类的括号）→ `{` 必须与声明头同行 |
| 14 `check_duplicate_constants` | 同名**且同值**的 const 跨文件重复（warning） | 刻意收紧：只是同名（各类 `TAG`）不算；转发别名不算 |

全部已自证：注入坏例 → 精确报出 → 还原 → 零误报。

#### 3.10.4 Room schema v2 入库

CI #22 首绿后由 `pipeline/pull_schemas.py` 拉回
`app/schemas/com.healix.app.db.AppDatabase/2.json`（11 表），
与 `check_kotlin.py` 静态解析出的表清单**逐字一致** —— 两条独立路径互证。
v1（7 表）/ v2（11 表）迁移凭据至此齐备。

#### 3.10.5 凭据卫生

推送 / 拉 artifact 用完的 PAT 已全部删除；Temp 里残留的
5 个内嵌明文 PAT 的一次性脚本、以及旧 40 字符令牌 `_pat.txt` 一并清除。

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
| **#19** | 38be9d74 | ✅ **交付 APK（三项 bug 修复）** |
| **#20** | 8b89f85e | ✅ HANDOFF 补 3.9 + 状态同步 |
| **#21** | d5dafb5a | ❌ failure（4 个编译错误，见 3.10.1） |
| **#22** | d2b120c | ✅ **P0+P1 修复后首绿** |
| **#23** | 5d8ae3dd | ✅ **当前（schema v2 入库）** |

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
$PY pipeline/check_resources.py   # 资源静态检查（5 类）
$PY pipeline/check_kotlin.py      # Kotlin/Room 静态检查（现 **14 类**）
$PY pipeline/tests/test_norm.py   # Python 合约自测（67 断言）
```

### 推送代码的方法（git push 走不通）

> ⚠️ 2026-10-03 变更：工具已迁到 `build/`（被 .gitignore 忽略，不进仓库）。
> 旧名 `pipeline/push_via_api.py` 已废弃。

```bash
$PY build/_gh_publish.py <PAT文件路径>             # 增量推送，逐对象校验 SHA
$PY build/_gh_fetch_chain.py <PAT文件路径>         # 远端历史分叉时补全祖先链
$PY build/_gh_ci.py <PAT文件路径> <run_id> [关键词] # 取 CI job 日志（失败时定位用）
```

`github.com` 直连 000 / 代理 502，但 `api.github.com`（200）与 `uploads.github.com`（302）通
→ 走 Git Data API。**提交日期必须按 `<epoch> +0800` 复现**，否则远端会出现
「同内容不同 SHA」的静默分叉（已踩过一次，修复过程见 `.workbuddy/memory/2026-10-03.md`）。

### 拉回 Room schema

```bash
$PY pipeline/pull_schemas.py <github_token>              # 自动取最近成功的 run
$PY pipeline/pull_schemas.py <github_token> --run-id <id>
```

### 下载 APK

**没有现成脚本**（本轮的一次性脚本已清理）。思路：CI 产物 `healix-debug-apk`
是 ZIP 外壳，**必须解包**才是 `app-debug.apk`。
⚠️ 两个坑：① artifact 端点 302 到 Azure Blob，**带 Authorization 跟随会 401**，
必须剥掉该头（`NoAuthRedirect` 模式，可参考 `pipeline/pull_schemas.py` 的 `make_opener()`）；
② 匿名**不可**下载（public 仓库实测 401），必须带 PAT。

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

### 立即（真机验收，APK = CI #23 产物 `healix-debug-apk`）

> ⚠️ **装前先卸载旧版**（每轮签名证书不同，覆盖装必报签名冲突）。
> 下载方式见第五节「下载 APK」—— 需向用户要 PAT。

**A. 上一轮遗留（run#19 三项修复，若尚未验收）**

- **① 配置生效**：设置页选「智谱 GLM」→ 填 API Key → 「接入并启用」→ 状态条变
  「已接入 · 智谱 GLM · glm-4-flash」→ 「测试连通性」显示「连通 · 1.2s」
  → 回主界面「记一笔」输入「午饭吃了牛肉面」→ 记录列表应出现类型/热量
- **② 气泡**：对话页自己的消息靠右深色、AI 靠左白色描边
- **③ 两种网络**：Wi-Fi 与移动数据各「记一笔」都应成功；断网显示
  「无网络，已先记下，联网后自动补全」且提示条可点（批量重试）

**B. 本轮新增（P0+P1，按 `功能扩展设计方案.md` 验收标准逐条）**

| # | 操作 | 预期 | 对应项 |
|---|---|---|---|
| 1 | 设置页 → 目标组 | 能改体重目标、每周训练次数；组顶有一次性「依据提示」（首次进入才显示） | `G1` |
| 2 | 首页看大数字下方 | ≥3 个维度状态行（运动 N/M · 睡眠 Nh · 体重 Nkg）；无数据的维度**不显示**；热量大数字与进度条原样保留 | `G2` |
| 3 | 点任一状态行 | 进状态详情页：7 日趋势图 + 信号列表；页内可切 运动/睡眠/体重/身体 | `G3` |
| 4 | 连续 3 天记睡眠 <6h | 首页出现提示；**同一天内不重复**（`UNIQUE(rule_id, day_key)`） | `B1` |
| 5 | 计划页 → 「训练」Tab | 「生成计划」→ 含具体动作 + 组次，不是「力量训练 30 分钟」；生成一次整周复用 | `A1`+`A2` |
| 6 | 训练计划某天 → 「记一笔」 | 该日训练落库为 exercise 记录（**kcal = 0，不编造热量**）；按钮变「已记录」 | `A2` |
| 7 | 设置页 → 隐私 → 打开「隐藏体重」 | 首页状态行、状态详情页、对话页系统提示 **三处整块消失**（不留空位、不显示 `***`）；状态页 BMI 引导**不受**该开关连带关闭 | `R7` |
| 8 | 记一次不适，隔几天再记 | 状态页「身体」出现病程时间线「第 N 天」；**>3 天时出现就医引导**（安全条款 H8） | `B1`/`H8` |

**未决项（原 8 项，仍开放）**：`security-crypto` 版本号 / 4 项 MagicOS 真机行为 /
3 项功能完整度取舍 / CI 签名未固定 —— 详见 `docs/待核实清单.md`。

**技术债（新增 2 条）**

- 同名同值常量仍有 6 组跨文件重复（`DAY_MS` / `DEFAULT_DAY_START_HOUR` /
  `PARSE_PENDING` / `PARSE_FAILED` / `TAB_BODY` / `TAB_EXERCISE`）——
  `check_duplicate_constants` 会提示，**未收敛**（不在故障路径上，刻意缓办）
- `GOAL_MODE_*`（SettingsViewModel）与 `PRIMARY_GOAL_*`（HealthAggregator）语义重复，
  靠注释约束一致性，未做编译期关联

**技术债（历史遗留）**

- **n06 用例波动**：多事件拆分含体重时模型输出不稳定
  （v1 ✓ / v2 ✓ / v2b ✗，`58.2` 被吞成 `0.0`）。属模型不确定性非规则缺陷。
  建议多跑 3 次取多数，或报告加稳定性指标
- **Agent 化（L2/L3）**：地基缺口补完后才动。见 `功能补充与套壳选型.md` 第八章

### ⚠️ 安全
9. 智谱 GLM API Key（`871dbf24...`）曾出现在对话记录中 —— **建议重置**。
   GitHub PAT 属长期凭据：**用完即删**，不要写入代码 / 配置 / 文档 /
   Temp 目录的临时脚本（本轮已清理 5 个内嵌明文 PAT 的脚本残留）。

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
13. **★ 目标兜底值唯一来源**（2026-10-03 新增，见 3.10.2）：
    一律引用 `db/GoalEntities.kt` 的 `object GoalDefaults`
    （3 次/150 分/7.5h/1700ml/2500kcal），**任何文件不要再定义私有副本** ——
    曾散落 5 处、名字各异，以 CI #21 编译错误的形式连本带利还回来。
    `check_duplicate_constants()` 会提示同名同值的跨文件重复。
    ⚠️ `HealthRules` 的 H7（<150 分/周）是临床阈值，**刻意**不复用该常量。
14. **★ 顶层声明不得非限定引用 companion 成员**（CI #21 实证，见 3.10.1）：
    顶层 data class / 顶层函数里用 companion 常量必须带宿主前缀
    （`X.CONST` / `Owner.CONST`）—— 顶层声明的解析域不含别的类的 companion。
    `check_object_scope()` 会在本地拦死；这类错误编译器必报、单行正则检查器必漏。

---

## 九、文件速查

```
D:\桌面\AI Port\Healix\
├── pipeline\
│   ├── contract.py          # ★ 契约唯一来源（PROMPT_EXTRACT / PROMPT_TRAINING / 版本号）
│   ├── provider.py          # ★ 预设（已核实真实值）
│   ├── check_resources.py   # 资源静态检查（5 类，含预警文案合规）
│   ├── check_kotlin.py      # Kotlin/Room 静态检查（**14 类**，3.10.3）
│   ├── pull_schemas.py      # ★ 拉回 Room schema
│   ├── dump_manifest.py     # ★ 无 SDK 反编译 APK 内 AXML 清单
│   ├── run_regression.py    # 回归脚本
│   └── tests\test_norm.py   # 67 项断言
├── build\                   # ★ 发布工具链（.gitignore 忽略，不进仓库）
│   ├── _gh_publish.py       # ★ Git Data API 推送（原 pipeline/push_via_api.py）
│   ├── _gh_fetch_chain.py   # ★ 远端历史分叉时补全祖先链
│   └── _gh_ci.py            # ★ 取 CI job 日志
├── app\src\main\java\com\healix\app\
│   ├── db\
│   │   ├── SettingsKeys.kt  # ★★ settings 键名唯一事实来源
│   │   ├── GoalEntities.kt  # ★★ GoalDefaults（目标兜底值唯一来源）+ 4 张新表
│   │   ├── GoalDaos.kt      # goals / training_plans / body_signals / reminders 的 DAO
│   │   └── AppDatabase.kt   # v2（11 表）+ MIGRATION_1_2（纯 DDL，禁 destructive）
│   ├── rules\               # ★ 本轮新增（聚合与求值分离，0 次 AI 调用）
│   │   ├── HealthAggregator.kt  # 唯一碰 DB 的聚合器
│   │   ├── HealthRules.kt       # ★ 纯函数：H1–H8 / T1–T3
│   │   ├── MuscleRecovery.kt    # 肌群恢复度（训练计划用）
│   │   └── SignalText.kt        # 预警文案组装
│   ├── net\
│   │   ├── LlmProvider.kt   # ★ Provider 抽象 + 预设（已核实）
│   │   └── NetworkStatus.kt # ★ Wi-Fi/移动数据可达性判断
│   ├── parse\SchemaValidator.kt  # ★ 与 contract.py 同步
│   ├── repo\
│   │   ├── EventRepository.kt   # 串联 net → parse → db
│   │   └── QuotaGuard.kt        # 配额护栏
│   └── ui\
│       ├── MainViewModel.kt     # ★ HomeStatus 两态互斥状态行 + Offline 拆分
│       ├── StatusDetailActivity.kt  # ★ 状态详情页（本轮新增，462 行）
│       ├── StatusDetailViewModel.kt # ★ 四段 Section + 隐私同步（本轮新增）
│       ├── TrainingPlanner.kt       # ★ 训练计划（PROMPT_TRAINING 侧，本轮新增）
│       ├── PlanReviewActivity.kt    # ★ 计划页三 Tab：计划｜训练｜回顾
│       ├── SettingsActivity.kt      # ★ 目标/提醒/隐私三组 + 接入按钮
│       ├── TodaySummary.kt          # 多维聚合复用规则层
│       ├── ChatEngine.kt    # ★ systemPrompt 拼背景 + 隐私同步
│       └── ChatActivity.kt  # ★ 左右气泡
├── app\src\main\res\layout\
│   ├── activity_status_detail.xml   # ★ 状态详情页（本轮新增）
│   ├── activity_plan_review.xml     # ★ 计划页（本轮扩展三 Tab）
│   ├── item_training_day.xml        # ★ 周计划单日行（本轮新增）
│   └── item_illness_timeline.xml    # ★ 病程时间线行（本轮新增）
├── app\schemas\             # ★ Room schema v1 + v2（均已入库）
├── .github\workflows\
│   ├── ci.yml               # 编译 + 单测 + 静态检查 + schema 产物
│   └── release.yml
├── docs\待核实清单.md        # 未决项清单
├── 功能扩展设计方案.md       # ★ P0+P1 交付记录见第十六章
├── .workbuddy\memory\2026-10-03.md   # 详细工作日志
└── HANDOFF.md               # 本文件
```

---

## 十、给下一个会话的提醒

1. **不要本机装工具链**（用户明确决策，已问过）
2. **不要用裸 `python`**，用 managed 路径（见第五节）
3. **推送一律走 `build/_gh_publish.py <PAT文件>`**，不要试 `git push`（会挂在网络）；
   commit 日期必须按 `<epoch> +0800` 复现，否则远端静默分叉
4. **改动后必跑三连预检**，再推
5. **新增静态检查规则必须自证**（注入坏例 → 报出 → 还原 → 无误报）
6. **新增检查必须先剥注释**；作用域类检查还要先 mask 字符串（原始字符串里有 `{}`）
7. **PAT 从知识库读**（第二节，已验证有效），临时拷贝用完即删
8. 用户偏好：**细节详尽、中文、每个结论给依据**；
   改 prompt 要说明变更点；提交信息要写「问题/根因/修复/验证」结构
9. **有不明白的地方一定问用户，不可擅自做主**；
   需要决策的地方要询问；下载/安装任何东西需先经同意
