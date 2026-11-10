# Pocket Runtime Host（未接通产品的独立宿主基础）

此 crate 是独立可执行程序的基础，并保留可信本机 client 的兼容重导出；client、协议与描述符校验已提取到完全不依赖 agent 的 `pocket-runtime-ipc`。目前没有接入 Pocket broker 发现、助理调度、Android 产品流程或 Windows 安装清单。测试通过只证明下面的宿主协议和恢复规则，不能视为助理成品或真实原生会话验收。

宿主持有 `codex app-server --listen stdio://`，通过现有 `CODEX_HOME` 使用原生配置。broker TCP 连接关闭不会结束子进程；明确空闲退出才关闭 stdin。宿主退出后不保证原生任务继续。单实例锁及描述符中的原生 PID/创建时间负责拒绝重复启动：连接失败本身不是进程退出证据，旧原生身份没有被证实终止时，不能启动第二个原生进程。发现 API 只读取、核验及连接，不启动进程。

## 隐私和持久恢复

`state/recovery.json` 使用 schema 2，只持久化明确列出的字段：

- schema、runtime ID、代次、事件序号、是否需要核对；
- command / answer 的操作 ID、原始代次、是否完成；
- run 的 thread ID、turn ID、代次和 active / completed / unknown 状态；
- 待答原生请求的 ID 和代次。

**不持久化**原始问题、答案、私密消息、请求参数、事件正文、结果正文、原生错误正文或请求/答案摘要。包括 `isSecret` 低熵答案在内，所有消息体的 SHA-256 去重摘要只留在本次宿主进程内存。持久记录通过独立字段白名单序列化；内存 journal 本身没有序列化实现。操作、thread、turn ID 必须是无私密内容的不透明标识，调用方不能将消息或答案编码进 ID。

活着的同一宿主允许可信 broker 读取内存中的原始事件、问题及结果，并用内存摘要检测相同 ID 的不同请求体。内存最多保留 2048 个事件 / 8 MiB 事件正文、8 MiB 结果和 128 个待答请求 / 1 MiB 请求正文。磁盘及内存共用最多 4096 个 command + answer 记录和 256 个 run 的准入限制；满时拒绝新 ID，不驱逐不确定 dispatch 的证据。恢复文件最多 32 MiB。

写给原生进程前先原子持久化预留记录。未收到原生回复的 command、尚未确认写入的 answer 都是 `unknown`；它可能尚未发送，也可能已经执行。**任何恢复过程都不重放 dispatch**，包括相同 ID 换请求体。重启后不保留原始结果与摘要，因此不能比较旧请求体：已存在 ID 只返回原始代次的完成/未知标记，不能发送第二次。不要因为超时、断线、未知回执或结果不可用而生成新 ID 重试。

`completed` 表示宿主收到回复（command）或已写出响应（answer），不代表原生业务成功或用户已接受答案。重启后的 completed receipt 可以 `response: null`；client 的 `request()` 会报告结果不可用/不确定，调用方必须用 receipt 和原生会话核对。旧 active run 变为 unknown；待答问题恢复为 `{generation, requestId, state: "unknown"}`，不恢复问题正文，也不接受对旧代次问题的回答。已答问题的旧代次记录也不能作为当前问题的确认。

`host/replay` 只回放内存事件，不重发原生调用。重启或代次变化清空内存窗口；代次、序号不匹配或窗口被驱逐时返回 `snapshotRequired: true`。`host/recovery` 的待答元数据也不是可执行请求；调用方仍需读取原生会话进行核对。

读取合法 schema 1 快照时，先验证边界和 runtime 身份，再原子迁移为 schema 2，去掉正文、结果和摘要，保留 ID、完成/未知证据和序号。迁移在启动新原生子进程前完成，不另存私密快照备份。损坏、未知 schema、schema 2 的额外字段或配置不匹配会拒绝启动，不能自动清空后继续。替换文件不保证擦除旧磁盘块、系统备份或原生 Codex 自己的历史；这些不属于本 crate 的处理范围。

## 本机信任边界和 API

宿主使用私有状态目录、单实例文件锁、loopback 地址和随机 256-bit token。token 只用于私有描述符和本机认证帧，不写入恢复 journal、错误日志或 Debug 输出。解析和拒绝错误使用固定文案，不回显不可信字段或原生错误正文。原生 stderr 不采集。客户端检查文件及祖先路径的 symlink / reparse point，并比较已打开文件的物理路径；Windows 还明确拒绝 `Packages/.../LocalCache/...`，即使请求路径与物理路径相同。

每个连接的队列有界，最多八个本机连接。此 API 必须只给已完成用户授权的 Pocket broker / 协调器使用。**不得直接向手机公开描述符、token、任意原生 RPC、通用配置或动态工具调用**。宿主不替代 broker 的用户授权、设备鉴权、操作 ID 台账或调度。

| 接口 | 行为 |
| --- | --- |
| `HostClient::discover` / `load_descriptor` | 检查配置、协议、路径；连接时核验宿主及原生进程创建身份；不启动进程 |
| `host/status` / `health()` | 身份、基础能力、当前代次 active / pending 计数、核对要求 |
| `host/request` / `request()` | 预留、去重并转交已授权原生请求；超时不能重发 |
| `host/receipt` / `receipt()` | 原始代次的 command 完成/未知标记；冷恢复没有结果正文 |
| `host/respond` / `respond()` | 只接受当前代次的真实待答 ID；响应恰好含 result 或 error；先预留后写出 |
| `host/replay` / `replay()` | 有界内存事件窗口、当前问题；缺口要求原生状态快照 |
| `host/recovery` / `recovery()` | run 和待答证据，保留原始代次；不会恢复/执行 dispatch |
| `host/shutdown` / `shutdown_idle()` | 当前代次没有活动 run、问题或未知写记录时允许空闲退出 |

先订阅事件再请求回放。广播 lag / 断线需要回放或快照核对；不能将回放事件作为再次 dispatch 的指令。协议版本仍为 2，恢复文件 schema 为 2；这两个版本含义不同。

## 依赖边界

`client` / `protocol` 现在仅重导出 `pocket-runtime-ipc` 的相同类型与函数，保留原有公开路径。IPC 包没有 `pocket-agent` / `pocket-runtime-host` 的普通、可选、开发或平台依赖，也没有 server feature；进程身份类型仍来自 `pocket-core`。默认 `server` feature 继续通过可选 `pocket-agent` 使用现有 storage 工具。

后续 agent / NativeConnection **直接依赖 `pocket-runtime-ipc`**。不要让 agent 依赖 host 包，即使关闭 host 默认 feature；host 的可选 server-to-agent 包边仍存在。根 Cargo、agent 与安装脚本留给后续集成，两个 scoped 包当前各有独立 `[workspace]`；纳入根 workspace 时应一并调整声明和锁文件策略。具体消费者 API、信任边界和接线顺序见 [Runtime IPC README](../runtime-ipc/README.md)。

`tests/package_graph.rs` 用 Cargo metadata 校验所有 feature 下的已解析图和包含可选/开发/平台依赖的声明路径图：IPC 可达的本地包只有 IPC 与 core；host 默认 server 确实包含 agent，IPC 分支仍不包含 agent 或 host。此包图证据不能代替尚未实施的根 workspace 与产品接线验收。

## 构建和确定性测试

```powershell
cargo build --offline --manifest-path crates/runtime-host/Cargo.toml --target-dir target --bin pocket-runtime-host
cargo test --offline --manifest-path crates/runtime-host/Cargo.toml --target-dir target
cargo check --offline --manifest-path crates/runtime-host/Cargo.toml --target-dir target --no-default-features --lib
cargo test --offline --manifest-path crates/runtime-ipc/Cargo.toml --target-dir crates/runtime-ipc/target
cargo test --offline --manifest-path crates/runtime-host/Cargo.toml --target-dir crates/runtime-host/target --no-default-features --test ipc_compat --test package_graph -- --nocapture
```

运行参数为：

```text
pocket-runtime-host --state <private-host-dir> --descriptor <private-registry-file> --binary <absolute-codex-exe> --config-home <existing-CODEX_HOME>
```

`pocket-host-fixture` 仅为测试子进程，不纳入安装交付。测试只启动自己的假 app-server / 宿主，不调用真实模型，不控制用户已有进程。

host 单测检查：正文/摘要不落盘、schema 1 最小化迁移、schema 2 字段白名单、损坏快照不重置、未知预留不驱逐及回放缺口。描述符 MSIX LocalCache 与错误/Debug 脱敏单测随代码迁入 IPC；新增 IPC 测试覆盖帧边界、超限/残帧/非对象拒绝、操作 ID 范围、配置与字段校验、缺失发现及 PID 创建身份。兼容测试验证旧/新类型相同及帧函数互通；foundation 用新 IPC 导入，lifetime 保留旧路径。

`tests/foundation.rs` 和 `tests/lifetime.rs` 检查：连接断开后相同原生身份、错误 token / 代次 / 身份 / 配置拒绝、重复 command / answer 不重跑、取消请求保留 unknown、运行时拒绝退出、空闲退出、原生退出后恢复不重发。隐私端到端用假进程故意回显 secret 答案和私密结果/错误，验证它们只在内存回放中出现；重启后 completed 结果正文不可用、旧问题不可回答，假进程的 writes / answers 均保持 0。另有实际宿主可执行程序的启动失败 stderr 检查。

尚未完成：根 workspace 与 agent / NativeConnection 接线、产品发现及调度、安装交付、真实原生协议/会话恢复验收、宿主硬崩溃与系统重启验收、Windows 用户隔离的端到端攻击测试，以及本 crate 之外的日志/历史隐私审计。当前没有逐项修改恢复记录或消除 `reconciliationRequired` 的业务接口：现有持久证据不足以证明旧未知操作的实际原生结果，不能接收调用方任意“clear unknown”。后续原生核对产品路径须将可信证据绑定原始代次和逐项身份，继续遵守不重放 dispatch 的规则。
