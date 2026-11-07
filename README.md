<p align="center">
  <img src="design/brand/app-icon.png" width="88" alt="Codex Pocket 图标" />
</p>

# Codex Pocket

在 Android 手机上继续电脑中的 Codex 会话。项目由原生 Android 客户端、Windows 连接助手和可自行部署的中转服务组成，保留原生会话 ID、工作目录、模型配置和执行环境。

手机负责阅读和操作，任务仍在自己的电脑上执行。模型 API 凭据留在电脑，中转只转发端到端加密的数据。Codex Pocket 是独立社区项目，与 OpenAI 官方产品没有隶属关系。

**当前源码版本：0.5.2 / Android versionCode 7。** APK、Windows 包、源码归档和 SHA-256 清单在 [GitHub Releases](https://github.com/znjhahaha/Codex-Pocket/releases) 下载；安装包与本机配置不纳入 Git 历史。0.5.2 暂以预发布提供，最终阅读修复的设备验收尚未完成。

## 功能

- **会话工作台**：按项目浏览桌面端和 CLI 会话，支持搜索、置顶、重命名、归档与本地草稿。
- **任务阅读**：成功完成的任务突出最终回答，中间说明、工具和插话收拢为可展开过程；失败、停止和待处理问题继续可见。原生摘要按段展示，未知耗时不计数。
- **原生远控**：续聊、停止、队列、Steer、审批与问题处理。可用操作取决于当前原生运行时公布的能力。
- **紧凑聊天控件**：浏览历史时输入区缩为独立的草稿预览胶囊，点击恢复多行编辑；强度滑轨仅显示模型支持的 low～max 档位。问题卡片逐题作答，完成后统一提交。
- **运行过程**：原生摘要直接以灰色正文展示，点击原位展开各段；计时移至标题栏。机器人图标和智能体数量用于协作行，完整命令与消息保留在展开详情中。
- **对话缩略导航**：左侧静止时保留细刻度，点击展开缩略图，拖动玻璃滑块定位已加载内容；操作结束后自动收起，分页及过程折叠按原生条目定位。
- **计划确认**：执行步骤与正式方案分别展示。实施方案独立提交并保留普通草稿、引用和附件；修改意见进入输入区，旧方案和结果未知的提交不能重复执行。
- **会话权限与上下文**：盾牌菜单选择当前会话后续回合的原生权限；上下文支持数值事件缓存及电脑本地历史回读。不会修改电脑全局配置，未知写入结果先核对。
- **输入建议**：在输入框键入 `/` 浏览可用命令和技能，键入 `@` 搜索经过工作区与原生目录验证的文件、技能和插件引用；已确认引用显示蓝色图标与名称，发送时保留真实身份，删除或改写后撤销关联。选择候选只编辑草稿，不发送消息。
- **设置同步**：按原生会话同步模型、思考强度、Plan 和 Fast，保留尚未发送的手机选择；未同步与原生默认分别处理，不将模型目录默认项冒充当前选择。
- **原位会话菜单**：重命名、目标和分支参数在菜单内编辑，上下文指示独立于累计消耗。额度区支持原生账户，以及电脑上 CC Switch 已配置的只读额度查询；凭据和脚本留在电脑。
- **子代理协作**：已确认子代理从首页隐藏，在主任务的居中面板内只读查看，可放大全屏并缩回；主任务草稿和阅读位置始终保留，普通分支不被隐藏。
- **中途接入**：主会话和子代理保留当前回合的原生摘要，工具执行不会清掉摘要；子代理运行状态结合已验证快照与原生事件，不把空闲状态当作完成。长工具过程按可见行加载。
- **项目阅读与审阅**：工作区内只读文件浏览、源码高亮、行号与搜索、Markdown 预览。主会话与子代理共用内联文件差异，固定行号、横向滚动，放大和缩回保留位置；主会话支持将行内评论加入草稿。
- **玻璃交互**：独立浮动导航和输入区、锚定菜单、浅深主题、按压回弹与状态反馈。
- **配对和恢复**：扫码、相册识码与手动配对，独立的电脑／运行时／会话状态，断线保留缓存和草稿。

## 工作方式

```mermaid
flowchart LR
    Phone[Android 客户端] <-->|WSS 内承载端到端 TLS| Relay[自行部署的密文中转]
    Relay <-->|转发加密字节| Agent[Windows 连接助手]
    Agent <-->|本地连接| Desktop[Codex 桌面端]
    Agent <-->|本地连接| CLI[Codex CLI]
```

电脑需要保持开机，手机和电脑均需能访问中转。手机通过配对使用电脑现有的原生配置，包括已配置的第三方服务；不需要在手机重复填写模型凭据。中转的 HTTPS 健康检查仅代表服务在运行，实际在线状态还取决于助手心跳和认证连接。

结果未知的写操作会保留待核对状态。先检查原生历史及电脑执行结果，再处理后续请求，应用不会自动重发。

## 开始使用

**另一台新电脑首次接入需要初始化中转配置和安装脚本，当前不是双击单个 EXE 即完成安装。** 每台电脑使用独立身份；公开下载包不附带共享服务凭据。已安装并配对的电脑，日常打开 Codex Pocket 快捷方式即可。完整步骤见 [Windows 使用说明](docs/windows-user-guide.md)。

1. 按下方步骤构建 Android APK 和 Windows 包。
2. 按 [中转部署说明](deploy/README.md) 部署自己的服务，并为首次安装初始化 Windows 私有状态。已有安装升级时保留原状态与签名。
3. 按 [Windows 使用说明](docs/windows-user-guide.md) 校验交付清单，预览并安装连接助手；CLI 接入有单独的安装入口。
4. 在电脑的 **Codex Pocket** 中启用连接，生成配对二维码，在手机中扫描。
5. 等电脑当前任务结束后重新打开已接入的 Codex 桌面或 CLI，手机即可选择对应项目和会话。

如果授权曾被主动撤销，需要生成新二维码重新配对。不要从历史备份恢复已撤销的授权。

升级 0.5.2 时需要同时更新 Windows 助手。已运行的 Codex 仍可能使用旧桥接，手机会显示“电脑桥接待更新”；结束当前工作后重新打开对应 Codex 才能启用新桥接。复制新文件不会自动替换正在运行的原生进程。本轮验证范围见 [0.5.2 验收记录](docs/V052-ACCEPTANCE.md)。最后一批阅读修复仅完成本地验证，按要求暂停 MuMu 测试，设备流畅度和视觉结果仍待验收。

## 从源码构建

主要开发环境是 Windows x64。需要 Git、Rust MSVC 工具链、Windows C++ 构建工具、Node.js 22+、JDK 21、Android SDK 36 和 PowerShell 7。Android 最低支持 API 28。Gradle wrapper、Cargo.lock 和 npm lockfile 均已提交。

```powershell
git clone https://github.com/znjhahaha/Codex-Pocket.git
Set-Location Codex-Pocket
```

### Android 开发版

设置 `JAVA_HOME`，并通过 `ANDROID_HOME` 或本地 `android/local.properties` 指定 Android SDK。

```powershell
Set-Location android
.\gradlew.bat :app:assembleDebug --no-daemon
Set-Location ..
```

输出为 `android/app/build/outputs/apk/debug/app-debug.apk`。开发版使用调试签名，与正式版签名不同；覆盖正式安装时应使用原签名构建。

### Windows 与前端

```powershell
cargo build --locked -p pocket-agent -p pocket-bridge
Set-Location desktop
npm ci
npm run build
npm run tauri -- dev
Set-Location ..
```

`desktop` 是独立 Rust workspace。启动开发界面不等于完成助手安装，实际接入按 Windows 使用说明进行。

### 完整签名交付

在 Git 忽略的 `.local/signing/` 下准备自己的 `release.jks` 和 `password.txt`，密钥别名为 `codex-pocket`。脚本读取已有身份，不自动生成或替换签名。后续升级须使用同一把密钥。

```powershell
.\scripts\build-release.ps1 -OutputDirectory .\dist\codex-pocket-0.5.2
.\scripts\windows-verify-release.ps1 -ReleaseDirectory .\dist\codex-pocket-0.5.2
```

输出包括签名 APK、Windows ZIP、Windows 文件目录与 SHA-256 清单。每次选择一个不存在的输出目录，旧交付会保留。源码快照可用 `scripts/package-source.ps1 -OutputDirectory <新目录>` 单独生成。

## 验证

以下命令运行本地测试；真实设备测试需要单独显式启用。不要把真实模型调用作为普通构建检查。

```powershell
cargo test --workspace --locked
node --test scripts/test-bridge.mjs scripts/cli-wrapper-tests.mjs
pwsh -NoProfile -File scripts/windows-installer-tests.ps1
Set-Location desktop
npm ci
npm test
npm run build
Set-Location ../android
.\gradlew.bat :wire:test :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:lintDebug --no-daemon
Set-Location ..
```

0.5 最终签名包在 MuMu Android 15 完成 20 次真实桌面会话切换与 120 秒停留，所有历史读取成功、断线 0 次、模型请求 0 次，最长读取 1,020 条记录。浅深主题、大字体、强度滑轨、逐题作答和输入建议已有设备证据；真实额度未取得有效余额，模拟器性能指标尚未达标。该结果不替代真机、CLI、原生生成控制或长期稳定性验证。具体范围见 [当前状态](docs/STATUS.md)。

## 目录

| 目录 | 内容 |
| --- | --- |
| `android/app` | Compose 界面、Room 缓存、草稿、通知与分享 |
| `android/wire` | WSS、端到端 TLS、配对和消息协议 |
| `crates/agent` | Windows 助手、运行时发现、会话与文件访问 |
| `crates/bridge` | Codex 桌面 stdio 桥与双端审批仲裁 |
| `crates/cli-adapter` | 原生 CLI daemon 本地传输 |
| `crates/core`、`crates/tunnel` | 会话归属、命令记录、TLS 与身份存储 |
| `crates/relay`、`deploy` | 密文中转、容器模板与可选推送网关 |
| `desktop` | Tauri 界面、Windows 监督进程与稳定启动器 |
| `scripts` | 构建、可恢复安装和测试脚本 |
| `design` | 自绘图标与品牌资源 |

## 文档与许可

- [Windows 安装、升级与恢复](docs/windows-user-guide.md)
- [中转部署](deploy/README.md)
- [协议与错误处理](docs/WIRE.md)
- [版本状态与验收范围](docs/STATUS.md)
- [第三方许可说明](THIRD_PARTY_NOTICES.md)

项目采用 [MIT License](LICENSE)。本地日志、配对信息、生产服务配置、签名材料和历史任务报告均应保留在 Git 忽略目录中。
