# Healix

自用安卓健康记录工具。手机端原生 App，云端大模型做结构化抽取与每日复盘/计划。

- **目标机**：荣耀 Magic6 Pro（骁龙 8 Gen 3 / Android 14）
- **焦点**：饮食 / 运动 / 增重计划 / 生病记录
- **架构**：Prompt Chaining（两级链路：抽取链 + 计划链），**不是 Agent**
- **数据**：只存手机本地 SQLite，不自建后端

---

## 文档索引

| 文件 | 作用 |
|---|---|
| `总方案.md` | 总纲：已验证结论、技术栈、数据模型、prompt、执行计划 |
| `功能补充与套壳选型.md` | 9 项工程缺口、可借鉴功能、Provider 抽象、交互式 Agent |
| `功能扩展设计方案.md` | ★ 扩展为「预防生病 + 运动计划 + 多维度健康」的 PRD（**6 项决策已全部拍板**；含第十五章「先落库再识别」写入路径） |
| `参考产品研究与取舍.md` | ★ 竞品研究：Fitbod / Bearable / Apple Health / Whoop 等的精华与糟粕 |
| `Healix设计规范系统.md` | ★ 设计令牌与组件规格（**v4**，含扩展功能 UI：状态行 / 趋势图 / 状态详情页 / 训练 Tab，可直接进 XML） |
| `UI设计方案.md` | v2 设计语言（规范系统的前身） |
| `prototype.html` | 390×844 可交互高仿真原型，**7 屏**（v4 已同步状态详情页、训练 Tab 与直写路径） |
| `docs/contract.md` | ★ Python↔Kotlin 契约（schema / 后处理 / prompt / 幂等 / 日界线） |
| `docs/security.md` | ★ API key 生命周期、脱敏规则、签名密钥、数据丢失风险 |

---

## 目录结构

```
Healix/
├── pipeline/                    # P1 抽取链 Python 竖切片（契约的权威实现）
│   ├── contract.py              #   schema + 后处理规则 + prompt + day_key
│   ├── cases.py                 #   22 条用例集（normal 10 / boundary 8 / adversarial 4）
│   ├── provider.py              #   Provider 抽象 + OpenAI 兼容实现 + 退避重试
│   ├── store.py                 #   SQLite 定稿 schema + 幂等插入 + 埋点
│   ├── run_regression.py        #   回归脚本（完整链路）
│   └── tests/test_norm.py       #   后处理层离线自测（67 项断言，不需要 key）
│
├── app/src/main/
│   ├── AndroidManifest.xml      # 权限 + 前台服务 type（API 34 必须）
│   ├── java/com/healix/app/
│   │   ├── db/                  # Room：实体 / DAO / Database（禁 destructive migration）
│   │   ├── net/                 # LlmProvider / OpenAiCompatProvider
│   │   ├── parse/               # SchemaValidator（Kotlin 版后处理）
│   │   ├── repo/                # EventRepository / QuotaGuard
│   │   ├── notify/              # QuickInputService + Receiver
│   │   ├── security/            # SecretStore（EncryptedSharedPreferences）
│   │   └── ui/                  # Activity / ConfirmSheet
│   └── res/                     # 设计令牌：colors / type / dimens / strings
│
├── .github/workflows/           # ci.yml（编译+单测）/ release.yml（签名 APK）
└── docs/                        # contract.md / security.md / 待核实清单.md
```

---

## 快速开始

### 1. Python 竖切片（P1，不需要 key）

```bash
# 离线自测：后处理层 + 用例集
python pipeline/tests/test_norm.py
# → 全部通过（67 项断言）

# 真实回归：需要 provider 配置
export HEALIX_BASE_URL="https://..."   # ⚠️ 对着官方文档确认，不要凭记忆
export HEALIX_MODEL="..."
export HEALIX_API_KEY="..."
python pipeline/run_regression.py
# → 通过率 ≥ 95% 时退出码 0

# 试水 / 分片
python pipeline/run_regression.py --limit 3
python pipeline/run_regression.py --tag boundary
```

报告写入 `pipeline/regression_report.json`。

### 2. Android 构建（本机零安装，走 CI）

```bash
# 首次需要生成 gradle wrapper（三件套不在仓库里）
gradle wrapper --gradle-version 8.9

# 本地构建（可选，需 JDK 17 + Android SDK）
./gradlew assembleDebug

# 或直接 push，由 GitHub Actions 出 APK
git push origin main
# → Actions 页面下载 healix-debug artifact
```

### 3. Release 签名 APK

需在仓库 Settings → Secrets 配置：

| Secret | 说明 |
|---|---|
| `HEALIX_KEYSTORE_BASE64` | keystore 文件 base64：`base64 -w0 healix.jks` |
| `HEALIX_KEYSTORE_PASSWORD` | keystore 口令 |
| `HEALIX_KEY_ALIAS` | 密钥别名 |
| `HEALIX_KEY_PASSWORD` | 密钥口令 |

生成 keystore：
```bash
keytool -genkeypair -v -keystore healix.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias healix
```

打 tag 触发 release：`git tag v0.1.0 && git push origin v0.1.0`

---

## 验收标准

| 阶段 | 内容 | 验收 | 状态 |
|---|---|---|---|
| **S0** | 契约层（schema / 后处理 / 用例集 / prompt v1） | 离线自测 67 项断言全绿 | ✅ 完成 |
| **P1** | 抽取链竖切片 + 22 条用例回归 | 通过率 ≥ 95% | ⏳ 待你提供 provider 配置 |
| **P2** | Kotlin App（速记框 / 通知栏 / 列表 / 汇总 / 确认编辑） | 编译通过、可安装 | 🚧 进行中 |
| **S1** | Provider 抽象层 + 设置页 + 连通性测试 | 看到「连通 · 1.2s」，换 provider 也能通 | 🚧 进行中 |
| **P3** | 云构建出 APK，装到真机 | 真机能用 | ⏳ |
| **P4** | 每日复盘 + 计划 | 输出有具体依据，不是空话 | ⏳ |

---

## 核心约束（改代码前必读）

1. **API key 绝不进代码 / 仓库 / 日志 / SQLite** —— 见 `docs/security.md`
2. **baseUrl 与模型名绝不硬编码** —— 全走设置页，代码里只有占位符
3. **Room 禁用 `fallbackToDestructiveMigration()`** —— 迁移失败宁可崩溃
4. **所有外部调用必须有显式超时 + 重试 + 降级**
5. **`raw_text` 必落库** —— 抽错时唯一的后悔药
6. **`client_event_id` 幂等** —— RemoteInput 会重复投递
7. **`day_key` 按 4:00 日界线** —— 凌晨夜宵归前一天
8. **设计规范**：1 个强调色 / 无卡片 / 无阴影 / 无 emoji / 圆角只有 8dp / 字重只有 400·500

---

## 已知限制

- 本机未装 Android 工具链，Android 侧编译验证依赖远端 CI 反馈
- `foregroundServiceType="specialUse"` 在部分国产 ROM 上可能被忽略 —— 需真机验证
- 后台定时复盘不可靠（国行 ROM 杀后台），改为「打开 App 时触发 + 手动按钮」
- 热量数字全部是估算值，非医疗建议
