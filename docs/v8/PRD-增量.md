# Healix v8 增量 PRD

> 作者：产品经理 许清楚
> 日期：2026-10-05
> 适用范围：Healix v8 增量（9 项修复与优化，一轮全做）
> 依据：本 PRD 每一条「现状」均落实到代码 `文件:行号`，未核实结论一律标注 `[待核实]`。

---

## 一、项目信息

| 项 | 值 |
|---|---|
| Language | 中文 |
| Programming Language | Android Kotlin 存量栈（ViewBinding + Material 1.12.0 + Room 2.6.1，**无 Compose、无 ViewPager**），minSdk 29 / targetSdk 35 |
| Project Name | `healix_v8_increment` |
| 包名 | `com.healix.app` |
| 构建/验证 | 本机无 JDK/Android SDK，编译只在 GitHub CI；`pipeline/check_kotlin.py`、`pipeline/check_resources.py`、`pipeline/tests/test_norm.py` 是推送前唯一防线 |

### 原始需求复述

1. 页面切换（前进/后退）卡顿约 1 秒，套用 GitHub 成熟方案消除。
2. 底部 Tab 点击延迟偏高，对标微信底部 Tab 的即时响应。
3. **P0 Bug**：弹窗「确定」按钮看不见（字色与底色融合），用户称「所有同类输入框小弹窗均有此问题」。
4. 设置页「提醒」栏去掉默认提醒、只留「添加提醒」，新增左滑删除；「目标」栏同样处理。
5. 「记录」首页顶部显示**主目标**，下方新增**次目标进度图表**；未设置目标时首次进入弹窗引导。
6. 删除设置页「隐私」栏。
7. 把「训练」与「计划」合并为**同一条时间轴**，计划**根据用户数据实时自动更新**，去掉手动「更新」。
8. 本地数据继承配置：升级/迁移后旧数据不丢失。
9. UI/交互成熟化：功能线优先跑通、切实好用；调研主流健康/运动 App 做二次改造落地。

### 本期已拍板决策（不再讨论）

| # | 决策 |
|---|---|
| D1 | 第 1、2 项走**单 Activity + Fragment 重构**（二级页全 Fragment 化，底部 3 Tab 用常驻 Fragment `add/show/hide` 零动画切换）；用户明确"不怕风险与工作量"。 |
| D2 | 第 8 项由产品经理调研后给**单一推荐方案**（结论 + 理由 + 成本）。 |
| D3 | 第 4 项目标栏改为**由 `goals` 表动态渲染**；左滑删除 = `status` 置 `archived`；**主目标行固定不可删**。 |
| D4 | 交付节奏：9 项一轮全做。 |

### 必须遵守的硬约束（不得提出违反项）

- **契约冻结**：`pipeline/contract.py` ↔ `parse/SchemaValidator.kt`；`PROMPT_EXTRACT` 字节冻结，`PROMPT_VER` 仅在其变化时递增；`ChatEngine.systemPrompt`、`TrainingPlanner.PROMPT_TRAINING` 字节冻结。
- **events 表**：`db/EventEntity.kt` ↔ `pipeline/store.py` SCHEMA_SQL 必须一字不差；改一侧即要同步 + 升 Room version + 新 Migration；**禁止 `fallbackToDestructiveMigration()`**（`db/AppDatabase.kt:143-145`）。
- **兜底默认值唯一来源** `db/GoalEntities.kt` 的 `GoalDefaults`；**settings 键名唯一来源** `db/SettingsKeys.kt`；**禁止私有副本**（`pipeline/check_kotlin.py:694 check_settings_keys` / `:1150 check_duplicate_constants`）。
- **日界线**：读设置必经唯一夹取入口 `parse/SchemaValidator.kt:587 dayStartHourOf(raw)`；日键一律 `dayKeyOf(ts, dayStart)` / `todayDayKey()`；**禁止用 `LocalDate.now()` 当今日日键**（`MainViewModel.kt:135-139` 是正例）。
- **埋点计数不变式**：`repo/QuotaGuard` 按 `llm_calls` **行数**计配额 → 一次 provider 往返**恰好一行**；"HTTP 通但解析失败"必须**移位**状态记录，**禁止补记**（`ui/PlanGenerator.kt:259-299` 是唯一正确写法）。
- **模型调用收敛到唯一入口**；读缓存 / 本地兜底**绝不触网**。
- **设计规范 v3**：无卡片、无阴影、无渐变、全局唯一强调色 `accent`；字重只有 400/500；圆角全局 8dp（进度线/Tab 下划线/accent 竖线例外）。
- **禁用游戏化**：不做 streak / 连续打卡 / 归零 / 勋章 / 积分 / 排行（`pipeline/check_kotlin.py:643 check_no_gamification`）。

---

## 二、产品定义

### 产品目标（3 个正交目标）

1. **顺手**：页面切换与 Tab 切换达到"无感"——点击到内容可见 ≤ 1 帧，前进/后退不再有 1 秒冻结。
2. **可信**：所有主按钮清晰可辨、所有删除可撤销/可恢复；升级与迁移后本地数据零丢失。
3. **聚焦**：首页一眼看到"我要去哪（主目标）+ 我现在到哪（次目标进度）"；计划页只有一条时间轴，数据一变内容即变，不需要用户记得点「更新」。

### 用户故事

- 作为**增重用户**，我打开 App 后希望首页顶部直接显示我的主目标和本周进度，这样我不用点进设置去确认目标。
- 作为**高频记录用户**，我点「记录 / 助理 / 我的」时希望立刻切过去，不要等动画，这样"打开即记"的摩擦最低。
- 作为**首次安装用户**，我希望被一次性引导把主目标设好，而不是看到一堆系统预置的目标却不知道是我设的还是系统给的。
- 作为**谨慎的用户**，我在设置里删掉一条提醒或目标后希望它能被找回，这样误删不会造成不可逆损失。
- 作为**换机/重装用户**，我希望旧数据能完整恢复，同时我的 API Key 绝不落进任何明文备份。

---

## 三、逐项需求

> 优先级口径：**P0 = 必须做（阻塞/数据安全/可见 Bug）**；**P1 = 应该做**；**P2 = 可以做**。

### 需求 1｜转场卡顿 → 单 Activity + Fragment 重构

**优先级：P0**

**现状（已核实）**
- 二级页普遍用 `overridePendingTransition(R.anim.in_fwd/out_fwd/in_back/out_back)`，动画为 400ms 纯 `translate`：`res/anim/in_fwd.xml`、`out_fwd.xml`、`in_back.xml`、`out_back.xml` 时长均 `android:duration="400"`。
- 大布局深度嵌套：`activity_settings.xml`(≈14KB)、`activity_plan_review.xml`(≈23KB)、`activity_status_detail.xml`(≈20KB)。
- 根因：转场期间新旧两个 Activity 窗口都要绘制，而新窗口 `onCreate` 在主线程做膨胀 + 读库，主线程被占 → 两边一起冻结约 1 秒。
- 现有页面清单（`AndroidManifest.xml`）：`MainActivity` / `ChatActivity` / `PlanReviewActivity` / `SettingsActivity` / `PersonalInfoActivity` / `KnowledgeBaseActivity` / `ResourceActivity` / `PresetManageActivity` / `DebugActivity` / `StatusDetailActivity`，共 10 个 Activity。

**目标交互（动作序列）**
1. 用户点首页「设置」图标 → 单 Activity 内 `replace` 为 `SettingsFragment`，返回栈入栈，转场 ≤ 180ms 且只做位移/透明度。
2. 用户按返回键 → 出栈回 `RecordFragment`，转场 ≤ 180ms。
3. 用户在二级页（如设置）点返回箭 → 同上出栈。
4. 任意二级页首帧：列表/表单先绘制骨架，DB 读取在 `viewModelScope + Dispatchers.IO` 完成后回填（不阻塞首帧）。

**技术方向（供架构师确认，二选一给结论）**
- **推荐：单 Activity + Fragment + Jetpack Navigation Component**（Google 官方 Single-Activity 架构，GitHub 上最成熟、长期维护、社区最大）。
- 备选：手写 `FragmentManager`（`add/show/hide` + `replace/addToBackStack`），零新增依赖，但返回栈/转场要自己维护。

**验收标准（checklist）**
- [ ] 主界面为唯一 Activity（`ChatActivity` 等 9 个 Activity 全部 Fragment 化并从 Manifest 移除，或保留但不再作为导航载体；最终以架构师方案为准）。
- [ ] 前进/后退转场实测时长 ≤ 180ms，无 1 秒冻结（真机 Magic6 Pro 录屏逐帧核对）。
- [ ] 二级页首帧不出现空白/闪白（`android:windowBackground=@color/bg` 兜底保留，`themes.xml:15`）。
- [ ] `check_manifest_classes()`（`pipeline/check_kotlin.py:835`）与 `check_manifest_resources()`（`:863`）在 CI 通过。
- [ ] 深嵌套大布局做扁平化 / 按需膨胀（`ViewStub` 或 `include` 拆层），单页 inflate 深度显著下降。

**待确认问题**
- 是否引入 `androidx.navigation:*` 依赖（CI 需联网拉包），还是手写 FragmentManager？**请架构师给结论**。
- `ChatActivity`（助理页）Fragment 化后，其 IME 行为与全局 `TabBar`（`ui/TabBar.kt:98 bindImeGuard`，依赖 `@id/tabbar` 与 `@id/input`）如何迁移？

---

### 需求 2｜底部 Tab 即时响应（对标微信）

**优先级：P0**

**现状（已核实）**
- `ui/TabBar.kt:124 chatTo()` 用 `startActivity(CLEAR_TOP|SINGLE_TOP)` **重启** `MainActivity` 并 `finish()`，再 `overridePendingTransition(R.anim.in_tab, R.anim.hold)`（`in_tab.xml` 240ms）。
- 「记录 / 我的」已在同一 Activity 内用 `showTab()` 做 View 显隐（`MainActivity.kt:228-242`）；「助理」是独立 `ChatActivity`（`TabBar.kt:61-66`）。→ **Tab↔助理 每次切换都走一次 Activity 窗口转场**，这是延迟主因。

**目标交互（动作序列）**
1. 用户手指按下「助理」→ 该 Tab 文字立即高亮（`ACTION_DOWN` 即高亮，不等内容切换）。
2. 手指抬起 → 三个 Tab 常驻 Fragment 用 `add/show/hide` 切换，无窗口转场、无动画，目标内容 ≤ 1 帧可见。
3. 用户连点同一 Tab 或快速来回点 → 不重启任何 Activity，不丢输入框焦点与草稿。
4. 用户从「助理」返回「记录」→ 记录页保持离开时的滚动位置与输入状态。

**验收标准（checklist）**
- [ ] 三个 Tab 为常驻 Fragment（`show/hide`），切换无 `startActivity`、无 `overridePendingTransition`。
- [ ] 高亮在 `ACTION_DOWN` 或点击首帧完成。
- [ ] 真机连续快速切换 10 次，无 ANR、无白屏、无焦点丢失。
- [ ] 移除 `TabBar.chatTo()` 的 Activity 重启路径（或改造为 Fragment 切换）。
- [ ] 进入二级页时 TabBar 隐藏、返回时恢复（保留现有白名单语义，`TabBar.kt:31-37`）。

**待确认问题**
- Tab 内容首帧需要读库（记录列表 / 我的页计数）时，是否允许"先展示上次缓存、IO 回来再刷"以避免任何卡顿？

---

### 需求 3｜P0 Bug：弹窗主按钮不可见

**优先级：P0**

**现状（已核实，根因已定位）**
- 使用主按钮样式的 `<Button>` 共 **7 处**：
  - `res/layout/sheet_field_edit.xml:65-67`
  - `res/layout/sheet_confirm.xml:93-95`
  - `res/layout/sheet_action_confirm.xml:67-69`
  - `res/layout/activity_settings.xml:94-96`（`btnApply`）
  - `res/layout/activity_settings.xml:117-119`（`btnTest`，用 `Widget.Healix.TextButton`）
  - `res/layout/activity_knowledge.xml:105-107`
  - `res/layout/activity_plan_review.xml:425-427`
- `Widget.Healix.Button`（`values/themes.xml:45-57`）parent = `Widget.AppCompat.Button.Borderless`，`android:background=@drawable/bg_btn_primary`（accent 底，`drawable/bg_btn_primary.xml`），`android:textColor=@color/btn_primary_text` = **#FFFFFF**（`values/button_colors.xml:3`）。
- 主题 parent = `Theme.MaterialComponents.Light.NoActionBar`（`themes.xml:10`）→ 布局里的 `<Button>` 被 MaterialComponents 替换为 `MaterialButton`，**`MaterialButton` 忽略 `android:background`** → 按钮底变透明；白字压白底（弹窗底色 `bg_sheet` → `surface` = **#FFFFFF**，`values/colors.xml:11`）→ 完全看不见。

**目标交互（动作序列）**
1. 用户在速记/编辑/确认等任意弹窗打开后，看到「确定」为 accent 底 + 白字的实心圆角按钮。
2. 按钮禁用态 → 灰底（`btn_disabled_bg`）+ `text_3` 字；按压态 → `accent_press`。
3. 用户在任一含主按钮的页面（设置 / 知识库 / 计划）看到同样的实心按钮外观。

**修复范围（必须覆盖全项目所有主按钮，不止 3 个弹窗）**
- 覆盖上列 **7 处** `<Button>`；统一改为**尊重 `android:background` 的载体**（推荐显式 `androidx.appcompat.widget.AppCompatButton`，不改全局主题，影响面最小）。
- 补静态检查：在 `pipeline/check_resources.py` 增加"禁止 `<Button>` 使用 `Widget.Healix.Button/TextButton`"规则，防止回归。

**验收标准（checklist）**
- [ ] 7 处按钮全部实测可见：accent 底 + 白字，文字/背景对比度 ≥ 4.5:1。
- [ ] 禁用态与按压态颜色正确。
- [ ] 3 个弹窗（字段编辑 / 确认 / 动作确认）逐个截图核对。
- [ ] `check_resources.py` 新增规则在 CI 生效。
- [ ] 全项目 `grep "<Button"` 结果为 0（或全部为显式 AppCompatButton/MaterialButton 且已验证外观）。

**待确认问题**
- 是否统一改用 `MaterialButton` + `app:backgroundTint`（需 `shapeAppearance` 配 8dp 圆角）以贴合 Material 体系，还是保持 AppCompat 简单方案？**推荐 AppCompat，成本最低、风险最小。**

---

### 需求 4｜设置页「提醒」与「目标」栏改造

**优先级：P1**

**现状（已核实）**
- **提醒默认预置**：`ui/SettingsViewModel.kt:222 ensureReminderDefaultsIfEmpty()` 在空表时预置 3 条默认提醒（体检 365 / 洗牙 180 / 配镜 365，`SettingsViewModel.kt:226-241`），由 `init`（`:140-141`）调用。
- **提醒栏已动态渲染**：`renderReminders()`（`SettingsActivity.kt:312-327`），点行弹三选项（`:330-350`），删除走 `deleteReminder(id)`（`SettingsViewModel.kt:442-444`）。
- **目标栏为固定行**：`activity_settings.xml:225-253` 的 5 个 `<include>`（rowGoalPrimary/Weight/Train/Sleep/Water），由 `renderGoals()`（`SettingsActivity.kt:221-244`）填充，**无删除能力**。
- **左滑能力已存在**：`ui/SwipeController.kt`（全局单开、拖拽 ≥ 半程吸附、300ms click 屏蔽），但仅用于首页记录列表（`MainActivity.kt:87-97`）。

**目标交互（动作序列）**
1. 用户进入设置页 → 「提醒」栏只显示已存在的提醒列表 + 一行「添加提醒」；首次安装不再出现任何预置提醒。
2. 用户在某条提醒行上从右向左滑动 → 行左移露出「删除」按钮（复用 `SwipeController`）。
3. 用户点「删除」→ 该提醒移除，弹 5 秒撤销条（复用 `UndoBar`），点「撤销」原位恢复。
4. 用户进入「目标」栏 → 目标行由 `goals` 表 **动态渲染**（与提醒栏同构）；主目标行左滑无删除按钮。
5. 用户左滑某条次目标 → 露出「删除」→ 点击后该目标 `status` 置 `archived`，从列表消失；弹撤销条。
6. 用户点目标组底部「添加目标」→ 弹出可添加/可恢复的目标列表 → 选中后该目标恢复为 `active`。

**验收标准（checklist）**
- [ ] 首次安装进入设置页，提醒栏为空，仅有「添加提醒」入口。
- [ ] 老用户设备上已存在的默认提醒**不被动删除**（只停止预置行为）。
- [ ] 提醒/目标左滑均能露出删除按钮，且全局同时只开一行。
- [ ] 目标栏行数据完全来自 `goalDao().observeActive()`，无硬编码行。
- [ ] 主目标行（`metric == GoalMetrics.PRIMARY`）左滑不显示删除按钮。
- [ ] 删除目标 = `goals.status='archived'`（不是物理删除）；「添加目标」能恢复。
- [ ] 撤销后 Room Flow 自动回插，顺序正确。

**待确认问题**
- 「添加目标」的可选项边界：只允许恢复已归档的既有目标，还是也允许为尚未创建的固定指标（体重/训练/睡眠/饮水）新建？**推荐：仅恢复已归档目标 + 补齐固定指标集，不引入用户自定义指标。**
- 删除目标是否需要二次确认？**推荐：不需要，靠 5 秒撤销。**

---

### 需求 5｜首页主目标展示 + 次目标进度图表 + 首次引导

**优先级：P1**

**现状（已核实）**
- `activity_main.xml` 顶部只有日期标签（`:30-36`）+ 设置按钮（`:38-48`），**主/次目标零展示**。
- 数据能力已就绪：`GoalDao.observeActive()`（`db/GoalDaos.kt:12-13`）、`GoalMetrics`（`db/GoalEntities.kt:70-77`）、`GoalDefaults`（`:102-117`）；周训练次数已在 `MainViewModel.buildSummaryLine()` 计算（`MainViewModel.kt:235-243`）。
- **无法区分"用户设过"vs"系统灌的"**：`ensureGoalDefaultsIfEmpty()`（`SettingsViewModel.kt:163-213`）**无条件**在空表预置 6 条默认目标，判据仅为 `countActive() > 0`（`GoalDaos.kt:24`）。
- 已有自绘折线控件 `ui/widget/TrendChartView.kt`（无网格/无坐标轴/末点圆点，用在 `activity_status_detail.xml:250,288`）。

**目标交互（动作序列）**
1. 用户进入「记录」首页 → 顶部（日期行之下、汇总区之上）显示一行「主目标」：左侧主目标名（增重/减重/保持），右侧「去调整 ›」文字入口，点击 → 进设置页目标组。
2. 用户向下看 → 看到「次目标进度」区块（3 项，见下方选型）。
3. 用户点次目标区块 → 进入状态详情页对应段。
4. **首次进入（`GOAL_SETUP_DONE != "true"`）** → 记录页 onCreate 后弹一次性引导：选择主目标（增重/减重/保持）+ 目标体重（可跳过）→ 保存后写 `GOAL_SETUP_DONE="true"`，后续启动不再弹。

**主目标顶部展示什么字段（明确推荐）**
- 第一行：`主目标 · {增重|减重|保持}`（来自 `goals.metric='primary'` 的 `target_value` 编码 0/1/2）+ 右侧 `去调整 ›`。
- 第二行（可选，有则显示）：用户自述目标 `SettingsKeys.GOAL_STATEMENT`（P1 已存在的自由文本键，`SettingsKeys.kt:173`），如「年底前跑半马」。
- 不显示分数/评分（违反禁用游戏化与"不给黑箱分"纪律）。

**次目标进度图表选型（明确推荐）**
- **选 3 个指标**（都是本地已有真实数据、且能与目标对比的）：
  1. **本周训练次数** —— `COUNT(type='exercise', 本周) / sessions_per_week`（口径见 `MainViewModel.kt:235-243`）。图形：**2dp 进度线**（沿用 `progress_line`，符合规范 3.7 唯一允许的进度线场景）。
  2. **近 7 日睡眠** —— `events(type='sleep').sleep_h` vs `sleep_h` 目标。图形：**`TrendChartView` 折线**（复用现成控件）。
  3. **近 30 日体重** —— `events(type='body').weight_kg` vs `weight_kg` 目标。图形：**`TrendChartView` 折线**。
- **明确不选**：饮水（`water_ml` 只有目标值，**无任何 event 数据源** —— 见 `db/GoalEntities.kt:76` 与全项目 grep，无法算进度）；训练分钟（`train_minutes_per_week` 无结构化 event 字段）。
- **图形禁令**：不用饼图/环形图/柱状图/热力图（违反无卡片、彩色面唯一化，且饼图表达不了"与目标比"）。
- 数据不足 3 点 → 沿用 `TrendChartView` 占位文案（`TrendChartView.kt:157-162`）。

**如何与 `ensureGoalDefaultsIfEmpty()` 共存（关键）**
- 保留 `ensureGoalDefaultsIfEmpty()`：它保证下游读取（`MainViewModel` / `PlanGenerator` / `TrainingPlanner`）永远有值可读，属于"兜底"。
- 新增持久标记 `SettingsKeys.GOAL_SETUP_DONE`（如 `"goal_setup_done"`）区分**"系统预置过"**与**"用户设置过"**。引导弹窗判据 = `GOAL_SETUP_DONE != "true"`。
- 引导完成 → 写 `GOAL_SETUP_DONE="true"` + 经 `setPrimaryGoal()`（`SettingsViewModel.kt:372-378`）落主目标；用户点跳过 → 也只写标记（不反复骚扰），首页「主目标」行始终提供 `去调整` 入口。
- 现有一次性提示标记 `SettingsKeys.GOAL_SOURCE_SEEN`（`SettingsKeys.kt:163`）继续独立使用，互不影响。

**验收标准（checklist）**
- [ ] 首页顶部可见主目标一行，右侧可点进设置 → 目标组。
- [ ] 次目标区块显示 3 项指标；训练为进度线，睡眠/体重为折线；无数据时显示占位文案。
- [ ] 首次进入且有未完成引导时弹引导；完成后（或跳过后）不再出现。
- [ ] 老用户（已有目标）不弹引导。
- [ ] 隐藏热量/体重时（`HIDE_KCAL/HIDE_WEIGHT`，需求 6 保留键），次目标中体重项同步遵循隐藏规则（不显示数字）。
- [ ] 200% 字号下首页不被挤坏（沿用 `MainViewModel.kt:258-259` 的降级策略）。

**待确认问题**
- 引导弹窗用哪个载体？**推荐复用 `FieldSheet` 风格**（非系统 AlertDialog，避免需求 3 的按钮坑，且贴合"无底色容器"规范）。
- `GOAL_SETUP_DONE` 键需在 `SettingsKeys.kt` 登记（唯一事实来源纪律）。

---

### 需求 6｜删除设置页「隐私」栏

**优先级：P2**

**现状（已核实）**
- UI：`activity_settings.xml:286-316`（`group_privacy` + `rowHideKcal` + `rowHideWeight`）；`SettingsActivity.kt:86-87`（点击切换）、`:126-129`（值渲染）；字符串 `strings.xml:410 group_privacy`、`:418 setting_hide_kcal`、`:419 setting_hide_weight`。
- 存储与消费方（**广泛，保留**）：`SettingsKeys.HIDE_KCAL = "hide_kcal"` / `HIDE_WEIGHT`（`SettingsKeys.kt:146,149`）；消费方 `MainViewModel.hideKcal`（`MainViewModel.kt:184-187`）、`MainActivity.kt:469-477`、`StatusDetailActivity`、`ChatEngine` 系统提示。

**目标交互（动作序列）**
1. 用户进入设置页 → 列表依次为 模型服务 / 调用限制 / 目标 / 提醒，**隐私组整组不再出现**。
2. 若用户此前把「隐藏体重」设为 true，其隐藏行为在首页与状态页**继续生效**（仅移除入口，不改语义）。

**验收标准（checklist）**
- [ ] 设置页不再渲染隐私组（布局、点击绑定、值渲染一并移除）。
- [ ] `SettingsKeys.HIDE_KCAL/HIDE_WEIGHT` 与其消费逻辑**全部保留**。
- [ ] 已设为 true 的老用户，升级后首页/状态页仍隐藏对应数字。
- [ ] 删除 `group_privacy` 等字符串需先确认无其它引用（`check_resources.py` / `check_manifest_resources`）。

**待确认问题**
- 隐私开关彻底失去 UI 入口后，用户若想重新显示数字怎么办？**建议：不新增入口，保持"最后一次设置"生效**（当前目标用户为单人自用，且删除是用户主动要求）。若后续需要，可在「我的」页兜底。请产品/用户确认。

---

### 需求 7｜「训练」与「计划」合并为单条时间轴 + 实时自动更新

**优先级：P1**

**现状（已核实）**
- `ui/PlanReviewActivity.kt` 现有三个文字 Tab（计划 / 训练 / 回顾，`:38-43`），手动「刷新」按钮（`btnRefresh`，`:37`）+「更新」按钮（`btnUpdatePlan`，`:43`），刷新可见性 `updateRefreshVisibility()`（`:82-85`）。
- 计划链：`PlanGenerator.loadCached(todayKey)`（`PlanGenerator.kt:165-176`，**force=false 绝不触网**）、`update()`（`:231-318`，唯一调模型入口）、`TimelineItem(time/type/title/detail/kcal/duration/why)`（`:78-86`）、`buildTimeline()`（`:407-485`）。
- 训练链：`TrainingPlanner.loadOrGenerate(force)`（`TrainingPlanner.kt:149-156`）、`TrainingPlan(focus/days/note/source)`（`:112-117`）、`TrainingDay(dow/title/items/isRest)`（`:100-109`）、`completedDows()`（`:475-489`）、`logPlanDay()`（`:430-463`）。
- 配额：`QuotaGuard` 按 `llm_calls` **行数** 计，抽取桶（含 `PURPOSE_PLAN` + `PURPOSE_TRAINING`）共用 `DEFAULT_DAILY_CALL_LIMIT = 20`/日（`QuotaGuard.kt:44-50,66-68`）。

**合并后单条目数据模型（明确推荐）**

统一轴 = **本周（周一→周日）**；「今天」这一天内部按 `HH:mm` 展开为计划条目，其余天只承载训练安排。条目模型：

```kotlin
data class TimelineEntry(
    val dayIndex: Int,      // 0..6（周一=0），排序第一键
    val sortKey: String,    // 当天内排序：计划项 = "HH:mm"；训练日 = "" (排该天最前)
    val timeLabel: String,  // 展示用 "HH:mm"；训练日为空 → 隐藏时间列
    val type: String,       // meal|exercise|sleep|habit（沿用，"记一笔"依赖它）
    val title: String,
    val detail: String,
    val meta: String,       // duration · why（沿用 item_plan_timeline 的 meta 口径）
    val source: String,     // "plan" | "training"
    val canLog: Boolean,    // meal/exercise 且该条目属于今天且时间未过
    val done: Boolean,      // 训练日已完成（completedDows）
)
```
- 排序：`(dayIndex, sortKey)`。
- 训练日条目：`dayIndex=dow-1`，`timeLabel=""`（无具体时刻），`type="exercise"`，`done=completedDows` 命中。
- 计划条目：`dayIndex=今天`，`timeLabel=item.time`，`type` 沿用 `TimelineItem.type`。
- **不新增数据库表/列**（纯 UI 层合并），因此**不触碰 events schema、不需 Migration**。

**"实时自动更新"触发时机（明确推荐）**

| 触发 | 动作 | AI 调用 |
|---|---|---|
| 打开页面 | `loadCached(todayKey)`；无缓存 → `localTimeline()` | **0** |
| 数据变化（Room Flow / 记一笔 / 训练直写） | 重算本地时间轴并重渲染 | **0** |
| 跨日 / 回前台（`MainActivity.onResume` 口径） | 重算 `todayKey`（`dayKeyOf`）→ 重读缓存 | **0** |
| 缓存不存在 且 已配置 provider 且 有配额 且 距上次 AI 生成 > 6h | 调一次 `PlanGenerator.update()` | **恰好 1 行 `llm_calls`** |
| 用户显式「重排」（见下） | 调一次 `update()` | 1 行 |

**配额成本结论**：所谓"实时"= **本地即时（0 成本）**；AI 重排仍是低频事件，因为一次往返 = 一行 `llm_calls` = 消耗当日 20 次抽取配额中的 1 次。**绝不能在每次进入页面时调 AI** —— 否则一天进出几次即耗尽配额。

**去掉手动「更新」后的补救入口**：默认隐藏；**仅当**「来源=本地兜底」或「生成时间距今 > 12h」时，在来源行右侧显示低调文字入口「重排」（非主按钮）。这样常规路径零手动，异常路径用户仍可主动纠正。`[待确认]`

**验收标准（checklist）**
- [ ] 计划页只有一条按周铺开的统一时间轴；训练日与今日计划条目同轴渲染。
- [ ] 页面无「更新」主按钮；无手动点击也能拿到最新内容。
- [ ] 打开页面、数据变化、跨日三种场景均 **0 次 AI 调用**（用 `QuotaGuard.usedExtractToday()` 断言前后不变）。
- [ ] 自动重排路径下，一次 provider 往返恰好 1 行 `llm_calls`（不变式保持）。
- [ ] 本地兜底时页顶有来源/估算提示（沿用 `plan_source_estimated`）。
- [ ] 「记一笔」仍只对 `meal/exercise` 显示，直写后时间轴即时刷新。

**待确认问题**
- "重排"低调入口是否保留？**推荐保留（低频、可发现）。** 请架构师确认放置位置。
- 6h 的自动重排节流阈值是否合适？**推荐 6h，且同一 `day_key` 内最多自动触发 1 次。**

---

### 需求 8｜本地数据继承配置

**优先级：P0（数据安全）**

**现状（已核实）**
- `db/AppDatabase.kt` version=3，含 `MIGRATION_1_2`（`:83-101`）、`MIGRATION_2_3`（`:109-121`），**无 `fallbackToDestructiveMigration()`**（`:143-145`）。
- `ui/ExportWriter.kt` 只导出 JSON（`buildJson`，`:30-121`），内容含 events/presets/daily_plans/daily_reviews/**非敏感 settings**；**无导入**（全项目 grep `OPEN_DOCUMENT`/`restore` 无业务命中）。
- 备份配置：`AndroidManifest.xml` `android:allowBackup="false"` + `android:fullBackupContent="false"` + `android:dataExtractionRules="@xml/data_extraction_rules"`；`res/xml/data_extraction_rules.xml` 对 cloud-backup 与 device-transfer **全部 exclude**。
- API Key：`security/SecretStore.kt`（EncryptedSharedPreferences，`SecretStore.kt:44-50`），**从不落 SQLite**（`:16-18` 纪律）。

**方案（详细论证见 `调研-数据继承方案.md`）**
- 推荐：**应用内 JSON 导出/导入（双向）+ `allowBackup=false` 保持不变**，并补齐 schema 迁移安全网。
- 具体缺口与落地方案见调研文档"现存缺口"一节。

**验收标准（checklist）**
- [ ] 导入能力落地：`ExportWriter` 增加对应的 `ImportReader`，支持 `ACTION_OPEN_DOCUMENT` 选择 JSON 并写库。
- [ ] 导入幂等：`events` 按 `client_event_id` UNIQUE 去重（`EventEntity.kt:17`），重复导入不产生重复行。
- [ ] 导入不改 schema 版本；用户可见导入结果（成功条数/跳过条数）。
- [ ] API Key **绝不**进入导出文件与任何系统备份（`SecretStore` 纪律不破）。
- [ ] 每次升 Room version 必须新增 Migration + 提交对应 `app/schemas/*.json`（见调研文档缺口）。

**待确认问题**
- 导入是否覆盖现有数据（清库后写入）还是合并？**推荐：合并（按 `client_event_id` 去重），不删现有数据。**

---

### 需求 9｜UI/交互成熟化（竞品借鉴落地）

**优先级：P1**

**现状**：`docs/参考产品研究与取舍.md`（2026-10-03 旧版竞品研究）已有 Fitbod / Bearable / Apple Health / 荣耀运动健康 / Whoop-Oura-Garmin 的取舍结论；设计规范 `docs/Healix设计规范系统.md`。

**目标交互（动作序列）**
1. 用户在一次「记录」完成后，能看到与其相关的、可核对的事实型反馈（沿用既有"可验算数字"纪律）。
2. 用户在需要时能一键把一段时间窗内的事实整理成可交给医生的摘要。
3. 用户在训练日看到基于本地恢复度的安排建议（0 AI 成本）。

**落地清单与优先级**：见 `调研-竞品功能借鉴.md` 的"推荐结论与优先级排序"（Top3-5）。

**验收标准（checklist）**
- [ ] 每个落地的借鉴功能，都能指到数据来源（已有表/键），且标注是否需要 AI。
- [ ] 不引入卡片/阴影/渐变/彩色面；不引入游戏化（CI `check_no_gamification` 通过）。
- [ ] 每个功能都能在"离线 + 未配置 provider"下走本地降级路径。

**待确认问题**
- 借鉴功能的取舍最终以 PRD 推荐清单为准，还是需要用户逐项挑选？**推荐：按推荐清单 Top3 先做，其余列 P2。**

---

## 四、Open Questions 汇总（需架构师/用户先确认）

1. **导航方案**：引入 Jetpack Navigation 依赖，还是手写 FragmentManager？（需求 1）
2. **`ChatActivity` 与 IME/TabBar 迁移**：Fragment 化后如何保持现有键盘守卫行为？（需求 1、2）
3. **一键隐藏开关的最终归属**：隐私栏删除后是否需要在别处兜底？（需求 6）
4. **"重排"入口**：是否保留低调手动入口与 6h 节流？（需求 7）
5. **数据导入语义**：合并 vs 覆盖；是否需要"导入后重启"？（需求 8）
6. **`GOAL_SETUP_DONE` 键名** 需在 `SettingsKeys.kt` 登记并同步 CI 检查。（需求 5）
7. **schema 3.json 缺失**：`app/schemas/com.healix.app.db.AppDatabase/` 仅有 `1.json`/`2.json`，而 DB version=3。需确认是否应在本次补齐 `3.json`（关系到迁移安全网与 CI 校验）。`[待核实]`
