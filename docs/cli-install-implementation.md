# CLI 接入与恢复

此文档保留原文件名，以兼容 Windows 交付清单。个人电脑的历史安装记录和探针日志不随源码发布。

## 安装

在完整 Windows 交付目录中，使用 PowerShell 7 先预览，再应用：

```powershell
pwsh -NoProfile -File .\cli-install.ps1
pwsh -NoProfile -File .\cli-install.ps1 -Apply
```

CLI 安装器仅接受它能够识别和核验的 npm 启动脚本。它备份原入口并安装可恢复的包装器；包装器复用原生 CLI daemon 的本地传输。模型配置和凭据继续由电脑上的原生 CLI 管理。

原生 CLI 升级可能改变启动脚本或协议。校验拒绝时应检查当前版本的兼容性，不要绕过入口校验。安装操作不等于实际远控验收。

## 恢复

```powershell
pwsh -NoProfile -File .\cli-install.ps1 -Uninstall
pwsh -NoProfile -File .\cli-install.ps1 -Uninstall -Apply
```

卸载恢复备份的入口，不自动停止现有 daemon。若入口已被其他软件修改，安装器会保留现场并报告冲突。

完整电脑安装说明见 [windows-user-guide.md](windows-user-guide.md)，验收范围见 [cli-native-probe-result.md](cli-native-probe-result.md)。
