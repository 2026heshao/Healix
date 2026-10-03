# Healix 契约文档

本文件是**唯一契约来源**的说明。任何一侧改动契约，必须同步另一侧并递增版本。

| 契约 | Python 实现（权威） | Kotlin 镜像 | 版本 |
|---|---|---|---|
| 数据 schema | `pipeline/store.py` `SCHEMA_SQL` | `db/AppDatabase.kt` + `db/*Entity.kt` | v1 |
| 后处理规则 | `pipeline/contract.py` | `parse/SchemaValidator.kt` | v1 |
| Prompt | `pipeline/contract.py` `PROMPT_EXTRACT` / `PROMPT_VER="v1"` | 同文字，`SchemaValidator.PROMPT_EXTRACT` | v1 |
| Provider 接口 | `pipeline/provider.py` | `net/LlmProvider.kt` | v1 |
| LLM 埋点 | `pipeline/store.py` `log_llm_call` | `db/LlmCallEntity.kt` | v1 |

---

## 一、后处理流水线（6 步，缺一不可）

对应 `总方案.md` 第四节。Python 侧实现落在 `pipeline/contract.py`，每一步都是**纯函数**。

| 步 | 规则 | Python 函数 | 硬性验证 |
|---|---|---|---|
| 1 | 退避重试（429 / 1305，`1.5 × 2^(n-1)` 秒，最多 5 次） | `provider.OpenAiCompatProvider.chat` | 见下方退避表 |
| 2 | 类型强转（`kcal`→Int、`weight_kg`/`sleep_h`→Double、`foods`→List\<String\>） | `to_int` / `to_float` / `to_str_list` | `test_norm.py` 断言 22 项 |
| 3 | 字段兜底（缺失补 `""` / `[]` / `0`） | `normalize_event` | 断言「字段齐全」 |
| 4 | 异常值清洗（`"空串"` / `"null"` / `"无"` → `""`） | `clean_literal` | 断言 6 项 |
| 5 | 保留原文（`raw_text` 必落库） | `store.insert_event` | 列 NOT NULL |
| 6 | 用户确认（UI 展示可编辑，不静默入库） | — （UI 层） | ConfirmSheet |

**第 2 步的区间钳制规则（易错点）**：超界**回落默认值**，不钳到边界。
钳到边界会造出假数据 —— 模型说「吃了 99999 kcal」时，记 5000 比记 99999 更糟，两者都该记 0。

| 字段 | 下限 | 上限 | 超界结果 |
|---|---|---|---|
| `kcal` | 0 | 5000 | 0 |
| `weight_kg` | 20.0 | 300.0 | 0.0 |
| `sleep_h` | 0.0 | 24.0 | 0.0 |

---

## 二、退避重试参数

| 项 | 默认 | 可配置 | 说明 |
|---|---|---|---|
| 最大重试次数 | 5 | ✅ 设置页 | 不同平台限流策略不同 |
| 初始间隔 | 1.5 s | ✅ 设置页 | |
| 指数退避 | 开 | ✅ 设置页 | 关闭则每次等固定初始间隔 |

退避序列（指数开）：`1.5 → 3.0 → 6.0 → 12.0 → 24.0` 秒。最坏总等待 46.5 s。

**不重试（终止）**：HTTP `400 / 401 / 403 / 404 / 422`。重试也是白等。
**重试**：`429`（RATE_LIMIT）/ `5xx`（HTTP）/ 超时（TIMEOUT）/ 网络错误（NETWORK）/ JSON 非法（PARSE）。

---

## 三、多事件输出形态（功能补充 1.5，破坏性改动已并入）

模型输出**目标形态**是：

```json
{"events": [ {...}, {...} ]}
```

但解析器必须兼容 4 种真实形态（`extract_events_from_response`）：

| # | 形态 | 来源 |
|---|---|---|
| 1 | `{"events":[...]}` | 目标 |
| 2 | `{...单条字段...}` | 模型退化为旧格式 |
| 3 | `[{...},{...}]` | 模型直接给数组 |
| 4 | `{"answer":{...}}` / `{"data":[...]}` | 被包了一层（prompt 明令禁止但仍会发生） |

外加：剥 ` ```json ` 围栏、抓最外层 `{}` 或 `[]`（模型前后加解释文字）。

**单条坏不影响其他条**：数组里混入 `"垃圾"` 这类元素时，该条被兜底为 `type="other"`，其余条不受影响。

---

## 四、幂等（功能补充 1.3）

```
client_event_id TEXT NOT NULL UNIQUE
插入用 OnConflictStrategy.IGNORE / INSERT OR IGNORE
返回 -1（Kotlin）/ rowid 为 -1（Python） 即视为重复，UI 不报错、不出新行
```

实测验证：同一 `client_event_id` 插两次 → 第二次返回 `-1`，表内行数仍为 1。

幂等键的第二个用处：编辑后重新抽取用同一个 id **覆盖更新**，而不是新增一条。

---

## 五、日界线 day_key（功能补充 1.4）

```kotlin
fun dayKeyOf(ts: Long, dayStartHour: Int = 4): String
```

实现：**本地时间整体减 `dayStartHour` 小时后取日期** —— 无需任何边界分支。

| 输入 | 减 4h 后 | 归属 | 断言 |
|---|---|---|---|
| 10-03 01:00（夜宵） | 10-02 21:00 | 10-02 | ✅ |
| 10-03 03:59 | 10-02 23:59 | 10-02 | ✅ |
| 10-03 04:00（恰好） | 10-03 00:00 | 10-03 | ✅ |
| 10-03 06:00（早餐） | 10-03 02:00 | 10-03 | ✅ |
| 10-03 12:00 | 10-03 08:00 | 10-03 | ✅ |
| 10-03 23:00 | 10-03 19:00 | 10-03 | ✅ |

日界线小时数进设置页，默认 4。

---

## 六、离线待处理队列（功能补充 1.1）

```
用户输入 → 立即以 pending 入库（UI 立刻显示 raw_text，0ms）
  → 后台调 AI
    成功 → 回填字段、置 done、Room Flow 自动推 UI
    失败 → retry_count++，WorkManager 退避重试（约束 NetworkType.CONNECTED）
         → 5 次仍失败 → 置 failed，用户可手动重试，**不重试也能继续用**
```

`parse_status` 三态：`pending` / `done` / `failed`。索引 `events(parse_status)`。

---

## 七、安全约束（C6，不可协商）

| 项 | 做法 |
|---|---|
| API key 存储 | `EncryptedSharedPreferences`（AES-256-GCM + Keystore 托管密钥） |
| key 进 SQLite | ❌ 禁止。`settings` 表只存 `baseUrl` / `model` / 配额 / 重试参数 |
| key 进日志 | ❌ 禁止，**连长度都不打印**（防侧信道） |
| 错误信息脱敏 | `_sanitize()` 抹掉 `sk-xxx` / `Bearer xxx` / `api_key: xxx`，截断 200 字 |
| key 进构建 | ❌ 禁止。`app/build.gradle` 不注入任何 key |
| key 进仓库 | ❌ `.gitignore` 已排除 `*.keystore` / `local.properties` / `.env*` |
| 签署密钥 | 只从环境变量 / GitHub Secrets 读；缺变量则不注册 release signingConfig |

---

## 八、Room Migration 纪律（功能补充第六章）

- `exportSchema = true`，schema JSON 提交进仓库（`app/schemas/`）
- 每个版本一个显式 `Migration` 对象，加进 `AppDatabase.MIGRATIONS`
- **禁用 `fallbackToDestructiveMigration()`**

理由：数据只有手机本地一份副本。迁移失败静默删库 = 数据全灭。
宁可崩溃报错（可定位），也不要静默重建（不可恢复）。

---

## 九、回归验收命令

```bash
# 离线自测：后处理层 + 用例集（不需要 key、不需要网络）
python pipeline/tests/test_norm.py

# 真实回归：需要 provider 配置（只从环境变量读）
export HEALIX_BASE_URL="https://..."   # 必须对着官方文档确认，禁止凭记忆填
export HEALIX_MODEL="..."
export HEALIX_API_KEY="..."
python pipeline/run_regression.py

# 只跑部分
python pipeline/run_regression.py --limit 3
python pipeline/run_regression.py --tag boundary
```

通过线：**≥ 95%**（22 条用例，即最多错 1 条）。
退出码：`0` 达标 / `1` 未达标 / `2` 配置错误。

报告写入 `pipeline/regression_report.json`（含 pass_rate、P95 延迟、token 用量、失败清单）。
