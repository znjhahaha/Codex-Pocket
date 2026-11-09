# 0.6 路线与会话接手合约

所有新增字段均可选。配对仍为 v1，证书、电脑身份、设备密钥和命令 ID 沿用。

## 路线

`ConnectionRoute { id, kind, url, priority, expiresAt? }`。kind 为 lan / cloudflare / relay；公网 url 是完整手机 WSS 端点，不能再次拼接路径；LAN 为 `tls://私有字面 IP:端口`。旧 relayUrl 仍是 base URL。

policy 为 automatic / lan / public。hello 返回 routes、routePolicy、helperVersion。`routes/list {}` 返回 computerId、routes、policy。主机本地控制使用 routes/read 与 routes/configure，手机不能修改电脑监听配置。

LAN NSD `_codex-pocket._tcp.` 仅公布 computerId、protocol、port。发现结果是候选；手机仍通过证书 pin 和 hello 验证电脑，不信任广播授权。

RoutingPocketConnection 在连接失败时退避选择其他候选。连接成功后保持至少 30 秒，随后可探测优先路线；只有新路线通过内层 TLS 与设备 hello、且旧连接无在途请求时才切换。Repository 切换后同步游标。每个业务请求只发送到一个连接，未知写入不重播。

监听就绪、TLS 会话与已认证设备分别记录。仅成功 hello 才持有 authenticatedPhone 生命周期计数。

## 接手

- session/preferences/read；update 接收 mode、knownRevision、commandId。
- runtime/ensure 接收 source、commandId；默认 desktop，CLI 必须明确选择。
- thread/access/read 接收 runtimeId、threadId。
- thread/access/update 接收上述身份、mode、commandId。
- thread/access/cancel 接收 threadId、commandId 和可选 runtimeId。

接手状态保留 mode、source、ownerRuntimeId、phase、canSend、reason、operationId、canCancel。后台目录刷新不会订阅所有 CLI；释放订阅之后必须等原生已加载列表／关闭证据核对，不以 unsubscribe ACK 冒充释放。

## 媒体

原生用户图片投影沿用 thread/media/read，支持 recoverable 状态、contentPart、connectionId。按原生回合、条目、图片位置定位经过验证的历史来源，受限读取在阻塞线程池执行。消息载荷不携带大块历史图片，不接受手机传任意磁盘路径。
