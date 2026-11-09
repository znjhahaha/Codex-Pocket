# 旧版本脚本安装参考

以下保留 0.5 的安装记录。0.6 新用户请使用 Setup.exe。

﻿# Codex Pocket：Windows 使用说明

本交付连接已有的原版 Codex 桌面和 CLI，继续使用电脑现有的 API 配置；手机不需要另填模型凭据。当前源码版本为 0.5.2，已验收范围和待完成项目见源码中的 `docs/STATUS.md`。

## 新电脑与已安装电脑

已经安装并完成配对的电脑，日常从开始菜单打开 **Codex Pocket** 即可；后台助手按当前用户登录自启动。

首次在另一台电脑安装还需要初始化步骤，**不是单个 EXE 的一键安装版**：

1. 准备这台电脑自己的 Codex 桌面或 CLI 及现有模型配置，下载并解压完整 Windows ZIP；不要只复制一个 EXE。
2. 按 [中转部署说明](https://github.com/znjhahaha/Codex-Pocket/blob/main/deploy/README.md) 为这台电脑创建独立状态目录和身份，并让中转配置对应的新房间与凭据。公开安装包不附带可直接使用的共享中转凭据；不能复制另一台正在使用的电脑的配对目录来同时接入。
3. 按下方步骤校验包、预览并执行安装脚本，再打开 Codex Pocket 启用连接。
4. 等原版 Codex 的当前任务结束后，正常退出并重新打开，从手机扫描这台电脑生成的二维码。

当前没有完整的图形化首次初始化向导；EXE 提供已接入后的连接、设备和诊断界面。后续日常使用不需要反复输入安装命令。

## 核对和安装

Windows 交付面向 x64；使用 64 位 PowerShell。需要 Microsoft Visual C++ x64 运行库（`VCRUNTIME140.dll`），安装器会检查是否存在。托盘和桌面安装支持系统自带的 Windows PowerShell 5.1；CLI 安装需要 PowerShell 7。

1. 解压完整交付到固定目录。打开 PowerShell，在交付目录执行：

   ```powershell
   .\windows\windows-verify-release.ps1 -ReleaseDirectory .
   ```

   单独解压 Windows ZIP 时，在解压目录执行 `./windows-verify-release.ps1 -WindowsOnly -ReleaseDirectory .`。SHA-256 清单用于检查文件是否缺失或改变；发行者身份仍需通过可信渠道核对清单。Windows EXE 当前没有声明 Authenticode 签名。

2. 已安装的用户可直接从开始菜单打开 **Codex Pocket**。首次安装先按源码中的中转部署说明初始化私有状态；重装或迁移使用已经初始化、且与中转服务对应的配对目录。先预览，再安装；将下面的路径替换为实际路径：

   ```powershell
   .\windows\windows-install.ps1 -StateDirectory 'C:\实际配对目录'
   .\windows\windows-install.ps1 -StateDirectory 'C:\实际配对目录' -Apply
   ```

   默认目录是 `%LOCALAPPDATA%\CodexPocket\agent`。安装器不会生成或替换设备身份。需要迁移时，由操作人员先正常退出源目录的助手，再加 `-ExistingStateDirectory 'C:\原配对目录'`；目标目录必须不存在。迁移保留身份和历史状态，同时保留 TLS 身份要求的受保护权限。没有初始化目录时，交由部署人员准备与已部署中转一致的配置，不要重新生成密钥。

安装只设置当前用户的桌面启动覆盖及开始菜单/登录自启动快捷方式，不会关闭或启动现有桌面、助手或 CLI。首次接入由操作人员等电脑任务结束后正常退出并重新打开桌面。

## 配对和日常使用

- 从开始菜单打开 **Codex Pocket**，启用远程连接后在设备页选择 **连接新设备**。在 Android 客户端扫描二维码，或复制配对信息后在手机粘贴。配对码有效期五分钟；过期重新生成。不要把二维码或复制内容发送给别人。
- 日常继续打开原版 Codex；需要显式使用当前接入入口时，从托盘选择 **打开 Codex 桌面** 或从开始菜单打开 **Codex Pocket Desktop**。
- 已接入的 CLI 仍使用原来的 `codex`、`codex resume`。CLI 安装器是单独入口，需要 **PowerShell 7**；如果还未安装，由操作人员运行 `pwsh -NoProfile -File .\windows\cli-install.ps1` 预览，加 `-Apply` 执行。它仅接受已核验的 npm 启动脚本版本；不要为绕过拒绝而修改校验。
- 桌面界面可启用或暂停远程连接；关闭窗口隐藏到托盘，退出界面仍保留后台远控。暂停连接由监督进程停止其拥有的助手，不会停止原版桌面或 CLI daemon。电脑需保持开机；在线状态要求监督进程和助手心跳均有效且中转已连接。
- 操作失败时保留阶段、错误码和操作编号。遇到“旧的隔离状态”，从 Windows 开始菜单重新打开；遇到“安装版本已变化”，退出旧界面再打开。诊断可导出已获得的安全状态，部分信息失败不影响其余结果。
- 桌面 App 更新后，先等旧任务结束，再从 **Codex Pocket Desktop** 启动以刷新原版运行时路径。若升级后能力变化，以重新验收结果为准。

## 回滚和升级

1. 由操作人员在任务结束后正常退出托盘助手和已接入桌面。
2. 用原交付的安装器预览和恢复：

   ```powershell
   .\windows\windows-install.ps1 -Uninstall
   .\windows\windows-install.ps1 -Uninstall -Apply
   ```

   如果原交付目录已移动，可从 `%LOCALAPPDATA%\CodexPocket\desktop-install.json` 记录的 `bridge` 所在目录运行 `windows-install.ps1 -Uninstall -Apply`。恢复原 `CODEX_CLI_PATH` 和原快捷方式的准确字节；配对数据、安装记录和版本目录保留。

3. 需要撤销 CLI 入口时，单独执行 `pwsh -NoProfile -File .\windows\cli-install.ps1 -Uninstall` 预览，再加 `-Apply`。此操作不会停止 daemon。重新打开终端或桌面后再使用原入口。

安装器发现外部改动、损坏备份或旧版安装记录时会停止，保留现场供部署人员核对。不要删除记录后强行覆盖。安装中断可用同一版本重试安装或回滚。升级 Windows 交付前先恢复旧版本，再安装新版本；旧版本记录保留用于追溯。

Windows 与 CLI 的验收边界见同目录的 `resume-windows-report.md` 和 `cli-native-probe-result.md`，CLI 接入与恢复说明见 `cli-install-implementation.md`。个人机器的原始探针和部署报告不随公开源码发布。

## 构建人员

从源码根目录执行 `./scripts/build-release.ps1`，默认生成新的 `dist`。输出目录已存在时，用 `-OutputDirectory 'D:\实际交付目录\新版本'` 指定新目录；脚本不清空旧交付。入口始终从当前源码构建 Windows Release 和签名 APK，不使用 debug 或探针二进制。需要已有 `.local/signing/release.jks`、`password.txt`、alias `codex-pocket`、Rust、JDK 和 Android SDK；不会创建签名身份。

交付包含 Windows ZIP、签名 APK、逐文件大小/SHA-256 清单及验签证书指纹。构建脚本完成不代表原生会话或设备验收完成。
