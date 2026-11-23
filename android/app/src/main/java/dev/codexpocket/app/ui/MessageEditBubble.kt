package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*

/** Everything a message needs to be edited where it stands in the transcript. */
@Immutable
internal class MessageEditHost(val edit: MessageEditState, val online: Boolean, val running: Boolean,
    val onText: (String) -> Unit, val onSubmit: () -> Unit, val onCancel: () -> Unit, val onCheck: () -> Unit,
    val onPrepare: () -> Unit, val onStop: () -> Unit) {
    fun owns(item: TimelineItem): Boolean = item.type == "userMessage" && item.itemId == edit.itemId && item.turnId == edit.turnId
}

/** The edit in progress in the conversation on screen, if any. The bubble it belongs to turns into its editor. */
internal val LocalMessageEdit = compositionLocalOf<MessageEditHost?> { null }

/**
 * The message's own bubble, now a field: same corner, same place, outlined in the accent. The two
 * round buttons under it cancel and resend; whatever the edit is waiting for is said on the same line.
 * No panel opens and nothing covers the conversation.
 */
@Composable
internal fun MessageEditBubble(host: MessageEditHost, modifier: Modifier = Modifier) {
    val edit = host.edit
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val editable = !edit.busy && !edit.uncertain
    val focus = remember { FocusRequester() }
    // The repository owns the text; the field only adds the caret, which starts at the end.
    var field by remember { mutableStateOf(TextFieldValue(edit.text, TextRange(edit.text.length))) }
    val shown = if (field.text == edit.text) field else field.copy(text = edit.text,
        selection = TextRange(field.selection.start.coerceAtMost(edit.text.length), field.selection.end.coerceAtMost(edit.text.length)))
    SideEffect { if (shown !== field) field = shown }
    var reported by remember(edit.text) { mutableStateOf(edit.text) }
    LaunchedEffect(editable) { if (editable) runCatching { focus.requestFocus() } }
    val outline by animateColorAsState(if (editable) c.accent.copy(alpha = .75f) else c.line, PocketMotion.color<Color>().orSnap(motion), label = "edit-outline")
    // The bubble swells a touch as it opens for editing, from the corner it hangs by.
    val open = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { open.animateTo(1f, PocketSprings.snappy(.34f)) }
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.fillMaxWidth().testTag("message-edit-bubble"), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.widthIn(max = 560.dp).fillMaxWidth()
            .graphicsLayer { val size = .97f + .03f * open.value; scaleX = size; scaleY = size; transformOrigin = TransformOrigin(1f, 0f) }
            .clip(shape).background(c.surface).border(1.5.dp, outline, shape).padding(horizontal = 16.dp, vertical = 11.dp)) {
            BasicTextField(shown, { next -> field = next; val changed = reported != next.text; reported = next.text; if (changed) host.onText(next.text) },
                Modifier.fillMaxWidth().heightIn(max = 220.dp).focusRequester(focus).testTag("message-edit-text")
                    .semantics { contentDescription = "编辑消息" },
                enabled = editable, textStyle = Pocket.type.body.copy(color = if (editable) c.ink else c.inkMuted),
                cursorBrush = SolidColor(c.accent))
        }
        edit.reason?.let { PText(it, Modifier.padding(horizontal = 6.dp), style = Pocket.type.bodySmall, color = c.danger) }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val note = when {
                edit.busy -> when (edit.phase) {
                    MessageEditPhase.Preparing -> "正在核对原消息"
                    MessageEditPhase.Reverting -> "正在撤回会话"
                    else -> "正在提交修改"
                }
                edit.reverted -> "原消息及后续会话已撤回，编辑稿已保留。"
                else -> "重发会撤回此回合及后续会话，不会撤销文件修改。"
            }
            if (edit.busy) PDots(dot = 4.dp)
            PSwapText(listOfNotNull(note, "保留 ${edit.retainedItems} 个原附件或引用".takeIf { edit.retainedItems > 0 && !edit.busy }).joinToString(" "),
                Modifier.weight(1f).testTag("message-edit-note"), style = Pocket.type.caption, color = c.inkMuted, maxLines = 2)
            when {
                edit.busy -> Unit
                edit.uncertain -> PTextAction("核对操作结果", host.onCheck, enabled = host.online)
                host.running -> PTextAction("先停止当前任务", host.onStop, enabled = host.online)
                edit.phase == MessageEditPhase.Unavailable || edit.token.isBlank() -> PTextAction("重新读取消息", host.onPrepare, enabled = host.online)
            }
            if (!edit.busy) EditRound(PocketIcons.Close, "取消编辑并恢复草稿", true, c.fillStrong, c.ink, host.onCancel, Modifier.testTag("message-edit-cancel"))
            val ready = editable && !host.running && edit.phase != MessageEditPhase.Unavailable && edit.token.isNotBlank()
            if (ready) {
                val can = host.online && edit.canSubmit
                EditRound(PocketIcons.ArrowUpward, if (edit.reverted) "发送修改后的消息" else "撤回并重新发送", can,
                    if (can) c.solid else c.fillStrong, if (can) c.onSolid else c.inkFaint, host.onSubmit, Modifier.testTag("message-edit-submit"),
                    haptic = PocketHaptic.Confirm)
            }
        }
    }
}

/** A 36dp disc in a 44dp target; its icon answers the tap. */
@Composable
private fun EditRound(icon: ImageVector, label: String, enabled: Boolean, fill: Color, ink: Color, onClick: () -> Unit,
    modifier: Modifier = Modifier, haptic: PocketHaptic? = PocketHaptic.Tap) {
    val taps = rememberPTaps()
    val motion = rememberPocketMotion()
    val shade by animateColorAsState(fill, PocketMotion.color<Color>().orSnap(motion), label = "edit-round")
    Box(modifier.size(44.dp).clip(CircleShape).pocketPressable(enabled, Role.Button, pressedScale = .9f, haptic = haptic, onClick = taps.then(onClick))
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).background(shade, CircleShape), contentAlignment = Alignment.Center) {
            PTapIcon(icon, taps.count, Modifier.size(18.dp), tint = ink)
        }
    }
}
