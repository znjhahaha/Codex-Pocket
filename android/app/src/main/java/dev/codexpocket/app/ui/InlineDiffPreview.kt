package dev.codexpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.app.data.ActivityFile
import dev.codexpocket.app.data.ActivityStatus
import dev.codexpocket.app.data.FileCommentDraft
import dev.codexpocket.app.data.TimelineItem
import dev.codexpocket.app.data.activityFiles
import dev.codexpocket.app.data.array
import dev.codexpocket.app.data.obj
import dev.codexpocket.app.data.string
import kotlinx.serialization.json.JsonObject

/** Parent supplies its existing draft callback around the main transcript; child readers override it. */
internal val LocalInlineDiffComment = compositionLocalOf<((FileCommentDraft) -> Unit)?> { null }

internal enum class InlineDiffFallback { Empty, Binary, Truncated }
internal fun inlineDiffFallback(diff: String, truncated: Boolean = false): InlineDiffFallback? = when {
    '\u0000' in diff || diff.lineSequence().takeWhile { !it.startsWith("@@") }.any {
        it == "GIT binary patch" || (it.startsWith("Binary files ") && it.endsWith(" differ"))
    } -> InlineDiffFallback.Binary
    truncated -> InlineDiffFallback.Truncated
    diff.isBlank() -> InlineDiffFallback.Empty
    else -> null
}

/** Native update.movePath is a rename, even though the operation's discriminant is still update. */
internal fun reviewActivityFiles(item: TimelineItem, files: List<ActivityFile> = activityFiles(item)): List<ActivityFile> {
    if (item.type != "fileChange") return files
    val changes = item.raw.array("changes")
    return files.map { file ->
        val index = file.id.removePrefix("file:").toIntOrNull()
        val change = index?.let { changes.getOrNull(it) } as? JsonObject
        val moved = change?.obj("kind")?.string("movePath").orEmpty().ifBlank { change?.string("movePath").orEmpty() }
        // Whole-file text normalization must not turn a native binary notice into added source lines.
        val rawDiff = change?.string("diff").orEmpty()
        val binaryDiff = rawDiff.takeIf { inlineDiffFallback(it) == InlineDiffFallback.Binary }
        if (moved.isBlank() && binaryDiff == null) file
        else file.copy(path = moved.ifBlank { file.path }, kind = if (moved.isBlank()) file.kind else "rename", diff = binaryDiff ?: file.diff)
    }
}

internal fun fileActionLabel(kind: String, state: ActivityStatus): String {
    val action = when (kind.lowercase()) {
        "add", "added", "create", "created" -> "创建"
        "delete", "deleted", "remove", "removed" -> "删除"
        "rename", "renamed", "move", "moved" -> "重命名"
        else -> "编辑"
    }
    return when (state) {
        ActivityStatus.Completed -> "已$action"
        ActivityStatus.Running -> "正在$action"
        ActivityStatus.Waiting -> "待批准$action"
        ActivityStatus.Failed -> "${action}失败"
        ActivityStatus.Interrupted -> "${action}已中断"
        ActivityStatus.Unknown -> action
    }
}

/** Same parsed, highlighted viewport in main/child and preview/full reader; comments are opt-in. */
@Composable
internal fun InlineDiffPreview(
    file: ActivityFile,
    modifier: Modifier = Modifier,
    readingState: DiffReadingState? = null,
    onDraftComment: ((FileCommentDraft) -> Unit)? = LocalInlineDiffComment.current,
    stateKey: String = file.id + ":" + file.path,
    truncated: Boolean = false,
) {
    val retained = rememberDiffReadingState(stateKey)
    val reading = readingState ?: retained
    val fallback = remember(file.diff, truncated) { inlineDiffFallback(file.diff, truncated) }
    var fullScreen by remember(stateKey) { mutableStateOf(false) }
    var position by remember(stateKey) { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    fun resize(full: Boolean) {
        position = Triple(reading.list.firstVisibleItemIndex, reading.list.firstVisibleItemScrollOffset, reading.horizontal.value)
        fullScreen = full
    }
    // Restore after the new viewport has laid out, including when a wide reader is shrunk again.
    LaunchedEffect(stateKey, fullScreen) {
        if (fallback == InlineDiffFallback.Empty || fallback == InlineDiffFallback.Binary ||
            (fallback == InlineDiffFallback.Truncated && !fullScreen)) return@LaunchedEffect
        position?.let { (index, offset, x) ->
            withFrameNanos { }
            reading.list.scrollToItem(index, offset)
            reading.horizontal.scrollTo(x)
        }
    }
    val clipboard = LocalClipboardManager.current
    val copy = { clipboard.setText(AnnotatedString(file.diff)) }
    val fontScale = LocalDensity.current.fontScale
    val previewHeight = remember(file.diff, fontScale, fallback) {
        // Short patches use only their needed space. Count a bounded prefix, without a second parse.
        val lines = file.diff.lineSequence().take(9).sumOf { if (it.startsWith("@@")) 44 else 28 }
        if (fallback != null) 280.dp else (37f + lines * fontScale).coerceIn(92f, 280f).dp
    }
    Column(modifier.fillMaxWidth().height(previewHeight).testTag("inline-diff:${file.id}")
        .background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        InlineDiffHeader(file, { resize(true) }, copy, false, fallback)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        if (!fullScreen) {
            if (fallback != null) DiffFallbackNotice(fallback, Modifier.weight(1f).fillMaxWidth())
            else DiffLines(file.diff, true, Modifier.weight(1f).fillMaxWidth(), file.path,
                onDraftComment = onDraftComment, readingState = reading, showControls = false,
                commentsEnabled = onDraftComment != null)
        }
        else Spacer(Modifier.weight(1f))
    }
    if (fullScreen) PocketModal(title = compactFileName(file.path), onDismiss = { resize(false) }, sheet = false, screen = true) {
        Column(Modifier.fillMaxSize().testTag("inline-diff-reader")) {
            InlineDiffHeader(file, { resize(false) }, copy, true, fallback)
            if (fallback == InlineDiffFallback.Empty || fallback == InlineDiffFallback.Binary) {
                DiffFallbackNotice(fallback, Modifier.weight(1f).fillMaxWidth())
            } else {
                if (fallback != null) DiffFallbackNotice(fallback, Modifier.fillMaxWidth())
                DiffLines(file.diff, true, Modifier.weight(1f).fillMaxWidth(), file.path,
                    onDraftComment = onDraftComment, readingState = reading, commentsEnabled = onDraftComment != null)
            }
        }
    }
}

@Composable
private fun InlineDiffHeader(file: ActivityFile, resize: () -> Unit, copy: () -> Unit, fullScreen: Boolean, fallback: InlineDiffFallback?) {
    val (add, remove) = pocketDiffColors()
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(compactFileName(file.path).ifBlank { "文件差异" }, Modifier.weight(1f), fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (fallback == null) Text(buildAnnotatedString {
            withStyle(SpanStyle(color = add)) { append("+${file.additions}") }
            append(" ")
            withStyle(SpanStyle(color = remove)) { append("−${file.deletions}") }
        }, fontSize = 14.sp, maxLines = 1, softWrap = false)
        InlineDiffAction(PocketIcons.ContentCopy, if (fallback == InlineDiffFallback.Truncated) "复制已收到的差异" else "复制文件差异", copy)
        InlineDiffAction(if (fullScreen) PocketIcons.CloseFullscreen else PocketIcons.OpenInFull,
            if (fullScreen) "返回行内差异" else "放大文件差异", resize)
    }
}

@Composable
internal fun DiffFallbackNotice(fallback: InlineDiffFallback, modifier: Modifier) {
    Box(modifier.padding(16.dp).testTag("inline-diff-fallback:${fallback.name}"), contentAlignment = Alignment.CenterStart) {
        Text(when (fallback) {
            InlineDiffFallback.Empty -> "暂无可显示的差异"
            InlineDiffFallback.Binary -> "二进制文件差异无法按行预览"
            InlineDiffFallback.Truncated -> "原生差异已截断 · 仅显示已收到的内容"
        }, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InlineDiffAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
