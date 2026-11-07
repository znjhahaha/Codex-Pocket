package dev.codexpocket.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*

@Composable
internal fun ReadingPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (selected) colors.primary else colors.surfaceContainerLow, tween(pocketMotionMillis(120)), label = "reading-mode")
    Box(Modifier.heightIn(min = 48.dp).clip(PocketOval).background(fill).clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) colors.onPrimary else colors.onSurface, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun ReadingCommentDialog(path: String, line: Int, side: ReviewSide, onDismiss: () -> Unit, onDraft: (FileCommentDraft) -> Unit) {
    var text by remember(path, line, side) { mutableStateOf("") }
    PocketDialog(onDismissRequest = onDismiss, title = { Text("添加到草稿") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$path · ${if (side == ReviewSide.OLD) "旧侧" else "新侧"} $line", style = MaterialTheme.typography.bodySmall)
            PocketTextField(text, { text = it }, label = "评论", minLines = 3, maxLines = 6)
            Text("仅加入当前草稿，由你确认发送。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        PocketButton("加入草稿", { onDraft(FileCommentDraft(path, line, side, text.trim())); onDismiss() }, enabled = text.isNotBlank())
    }, dismissButton = { PocketButton("取消", onDismiss, secondary = true) })
}

internal fun deliverReadingDraft(draft: FileCommentDraft, typed: ((FileCommentDraft) -> Unit)?, legacy: (String, Int, String) -> Unit) {
    if (typed != null) typed(draft) else legacy(draft.path, draft.line, "[${draft.side.name} ${draft.path}:${draft.line}]\n${draft.text}")
}
