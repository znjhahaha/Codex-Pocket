# DataChannel 原型（尚未接入手机连接）

此目录验证选定依赖可在本项目工具链构建并运行，不是产品 P2P 完成标记。

当前版本锁在 `dependencies.lock.json`，包含每个子模块的提交及工作区源文件摘要。通过上游源码实际核对 C API：可靠有序模式、消息大小、发送缓存；关闭音视频和 WebSocket 功能。MbedTLS 选择 Apache-2.0 许可；其他依赖许可文本保存在 `licenses/`。原始源码保留在 `.local/p2p/`，不改动上游文件。

首次取回（不接触生产中转）：

```powershell
git clone --branch v0.24.6 --recurse-submodules https://github.com/paullouisageneau/libdatachannel.git .local/p2p/libdatachannel
git clone --branch mbedtls-3.6.7 --recurse-submodules https://github.com/Mbed-TLS/mbedtls.git .local/p2p/mbedtls
pwsh -NoProfile -File scripts/build-p2p-fixture.ps1
pwsh -NoProfile -File scripts/build-p2p-fixture.ps1 -Platform android-arm64
```

构建脚本核验提交和源文件未修改；标签若被移动会拒绝构建。Windows 使用 MSVC x64，Android 使用 NDK 28.2.13676358、API 26、arm64-v8a；Android 仅交叉编译。

2026-11-10 单次 Windows loopback 实测：连接 95 ms，各方向 1 MiB，传输 992 ms，校验所有字节的顺序。每块至多 16 KiB 且不超过协商上限，样本最多一个在途块。观察到的发送缓存为 0，不能据此推断移动网络背压表现。Windows DLL 2,002,432 字节；Android 未 strip 的 so 32,743,312 字节，不能作为 APK 增量。

尚未实现：产品级 JNI/Rust 适配、Pocket TLS 字节流、受认证信令、网络代次、P2P 自动选路、真实 NAT 与移动网络验收。原型使用进程内信令，无 STUN、TURN 或云资源。不能把该 loopback 成绩当作手机端到端连接、互联网穿透成功率或服务容量。
