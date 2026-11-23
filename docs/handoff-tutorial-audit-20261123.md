# 教程文案核对（2026-11-23）

已核对 `PocketTourChapters` 的 10 章，修正文案并将 `PocketTutorialController.Version` 从 1 升至 2。章节顺序、标题、图标、每章三个要点及场景、版式、动效保持原样。修改限于 `PocketTutorial.kt`、`PocketTutorialTest.kt` 和本报告。

| 章 | 核对结果与处理 | 代码依据 |
|---|---|---|
| 1 欢迎 | 手机用本地数据库保存草稿，不能描述为与电脑草稿共享。改为“会话与历史保存在执行位置，草稿保存在手机”，补齐设置内重看教程的路径。 | [DraftEntity 与 pocket.db][draft]；[使用教程入口][tutorial-entry] |
| 2 配对 | 电脑实际按钮是“添加设备”。有效期为 300 秒；扫码和手动确认都会校验有效期。保留 5 分钟与相机权限说明，补充相册选码、电脑名称确认，保留手动粘贴。 | [电脑入口][desktop-pair]；[有效期][pair-expiry]；[相机、相册及手动入口][pair-ui]；[确认时校验][pair-check] |
| 3 执行位置 | 首页电脑菜单和设置“电脑连接”均可切换。SSH 先核对主机指纹，支持密码或密钥；本机入口取决于安装包能力，开聊前需账号或模型服务。删除“填好即自动装好”的承诺。 | [首页切换][locations-home]；[设置切换][locations-settings]；[SSH 表单][ssh]；[本机入口条件][phone-entry]；[本机登录提示][phone-access] |
| 4 首页 | 三个页签、右上角新建、搜索与项目归组、底栏拖动均有对应入口。此章保留原文。新建按钮已有连接与运行时能力判断。 | [首页列表][home-list]；[顶部入口与拖动底栏][home-chrome] |
| 5 发送 | 运行中发送进入队列，立即引导作用于当前回合，停止为独立操作；按钮可用性由连接和运行时能力判断。教程使用实际按钮名，附件改为图片或文本，模型与盾牌入口分别说明。 | [发送能力及操作栏][composer]；[按钮名称][send-labels]；[引用与技能][suggestions] |
| 6 过程 | 命令、读写和搜索按步骤展示，当前思考位于底部状态区，历史不逐条展示思考。过程行负责展开、收起，完整详情另有按钮；文案采用实际按钮名，文件差异限定为有改动的回合。 | [过程行与详情按钮][activity]；[回合文件汇总][changes]；[底部思考与回到最新][chat] |
| 7 确认 | 命令和写文件并非每次询问；“本会话允许”只在请求提供该选项时出现。计划提案完成后才有实施或修改入口。通知使用实际设置名“后台关注”，注明允许通知。 | [权限策略][permission-policy]；[审批选项][approval]；[计划显示条件][plan-state]；[计划操作][plan-ui]；[通知设置][notifications] |
| 8 修改 | 编辑仍在气泡内；仅支持每轮首条用户消息，运行中须先停止。保留撤回对话不撤销文件修改的说明；复制按钮描述改为消息及已完成回复。 | [编辑条件][edit-state]；[气泡编辑器][edit-ui]；[消息与回复操作][transcript] |
| 9 助理 | 首页圆形入口进入助理，任务、成果、计划、记忆均有对应页。保存使用系统位置选择器；计划按所选时区执行，默认跳过错过时间，详情可调整补执行。记忆支持修改、删除及撤销自动保存。 | [入口][home-chrome]；[助理页面路由][assistant]；[成果保存][artifacts]；[计划设置][schedules]；[调度条件][scheduler]；[记忆操作][memory] |
| 10 用量与设置 | 用量页展示原生 Codex 或 CC Switch 的记录，不能称为账户剩余额度。改为“用量记录”。连接诊断仅在线可用，因此提示离线先重新连接。外观设置保留。 | [用量来源][usage]；[诊断与重连][connection]；[外观设置][notifications] |

## 测试与交接

- 红灯日志：[handoff-core-green-tutorial-red-20261123.log](../.local/handoff-core-green-tutorial-red-20261123.log)。主代理运行 65 项，6 项失败，全部为本次新增教程测试。红灯之后修改生产文案与版本。
- 新增测试共 6 项：草稿存储误导、真实配对入口与有效期、运行中的发送操作、审批条件、用量非额度、Version 1 → 2 重开与完成记录。原有 5 项覆盖、翻页、返回、静止画面及重开测试保留。
- 绿灯：[handoff-targeted-integrated-20261123.log](../.local/handoff-targeted-integrated-20261123.log) 中教程 11 项全部通过；对应 JUnit XML 为 11 tests、0 failures、0 errors、0 skipped。
- 静态检查通过：`git diff --check` 无错误；与审计起点 `769c070` 直接对比，生产文件仅字符串与 Version 改变，仍为 10 章、30 个要点；报告的 34 个代码引用均指向已有文件。Gradle 由主代理串行执行。
- 主代理已检查 `android/app/build/reports/tutorial/` 下 10 章实际渲染，文字未裁切或重叠；联系表位于 `.local/tutorial-contact-sheet.png`。最终全量单测日志 `handoff-integrated-unit-20261123.log` 中教程 11 项仍全部通过。真实设备上的首次展示和重开验收待 QA 包安装后完成。

需要再次运行教程测试时，在 `android/` 使用：

```bash
../.local/run-gradle.sh handoff-tutorial-green-20261123 :app:testDebugUnitTest --tests "dev.codexpocket.app.ui.PocketTutorialTest"
```

[draft]: ../android/app/src/main/java/dev/codexpocket/app/data/PocketDatabase.kt#L61
[tutorial-entry]: ../android/app/src/main/java/dev/codexpocket/app/ui/SettingsScreen.kt#L285
[desktop-pair]: ../desktop/src/App.tsx#L84
[pair-expiry]: ../crates/agent/src/local.rs#L395
[pair-ui]: ../android/app/src/main/java/dev/codexpocket/app/ui/PairingScreen.kt#L86
[pair-check]: ../android/app/src/main/java/dev/codexpocket/app/ui/PairingScannerState.kt#L63
[locations-home]: ../android/app/src/main/java/dev/codexpocket/app/ui/PocketRoot.kt#L355
[locations-settings]: ../android/app/src/main/java/dev/codexpocket/app/ui/SettingsScreen.kt#L184
[ssh]: ../android/app/src/main/java/dev/codexpocket/app/ui/SshLocationSheet.kt#L62
[phone-entry]: ../android/app/src/main/java/dev/codexpocket/app/ui/ExecutionLocationsRoot.kt#L52
[phone-access]: ../android/app/src/main/java/dev/codexpocket/app/ui/ThisPhoneSheet.kt#L67
[home-list]: ../android/app/src/main/java/dev/codexpocket/app/ui/HomeScreen.kt
[home-chrome]: ../android/app/src/main/java/dev/codexpocket/app/ui/HomeChrome.kt#L100
[composer]: ../android/app/src/main/java/dev/codexpocket/app/ui/Composer.kt#L69
[send-labels]: ../android/app/src/main/res/values/v02_strings.xml#L25
[suggestions]: ../android/app/src/main/java/dev/codexpocket/app/ui/ComposerSuggestions.kt
[activity]: ../android/app/src/main/java/dev/codexpocket/app/ui/NativeActivityRows.kt#L217
[changes]: ../android/app/src/main/java/dev/codexpocket/app/data/TurnChangeSummary.kt
[chat]: ../android/app/src/main/java/dev/codexpocket/app/ui/ChatScreen.kt
[permission-policy]: ../crates/bridge/src/permission_policy.rs#L43
[approval]: ../android/app/src/main/java/dev/codexpocket/app/ui/ApprovalCard.kt#L90
[plan-state]: ../android/app/src/main/java/dev/codexpocket/app/data/PlanPresentation.kt#L23
[plan-ui]: ../android/app/src/main/java/dev/codexpocket/app/ui/PlanConfirmation.kt#L55
[notifications]: ../android/app/src/main/java/dev/codexpocket/app/ui/SettingsScreen.kt#L263
[edit-state]: ../android/app/src/main/java/dev/codexpocket/app/data/MessageEdit.kt#L32
[edit-ui]: ../android/app/src/main/java/dev/codexpocket/app/ui/MessageEditBubble.kt
[transcript]: ../android/app/src/main/java/dev/codexpocket/app/ui/Transcript.kt#L100
[assistant]: ../android/app/src/main/java/dev/codexpocket/app/ui/AssistantWorkspace.kt#L90
[artifacts]: ../android/app/src/main/java/dev/codexpocket/app/ui/AssistantArtifacts.kt#L68
[schedules]: ../android/app/src/main/java/dev/codexpocket/app/ui/AssistantSchedulesPage.kt#L24
[scheduler]: ../crates/agent/src/assistant/service.rs#L1986
[memory]: ../android/app/src/main/java/dev/codexpocket/app/ui/AssistantMemoryPage.kt#L54
[usage]: ../android/app/src/main/java/dev/codexpocket/app/ui/UsageScreen.kt#L168
[connection]: ../android/app/src/main/java/dev/codexpocket/app/ui/SettingsScreen.kt#L206
