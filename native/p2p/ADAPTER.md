# P2P 第一步：接线与验证报告

2026-11-10，`D:\codex2`。本步完成独立适配层和真实 local two peers 字节流 fixture，尚未接入生产连接。原 `README.md`、`probe.cpp`、`scripts/build-p2p-fixture.ps1` 保持原样；原 README 描述原型基线，新增适配以本报告为准。

## 接口与行为

| 层 | 文件 | 接口 |
|---|---|---|
| 共用 C ABI | `include/pocket_p2p.h`、`src/transport.cpp` | create/start、remote description/candidate、read/write/flush/status/close/destroy、generation 回调、队列统计 |
| Rust | `../../crates/p2p/src/lib.rs` | `DataChannelStream: AsyncRead + AsyncWrite`、可克隆 Control、Description/Candidate Signal |
| JNI | `../../android/wire/src/main/cpp/DataChannelJni.cpp` | 同一 C ABI、attach/detach、GlobalRef 生命周期、字节拷贝、Java 异常隔离 |
| Kotlin | `../../android/wire/src/main/kotlin/dev/codexpocket/wire/DataChannelTransport.kt` | BinaryTransport、start、remoteDescription/Candidate、onWritable、后台 awaitReleased |

- 固定 DCEP label `pocket-tls`、protocol `pocket.tls.bytes.v1`，可靠、有序，无寿命/重传次数限制。接收端核验这些属性，拒绝第二条/不匹配 channel、文本和非法消息。
- 每次发送拆到 `min(16384, rtcMaxMessageSize(channel))`；本地接收上限 16 KiB。读取允许跨消息边界和部分读取，不加业务消息头、不解释 TLS 字节。
- TX payload 准入统计应用待发送字节加可见 RTC buffer：高 1 MiB、低 256 KiB。发送期间前块仍计入预算，转移到 RTC 后再更新；低水位回调加有条件的 10 ms 轮询唤醒。RX payload 上限 1 MiB，溢出报错并关闭，不能丢密文后继续。TX/RX 各限制 1024 项。这不是整个进程、SCTP/DTLS/OS 缓冲或对象开销的内存上限。
- C write 原子接受 0..65536 字节或返回 AGAIN，后者没有接受字节。Rust 大 write 部分接受；Kotlin send 成功前复制 caller 数组，false 无部分成功。Kotlin delivery 线程从原生 RX 拉取，每次至多 16 KiB 临时块，没有第二个接收字节队列。
- 信令/命令队列各限 256 项/1 MiB；每 generation 一个 remote SDP、至多 256 个 remote candidates、累计远端信令至多 1 MiB，避免 RTC 在 SDP 前无限保存 candidate。SDP 最长 64 KiB，candidate 4096，type/mid 256。
- create 不协商，主控先 start 明确 offerer/answerer；信令接口返回 0 只证明入队，执行失败走 ERROR。输出携带创建时的非零 generation，输入必须相等；新代次需新 handle。**相等检查不证明信令可信或当前代次，上层必须先验证认证、设备归属和当前 negotiation generation。**
- C 应用回调在 peer worker 上，必须及时返回，不能跨 ABI 抛异常或同步 destroy。RTC 回调用 ID registry 获得 shared 所有权。destroy 等待回调 worker 退出，并发 destroy 也等待同一次退出；不调用全局 rtcCleanup。误从自身回调 destroy 仅请求关闭，须由另一线程再次释放。
- Rust 在检查原生状态前登记 waker，关闭/错误唤醒 read/write/flush/shutdown/opened；opened 最多 64 个并发等待者，取消清除登记。Stream drop 关闭 transport，即使 Control 仍持有；最后释放若在信令回调中发生，移到另一线程 join 后再释放 callback Box。
- Kotlin listener/onWritable 在 `pocket-dc-delivery`，原生回调只合并状态标记。现有 Pocket listener 的输入等待不会占住原生发送 worker。信令 lambda 在 native worker，须及时送到主控的有界队列。terminal 仅一次；`pocket-dc-release` join 后释放 GlobalRef；awaitReleased 只能后台等待。
- graceful close 停止新写，最多等 5 秒排空本地队列和可见 RTC buffer，超时 ERROR。flush/close 不证明远端收妥或 TLS close_notify 被确认。abort/error/drop 可舍弃未发送字节；干净 remote close 可先读完 RX 再 EOF。

## 主控下一步接线

### Windows Rust

`crates/p2p` 有独立 workspace 和 lock，本步未加入根 Cargo。

1. 单独构建 native；设置 P2P_NATIVE_DIR 到 Release。交付时一起提供 `pocket-p2p.dll`、`datachannel.dll`，为调用进程设置 DLL 搜索目录。
2. `DataChannelStream::new(generation, bind, signal_sink)` 返回 Stream/Control。signal_sink 送到经过认证的主控信令层，主控确定角色后 start；先验证远端信令和当前 generation，再 remote_description/candidate。
3. 等待 opened 后，把 Stream 交给现有 `ServerIdentity::accept_tls<S>`（`crates/tunnel/src/identity.rs`）或既有 pinned TLS client。其泛型界限已是 AsyncRead/AsyncWrite/Unpin；本步只确认接口契合，没有执行 Pocket TLS 握手。
4. 复用现有证书、pin、设备授权和 TLS 后消息处理，不直接在 DataChannel 发 Pocket JSON/令牌/模型请求。取消和代次变化时关闭旧 Control、释放 Stream。

### Android/Kotlin

wire 当前为 Kotlin/JVM 模块，不能直接增加 Android externalNativeBuild。本步 CMake 独立 target 已交叉编译 JNI，Gradle 未接线。

1. 后续 Android app 打包 arm64-v8a 的 `libpocket-p2p-jni.so`、`libpocket-p2p.so`、`libdatachannel.so`。本次 readelf 核对三层 NEEDED，没有 libc++_shared 依赖；若后续统一改用 c++_shared，需同时交付匹配 NDK 的 runtime。本步未复制到 app、修改 Gradle 或生成 APK。
2. R8 后续保留 JNI 类名/方法，例如 `-keep class dev.codexpocket.wire.DataChannelTransport { *; }`，包含 private onNativeEvent 和 native 方法。
3. 主控实现 TransportConnector，在 open(config, listener) 创建 DataChannelTransport、公布主控持有的实例，再按角色 start；将其接入 PocketClient。现有 TlsPump/pinnedEngine(config.certificateSha256) 继续负责 TLS、pin，后续设备认证保持既有流程。
4. PocketClient 当前把 send(false) 转成 BackpressureFailure("transport_send")。本步提供 onWritable，未改其他 wire 文件实现自动重试，所以接入后的高水位仍按既有失败关闭处理。产品排队/重试需要另行完成 TLS pump 接线。
5. 主动 close、后台 awaitReleased；代次变化创建新实例。本步未接自动选路、信令服务、生产路由或设备。

## 构建与验证

依赖来自已锁 `.local/p2p`，脚本核验提交和 tracked 源未变：libdatachannel v0.24.6 `6b1e2e620f1e37f0eafeee702eaea0043cb305fd`、libjuice `b89c792e3612faf2f12cf35bcc56857313a06be3`。USE_NICE=OFF、NO_MEDIA/NO_WEBSOCKET=ON，MbedTLS 静态。曾复现二次 configure 的共享库缓存导致 everest 错建 DLL/链接失败，已在加入 MbedTLS 前显式设 BUILD_SHARED_LIBS=OFF；未改上游。

```powershell
pwsh -NoProfile -File native/p2p/build-adapter.ps1 -BuildDir D:/codex2/native/p2p/.build-step1-win -Jni
pwsh -NoProfile -File native/p2p/build-adapter.ps1 -Platform android-arm64 -BuildDir D:/codex2/native/p2p/.build-step1-android
$env:P2P_NATIVE_DIR='D:/codex2/native/p2p/.build-step1-win/Release'
$env:PATH=$env:P2P_NATIVE_DIR+';'+$env:PATH
cargo test --offline --manifest-path crates/p2p/Cargo.toml --target-dir crates/p2p/target-step1
cargo clippy --offline --manifest-path crates/p2p/Cargo.toml --target-dir crates/p2p/target-step1 --all-targets -- -D warnings
pwsh -NoProfile -File native/p2p/test-kotlin.ps1 -NativeDir D:/codex2/native/p2p/.build-step1-win/Release
```

build-adapter 不指定 BuildDir 时创建随机独立目录。本次没有复用 `.local/p2p/build-*` 或其他构建，Rust 也独立 target-step1。Kotlin 编译器来自缓存，没有调用 Gradle、fetch、模型或公网。全部 fixture bind=127.0.0.1、iceServers 为空，不访问 STUN/TURN 服务；ICE 本地连通性检查仍是 RTC 协议的一部分。

| 证据 | 结果与边界 |
|---|---|
| Windows C ABI two peers | 正向 2,097,885、反向 2,097,423 字节；另有 1,048,577 字节高/低水位恢复；997 字节部分读取逐字节校验 |
| 队列与终止 | peak TX=1,048,576、max message=16,384；RX 溢出失败关闭、过时代次拒绝、EOF、并发 destroy、销毁后 handle 拒绝通过 |
| 原 probe | 仍构建/运行，各方向 1 MiB 校验通过 |
| Rust | 7 tests passed：真实双向流、pending read/write 关闭唤醒、native error、drop、5 秒 drain deadline、多个 opened 等待者关闭唤醒；clippy -D warnings 通过 |
| Kotlin/JNI host | 实际 DataChannelTransport.kt 与从 OuterTransport.kt 提取的两个真实接口声明编译；正/反向 2,097,469 / 2,097,871 字节；数组所有权、preopen 高水位、错误/释放、阻塞 listener 不妨碍反向发送通过 |
| Rust ↔ Kotlin/JNI | 两进程真实本地 ICE/DataChannel，stdin/stdout 只传 fixture 信令；回传 2,097,661 字节校验通过。测试默认 ignored，由 test-kotlin.ps1 显式执行并通过 |
| Android | NDK 28.2.13676358、API 26、arm64-v8a 共用 core/JNI 链接成功；仅交叉编译 |

原始日志在 `.build-step1-win/build-jni.log`、`rust-tests.log`、`rust-clippy.log`、`kotlin-tests.log` 和 `.build-step1-android/build.log`，独立 build 目录已 ignore。

宿主 JNI 曾一次输出结果后非零退出，未获得崩溃堆栈；已补 nativeDestroy 完成等待、独立 delivery 和退出检查，重新验证通过，最终宿主退出检查连续三次 exit 0（kotlin/repeat.log）。打印 success 不能替代退出码和释放检查，不能外推 Android 运行稳定性。

## 未验证与未交付

2026-11-10 后续编译补充：`build-adapter.ps1 -Platform android-x86_64` 已用同一 NDK 28.2.13676358/API 26 和锁定依赖构建 core/JNI/datachannel 三层。ELF 头确认为 x86-64；原文件保留，对复制件执行 `llvm-strip --strip-unneeded`。原始/strip 后字节分别为 `libpocket-p2p.so` 801856/72616、`libpocket-p2p-jni.so` 2983200/373816、`libdatachannel.so` 31506432/3751672。它们尚未加入 APK；此数值不是 APK 体积增量或 Android 运行验证。证据为 `.local/p2p-round2-x86_64-build.log` 和 `.local/p2p-round2-x86_64-stripped/sizes.json`。

主控后续补充：`crates/p2p/tests/pocket_tls.rs` 已通过真实本地 DataChannel 承载 Pocket 原有 TLS、设备 hello 和业务 ping 的集成测试，错误 pin 和设备凭据被拒绝。信令仍为本地 fixture，不能当作手机公网或产品选路验收。原先“没有 Pocket TLS 握手”边界仅描述适配层交付时刻。

- 没有手机 E2E、Android 进程内 JNI 运行、真实 NAT、移动网络、STUN/TURN 或公网穿透验证。
- 受认证产品信令、生产选路和 TLS 上层背压接线尚未验证；本地 Pocket TLS、pin 拒绝及设备认证已由上段后续夹具覆盖。
- 没有完整 Gradle/app 构建、APK/R8、strip 增量或服务容量验收。宿主 Kotlin 编译不等于整模块 Gradle 构建。
- 未实测远端协商 max 小于 16 KiB 的异构 peer、移动网络持续拥塞/重连；代码逐次读 RTC max，本次两端本地上限一致。
- 当前一代一个 peer；主控负责确认当前代次、关旧 peer 和建新 peer。

未改生产、启动新服务、stage/commit/push 或停止已有进程。共享工作区中 agent/bridge/desktop/app 和其他 wire 文件的并发修改属于其他任务；本次工具只写指定 P2P 范围，未纳入其他任务的验证结论。
