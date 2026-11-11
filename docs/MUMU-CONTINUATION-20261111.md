# 2026-11-11 MuMu 会话功能验收

用户重新授权 MuMu 后，在 Android 15 实例 `127.0.0.1:16416` 上覆盖安装最终签名候选 `0.6.3 / versionCode 13`。安装方式为保留数据的 `adb install -r`，没有卸载或清除数据。

安装后设备 `base.apk` SHA256 为 `1d8f09e44397dc4401bb81fb3972a99c10a739b196ea8bede8578245c6e4bf78`，与 `dist/codex-pocket-0.6.3-20261111-delivery/codex-pocket-0.6.3.apk` 一致。Android 测试 APK 另外构建并使用既有签名，未替换候选产品 APK。

## 设备结果

`ContinuationDeviceTest` 在设备上使用实际 release UI、Repository 和内存数据库，传输层提供固定响应并拒绝真实连接。以下六项通过；首发通过实际 `composer-send` 按钮点击触发。

| 检查 | 实际结果 |
| --- | --- |
| 离线、空会话和子代理复制链接 | 剪贴板原文等于 `codex://threads/<原生 UUID>` |
| Markdown 链接显示 | 自定义标题保留，裸链接显示“打开会话”和会话图标；代码保持原文 |
| 创建并重新打开空会话 | 无模型轮次，历史状态 Ready；从列表点击后显示正常空会话和输入框 |
| 普通历史读取失败 | 保持 Failed，不冒充空会话；草稿保留 |
| 助理消息中的当前会话链接 | 点击后关闭助理页并进入会话，保留草稿 |
| 首次手机发送 | 没有先前轮次或桌面预热消息时，点击发送只派发一次 `turn/start`，接受后清空草稿 |

另外使用已保存的真实配对执行 `LiveConnectionTest`，实际加密传输、设备认证和同步均成功，发现 1 个原生运行时。此探针不创建、恢复或发送原生轮次。

`StoredStatePreservationDeviceTest` 在升级前、升级后及最终隔离测试后比较 `encrypted_pair.xml`、`pocket.db`、`pocket.db-wal` 的字节摘要，三项一致。测试没有打开真实聊天数据库或解密配对内容；真实连接探针只在内存中使用现有配对凭据。

## 证据和复现

- 设备测试：`android/app/src/androidTest/java/dev/codexpocket/app/ContinuationDeviceTest.kt`。
- 构建：`:app:assembleReleaseAndroidTest -PpocketTestBuildType=release` 成功。
- 运行：`am instrument -w -r -e class dev.codexpocket.app.ContinuationDeviceTest -e continuationDevice true dev.codexpocket.app.test/androidx.test.runner.AndroidJUnitRunner`，`OK (1 test)`，包含上述 6 项检查。
- 本地日志和机器可读结果：`.local/mumu-continuation-20261111/` 下的 `device-final.log`、`preservation-final.log`、`connection-report.json` 和 `proof-final/result.json`。
- 视觉核对使用实际 Activity 窗口的 PixelCopy，图片为 `proof-final/copy-and-render.png` 和 `proof-final/empty-conversation.png`。MuMu 的默认显示器截图会捕获启动器，因此早期全屏截图未用于验收。

## 范围

设备上的首发与空历史回归使用固定传输响应，证明 Android 按钮、派发、导航和状态处理。它不等同于真实 Windows 开机后到模型回复的整链路验证。本轮没有新模型生成、真机或外部 NAT 验收。

## 最终 Windows 二进制的隔离链路

直接运行最终候选中的 agent 和 bridge，结合固定 native 及隔离的真实 native 零模型创建/读取，目标链路 77 项断言通过、0 失败。这些是进程、协议及隔离断言，不能当作 77 个独立场景或与既有全量测试相加。

- 覆盖握手先完成和 schema 先完成两种顺序。初始化未完成时，真实 helper 拒绝手机提前创建；就绪后主动推送能力，不需要桌面业务消息。
- 首次创建使用 legacy，空会话经过同线程元数据核对后返回 `pocketHistoryState=awaiting_first_message`。普通历史错误保持错误。
- 固定 native 的首发及相同 commandId 重放只产生一次实际派发。真实 native 只做握手、创建与读取，没有真实模型轮次。
- 单独绕过 helper 直连 bridge 的附加强化预期有 1 项失败：bridge 在 initialized 通知前仍会把 thread/start 转发给 native，由 native 拒绝。真实手机经过 helper 的同一时序门禁已通过，此项保留为独立边界，不计作目标链路通过。
- 曾有一次测试 TLS 客户端在 bridge 启动前配对超时，独立重跑通过，原因尚未确定；不能据此声明长期稳定性已验收。

详细证据：`.local/continuation-native-e2e-20261111/REPORT.md` 和 `summary.json`。自建进程、runtime descriptor 和临时凭据已清理。

## Windows 安装生效仍待完成

重启后的 Windows 仍加载旧 `c1e02a…` 代 Pocket bridge/helper；重启本身未安装最终修复包。final 包 26 项清单检查通过，目标代为 `c9d7fccdb667d38179899f23af6c4f9c5081f053616aca231a0f7078a5526a5e`。

当前 Codex 打包进程存在混合文件重定向：安装记录来自普通 LocalAppData，launcher、身份、配置和部分快捷方式来自包的 LocalCache。安装器的真实句柄路径检查因此停止；外部 WMI 启动也遇到“拒绝访问”，未进入安装脚本。没有绕过原始保护。

已准备 `.local/windows-install-followup-20261111/install-final-safe.ps1`，需要用户从 Windows 开始菜单打开的独立 PowerShell 执行 `-Apply`。脚本固定 final manifest、保留原安装器保护、核对既有身份和配置，不启动或终止现有 Pocket/Codex 进程。安装成功也需要在正常生命周期切换后另行核对 bridge/helper/supervisor，不把单独重启 Codex 当成全部组件更新。

Windows 诊断的 14 项文件哈希不变，18 个现有进程 PID/创建时间保持不变。主控早期快捷方式恢复尝试在安装预检停止后已经撤回；最终未切换安装记录。详细证据：`.local/windows-install-followup-20261111/REPORT.md`、`verification.json`。

## 13:32 独立 Windows 安装更新

后续验收提交 `dbc7b253c425ea61badcc2670d7e321c5c9d3d9c` 的 CI 全部通过。

用户从独立 PowerShell 执行已复核安全包装后，final 核心已安装并通过物理路径校验，generation 为 `c9d7fccdb667d38179899f23af6c4f9c5081f053616aca231a0f7078a5526a5e`。可选集成 pending 为空，身份、配置、配对哈希前后保持一致，既有进程保持。未迁移状态或重新配对。

当前逐项证据显示 root/cache 证书、computerId 和完整手机连接指纹一致，差异为配置中的 relayUrl、runtimeDirectories、routePreferences。此前合并报错没有记录具体差异；新版移除了历史配置整文件哈希这一过宽门槛，保留当前身份、私钥和 helper 物理归属检查。

正在运行的旧 helper/bridge/supervisor 尚未切换；要在正常生命周期重启后单独核验。此结果不等于真实冷开机首发验收。原始证据：`.local/windows-install-followup-20261111/run-20261111-133244-652e17f5/result.json`。
