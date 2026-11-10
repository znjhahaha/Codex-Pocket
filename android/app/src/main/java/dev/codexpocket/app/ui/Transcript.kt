package dev.codexpocket.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TranscriptRowView(row: TranscriptRow, messageScope: String, expanded: Boolean, onExpand: () -> Unit, onDetail: (TimelineItem) -> Unit, onSideChat: (TimelineItem) -> Unit = {}, canFork: Boolean = false, onPlan: ((Boolean) -> Unit)? = null,
    activityExpansions: Set<String>? = null, onActivityExpansion: ((String) -> Unit)? = null, timeline: NativeTimeline? = null, onReopenPlan: (() -> Unit)? = null,
    onEdit: ((TimelineItem) -> Unit)? = null, online: Boolean = false) {
    val item = row.items.first()
    val clipboard = LocalClipboardManager.current
    when (row.kind) {
        TranscriptKind.Process -> Column(Modifier.fillMaxWidth()) {
            val interaction = remember { MutableInteractionSource() }
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp)
                .clickable(interactionSource = interaction, indication = null, onClick = onExpand),
                verticalAlignment = Alignment.CenterVertically) {
                Text(row.processLabel ?: "查看过程", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val angle by androidx.compose.animation.core.animateFloatAsState(if (expanded) 90f else 0f, pocketPressSpring(), label = "process-chevron")
                Icon(PocketIcons.ChevronRight, null, Modifier.padding(start = 5.dp).size(14.dp).graphicsLayer { rotationZ = angle }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            row.processDetail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // Completion remains visible when the successful task's other work is folded.
            if (!expanded) row.items.filter { it.type == "contextCompaction" }.forEach { CompactionStatus(it, timeline) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
        }
        TranscriptKind.User -> Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val message = remember(item.text) { UserMessageParsing.parse(item.text) }
            // Attachments sit above the bubble, Codex-style, listed once whichever source named them.
            val attachments = remember(item.raw, message.files) { userAttachments(item, message.files) }
            // Images show as thumbnails (nine-cell grid like WeChat); other files stay chips.
            val (images, files) = remember(attachments) { attachments.partition(::looksLikeImage) }
            val nativeImages = remember(item.raw) { userMessageMedia(item) }
            if (nativeImages.isNotEmpty()) ToolMediaGrid(nativeImages, alignEnd = true)
            else if (images.isNotEmpty()) ImageGrid(images, alignEnd = true)
            if (files.isNotEmpty()) AttachmentChips(item.key, files, alignEnd = true)
            val replies = remember(message.request) { AsyncQuestions.replies(message.request) }
            val prose = if (replies.isNotEmpty()) replies.joinToString("\n\n") { "${it.string("question")}\n${it.string("answer")}" }
                else if (message.request.trimStart().startsWith("<send_user_message_question_reply>")) stringResource(R.string.submit_answers) else message.request.trim()
            val hasImages = remember(item.raw) { transcriptImageSources(item).isNotEmpty() }
            if (prose.isNotBlank() || hasImages) Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 560.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                    val implementation = remember(item, timeline) { timeline?.let { PlanPresentation.implementedProposal(item, it) } }
                    if (implementation != null) {
                        var showingPlan by androidx.compose.runtime.saveable.rememberSaveable(messageScope, item.key) { mutableStateOf(false) }
                        Row(Modifier.heightIn(min = 32.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showingPlan = !showingPlan }, verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("是，实施此计划", style = MaterialTheme.typography.bodyLarge)
                            ActivityExpansionIcon(showingPlan)
                        }
                        if (showingPlan) Box(Modifier.padding(top = 10.dp)) { MarkdownText(implementation.content, "$messageScope/${item.key}/implemented-plan") }
                    } else if (prose.isNotBlank()) SelectionContainer { SentReferenceText(prose, item.raw) }
                    TranscriptImages(item, includeLocal = false)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                val context = LocalContext.current
                val timestamp = remember(item.raw, item.key, timeline?.turns, timeline?.items?.keys) { messageTimestamp(item, timeline) }
                timestamp?.let { time ->
                    val date = java.util.Date(time.epochMillis)
                    val label = android.text.format.DateFormat.getTimeFormat(context).format(date)
                    val full = android.text.format.DateFormat.getMediumDateFormat(context).format(date) + " " + label
                    Text(label, Modifier.padding(end = 4.dp).testTag("message-time:${item.key}")
                        .semantics { contentDescription = (if (time.fromTurnStart) "回合开始时间：" else "发送时间：") + full },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CopyAction(prose) { clipboard.setText(AnnotatedString(prose)) }
                if (onEdit != null) QuietAction(PocketIcons.Edit, "编辑此消息") { onEdit(item) }
            }
        }
        TranscriptKind.Answer, TranscriptKind.Commentary -> Column(Modifier.fillMaxWidth()) {
            val answer = remember(item.text) { AnswerDirectives.parse(item.text) }
            MarkdownText(answer.text, messageScope + item.key)
            val (citedImages, citedFiles) = remember(answer.files) { answer.files.partition(::looksLikeImage) }
            if (citedImages.isNotEmpty()) ImageGrid(citedImages, alignEnd = false, modifier = Modifier.padding(top = 8.dp))
            if (citedFiles.isNotEmpty()) Box(Modifier.padding(top = 6.dp)) { AttachmentChips(item.key + "/cited", citedFiles, alignEnd = false) }
            TranscriptImages(item)
            if (row.kind == TranscriptKind.Answer && item.complete) Row(Modifier.padding(top = 2.dp).offset(x = (-8).dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                CopyAction(answer.text) { clipboard.setText(AnnotatedString(answer.text)) }
                if (canFork) QuietAction(PocketIcons.CallSplit, stringResource(R.string.v02_sidechat)) { onSideChat(item) }
            }
        }
        TranscriptKind.Error -> ConversationErrorRow(remember(item, timeline?.activeTurn, timeline?.completedTurns, timeline?.items) {
            ConversationErrorPresentation.from(item, timeline)
        }, messageScope + item.key)
        TranscriptKind.Plan -> NativeActivityCard(item, messageScope + item.key, expanded, onExpand, { onDetail(item) }, onPlan, onReopenPlan,
            writingPlan = PlanPresentation.isWriting(item, timeline, online))
        TranscriptKind.Activity -> if (item.type == "contextCompaction") CompactionStatus(item, timeline)
            else NativeActivityTrace(row, messageScope, expanded, onExpand, onDetail, activityExpansions, onActivityExpansion)
    }
}

@Composable
private fun CompactionStatus(item: TimelineItem, timeline: NativeTimeline?) {
    CompactionStatusRow(ConversationPresentation.compactionLabel(item, timeline), Modifier.testTag("compaction-status:${item.key}"))
}

/** The native compaction milestone uses the same compact row in live and historical views. */
@Composable
internal fun CompactionStatusRow(label: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(PocketIcons.FoldVertical, null, Modifier.size(16.dp).testTag("compaction-status-icon"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small gray icon under an answer; the press is a soft circle, not a 48dp tile. */
@Composable
internal fun QuietAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) .88f else 1f, pocketPressSpring(), label = "quiet-press")
    Box(Modifier.size(40.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
        .background(if (pressed) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Copy morphs into a check for a moment, the way iOS confirms a copy. */
@Composable
internal fun CopyAction(text: String, onCopy: () -> Unit) {
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1_400); copied = false } }
    val label = stringResource(R.string.copy)
    AnimatedContent(copied, transitionSpec = {
        (fadeIn(tween(pocketMotionMillis(140))) + scaleIn(tween(pocketMotionMillis(180)), initialScale = .6f)) togetherWith
            (fadeOut(tween(pocketMotionMillis(90))) + scaleOut(tween(pocketMotionMillis(120)), targetScale = .6f))
    }, label = "copy-confirm") { done ->
        QuietAction(if (done) PocketIcons.Check else PocketIcons.ContentCopy, label) { onCopy(); copied = true }
    }
}

@Composable
internal fun itemLabel(type: String): String = stringResource(when (type) {
    "commandExecution" -> R.string.command
    "fileChange" -> R.string.file_changes
    "reasoning" -> R.string.reasoning_summary
    "agentMessage" -> R.string.v02_commentary
    "plan", "turn/plan/updated" -> R.string.plan
    "turn/diff/updated" -> R.string.changes
    "collabAgentToolCall", "subAgentActivity" -> R.string.v03_activity_agents
    "webSearch" -> R.string.v03_activity_web
    "functionCallOutput" -> R.string.v03_activity_result
    "imageView", "imageGeneration" -> R.string.v03_activity_image
    "error", "turn/error", "thread/error", "turn/failed" -> R.string.v02_error
    else -> R.string.v02_tool
})
