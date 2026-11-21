# 口袋助理实施记录（2026-11-17）

状态：限定写集内主要实现完成，Rust 已验证；等待主代理文件下载接线及 Android qa20261117 验证。保留共享工作区原有改动，无提交、部署、安装、真实模型请求、用户进程重启或新建子代理。

## 已实现

- 对话主区固定「全部任务／待处理／成果」入口，主要导航「对话／任务中心」。内容卡改为实底，导航与输入保留玻璃效果。输入工具不再包含记忆与定时管理。
- 任务中心搜索、分区和成果类型筛选；目标卡直接展示真实阶段、待处理及成果摘要；分组复用已有持久 TaskGroup 作为父目标，子任务一层展开，避免按成员作为默认导航。
- 任务详情「概览／过程／成果」，直接处理原问题和审批，复用原 request/operation 身份；恢复请求保留原边界。任务补充绑定 taskId，原草稿只随匹配接收回执清空，未知发送不自动重试。
- 成果按 operation 保存版本；文字结果预览、SAF 保存、分享，链接打开；文件引用由 Host 在原任务权限范围内捕获 SHA-256 不可变快照，最大24MiB，原文件缺失和下载失败分别呈现。UI 已提供文件预览、保存、分享操作，待主代理接下载 source。
- 「基于成果继续」发送 artifactId + artifactVersion + taskId；后端校验来源和版本，附原不可变文件快照或文字结果上下文，不能借客户端路径扩大授权。
- 定时不强制编程项目，通用事项使用 Host 自有工作区；项目任务继续核验原项目证明。编辑、暂停、恢复、手动运行、持久执行历史；每次执行关联独立任务。默认 Asia/Shanghai（既有显式时区保留）；错过默认跳过，编辑可选仅补一次；不批量补历史。
- 记忆自动保存仅来自当前真实用户 handoff；保留来源、个人/事实/项目范围、变更时间记录。撤销从后续上下文移除，并阻止同文新自动保存恢复；手动恢复仍允许。任务详情展示提供给该次执行的 memory IDs，可禁止后续执行采用记忆。删除/撤销不保留旧正文副本。
- Coordinator 动态工具 revision 提升到3，使新 scope 参数进入可核验工具目录；继续沿用原执行回执、未知状态和权限隔离。

## 主代理必须接线

1. `broker.rs` 只读分发添加：
   `"assistant/artifact/read" => return self.assistant_artifact_read(&p).await,`
   handler 已在 broker_assistant.rs。
2. `PocketClient.ASSISTANT_READS` 加 `assistant/artifact/read`。其它新增 assistant 写方法已经自动分类，无需白名单。
3. Repository 添加 `suspend fun assistantArtifactFile(artifact: AssistantArtifact): File`，通过上述只读接口下载，不使用当前普通会话的 owner。请求只有 artifactId 和 offset（默认0）；返回 artifactId/version/sha256/mimeType/name/sizeBytes/offset/data(base64)/eof，每块最多192KiB，整体最大24MiB。绑定电脑和连接，逐块核对身份/版本/offset/大小，落盘后验 SHA-256，缓存到 `cache/capture/assistant/`。
4. `AssistantScreen` 外提供 `LocalAssistantArtifacts`：
   `AssistantArtifactSource { val ownerKey:String; suspend fun file(artifact:AssistantArtifact):File }`。
   ownerKey 绑定电脑和连接 epoch。UI 在 source 改变时丢弃旧下载响应。复用既有 `${applicationId}.files` FileProvider；无需改 Manifest。

新增写方法均沿用现有 `PocketRepository.assistantAction`：

| 方法 | 参数（另有原 runtimeId/commandId） |
|---|---|
| assistant/schedule/update | scheduleId、spec、missedPolicy=skip/run_once |
| assistant/schedule/pause、resume、run | scheduleId |
| assistant/memory/undo | memoryId |
| assistant/task/memory | taskId、enabled |
| assistant/send 增量 | artifactId、artifactVersion，须与原 taskId 同时使用 |

`assistant/read` 增量：artifacts、scheduleRuns、memoryMetadata、memoryUsage（operationId→memoryIds）、memoryDisabledTasks。旧客户端可忽略；Android 只修改 AssistantPresentation/AssistantProduct 解析，未改 NativeModels 或 PocketDatabase。

## 验证（实际执行）

- `cargo test -p pocket-agent --lib product_`：15 passed，0 failed。最后加入成果继续版本校验后再次执行仍15/15。
- `cargo test -p pocket-agent --lib assistant`：291 passed，3 failed。三个失败都在 `managed_runtime/tests/fixture.rs:78` 因未设置 `MANAGED_RUNTIME_TEST_HOST` 前置条件退出，未进入业务断言：fenced_startup_saved_profile_attaches_original_host_without_rewriting_checkpoint、silent_prepare_reuses_managed_host_reports_login_and_never_starts_model_turns、silent_restore_verified_msix_persists_only_binary_after_old_owners_end。
- `rustfmt --edition 2024 --emit stdout ... > $null`：解析通过；不改原格式。
- `git diff --check`（本次 assistant 写集）：通过。
- 测试使用 Fake 执行器，不调用真实模型。
- Android 新增 `AssistantProductTest`（4项）、`AssistantProductUiTest`（2项），交给主代理固定 qa20261117 运行；子代理未运行 Gradle/MuMu，避免并发构建。不得将他人初步编译结果等同于这些新测试通过。

## 剩余验收和实现边界

- 主代理完成3处文件下载接线并实测文件预览、保存、分享、断线/换电脑/哈希错误。Android 页面布局、键盘、滚动和真实触摸仍需验收。
- 需要带现有隔离 runtime-host 路径补跑上述3项fixture测试。
- 成果从最终输出中的 Markdown 文件/链接引用捕获；只写纯文件名且没有可解析路径的输出不会假装有文件成果。外部链接不自动下载；快照首次缺失或越界后保持该版本不可用，不会以之后出现的文件替换。
- 「不使用记忆」从后续执行上下文开始生效，已经执行的原生过程保留；UI明确说明，不会强制重启正在运行的工作。
- 首次新建定时采用skip；可通过编辑切换run_once。每次触发历史保留，通知送达没有独立的可验证回执，未伪造“已送达通知”。
- 没有新增外部服务连接、模型或图片输入能力。输入附件沿用现有能力，不展示虚假的上传按钮。

## 本次主要新增文件

Rust：assistant/service_product.rs、service_product_tests.rs，assistant_product/artifacts.rs、tests/product_flow.rs。
Android：data/AssistantProduct.kt，ui/AssistantTaskCenter.kt、AssistantArtifacts.kt，对应 AssistantProductTest 和 AssistantProductUiTest。
其它改动均在获准 assistant 写集内。未修改 broker.rs、lib.rs、PocketRepository.kt、NativeModels.kt、PocketDatabase.kt、wire、ChatScreen.kt、Composer.kt、desktop 或 scripts。


## 追加页面复核与 MuMu fixture（2026-11-17）

本轮只做静态复核、修改和测试编写，Gradle/instrumentation 由主代理统一运行。没有自行运行模拟器或真实模型。

修复：

- 二级页面导航保留来源栈：任务/成果→记忆/定时→返回会回到原页及阅读位置；主导航明确重置栈。重复点当前入口不再制造重复栈。
- 任务中心筛选与定时入口分两行，筛选等宽；成果操作改 FlowRow，覆盖360dp及大字体。
- 聊天「完整结果」页显示同一 operation 的成果卡和来源任务入口。
- 新增记忆清空上次编辑文本，避免误保存旧内容。
- 读状态过期、读取失败或正在派发时禁止依赖旧快照的助理写操作；草稿仍保留。

在 `dev.codexpocket.app.AssistantCollaborationDeviceTest` 新增三个 instrumentation 方法：

1. `productDispatchTaskDetailQuestionAndArtifactNavigation`：主输入交办，核对发送回执后展示父目标/子任务；任务卡→详情审批→补充问答；确认请求身份及pending禁止重复；fixture完成后进入成果页和成果中心；从记忆返回成果/详情，保留聊天草稿。
2. `productSchedulesMemoryAndLargeFontReturnToOrigin`：1.4x字体下检查筛选和定时入口的真实bounds；编辑已有事项保留09:30及Asia/Shanghai、默认skip、不可改原scope；暂停/手动运行回调；历史→任务→记忆编辑/撤销→逐层返回。含新增记忆不复用旧编辑文本断言。
3. `productTaskFollowupKeepsDraftUntilMatchingReceipt`：定向taskId、接收期间禁重发、错电脑回执不清草稿；正确回执保留随后编辑的新草稿；导航返回保留草稿，fresh=false禁止发送。

每个方法单独新进程运行，参数：`assistantCollaborationDevice=true`、`assistantCollaborationProofRun=qa20261117`。现有outer rule在Activity启动前移除生命周期reconnect observer、禁止Repository lazy访问；拦截MainActivity为fixture Activity，捕获回调不执行命令。截图使用真实Activity窗口PixelCopy；输出到应用external files下 `assistant-collaboration-proof/qa20261117/<method>/`（result.json及PNG）。MuMu次显示迁移只针对该fixture task，沿用已存在机制。

类/方法选择：`dev.codexpocket.app.AssistantCollaborationDeviceTest#<method>`。本轮未运行，等待主代理返回编译和instrumentation结果。测试不会真正打开分享选择器或保存到用户文件；只验证这些入口可见，真实文件传输/分享由主代理集成验收。

## 定向 Android 用例修复（2026-11-17）

主代理反馈15项定向测试14项通过，下载测试通过。读取 `TEST-dev.codexpocket.app.ui.AssistantProductUiTest.xml` 后确认，剩余 `taskDetailAnswersOriginalRequestAndNeverSendsApprovalAsChat` 在详情渲染时失败：fixture 直接挂载 `AssistantDetailPage`，缺少生产 `AssistantScreen` 提供的 `ProvideMarkdownRendering`，导致 `MarkdownText` 的 `LocalMarkdownCache` 空值检查抛错，尚未进入审批点击。

已给该 fixture 补齐相同 provider，保留审批使用原 requestId 和 `assistant/respond` 的断言。没有修改生产 Markdown 空值检查或审批逻辑。本次仅完成静态检查，未启动 Gradle；请主代理重跑 `dev.codexpocket.app.ui.AssistantProductUiTest`，通过前不将其记为已验收。

## 全量 Android 结果与旧用例适配（2026-11-17）

主代理本次全量运行结束：1175 tests，4 failed。已核对 `.local/implementation-20261117/android-checks.log` 及对应 XML；新 `AssistantProductUiTest` 两项、`AssistantProductTest` 四项、`AssistantArtifactDownloadTest` 两项均通过。先前 Markdown provider 的修复已在这次运行得到验证。

四项剩余失败的适配已写入，等待主代理下一轮执行：

- `AssistantHousekeeperTest.primaryDestinationsStayVisibleWithoutOpeningOverflow`：按批准导航验证 Tasks/Pending/Results 三个常驻入口逐个进入对应筛选，定时入口在任务中心可见，记忆经更多进入并能返回。不再要求旧的定时/记忆常驻快捷标签。
- `AssistantScreenTest.readFailurePreservesWaitingDraftAndBlocksSubmissionUntilFreshRead`（原 readFailureKeepsWaitingInputEnabledAndRetryIsReadOnly）：先写入答案，再模拟读取失败；草稿保留、提交禁用，重试只发 assistant/read；仅在 fixture 返回 fresh=true 且清除 readError 后恢复原 requestId 提交。保留既有过期请求禁提交边界，没有放宽生产逻辑。
- `AssistantScreenTest.schedulesCreateWithRealRulesAndMissedStateAndDelete`：移除旧文案依赖，保留权限状态断言，新增原项目 threadId 核验；错过状态验证“已记录”和“跳过”两层语义。
- `AssistantScreenTest.taskControlsRespectReconciliationAndPausedQuestionCannotApprove`：保留暂停审批禁用断言，之后通过常驻“全部任务”进入任务中心，再逐一进入任务详情验证暂停、恢复和核对限制。避免在聊天尾部用完整任务管理的假设寻找所有控件。

三个 product instrumentation fixture 已准备好，可以编译 androidTest。`productDispatchTaskDetailQuestionAndArtifactNavigation` 还补上了常驻入口/待处理筛选和详情回答遇到读失败后的草稿保留、只读刷新、恢复提交链路。类名、三个方法名和 opt-in 参数同上；每项仍须独立新进程运行，fixture 不访问 Repository 或真实模型。

本轮修改仅涉及上述助理测试与本文件；未运行 Gradle/instrumentation。`git diff --check` 无格式错误；设备布局与这四项修正尚待下一轮实际执行，不能据静态复核宣称通过。

## 主代理最终集成与验证（2026-11-17）

本轮最终 Android 全量结果为 1175 通过、0 失败/错误/跳过，APK、androidTest APK 和 lintDebug 均通过。上述四项旧用例适配已经验证，见 ../.local/implementation-20261117/android-delivery-check.log。

主代理接通 assistant/artifact/read 与手机分块下载，校验电脑/连接、成果 ID/版本、偏移/长度和 SHA256，文件缓存在独立成果目录。任务详情成果页签上移到稳定页面状态；MuMu 曾实际暴露返回成果后页签丢失，此问题修复后复测通过。任务中心也去除了已被父目标覆盖的空分组标题。

MuMu 最新独立包 dev.codexpocket.app.qa20261117：任务派发→父子分工→待处理审批/回答→成果预览→返回保留草稿，以及 1.4 倍字体定时/记忆导航分别通过。最新运行目录为 ../.local/implementation-20261117/mumu-delivery/；任务补充草稿/回执另有 mumu-assistant-followup.log 通过。图片草稿快速滑动与自动跟随两项测试通过。均为无模型请求的隔离 fixture，不能代替真实配对、模型交付或物理手机验收。

主代理已人工查看任务中心普通/大字体截图。文件分享选择器和用户保存目录的实际写入尚未执行。正式 App 与后台未替换，整体限制与交付见 IMPLEMENTATION-20261117.md。
