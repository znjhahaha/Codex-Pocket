package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.ChatEntity
import dev.codexpocket.app.data.PocketState

@Composable
internal fun ChatStatusLabel(state: PocketState, chat: ChatEntity) {
    val status = chatStatus(state, chat)
    val color = when (status) {
        "failed", "systemError" -> Pocket.colors.danger
        "pending", "unknown" -> Pocket.colors.accent
        "active", "inProgress", "running" -> Pocket.colors.solid
        else -> Pocket.colors.inkMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        PText(LocalContext.current.stateLabel(status), style = Pocket.type.labelSmall, color = color)
    }
}
