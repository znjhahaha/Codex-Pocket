# 构建与开发

以下说明用于开发人员；日常使用从 Setup.exe 安装。

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
.\scripts\build-release.ps1 -OutputDirectory .\dist\codex-pocket-0.6.0
.\scripts\windows-verify-release.ps1 -ReleaseDirectory .\dist\codex-pocket-0.6.0
```

输出包括签名 APK、Windows ZIP、Windows 文件目录与 SHA-256 清单。每次选择一个不存在的输出目录，旧交付会保留。源码快照可用 `scripts/package-source.ps1 -OutputDirectory <新目录>` 单独生成。

### Linux SSH 运行环境

手机通过 SSH 向 Linux 主机安装的后台（`pocket-agent` 与 `pocket-runtime-host`，静态 musl）不随仓库提交。Android 构建会把 `.local/execution-runtime/assets/linux-runtime/<架构>/` 打进 APK；该目录不存在时构建照常完成，应用在添加 SSH 主机时提示安装包不含此架构的运行环境。

```powershell
rustup target add x86_64-unknown-linux-musl
cargo install cargo-zigbuild          # 另需 zig（PATH 或 CARGO_ZIGBUILD_ZIG_PATH）与带 cryptography 的 Python
.\scripts\linux\build-runtime.ps1 -Architecture x86_64 -SigningKey <已有的 Ed25519 更新签名私钥路径>
```

脚本交叉编译、用现有签名私钥签署清单，并只在签名成功后替换上述目录；私钥与应用内固定的公钥不符时不会产生任何包，也不会生成或替换密钥。x86_64 的两个文件约 24 MB，调试版 APK 实测增大约 11 MB；`aarch64` 需要对应的 Rust 目标。

SSH 相关的真实主机测试默认跳过，只针对一次性的本机虚拟机运行：

```powershell
$env:POCKET_LINUX_QA = '<含 known_hosts 与 test-key 的夹具目录>'
$env:POCKET_LINUX_BIN = '<交叉编译输出目录>'            # 由测试用仅存在于本次进程的密钥签名
# 或 $env:POCKET_LINUX_BUNDLE = '<正式签名的包目录>'   # 首个用例按应用内固定公钥校验
Set-Location android
.\gradlew.bat :wire:cleanTest :wire:test --tests "dev.codexpocket.wire.SshLinux*" --no-daemon
```

夹具主机需要：用户 `pocket`（密钥登录、免密 sudo）、其 `~/.local/bin/codex` 指向已安装的 Codex CLI；测试自行创建无 sudo 的 `pocketqa2`、为 `pocket` 开启 linger，并切断虚拟机的默认路由。`POCKET_LINUX_QA_PORT` 可改 SSH 转发端口（默认 22247）；虚拟机管理器不能复位客户机时，用 `POCKET_LINUX_QA_RESTART` 指定在客户机关机后重新启动它的命令。

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
