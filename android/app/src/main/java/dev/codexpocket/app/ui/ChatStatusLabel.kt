package dev.codexpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
        "failed", "systemError" -> MaterialTheme.colorScheme.error
        "pending", "unknown" -> MaterialTheme.colorScheme.tertiary
        "active", "inProgress", "running" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(LocalContext.current.stateLabel(status), style = MaterialTheme.typography.labelMedium, color = color)
    }
}
