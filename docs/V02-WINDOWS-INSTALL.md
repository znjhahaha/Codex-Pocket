# Windows 安装与回滚契约

文件名保留用于交付兼容，本文描述当前通用安装行为，不包含个人机器的迁移记录。

## 安装目录与身份

安装器校验完整 Windows 文件清单，再将二进制放入安装根目录下的版本目录。稳定启动器引用已记录的代次。配置、配对身份、草稿与原生会话状态独立于交付目录；升级不替换已有身份。

安装状态默认位于当前用户的 `%LOCALAPPDATA%/CodexPocket`。安装器核验物理路径和重定向情况；应在独立 Windows PowerShell 中预览和执行安装。

```powershell
.\windows-install.ps1 -SourceDirectory . -StateDirectory 'C:\Private\CodexPocket\agent'
.\windows-install.ps1 -SourceDirectory . -StateDirectory 'C:\Private\CodexPocket\agent' -Apply
```

示例状态路径需要替换为已初始化的实际目录。明确迁移已有状态时使用 `-ExistingStateDirectory`；目标须符合安装器的路径和权限检查。

## 启动

稳定启动器承接用户级 `CODEX_CLI_PATH`，保留普通参数并发现当前原版 Codex 桌面运行时。监督进程管理自己启动的助手，远程连接意图由界面保存。关闭界面与停止远程连接是不同操作。

```powershell
.\windows-startup.ps1 -Action Status
.\windows-startup.ps1 -Action Enable
.\windows-startup.ps1 -Action Disable
.\windows-startup.ps1 -Action Start
```

上述入口读取已核验的安装记录。启停登录触发不等于终止电脑正在运行的原生任务。

## 恢复

```powershell
.\windows-install.ps1 -Rollback
.\windows-install.ps1 -Rollback -Apply
.\windows-install.ps1 -Uninstall
.\windows-install.ps1 -Uninstall -Apply
```

安装器按记录恢复环境覆盖、快捷方式和任务定义，保留历史代次及配对状态。发现外部改动、锁定文件或不完整事务时报告冲突；不删除记录后强行覆盖。

## 构建与测试

`scripts/build-release.ps1` 从源码构建助手、桥、桌面前端与监督进程，验证已有 APK 签名身份，再生成新的交付目录和 SHA-256 清单。输出目录存在时停止，保留旧交付。

本地安装器夹具使用临时目录和替代的系统边界，覆盖预览、安装、迁移、回滚和冲突恢复。夹具结果不能替代真实登录、睡眠恢复或手机使用验收。

日常操作见 [windows-user-guide.md](windows-user-guide.md)。
