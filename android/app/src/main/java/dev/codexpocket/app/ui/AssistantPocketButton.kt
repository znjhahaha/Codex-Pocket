package dev.codexpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.ui.kit.Pocket
import dev.codexpocket.app.ui.kit.pocketMorphSource
import dev.codexpocket.app.ui.kit.pocketPressable

/** The assistant's entry on the home bar. It wears the app's own accent there; green belongs to the assistant's stage. */
@Composable
fun AssistantPocketButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, attention: Boolean = false) {
    val c = Pocket.colors
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.requiredSize(60.dp).pocketMorphSource("assistant", 20.dp).testTag("assistant-pocket-button").shadow(1.dp, shape).clip(shape).background(c.sunken).border(1.dp, c.line, shape)
        .pocketPressable(enabled, Role.Button, onClick = onClick)
        .semantics { contentDescription = if (attention) "打开口袋助理，有待确认事项" else "打开口袋助理" }, contentAlignment = Alignment.Center) {
        AssistantAvatar(Modifier.alpha(if (enabled) 1f else .38f), size = 40.dp, tint = c.accent)
        if (attention) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(10.dp)
            .background(if (enabled) c.accent else c.inkMuted, CircleShape).border(2.dp, c.sunken, CircleShape))
    }
}
