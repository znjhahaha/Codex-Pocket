# 2026-11-17 Codex Pocket 接入、更新与助理实施记录

状态：源代码、工程构建和隔离验收已完成本轮交付；尚未发布或替换本机正式安装，完整生产升级和物理手机验收仍未通过。

## 范围与现场保护

按用户批准的方案实施，保留 Rust / Tauri / Jetpack Compose。当前工作区原有 214 个修改或未跟踪文件，基线保存在 ../.local/implementation-20261117/baseline.zip 与 baseline.json；没有清理原修改、提交、推送或发布。

用户后续明确允许 MuMu 和一个子代理，替代最初“不用 MuMu”的限制。实施仅使用 Dalton 一个子代理，负责助理数据和界面，主代理完成接入、历史、更新、滚动及集成验证。子代理最终复核请求遇到 429 后，余下修复由主代理完成。

正式 Pocket 仍是原安装；账号、配对、原生会话和助理数据库未作迁移操作。测试未发送真实模型请求、未关闭官方 Codex，也未执行真实历史接手。MuMu 使用独立包名 dev.codexpocket.app.qa20261117。

## 已实现

### 运行时与历史

- npm 新 vendor/<target>/bin/codex.exe 和旧路径、独立 CLI、当前用户注册的 MSIX 包发现；失效版本目录可按包身份重新发现。
- 程序安装、连接、历史读取和会话控制能力分开；普通官方入口不依赖全局 CODEX_CLI_PATH。
- 会话目录按电脑、配置档案与原生会话 ID 去重；运行时断开时仍提供持久历史目录。
- state_5.sqlite / thread_history_1.sqlite 只读适配与旧 rollout 回退，分页、搜索、CLI/桌面来源、图片和工具内容；未知格式、空会话与缺失正文分开处理。
- threads/history/read 以配置档案与原会话 ID 读取，不 resume。历史图片沿现有授权媒体通道传输；助手内部会话不进入普通目录。
- 显式“接手继续”在外部执行释放后才启动或连接共享 daemon。检查已注册进程的 PID 与创建时间，并扫描原生名字、所选二进制路径及 app-server 命令行；身份不明维持等待。扫描命令行不写入日志或响应。
- Android 使用原生内容接口优先，持久历史回退；阅读来源和分页位置保留，历史媒体引用可刷新。

实际只读探测：12 条 CLI 会话，36 回合，211 条正文条目，6 条空会话。日志 ../.local/implementation-20261117/history-probe.log。这证明读取器适配本机数据，不代表正式后台已升级运行。

### 普通会话输入与滚动

- 移除“列表距底→输入框收缩→底部几何变化→再次滚动”的联动；普通会话输入框采用稳定布局。
- 用户拖动暂停自动跟随，返回末尾才恢复；附件和输入文字不再因列表滚动折叠。
- MuMu 图片加中文草稿快速滑动测试通过，15 次观测输入框高度均为 609px、静止后的列表位置均为 34692。
- 自动跟随在布局阻塞、内容增长和用户拖动时的测试通过。
- 未可靠复现修复前的原始弹簧抖动，不能宣称原故障已完成红绿复现；MuMu 也不代替物理手机触摸验收。

### 更新生命周期

- Velopack 固定 1.2.161，负责安装管理器与包更新。管理器位于 CodexPocketManager，持久后台代际仍在独立 CodexPocket/bin/<generation>，避免管理器安装过程结束正在工作的任务进程。
- 完整包仍由现有 Ed25519 更新信任验证，校验包长度、SHA256、包身份、清单及逐文件库存后暂存。没有生成替代正式签名密钥。
- 统一复用检查点交接：等待空闲、保存状态、退出 Pocket 后台、验证候选握手/数据/构建身份，失败恢复。新后台已接任务后不以旧数据库覆盖新数据。
- Pocket 更新不自动停止官方 Codex 和它的原生会话；旧桥接保留至正常释放。
- 修复安装指针已切换、交接事务尚未建立时的中断窗口：在安装及协调器锁内恢复原程序指针，保留用户数据。
- 显式本地安装与自动更新设置分开；本地固定签名安装包允许过期后离线使用，在线更新源仍验证有效期。
- 新增独立本地安装协调器，绑定具体操作 ID，等待旧后台空闲，不依赖旧版本理解新状态。只为该事务注册当前用户登录恢复项，结束后移除；错误或进程组限制保留可诊断状态。
- 管理器状态损坏不阻塞后台生命周期；独立管理器更新失败记录原因并提供重试。
- UI 代际替换先由等待进程捕获旧 UI 的 PID/创建时间，再确认旧 UI 可以退出；退出后打开当时有效的安装代际。相同版本的轮询只读小清单，不重复哈希整个 UI 或访问后台。
- 桌面更新页显示目标、已安装和实际运行版本，以及管理入口与独立协调器状态。安装失败有可见错误提示。
- CI 与打包脚本已切换默认 Velopack 路径，NSIS 仅保留显式 legacy 路径。

真实 Velopack 隔离样本完成 1.2.2→1.2.3 更新，实际安装清单为 1.2.3；损坏包失败并保留旧版本。证据 ../.local/implementation-20261117/velopack-fixture/acceptance.json。这是使用真实 Velopack 和 Rust API 的无业务样本，不能冒充正式 Pocket 的完整升级验收。

### 口袋助理功能布局

- 对话常驻“全部任务 / 待处理 / 结果”，任务中心搜索与稳定排序；父目标和子任务状态行、真实等待原因与已有成果摘要。
- 任务详情“概览 / 过程 / 成果”，直接回答与审批，补充要求绑定任务；重复点击不能重复执行，未知回执保留草稿和核对入口。
- 成果保存来源和版本，文件快照及 SHA256 校验，手机分块下载、预览、保存/分享入口，基于成果继续绑定原任务/文件版本。
- 定时事项默认 Asia/Shanghai，编辑、暂停、手动执行、历次记录；错过默认跳过，可选择补跑，通用任务不强制项目。
- 记忆自动保存用户事实并保留来源/作用域/修订，可编辑、删除和撤销；不扩大执行授权。
- 导航和草稿保留。设备测试发现并修复从成果返回任务详情后页签丢失；任务中心空分组标题也已去除。

详细数据和行为记录见 IMPLEMENTATION-20261117-ASSISTANT.md。

## 验证结果

以下日志位于 ../.local/implementation-20261117/，自动化和样本测试不等于所有发布门槛通过。

| 检查 | 结果 | 证据 |
|---|---|---|
| Rust 主工作区全量 | 1005 通过，12 忽略；之后历史局部修改另测 | workspace-final.log |
| 历史相关最终回归 | 69 通过，4 fixture 忽略 | history-delivery-check.log |
| 新接手进程校验 | 2 通过，含真实改名样本进程 | takeover-final.log |
| Windows 运行时最后全量 | 232 通过，13 忽略 | desktop-delivery-final.log |
| Tauri 管理命令 | 55 通过 | tauri-delivery-final.log |
| 桌面前端 | 249 通过，TypeScript/Vite 构建通过 | desktop-ui-delivery.log |
| Android 全量单元 | 1175 通过，0 失败/错误/跳过 | android-delivery-check.log 与 XML 报告 |
| Android 构建 | Debug APK、androidTest APK、lintDebug 通过 | android-delivery-check.log |
| Agent / Bridge / Runtime Host / 桌面 | 当前源码 Debug 工程构建通过，UI 包含内置前端 | *-delivery-build.log |
| MuMu 最新任务全链路 | 1 通过 | mumu-delivery-task.log |
| MuMu 最新定时/记忆/大字体 | 1 通过 | mumu-delivery-schedule.log |
| MuMu 任务补充草稿/回执 | 1 通过 | mumu-assistant-followup.log |
| MuMu 图片草稿/自动跟随 | 2 通过 | mumu-scroll-final.log |
| 真实 Velopack 样本 | 更新与损坏包保留旧版通过 | velopack-fixture/acceptance.json |
| 真 supervisor/coordinator/agent 全链路 | 未通过环境前置条件，不能记成功 | actual-handoff.log |

PowerShell 安装生命周期 32 项及首启、空闲、信任测试已在本轮早期通过；9 项 handoff_product 实际进程测试包含在主工作区全量结果中。忽略项包含需显式启用的设备/真实进程样本，未混入通过计数。

已人工查看 MuMu 任务中心普通/1.4 倍字体截图，目标与子任务、待处理操作、定时入口可见；没有用模型生成的图片替代截图。分享选择器、用户保存目录写入及真实外部服务未做端到端验收。

## 尚未通过的发布门槛

1. 更正：原 Android 与 Ed25519 发布密钥存在于用户私密目录，上一轮只检查项目目录而误报缺失。现已沿用原更新信任密钥生成并核验 0.7.1-rc.20261117.1 Windows 安装包；用户选择自行安装，完整安装升级验收仍待执行。EXE 未作 Windows Authenticode 签名。
2. 执行器 Job Object 拒绝 CREATE_BREAKAWAY_FROM_JOB（Windows 错误 5），完整后台链路测试在启动前失败。独立隐藏进程启动尝试未产生可核验结果，不能作为通过证据。未为测试放宽产品进程独立性要求。
3. 全新正式安装、真实旧版升级、断网、升级途中退出/重启恢复、真实管理界面替换，以及官方入口重新发现矩阵仍需要候选签名安装包验收。
4. 官方桌面 stdio 未开放控制时仍只能读历史并等待安全接手；不伪报“已接入”。本轮没有打断当前官方进程来验证接手。
5. 物理手机连续触摸、真实配对/断线重连和模型执行交付仍未验收。

## 交付

- Windows x64 候选安装包：[codex-pocket-0.7.1-rc.20261117.1-windows-setup.exe](../dist/v0.7.1-rc.20261117.1/codex-pocket-0.7.1-rc.20261117.1-windows-setup.exe)，51,261,090 字节（约 49 MiB）；版本与构建编号均为 0.7.1-rc.20261117.1。
- [安装说明](../dist/v0.7.1-rc.20261117.1/INSTALL-NOTES.md)与 [SHA256SUMS.txt](../dist/v0.7.1-rc.20261117.1/SHA256SUMS.txt)。SHA256：395dabe2aa01fcc9af007b99fd717694e2a3c0093e775e460fb0a4382302299f。
- 最终 Release 构建、生产安装源验证、3 份 Ed25519 签名验证、完整 nupkg 解包和 27 个载荷文件核对通过，包内 pocket-setup.exe 与最终构建相同。证据位于 ../.local/desktop-package-20261117/desktop-package-fix-release.log、package-final.log、final-package-verified.log、delivery-verification.json。
- 完整包检查发现 Velopack 依据产品标题生成 Codex Pocket_ExecutionStub.exe，原读取器误拒绝空格。仅兼容该准确的管理器文件名且不提取；逃逸路径和载荷目录同名文件仍拒绝。回归先失败后通过，共 3 项通过；修正已纳入最终安装包。初始产物已移到本地检查归档，未交付。
- 隔离 QA APK：../dist/pocket-preview-20261117/codex-pocket-qa20261117.apk
- 截图及说明：../dist/pocket-preview-20261117/
- 正式安装仍保持原状。需要原安装数据的产品验收应使用正式签名候选包，不将 QA 包解释为生产更新。
