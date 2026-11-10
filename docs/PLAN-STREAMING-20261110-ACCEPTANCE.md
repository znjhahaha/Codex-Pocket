# 流式计划与传输背压阶段验收

日期：2026-11-10。范围为手机计划卡片、TLS 背压前置及其回归。此记录不宣称完整助理、P2P 产品接线或外部 Windows 首装问题完成。

## 改动

- 同一原生 plan item 更新时保留上次排版结果、固定预览、阅读器和滚动位置，不再在每个 revision 清空正文。
- Markdown 按稳定身份顺序排版并合并尚未处理的增量；身份改变取消旧 owner，迟到结果不能覆盖新计划。
- 原生活跃计划显示“编写计划”并复用文字扫光；完成、等待用户、离线及已有动画降级条件下停止。
- TLS 新增可选的 accepted / would-block / closed 接口。暂不可写时保留尚未接收的密文字节，仅由 writable 通知继续；有界队列与原有未知写结果规则保留。现有 WSS/LAN 保持兼容。该接口是 DataChannel 前置，不等于 P2P 已接入产品。

## 实际验证

| 检查 | 结果 | 本地证据 |
| --- | --- | --- |
| 修复前流式计划用例 | 预期失败，增量更新使打开的阅读器消失 | `.local/plan-stream-red.log` |
| 修复后定向 JVM | 12 项通过：PlanPresentation 3、PlanStreamingRendering 2、V053PlanControls 7 | `.local/plan-stream-frozen-targeted.log`、对应 JUnit XML |
| wire 回归 | 71 项，0 失败/错误 | 同次 Gradle、wire JUnit XML |
| Release app + instrumentation 构建 | 成功；关闭 Kotlin 增量编译后全量编译 | `.local/plan-stream-release-full-compile-r2.log` |
| 原签名校验 | App 与测试包均为原证书 | `.local/plan-stream-candidate-r3/*.signature.txt` |
| MuMu Android 15 UI | 显式启用 `scrollRegression=true`，13 项实际执行通过，33.924 秒 | `.local/plan-stream-device-r3-enabled.log` |
| 覆盖安装与测试后数据保留 | 两次 after 均匹配已有 before；没有打开/解密真实数据库 | `.local/plan-stream-preservation-after-install.log`、`plan-stream-preservation-after-ui.log` |

设备 UI 覆盖计划连续追加、阅读器追加表格后锚点、首页返回、历史问题/答案、变更卡、图片恢复/过期引用、子代理与思考、末尾命令展开和上下滚动。

失败或未执行记录保留：首次 Release 增量编译出现 unresolved reference；第一次关闭增量编译的 PowerShell 参数未加引号而被错误拆分；第一次未显式启用 UI 测试开关的运行被跳过，不计作设备通过。签名最初检查了不存在的 `app-release.apk`，随后使用实际 unsigned 构建产物与原密钥签名并验证。MuMu 不支持增量安装，ADB 回退到 streamed install 成功，未卸载或清数据。

## 候选产物

- App：`.local/plan-stream-candidate-r3/codex-pocket-0.6.3.apk`
- App SHA-256：`c5cadfa7acaae7428a240fecab054a19826e3c02d746d1cd3a647d5bcfb693ea`
- 测试 APK SHA-256：`26019ad640fb9f1eb18e2f2d193ba9aa905259e3f0dc8cdfb1156aec5f844f19`
- 签名证书 SHA-256：`2d53a8ae9d77804fdcb1174cfcd81548ea97ae6a664ce6f1407fd6c1f5a57a8a`

候选包含本批手机/wire 修改，没有用量 UI 或 P2P 产品入口。真实手机、真实模型输出、不同网络、持续流式性能与真实 Windows 登录均不在上述通过范围。静态截图不能单独证明扫光动画或端到端连接成功。
