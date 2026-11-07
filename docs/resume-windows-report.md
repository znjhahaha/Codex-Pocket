# Windows 交付与验收说明

此文档保留原文件名，以兼容 Windows 交付清单。生产机器标识、绝对路径、配置指纹和原始探针输出不随公开源码发布。

## 交付结构

- `pocket-launcher.exe`：稳定入口，核验安装代次并发现当前原版桌面运行时。
- `pocket-supervisor.exe`：管理助手进程、连接意图、心跳和恢复。
- `pocket-agent.exe`：认证配对设备、发现运行时、转发原生操作和受限文件读取。
- `pocket-bridge.exe`：接入原版桌面 stdio，隔离请求和审批。
- `pocket-desktop.exe`：连接、配对、设备和诊断界面。

安装记录、配对身份和运行状态需指向实际的当前用户目录。MSIX 隔离环境可能重定向路径；安装与诊断应使用独立的 Windows PowerShell 上下文，并核验物理路径。

## 0.4 已有证据

既有候选完成 Windows Release 构建和前端 14 项 domain 测试。原签名 Android 覆盖安装后保留配对，端到端认证及基础同步通过。

2026-11-06 部署中转背压修复后，MuMu Android 15 在八个桌面会话间切换二十次，停留一百二十秒，没有传输断开，也没有发送模型测试请求。这只证明该设备与桌面历史路径的既有验收结果。

## 仍需单独验证

完整 CLI 历史、原生生成与控制、真实失败会话隔离、休眠和网络变化、真机性能、HMS 及二十四小时稳定性需要独立结果。不能用界面在线、构建通过或短时健康检查替代。

安装与升级步骤见 [windows-user-guide.md](windows-user-guide.md)，详细安装契约见 [V02-WINDOWS-INSTALL.md](V02-WINDOWS-INSTALL.md)。
