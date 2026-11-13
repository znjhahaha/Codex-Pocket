# 助理协作与 CLI 静默准备验收

日期：2026-11-12（Asia/Shanghai）。基线：`70c4ae6` / 0.6.6；分支：`codex/assistant-collaboration`。

当前状态：本机已恢复，最新助理状态为 ready，后台连接正常；交付包保留 0.6.6 版本号，此次提交用于 GitHub 源码同步，安装包尚未正式发布。完整安装后的首次自动恢复仍存在需要追加 Pocket helper 重启的限制，见最新恢复记录。下文 Windows 未安装描述记录首次候选交付时的范围。用户随后授权了完整现场修复：旧后台和失败交接已恢复，Windows 界面曾验证已连接；最终安装器新增完整停机、重启、缓存启动器恢复与就绪核验。最新证据与实际安装结果位置见 `WINDOWS-INSTALL-RECOVERY.md`，不能把后续结果归于最初的候选安装器。

## 已验证的范围

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| Rust 工作区（最终审查修复前） | 735 通过，11 项显式探针/环境夹具跳过 | `.local/workspace-final-tests-20261112.log` |
| Agent 库（最终审查修复后） | 483 通过，8 跳过；其中助理定向 96 通过 | `.local/backend-final-lib-20261112.log` |
| Runtime Host | 18 通过 | `.local/runtime-host-final-tests-20261112.log` |
| Runtime IPC | 10 通过 | `.local/runtime-ipc-final-tests-20261112.log` |
| 桌面前端 | 245 通过，生产构建通过；轮询浏览器夹具 2 通过 | `.local/desktop-powershell-popup-20261112.md` 与最终全套日志 |
| 桌面 Rust | 202 通过，1 跳过；最终文案更正后集成修复检查 19 通过 | `.local/desktop-final-wording-tests-20261112.log` |
| Android App | 最终全部修改后 141 个套件、1,042 项通过，无失败/错误/跳过 | `.local/android-post-render-final-suite-20261112.log`、`.local/android-post-render-suite-results-20261112/` |
| Android lint | 最终无错误，186 警告、1 提示 | `.local/android-post-render-lint-20261112.xml` |
| 会话链接换行 | 六类 23 项通过；窄屏、大字体原生渲染已检查 | `.local/thread-link-layout-fix-20261112.md` |
| 归属提示与重新核验 | Android 28 项通过；后端归属回归包含于上述 483 项 | `.local/owner-conflict-fix-20261112.md` |
| Android Wire | 88 通过 | `android/build/android-completion-20261112-final.log` |
| Bridge 与 CLI 包装器 | 16 通过 | `.local/node-final-tests-20261112.log` |
| Windows 安装器 | 33 通过，隔离夹具 | `.local/installer-final-tests-20261112.log` |
| 原对话历史只读探针 | 3 通过；三组原始提问、四个回答与七张图片可恢复 | `.local/real-history-final-probes-20261112.log` |
| 本次五张真实上传截图 | 0.6.3 媒体代码回放全部失败；候选媒体代码回放全部读取成功 | `.local/user-screenshot-media-diagnosis-20261112.md` |
| MuMu 缓存只读核验 | 1 项通过，零 repository/transport 调用，数据库与 WAL 内容未变 | `.local/mumu-assistant-20261112/room-approved-1420/` |
| MuMu 最终硬件界面 | 六项全部通过；历史问答另做三次独立进程复测均通过；所有测试零 repository 访问 | `.local/mumu-assistant-20261112/final-isolated-v5/`、`history-cold-v5-{1,2,3}/` |
| 返回阅读位置 | 四项单元检查通过；MuMu 展开成员树前后锚点均为 1,896px | `.local/android-near-bottom-recovery-20261112.log`、最终设备报告 |
| 原生依赖 | 与原 0.6.6 交付内的 6 个 Android 库、2 个 Windows 库及 cloudflared 文件逐项哈希相同 | `.local/build-assistant-candidate-20261112.ps1 -PreflightOnly` |

工作区首次运行发现 CLI 测试将 AF_UNIX socket 放在过长的 managed worktree 路径下。测试夹具改用系统临时目录后全套通过；产品的 108 字节地址校验未放宽。

原对话探针使用已有记录和本机图片，不向模型发送任务。历史恢复测试通过不代表真实手机的网络传输已验收。

## 审查收尾

最终审查问题已修复：显式暂停、待送达消息取消及命令回执原子提交；任务组等待已接受的补充结束再汇总；补充使用持久递增序号调度。回退任务和匿名任务保留顺序屏障，较新的原生追加不能越过旧补充。旧数据迁移同时遵守经验证的聊天先后关系、既有正序号链及回退父任务因果关系，时间仅用于在满足这些约束的记录间排序。故障触发用例先复现失败，再验证通过。最终定向 96 项通过，完整 Agent 库 483 项通过、8 项跳过；限定范围的最终独立复核已通过。没有把不同阶段结果相加冒充一次最终工作区全套运行。

旧版排队记录保持经过核对的聊天顺序，并按时间合并缺失聊天证据的记录；若缺失证据、相同时间戳或时钟回退导致历史位置不明确，无法还原准确的旧提交顺序，迁移仅保证确定且持久的顺序。新提交使用单调序号。

Android 已修复自动准备时的旧连接响应、历史分页边界、Markdown 第一行裁切以及图片重试测试的同步问题。MuMu 硬件渲染发现标题过于通透，标题改用现有 dense 材质，保留真实背景模糊与折射。最终全套覆盖链接、归属、标题、返回阅读位置和纯色背景源修改：1,042 项全部通过，XML 保存在 `.local/android-post-render-suite-results-20261112/`。lint 无错误，仍有上述警告和提示。

## 本次链接、归属与图片反馈

用户明确截图来自 MuMu Pocket，失败图片是其发到聊天里的截图。链接标题此前嵌套成固定占位，与后续正文基线不一致；现在仅图标占位，标题与正文共用文本布局。归属提示此前把不确定归属和多个在线拥有者都说成“多个运行端”；现在区分两种证据，旧助手使用中性文案，并可重新核验。只有在线、同一配置库、相同模式的明确发送授权，才能清除同一运行端残留的本地冲突。

当时电脑助手和桥接实际仍是 **0.6.3**，MuMu 为候选 APK。五张原始截图都存在；相同原生记录在未改动的 0.6.3 图片处理模块回放中全部失败，候选模块全部读取成功。MuMu 的五个缓存引用也均为旧版 `recoverable`、零字节、无 MIME；对应当前会话缓存 `conflict=true`，仅一个 runtime。只读诊断没有启动 Activity 或 repository，数据库/WAL 内容未变；SHM 变化单独记录，不能当作 SQL 内容写入。安装新版 APK 无法替换正在运行的旧电脑助手，因此这项修复需要电脑端一起更新。

旧电脑端诊断同时报告 `assistant_restore_failed`，原生状态为 `initialized:false`，尽管历史只读方法可以返回记录。未通过清除归属或放宽写入保护规避此状态；本轮没有替换正在运行的 Windows 安装，因此安装后的准备恢复、归属授权与真实图片传输仍需验证，不能由单元测试或界面夹具推断已经恢复。

## Windows PowerShell 弹窗

用户报告火绒“系统加固”持续提示 `pocket-desktop.exe` 启动 PowerShell。核对发现已安装的 Pocket 从 12:07 运行，程序文件为前一天版本，本轮尚未替换它。火绒版本文件和程序目录在 12:28 更新，服务随后重启；这只是拦截行为变化的时间线索，未取得具体规则变更证据。

源码中自启状态和安装修复能力检查各有 60 秒缓存，过期后后台再次调用 PowerShell，具备重复触发提示的条件。候选代码已消除这些自动脚本调用：自启快照失效后报告未知，只在点击“核验登录任务”时检查；自动安装器完整性检查改用 Rust 哈希与受保护的文件读取，昂贵能力探测保留给明确的修复/核对请求。后台轮询与诊断导出不构造这两类脚本命令。

未知修复的再次核对保持原 operation ID，文案不再错误断言之前的修复从未执行。手动核验、启停登录任务、修复和未知结果核对仍可调用 PowerShell，不能承诺安全软件永不提示。未修改安全软件设置，未为程序添加信任规则。桌面 Rust 初次真实 PowerShell 夹具超时随后已在串行、并行及完整桌面检查中通过；未证明历史超时由火绒造成。当前已安装 Windows 应用尚未替换，因此仍可能按旧逻辑弹窗。

## 模拟器与交付

用户已明确允许本轮使用 MuMu。最终 `final-isolated-v5` 的六项独立进程 UI 夹具全部通过：准备重试与草稿、已答问答折叠展开、任务组/成员/子代理与返回恢复、明暗主题/大字体/动效降级、会话链接与受控图片重试、系统键盘避让。另对历史问答做了三次独立进程复验，均通过。检查了硬件截图，未创建真实模型轮次，未卸载或清除已安装应用的数据。

早期设备测试暴露了两个产品缺陷。第一，返回详情时临时为零的顶栏/输入栏占位会钳制列表偏移，现与阅读状态一同保存其尺寸。第二，滚动到新增历史问答会触发 RenderNode 递归崩溃；将原本仅为纯色的背景源改为直接绘制颜色后，同一路径通过。页面与聊天内容仍作为真实玻璃采样源，模糊、折射和布局保留。源码调查支持图层重录导致回采的假设，但没有节点身份追踪，不能把具体库内部路径当作已观测事实。

MuMu 自带的 `nemu-vinput` 输入法报告已显示却只有零高度窗口，因此不能以该标志验收。测试 APK 内的专用 Android InputMethodService 提供真实系统键盘窗口，最终实际高度 735px、顶部 1,665px，输入框底部 1,597px。截图确认无遮挡；测试后原默认输入法、启用列表及子类型设置全部恢复。此证据覆盖系统 inset 处理，不代表所有第三方输入法或物理手机均已验收。测试服务不会进入交付 APK。

候选输出目录：`D:/codex2/dist/codex-pocket-0.6.6-assistant-candidate-20261112`。构建助手已重建当前 Agent、Bridge、Runtime Host、桌面与 Android 应用；仅复用哈希已验证且源码未改动的原生依赖。APK 保持原签名身份，交付文件与最终设备验收文件哈希一致。Windows ZIP、安装包及负载清单已生成校验；尚未安装或部署 Windows 候选程序。

| 文件 | SHA-256 |
| --- | --- |
| `codex-pocket-0.6.6.apk` | `80eae100d35425861a60e83b5809974412a61c1864aedab2b210284b7cf96e2f` |
| `codex-pocket-0.6.6-windows.zip` | `3a7efba3c8b3f02cd7e2d9b480651282ae5ee5cbf5a5cf2f807590f7a6b69a5e` |
| `codex-pocket-0.6.6-windows-setup.exe` | `06f5e74783ebc571f5dd2af5276bdaa3e74125a0cdcb6242cb369d59f94131d0` |

APK 签名证书 SHA-256：`2d53a8ae9d77804fdcb1174cfcd81548ea97ae6a664ce6f1407fd6c1f5a57a8a`。APK、Windows ZIP 和完整负载位于交付目录的 `release/`，清单为 `release/release-manifest.json`；安装包及说明文件位于交付目录根部。该结构保持发布清单的严格文件集合校验，全部交付文件校验见 `SHA256SUMS.txt`。本文记录 2026 年 11 月 12 日各阶段的验收范围；后续源码提交不扩大这些结论。
