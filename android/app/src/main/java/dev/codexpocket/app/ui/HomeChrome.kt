package dev.codexpocket.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.flow.MutableSharedFlow

internal val LocalHomeContentPadding = staticCompositionLocalOf { PaddingValues(0.dp) }
internal val LocalHomeSearchCoordinator = staticCompositionLocalOf<HomeSearchCoordinator?> { null }
@Stable
internal class HomeSearchCoordinator {
    val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    var expanded by mutableStateOf(false)
    fun toggle() { requests.tryEmit(Unit) }
}
private enum class HomeFrameSlot { Header, Dock, Content }

/** Only the content is sampled: the floating controls never capture themselves. */
@Composable
internal fun PocketHomeFrame(s: PocketState, tab: HomeTab, attention: Int, compact: Boolean, hostExpanded: Boolean,
    onHost: () -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onTab: (HomeTab) -> Unit, content: @Composable () -> Unit) {
    val background = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop { drawRect(background); drawContent() }
    val search = remember { HomeSearchCoordinator() }
    CompositionLocalProvider(LocalHomeSearchCoordinator provides search) {
    SubcomposeLayout(Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding()) { constraints ->
        val loose = constraints.copy(minHeight = 0)
        val header = subcompose(HomeFrameSlot.Header) {
            CompositionLocalProvider(LocalPocketBackdrop provides backdrop) {
                HomeHeader(s, tab, compact, hostExpanded, onHost, onNew, onSettings)
            }
        }.map { it.measure(loose) }
        val dock = subcompose(HomeFrameSlot.Dock) {
            CompositionLocalProvider(LocalPocketBackdrop provides backdrop) {
                PocketHomeNavigation(tab, attention, onNew = onNew,
                    canCreate = s.online && s.runtimes.any { it.online && s.supports("thread/start", it) }, onSelect = onTab)
            }
        }.map { it.measure(loose) }
        val top = header.maxOfOrNull { it.height } ?: 0
        val bottom = dock.maxOfOrNull { it.height } ?: 0
        val body = subcompose(HomeFrameSlot.Content) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                CompositionLocalProvider(LocalHomeContentPadding provides PaddingValues(top = top.toDp() + 4.dp, bottom = bottom.toDp() + 16.dp)) { content() }
            }
        }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            body.forEach { it.placeRelative(0, 0) }
            header.forEach { it.placeRelative(0, 0) }
            dock.forEach { it.placeRelative(0, constraints.maxHeight - it.height) }
        }
    }
    }
}

@Composable
internal fun HomeHeader(s: PocketState, tab: HomeTab, compact: Boolean, hostExpanded: Boolean, onHost: () -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val padding by animateDpAsState(if (compact) 4.dp else 8.dp, tween(pocketMotionMillis(220)), label = "home-header-density")
    val arrow by animateFloatAsState(if (hostExpanded) 180f else 0f, tween(pocketMotionMillis(140)), label = "host-chevron")
    val search = LocalHomeSearchCoordinator.current
    val context = LocalContext.current
    val connection = context.stateLabel(if (s.online) "online" else "offline")
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = padding, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PocketGlassSurface(onClick = onHost, shape = PocketOval, modifier = Modifier.weight(1f).testTag("home-host")
                .semantics { contentDescription = "选择电脑：${s.computerName}，$connection"; stateDescription = if (hostExpanded) "已展开" else "已收起" }) {
                // One line: title, then the computer in gray with its connection dot. Less stacked text than before.
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when(tab) { HomeTab.Chats -> "Codex"; HomeTab.Attention -> "待处理"; HomeTab.Devices -> "设备" },
                        Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (s.busy) PocketActivityIndicator("正在处理")
                    else Box(Modifier.size(6.dp).background(if (s.online) Color(0xFF34C759) else MaterialTheme.colorScheme.outline, CircleShape))
                    Text(s.computerName.ifBlank { stringResource(R.string.v03_home_computer) }, Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(PocketIcons.ExpandMore, null, Modifier.size(14.dp).graphicsLayer { rotationZ = arrow }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (tab == HomeTab.Chats && search != null) PocketGlassSurface(onClick = search::toggle, shape = CircleShape, modifier = Modifier.size(48.dp).testTag("home-header-search")
                .semantics { contentDescription = context.getString(if (search.expanded) R.string.v03_home_hide_search else R.string.v03_home_search) }) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(if (search.expanded) PocketIcons.Close else PocketIcons.Search, null, Modifier.size(21.dp)) }
            }
            PocketGlassSurface(onClick = onSettings, shape = CircleShape, modifier = Modifier.size(48.dp).testTag("home-settings")
                .semantics { contentDescription = context.getString(R.string.settings) }) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(PocketIcons.Settings, null, Modifier.size(21.dp)) }
            }
        }
    }
}

/** Navigation holds its geometry while icons respond to selection; motion never changes hit targets. */
@Composable
internal fun PocketHomeNavigation(tab: HomeTab, attention: Int, onNew: (() -> Unit)? = null, canCreate: Boolean = true, onSelect: (HomeTab) -> Unit) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    // Dense: list text scrolls right under the dock and showed through its rounded end as a gray patch.
    PocketGlassSurface(modifier = Modifier.weight(1f).testTag("home-navigation"), shape = PocketOval, dense = true) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(6.dp)) {
            val itemWidth = maxWidth / 3
            val selectionX by animateDpAsState(itemWidth * tab.ordinal, tween(pocketMotionMillis(180), easing = FastOutSlowInEasing), label = "navigation-selection")
            Box {
            Box(Modifier.matchParentSize()) {
                Box(Modifier.offset(x = selectionX).width(itemWidth).fillMaxHeight().clip(PocketOval)
                    // Opaque: a translucent pill let the refracted list text read as a gray smudge at its end.
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f).compositeOver(MaterialTheme.colorScheme.surface)).testTag("home-tab-selection"))
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
                HomeTab.entries.forEach { item ->
                    val selected = tab == item
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (pressed) .96f else if (selected) 1.04f else 1f,
                        pocketPressSpring(), label = "nav-icon-size")
                    val offset by animateDpAsState(if (selected) (-1).dp else 0.dp, tween(pocketMotionMillis(220)), label = "nav-icon-lift")
                    val tint by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        tween(pocketMotionMillis(180)), label = "nav-icon-tint")
                    val label = when(item) { HomeTab.Chats -> "会话"; HomeTab.Attention -> "待处理"; HomeTab.Devices -> "设备" }
                    Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 58.dp).clip(PocketOval)
                        .selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = { onSelect(item) })
                        .semantics { if (item == HomeTab.Attention && attention > 0) stateDescription = "$attention 项待处理" }
                        .testTag("home-tab-${item.name.lowercase()}"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(Modifier.offset(y = offset).graphicsLayer { scaleX = scale; scaleY = scale }) {
                            Icon(when(item) { HomeTab.Chats -> PocketIcons.ChatBubbleOutline; HomeTab.Attention -> PocketIcons.NotificationsNone; HomeTab.Devices -> PocketIcons.Devices }, null, Modifier.size(21.dp), tint = tint)
                            if (item == HomeTab.Attention && attention > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-1).dp).size(6.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
                    }
                }
            }
            }
        }
    }
    if (onNew != null) {
        PocketGlassSurface(onClick = onNew.takeIf { canCreate }, shape = CircleShape, modifier = Modifier.size(60.dp).testTag("home-new-chat").semantics { if (!canCreate) disabled() }) {
            Box(Modifier.fillMaxSize().padding(5.dp).clip(CircleShape)
                .background(if (canCreate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(PocketIcons.Edit, stringResource(R.string.new_chat), Modifier.size(24.dp), tint = if (canCreate) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }
}
