package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.ConversationErrorPresentation

/** Compact native error, shared with the agent reader. Expanding never triggers a resend. */
@Composable
internal fun ConversationErrorRow(error: ConversationErrorPresentation, identity: String,
    modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    var expanded by rememberSaveable(identity) { mutableStateOf(false) }
    val colors = Pocket.colors
    val clipboard = LocalClipboardManager.current
    val angle by animateFloatAsState(if (expanded) 180f else 0f, pocketPressSpring(), label = "error-chevron")
    val interaction = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth().testTag("conversation-error:$identity")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 32.dp)
                .testTag("error-toggle:$identity")
                .semantics { stateDescription = if (expanded) "错误详情已展开" else "错误详情已折叠" }
                .clickable(interactionSource = interaction, indication = null, role = Role.Button) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PIcon(if (error.retrying) PocketIcons.Wifi else PocketIcons.ErrorOutline, null,
                    Modifier.size(16.dp), tint = if (error.retrying) colors.inkMuted else colors.danger.copy(alpha = .8f))
                PText(error.title, Modifier.weight(1f, fill = false), style = Pocket.type.bodySmall,
                    color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PIcon(PocketIcons.ExpandMore, null, Modifier.size(16.dp).graphicsLayer { rotationZ = angle }, tint = colors.inkMuted)
            }
            if (action != null) PocketTextButton(onClick = onAction) { PText(action) }
        }
        PText(error.summary, Modifier.padding(start = 22.dp, bottom = 4.dp), style = Pocket.type.bodySmall,
            color = colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (expanded) PSurface(Modifier.fillMaxWidth().padding(start = 22.dp, top = 4.dp).heightIn(max = 260.dp).testTag("error-detail:$identity"),
            shape = RoundedCornerShape(10.dp), color = colors.sunken,
            border = BorderStroke(1.dp, colors.line.copy(alpha = .45f))) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    PText("错误详情", Modifier.weight(1f), style = Pocket.type.labelSmall, color = colors.inkMuted)
                    CopyAction(error.detail) { clipboard.setText(AnnotatedString(error.detail)) }
                }
                val shown = remember(error.detail) { error.detail.take(16_384) }
                Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        PText(shown, Modifier.fillMaxWidth().padding(10.dp), style = Pocket.type.bodySmall,
                            fontFamily = FontFamily.Monospace, color = colors.inkMuted)
                    }
                    if (shown.length < error.detail.length) PText("详情较长，仅预览前 16K 字符；复制可获取完整内容。",
                        Modifier.padding(10.dp), style = Pocket.type.caption, color = colors.inkMuted)
                }
            }
        }
    }
}
