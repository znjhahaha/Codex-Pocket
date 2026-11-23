package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Quiet reading surface, shared by the main transcript and read-only agent reader. */
@Composable
internal fun ToolResultFrame(title: String, copyText: String, wrap: Boolean, onWrap: () -> Unit,
    modifier: Modifier = Modifier, onClose: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Pocket.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember(copyText) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1_400); copied = false } }
    PSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = c.ink.copy(alpha = .035f).compositeOver(c.surface),
        border = BorderStroke(1.dp, c.line.copy(alpha = .65f))) {
        Column(Modifier.testTag("tool-result-frame")) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                PText(title, Modifier.weight(1f), style = Pocket.type.labelSmall,
                    color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.semantics { stateDescription = if (wrap) "自动换行已开启" else "自动换行已关闭" }) {
                    QuietAction(PocketIcons.WrapText, if (wrap) "关闭自动换行" else "开启自动换行", onWrap)
                }
                if (copyText.isNotBlank()) CopyGlyphAction(copied, if (onClose != null) "复制工具详情" else "复制工具内容") {
                    clipboard.setText(AnnotatedString(copyText)); copied = true
                }
                if (onClose != null) QuietAction(PocketIcons.Close, "收起工具详情", onClose)
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}
