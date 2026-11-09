# Codex Pocket 0.5.4 需求核对

日期：2026-11-08。核对 0.5.4 批准计划、继承的计划交互要求及本轮补充。代码存在、测试通过、设备验收是不同结论；未取得证据的项目保留待验收。

| 要求 | 实现与验证依据 | 当前结论 |
| --- | --- | --- |
| MiSans 随包、中文／西文统一、代码等宽 | ReadingTypography、四字重、assets 许可；MuMu 浅色原生排版检查 | MuMu 浅深主题与大字体完整阅读 30 阶段通过 |
| 行内代码圆角、跨行、原文不变 | RoundedCodeSpan、drawRoundedCode、V054ReadingTest | 重复矩形底色已移除，标点留白与跨行截图通过 |
| 圆角文件引用、完整路径、只读预览 | AttachmentPreview、SourceScreen、ChildSourcePreviewRepositoryTest | 主／子任务均已接通；子任务禁止评论写入 |
| 表格完整列与自适应行高 | MarkdownTable、StaticLayout；三列也允许横向滚动 | 本地测试及 MuMu 横向末列截图通过 |
| 成功任务保留首次问题和最终结果 | ConversationPresentation、V053／V054TaskFoldingTest | 本地通过；旧历史缺少任务关系时按原生回合降级 |
| 插话、多个最终片段、已处理问题收入过程 | 原生 rootTurnId、确认插话、resolvedRequests | 本地通过；不根据文字猜测关系 |
| 失败／停止／待审批保留可见 | TaskFoldReason 与生命周期测试 | 本地通过 |
| 折叠过程仍保留每条用户导航身份 | TranscriptProjection、ConversationMinimapMap | 本地通过；MuMu 等距、长按与拖动定位复测通过 |
| Codex 等距用户刻度、BaoCode 所有消息轮廓 | 最大 10dp 间距、圆端点、30dp／44dp 玻璃滑块 | 已实现；侧栏重排不覆盖正文 |
| 隐藏提问松手后展开并精确定位 | 原生 key、过程展开、选择代次、50ms 合并 | 本地回归通过 |
| 首页入口重排与统一管理 | HomeChrome、SettingsScreen | MuMu 通过；右下角留空 |
| 电脑连接／扩展管理／应用设置页内分段栏 | 独立页面采样、玻璃选中胶囊、各页滚动保存 | 6 项主机回归及 MuMu 三页切换通过，截图已留存 |
| 展开工具输出圆角框与主题背景 | ToolResultFrame 主会话／子代理共用 | 25 项时间／工具／子代理专项通过；MuMu 浅深工具框截图通过 |
| 用户消息下方发送时间 | 读取原生 userMessage.startedAtMs，插话使用自己的时间；初始提问可回退原生回合开始 | 6 项回归通过；MuMu 回读用户反馈的同一条插话，浅深主题显示下午 9:49；不复用同步时间 |
| 紧凑错误与真实重连状态 | 主／子会话共用错误行；HTML 摘要、原位详情、完整复制 | 41 项相关回归通过；MuMu 浅色／深色大字体折叠与展开通过；不代表上游故障已恢复 |
| 已有 MCP／Skills／应用插件查询与启停 | ExtensionManagement、Windows extensions 接口 | 本地协议／仓库测试通过；原生运行实例尚待完整启停验收 |
| 管理不允许任意路径／配置／凭据 | 后端 opaque ID、白名单、版本、命令去重 | 本地测试通过；登录失效需电脑处理 |
| 原生消息编辑与撤回 | MessageEdit、thread/edit/prepare／revert／status | 本地通过；真实可丢弃会话撤回未验收 |
| 编辑草稿、附件、引用保留 | 独立编辑账本、Windows retained input | 本地通过；撤回不回滚文件修改 |
| 未知结果先核对、永不自动重发 | UnknownRevert／UnknownSend、晚到回读身份校验 | 本地通过；关闭重开与新输入竞态已覆盖 |
| 提问幕布只改变绘制、不改变底部高度 | QuestionReveal、320ms／180ms、记忆问题身份 | 主机测试及 MuMu 切题通过 |
| 摘要扫光只在运行、可见、前台 | ReasoningSweep、1.4s、减少动效与省电门控 | 本地通过；MuMu 留有采样录像 |
| 工具文本／图片按顺序、主子任务复用 | toolOutputBlocks、ToolMediaGrid、媒体引用接口 | 本地通过；真实工具图片链路待新版桥接激活后验收 |
| 大图有界分块、损坏不阻塞连接 | 24MiB 上限、192KiB 分块、解码与身份校验 | 本地通过 |
| 历史子代理重新启用也计数 | 执行代次、已退休 turnId、resume 去重 | 主机测试通过；旧完成事件不能结束新周期 |
| 两张／多张图片展开不抖动 | 同尺寸容器、单层过渡、慢解码／反向操作 | MuMu 2／4 图浅深主题通过 |
| 扫码立即出现明确连接确认 | 居中玻璃弹窗、电脑名、连接／取消、过期重验 | 已实现；7 项主机用例及 MuMu 弹窗显示／取消通过 |
| 正式计划卡片与执行步骤分开 | PlanProposalCard／PlanPresentation | 本地与 MuMu 完整阅读、底部确认稳定性通过 |
| 计划确认固定底部替代输入框 | ChatScreen／PlanConfirmation／TranscriptScrolling | MuMu 底部跟随稳定夹具通过 |
| 实施一次、修改独立草稿、跳过仅本地 | PlanAction、PlanFeedbackDraft、版本绑定、去重 | 专项主机用例通过；底部稳定性 MuMu 夹具通过 |
| 计划 Markdown 保存／复制／放大 | PlanProposalCard 原文导出、完整阅读页 | 已实现；端到端文件导出未验收 |
| 输入框聚焦后滚动收缩、导航不闪退 | 独立多行编辑器和窄预览；V051NavigationDeviceTest | MuMu 50 次返回通过；系统中文 IME 单列待验收 |
| 20 次会话切换＋120 秒停留 | LiveBrowseTest，已有会话、0 模型请求 | 修复后 20 次切换＋120 秒通过，断线 0，新模型请求 0 |
| 1,000 消息＋200 工具性能 | Release MuMu FrameMetrics 和本地测量 | 已测量但未达标；不把夹具执行成功等同性能通过 |
| 模型／强度／Plan／Fast／权限／上下文 | 继承现有原生设置与能力门控 | 保留实现与本地覆盖；旧桥接新能力、真实生成控制单列待验收 |
| 原签名与数据保留、Windows 更新、源码哈希 | 原签名校验、install -r、独立 Windows 上下文 | Windows 升级与原签名覆盖安装通过；修订包哈希见最终交付清单 |

Mate 70 Pro、HMS 真实送达、24 小时稳定性、系统中文输入和新增真实生成控制不以 MuMu 或本地夹具替代。
