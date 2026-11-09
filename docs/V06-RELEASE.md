# 0.6 构建与发布

## 日常检查

`.github/workflows/checks.yml` 对 PR、main 和 codex/* 分支执行 Windows Rust、桌面前端、浏览器 UI、安装夹具、Android 主机测试／lint／Debug 构建，以及 Linux 中转镜像构建。PR 不读取签名凭据。

本地检查：

```powershell
cargo test --workspace --locked
cargo test --manifest-path desktop/Cargo.toml -p pocket-desktop-runtime --locked
npm --prefix desktop ci
npm --prefix desktop test
npm --prefix desktop run build
npm --prefix desktop run test:ui
./android/gradlew.bat -p android :wire:test :app:testDebugUnitTest :app:lintDebug --no-daemon
```

CI 不包含模拟器或实际模型请求。用户在 2026-11-09 重新授权的本地 MuMu 验证另见验收记录，不与主机测试混用。

## 原签名与凭据

GitHub `release` 环境需要 `POCKET_SIGNING_KEY_BASE64` 和 `POCKET_SIGNING_PASSWORD` 两个 Secret；仓库变量 `POCKET_EXPECTED_SIGNER` 保存公开证书的 SHA-256。原始 keystore 和密码不提交，不生成替代签名。

签名步骤仅运行在版本标签构建，不用于外部 PR。Secret 通过临时文件／环境变量传递，构建结束清理已知临时文件。构建后的 APK 再用 apksigner 核对原签名。

Cloudflare 和 SSH 的用户凭据不属于 CI。发布工作流不会登录用户服务器，也不会更新用户的中转配置。

## 构建产物

```powershell
./scripts/build-release.ps1 -OutputDirectory D:/delivery/new-release
./scripts/build-windows-installer.ps1 -ReleaseDirectory D:/delivery/new-release -OutputDirectory D:/delivery/new-release
./scripts/package-source.ps1 -OutputDirectory D:/delivery/new-source
```

输出目录必须全新，旧交付不覆盖。Windows 包含助手、桥接、UI、监督进程、稳定启动器、安装脚本和固定版本 cloudflared。cloudflared 下载前核对锁文件，启动前再次核对 SHA-256。

NSIS 将已校验的 `windows/` 文件打入当前用户安装器，不另外重编另一套 Windows 文件。安装器暂未做 Authenticode 签名。源码包排除 `.local`、缓存、日志、签名文件和配对配置。

## 草稿与正式发布

1. 核验版本号、原签名、本地测试和验收边界，提交并推送代码。
2. 创建与源码版本一致的版本标签。标签触发 `release.yml`；手动启动也必须选择版本标签。
3. CI 构建 APK、Setup、Windows ZIP、源码、哈希与中转镜像归档。全部成功后只生成 **GitHub Release 草稿**。
4. 人工核对草稿后正式发布。`publish-relay.yml` 读取已审阅的镜像归档，校验哈希再发布版本镜像；只有非预发布版本更新 `stable` 标签。

草稿中转镜像尚未公开到 GHCR 时，图形 SSH 部署对应新版本会明确失败；可先导出部署包，待版本正式发布后部署。不要把未发布镜像或健康检查成功写成手机远控验收通过。
