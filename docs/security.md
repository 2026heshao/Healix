# Healix 安全说明

配套：`总方案.md` 第七节、`功能补充与套壳选型.md` 1.9 / 9.1

---

## 一、API Key 的完整生命周期

| 阶段 | 做法 | 禁止 |
|---|---|---|
| 输入 | App 首次启动在设置页手动输入 | ❌ 硬编码在代码里 |
| 存储 | `EncryptedSharedPreferences`（AES-256-GCM + Android Keystore 托管主密钥） | ❌ 存进 SQLite `settings` 表 |
| 读取 | 仅供 provider 组装请求头时读取 | ❌ 传给 UI 明文展示（UI 只显示掩码） |
| 日志 | 不打印 key，**连长度都不打印** | ❌ `Log.d("key", apiKey)` / 打印 `apiKey.length` |
| 错误信息 | 经 `_sanitize()` 脱敏后只留 200 字 | ❌ 原样回传响应体 |
| 导出备份 | 备份**不含** key（key 不在业务表里） | ❌ 把 key 导出到 JSON |
| 构建 | `app/build.gradle` 不注入任何 key | ❌ `buildConfigField "String", "API_KEY", ...` |
| 仓库 | `.gitignore` 排除 `*.keystore` / `*.jks` / `local.properties` / `.env*` | ❌ 提交任何密钥文件 |

**为什么 SQLite 也不行**：明文 SQLite 可被 root 设备或 `adb backup` / 备份导出读到。
`EncryptedSharedPreferences` 的密钥由 Keystore 托管，脱离该设备无法解密。

---

## 二、脱敏函数的行为（已验证）

```
输入                                          → 输出
sk-abcdef1234567890abcdef                     → sk-***
Authorization: Bearer sk-xyz9876543210abcdefg → Authorization: Bearer sk-***
api_key="sk-verylongkeyvalue123456"           → api_key="sk-***"
HTTP 429 rate limited                          → HTTP 429 rate limited（不变）
model glm-x not found                          → model glm-x not found（不变）
```

规则（正则，保守优先）：
1. `sk-[A-Za-z0-9_-]{8,}` → `sk-***`
2. `api_key` / `api-key` 后跟 8+ 字符的值 → `***`
3. `Authorization: Bearer` 后的 8+ 字符 → `***`
4. 截断至 200 字

⚠️ 这是**兜底**，不是许可 —— 主防线是"根本不把 key 拼进日志字符串"。

---

## 三、签名密钥（Release APK）

只从环境变量读，缺任一则不注册 release signingConfig（debug 构建不受影响）：

| 变量 | 用途 |
|---|---|
| `HEALIX_KEYSTORE_PATH` | keystore 文件路径 |
| `HEALIX_KEYSTORE_PASSWORD` | keystore 口令 |
| `HEALIX_KEY_ALIAS` | 密钥别名 |
| `HEALIX_KEY_PASSWORD` | 密钥口令 |

GitHub Actions 侧对应 Secrets（`release.yml`）：
`HEALIX_KEYSTORE_BASE64` / `HEALIX_KEYSTORE_PASSWORD` / `HEALIX_KEY_ALIAS` / `HEALIX_KEY_PASSWORD`

**keystore 一经用于签名上架就不可更换**。自用 APK 直装无此约束，但建议本地留一份离线备份并记住口令 —— 丢了就只能卸载重装（数据会丢，见第四节）。

---

## 四、数据丢失风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| 手机丢失 / 损坏 | 全部历史归零 | 设置页「导出备份」→ SAF `ACTION_CREATE_DOCUMENT` 写 JSON |
| Room 迁移失败 | 静默删库（若用了 destructive fallback） | **禁用 `fallbackToDestructiveMigration()`**，宁可崩溃 |
| 误删记录 | 不可恢复 | 软删除（`deleted_at`）+ 30 天后才物理清理 |
| keystore 丢失 | 无法覆盖安装升级 | 本地离线备份 keystore + 口令 |

---

## 五、健康数据出网范围

| 出网内容 | 时机 | 去向 |
|---|---|---|
| `raw_text`（用户输入原文） | 用户主动记录时 | 用户自配的 provider（默认智谱） |
| 当日摘要数字（摄入/目标/缺口） | 打开对话或计划页时 | 同上 |
| 近 7 日聚合数据 | 生成计划/复盘时 | 同上 |

**不出网**：数据库整体、历史原始记录全量、API key、个人信息（身高体重年龄仅用于本地 BMR 计算，
仅在组装 prompt 时以"个人参数"形式最小化带上）。

**不自建后端**：不经过任何第三方服务器中转，直连 provider。

---

## 六、隐私边界

- 无账号体系、无云同步、无多设备
- 无埋点上报、无崩溃收集 SDK
- 无广告 SDK
- WorkManager / 网络调用全部用于用户主动触发的功能，无后台静默上传

---

## 七、⚠️ 历史遗留（必须处理）

首次测试用的 API key **曾明文出现在对话记录中**。
`总方案.md` 第七节已标注：**上线前必须到控制台重新生成并废弃旧的**。

当前代码库中**不含任何 key**（已复核：`pipeline/` 与 `app/` 内无明文密钥，全部从环境变量或设置页读取）。
但旧的 key 值仍存在于历史对话记录里 —— 请到 provider 控制台重新生成。
