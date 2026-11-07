package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*

/** Deliberate samples, reachable only by Preview/tests. Never a repository data fallback. */
internal object PocketPreviewStates {
    private val runtime = RuntimeInfo("preview-runtime", "desktop", "Preview", "preview-store", true, emptySet(), setOf("thread/start"))
    val chats = listOf(
        ChatEntity(ThreadKey("preview-pc", "preview-store", "preview-1"), runtime.id, "desktop", "把想法变成一个小工具", "", "D:/Projects/Studio", 2, "inProgress", false, pinned = true),
        ChatEntity(ThreadKey("preview-pc", "preview-store", "preview-2"), runtime.id, "cli", "检查登录流程中的边界情况", "", "D:/Projects/Pocket", 1, "completed", false),
        ChatEntity(ThreadKey("preview-pc", "preview-store", "preview-3"), runtime.id, "desktop", "梳理这周的开发计划", "", "D:/Projects/Notes", 0, "pending", false),
        ChatEntity(ThreadKey("preview-pc", "preview-store", "preview-4"), runtime.id, "desktop", "Pocket · 移动端交互", "", "D:/Projects/Pocket", 4, "completed", false, pinned = true),
        ChatEntity(ThreadKey("preview-pc", "preview-store", "preview-5"), runtime.id, "cli", "整理组件库与设计变量", "", "D:/Projects/Studio", 3, "completed", false, pinned = true),
        ChatEntity(ThreadKey("preview-pc", "preview-store", "preview-6"), runtime.id, "desktop", "审阅这次接口变更", "", "D:/Projects/Pocket", 2, "completed", false),
        ChatEntity(ThreadKey("preview-pc", "preview-store", "preview-7"), runtime.id, "cli", "为文档补充使用示例", "", "D:/Projects/Notes", 1, "completed", false),
    )
    val home = PocketState(initialized = true, paired = true, online = true, computerName = "Studio PC", connection = "online", runtimes = listOf(runtime), chats = chats, unreadKeys = setOf(chats[1].key.encoded))
}

@Preview(name = "Chats · Light", widthDp = 412, heightDp = 820, showBackground = true)
@Composable internal fun ChatsLightPreview() = HomeSample("light")
@Preview(name = "Chats · Dark", widthDp = 412, heightDp = 820, showBackground = true)
@Composable internal fun ChatsDarkPreview() = HomeSample("dark")
@Composable private fun HomeSample(theme: String) {
    PocketTheme(theme) { PocketHomeFrame(PocketPreviewStates.home, HomeTab.Chats, 0, false, false, {}, {}, {}, {}) {
        ChatsHome(PocketPreviewStates.home, {}, {}, { _, _, _ -> }, {}, {})
    } }
}

@Preview(name = "Devices · Dark", widthDp = 412, heightDp = 820)
@Composable internal fun DevicesPreview() { PocketTheme("dark") { PocketBackdrop { DeviceOverview(PocketPreviewStates.home, {}, {}, {}) } } }

@Preview(name = "Conversation · Light", widthDp = 412, heightDp = 820)
@Composable internal fun ConversationLightPreview() = ConversationSample("light")
@Preview(name = "Conversation · Dark", widthDp = 412, heightDp = 820)
@Composable internal fun ConversationDarkPreview() = ConversationSample("dark")
@Preview(name = "Conversation · Large text", widthDp = 360, heightDp = 800, fontScale = 1.5f)
@Composable internal fun ConversationLargeTextPreview() = ConversationSample("light")

@Composable internal fun ConversationSample(theme: String) {
    val model = NativeModel("preview", "gpt-6.1-sol", "gpt-6.1-sol", "Preview only", listOf("high"), "high", true, true)
    val state = PocketPreviewStates.home.copy(selected = PocketPreviewStates.chats.first(), models = listOf(model))
    val user = TimelineItem("sample-turn", "sample-user", "userMessage", "检查登录按钮重复提交的问题，并补一个测试。", complete = true)
    val command = TimelineItem("sample-turn", "sample-command", "commandExecution", "./gradlew test", complete = true)
    val change = TimelineItem("sample-turn", "sample-file", "fileChange", raw = buildJsonObject { put("changes", buildJsonArray { add(buildJsonObject { put("path", "src/LoginController.kt"); put("diff", "@@ -1 +1,2 @@\n-old\n+new\n+test") }) }) }, complete = true)
    val answer = TimelineItem("sample-turn", "sample-answer", "agentMessage", "修好了。登录请求开始后会立即锁定按钮，完成后再恢复。\n\n- 增加重复点击保护\n- 补充了回归测试\n\n```kotlin\nif (submitting) return\n```", complete = true)
    val rows = ConversationPresentation.rows(NativeTimeline(linkedMapOf(user.key to user, command.key to command, change.key to change, answer.key to answer)))
    PocketTheme(theme) { ProvideMarkdownRendering { PocketBackdrop { Column(Modifier.fillMaxSize()) {
        ConversationHeader(state, {}, {}, {}) { CircleAction(PocketIcons.MoreHoriz, "聊天操作") {} }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(rows.size) { TranscriptRowView(rows[it], "preview/", false, {}, {}) }
        }
        PocketComposer("", "gpt-6.1-sol", "高 · 默认", false, ComposerAvailability(false, false, true, false), {}, {}, {}, {}, {}, {}, {}, true)
    } } } }
}

@Preview(name = "Attention · Light", widthDp = 412, heightDp = 820)
@Composable internal fun AttentionPreview() {
    val request = PendingApproval("preview-runtime", JsonPrimitive("preview-approval"), "item/commandExecution/requestApproval", buildJsonObject { put("threadId", "preview-chat"); put("command", "./gradlew test"); put("reason", "运行刚添加的回归测试"); put("cwd", "D:/Projects/Pocket") })
    PocketTheme("light") { PocketBackdrop { Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text("待处理", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        ApprovalCard(request, true) { _, _ -> }
        Spacer(Modifier.weight(1f))
        PocketHomeNavigation(HomeTab.Attention, 1) {}
    } } }
}
