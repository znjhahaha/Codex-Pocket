package dev.codexpocket.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.codexpocket.app.ui.kit.pocketSelectable
import kotlin.math.PI
import kotlin.math.sin

/** Creation has its own optics; only the tide artwork is sampled, never the form or text. */
@Composable
internal fun CreationScene(newDirectory: Boolean, content: @Composable BoxScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    val dark = c.background.luminance() < .3f
    val backdrop = rememberLayerBackdrop()
    val tide by animateFloatAsState(if (newDirectory) 1f else 0f, creationTween(640), label = "creation-ambient-tide")
    val preferences = rememberPocketGlassPreferences()
    Box(Modifier.fillMaxSize().background(c.background)) {
        Canvas(Modifier.matchParentSize().layerBackdrop(backdrop)) {
            drawRect(c.background)
            if (!preferences.highContrast) {
                val upper = Offset(size.width * (.82f - tide * .58f), size.height * .17f)
                drawRect(Brush.radialGradient(listOf(Color(0xFF9BAED1).copy(alpha = if (dark) .15f else .22f), Color.Transparent), upper, size.width * .95f))
                drawRect(Brush.radialGradient(listOf(Color(0xFF94BDB7).copy(alpha = if (dark) .13f else .19f), Color.Transparent),
                    Offset(size.width * (.10f + tide * .75f), size.height * .73f), size.width * 1.1f))
                // Broad, quiet waterlines give the clear controls something to refract.
                repeat(3) { index ->
                    val y = size.height * (.68f + index * .085f)
                    val path = Path().apply {
                        moveTo(-size.width * .2f, y)
                        cubicTo(size.width * .22f, y - size.width * (.18f + tide * .1f),
                            size.width * .65f, y + size.width * .23f, size.width * 1.2f, y - size.width * .13f)
                    }
                    drawPath(path, c.onSurface.copy(alpha = if (dark) .035f else .035f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
            }
        }
        CompositionLocalProvider(LocalPocketBackdrop provides backdrop, LocalPocketClearGlass provides true) { content() }
    }
}

@Composable
internal fun <T> creationTween(duration: Int): FiniteAnimationSpec<T> {
    val p = rememberPocketGlassPreferences()
    return if (p.animatorsEnabled && !p.highContrast && !p.batterySaver)
        tween(duration, easing = FastOutSlowInEasing) else snap()
}

/** A clipping edge moves like a tide. It never changes measured height or scroll position. */
internal class CreationTideShape(private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = progress.coerceIn(0f, 1f)
        val y = size.height * (1f - p)
        val swell = size.width * .24f * sin(PI * p).toFloat()
        return Outline.Generic(Path().apply {
            moveTo(0f, y)
            cubicTo(size.width * .28f, y - swell, size.width * .68f, y + swell, size.width, y)
            lineTo(size.width, size.height); lineTo(0f, size.height); close()
        })
    }
}

@Composable
internal fun CreationIconButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    CreationCapsule(onClick, Modifier.size(48.dp).semantics { contentDescription = label }, enabled) {
        Icon(icon, null, Modifier.size(20.dp))
    }
}

/** Native input/accessibility, custom pill optics and a bounded squash on press. */
@Composable
internal fun CreationCapsule(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    primary: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, pocketPressSpring(), label = "creation-pill-press")
    val shape = RoundedCornerShape(50)
    PocketGlassSurface(modifier.heightIn(min = 48.dp)
        .clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .graphicsLayer {
            scaleX = 1f + press * .015f; scaleY = 1f - press * .045f
            alpha = if (enabled) 1f else .46f
        }, shape = shape) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (primary) Modifier.background(c.primary, shape) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides if (primary) c.onPrimary else c.onSurface) { content() }
        }
    }
}

@Composable
internal fun CreationDirectorySwitch(selected: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    val position by animateFloatAsState(if (selected) 1f else 0f, creationTween(380), label = "directory-liquid-pill")
    PocketGlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(50)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(5.dp).selectableGroup()) {
            val half = maxWidth / 2
            val stretch = sin(PI * position).toFloat()
            PocketGlassSurface(Modifier.width(half).height(48.dp).graphicsLayer {
                translationX = half.toPx() * position
                scaleX = 1f + stretch * .13f; scaleY = 1f - stretch * .05f
            }, shape = RoundedCornerShape(50)) {
                Box(Modifier.fillMaxSize().background(c.secondary.copy(alpha = .12f), RoundedCornerShape(50))
                    .border(1.dp, c.secondary.copy(alpha = .18f), RoundedCornerShape(50)))
            }
            Row {
                listOf(false to "已有目录", true to "新建目录").forEach { (value, label) ->
                    Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(50))
                        .pocketSelectable(selected == value, enabled, Role.Tab, pressedScale = .96f, haptic = null) { onSelect(value) },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (value) PocketIcons.Add else PocketIcons.FolderOpen, null, Modifier.size(17.dp),
                            tint = if (selected == value) c.onSurface else c.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 14.sp, fontWeight = if (selected == value) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected == value) c.onSurface else c.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun CreationEmblem(newDirectory: Boolean) {
    val c = MaterialTheme.colorScheme
    val p by animateFloatAsState(if (newDirectory) 1f else 0f, creationTween(420), label = "creation-folder-morph")
    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        PocketGlassSurface(Modifier.size(62.dp).graphicsLayer { rotationZ = -9f + p * 18f }, shape = RoundedCornerShape(22.dp)) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.secondary.copy(alpha = .16f), Color.Transparent))))
        }
        Icon(PocketIcons.FolderOpen, null, Modifier.size(30.dp).graphicsLayer { rotationZ = -4f + p * 8f; translationY = -2.dp.toPx() * sin(PI * p).toFloat() })
        Box(Modifier.align(Alignment.BottomEnd).size(26.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
            Icon(if (newDirectory) PocketIcons.Add else PocketIcons.ArrowForward, null,
                Modifier.size(14.dp).graphicsLayer { rotationZ = p * 90f }, tint = c.onPrimary)
        }
    }
}

@Composable
internal fun CreationFolderBadge(selected: Boolean, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val pulse = remember { Animatable(0f) }
    val motion = rememberPocketGlassPreferences()
    LaunchedEffect(selected) {
        if (selected && motion.animatorsEnabled && !motion.highContrast && !motion.batterySaver) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, spring(dampingRatio = .62f, stiffness = 460f))
        } else pulse.snapTo(0f)
    }
    Box(modifier.size(38.dp).clip(RoundedCornerShape(14.dp))
        .background(if (selected) c.secondary.copy(alpha = .16f) else c.onSurface.copy(alpha = .045f)),
        contentAlignment = Alignment.Center) {
        Icon(PocketIcons.FolderOpen, null, Modifier.size(20.dp).graphicsLayer {
            rotationZ = -pulse.value * 11f; scaleX = 1f + pulse.value * .08f; scaleY = 1f + pulse.value * .08f
        }, tint = if (selected) c.secondary else c.onSurfaceVariant)
    }
}
