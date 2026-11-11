package dev.codexpocket.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun AssistantPocketButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, attention: Boolean = false) {
    val palette = assistantPalette()
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.requiredSize(60.dp).testTag("assistant-pocket-button").semantics {
            role = Role.Button
            contentDescription = if (attention) "打开口袋助理，有待确认事项" else "打开口袋助理"
        },
        shape = RoundedCornerShape(20.dp),
        color = palette.surface,
        contentColor = palette.accent,
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = 1.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AssistantAvatar(Modifier.alpha(if (enabled) 1f else .38f), size = 40.dp)
            if (attention) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    Box(
                        Modifier.size(10.dp)
                            .background(if (enabled) palette.accent else palette.muted, CircleShape)
                            .border(2.dp, palette.surface, CircleShape),
                    )
                }
            }
        }
    }
}
