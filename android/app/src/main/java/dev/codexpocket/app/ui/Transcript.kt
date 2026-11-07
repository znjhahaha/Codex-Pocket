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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TranscriptRowView(row: TranscriptRow, messageScope: String, expanded: Boolean, onExpand: () -> Unit, onDetail: (TimelineItem) -> Unit, onSideChat: (TimelineItem) -> Unit = {}, canFork: Boolean = false, onPlan: ((Boolean) -> Unit)? = null,
    activityExpansions: Set<String>? = null, onActivityExpansion: ((String) -> Unit)? = null) {
    val item = row.items.first()
    val clipboard = LocalClipboardManager.current
    when (row.kind) {
        TranscriptKind.User -> Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val message = remember(item.text) { UserMessageParsing.parse(item.text) }
            // Attachments sit above the bubble, Codex-style, listed once whichever source named them.
            val attachments = remember(item.raw, message.files) { userAttachments(item, message.files) }
            // Images show as thumbnails (nine-cell grid like WeChat); other files stay chips.
            val (images, files) = remember(attachments) { attachments.partition(::looksLikeImage) }
            if (images.isNotEmpty()) ImageGrid(images, alignEnd = true)
            if (files.isNotEmpty()) AttachmentChips(item.key, files, alignEnd = true)
            val replies = remember(message.request) { AsyncQuestions.replies(message.request) }
            val prose = if (replies.isNotEmpty()) replies.joinToString("\n\n") { "${it.string("question")}\n${it.string("answer")}" }
                else if (message.request.trimStart().startsWith("<send_user_message_question_reply>")) stringResource(R.string.submit_answers) else message.request.trim()
            val hasImages = remember(item.raw) { transcriptImageSources(item).isNotEmpty() }
            if (prose.isNotBlank() || hasImages) Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 560.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                    if (prose.isNotBlank()) SelectionContainer { Text(prose, style = MaterialTheme.typography.bodyLarge) }
                    TranscriptImages(item, includeLocal = false)
                }
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
        TranscriptKind.Error -> Notice(ConversationPresentation.detail(item).ifBlank { stringResource(R.string.v02_error_detail) }, error = true)
        TranscriptKind.Plan -> NativeActivityCard(item, messageScope + item.key, expanded, onExpand, { onDetail(item) }, onPlan)
        TranscriptKind.Activity -> NativeActivityTrace(row, messageScope, expanded, onExpand, onDetail, activityExpansions, onActivityExpansion)
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
private fun CopyAction(text: String, onCopy: () -> Unit) {
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
