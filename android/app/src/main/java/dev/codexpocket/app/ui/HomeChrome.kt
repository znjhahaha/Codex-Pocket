package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
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
import androidx.compose.ui.unit.Dp
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

/** Home's own moving glyphs, on the kit's grid. Each rests in the pose of the static icon it replaces. */
internal object HomeGlyphs {
    /** The bubble hops while its two lines are written again. */
    val Chat = MotionGlyph { t ->
        val first = 1f - .75f * GlyphMotion.pulse(GlyphMotion.window(t, 0f, .7f))
        val second = 1f - .75f * GlyphMotion.pulse(GlyphMotion.window(t, .25f, 1f))
        move(0f, -1.2f * GlyphMotion.pulse(t)) {
            path("M7 4.5h10a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3H10l-5.5 3V7.5a3 3 0 0 1 3-3")
            line(8.5f, 9f, 8.5f + 7f * first, 9f); line(8.5f, 13f, 8.5f + 4.5f * second, 13f)
        }
    }
    /** The phone steps out from the monitor and back. */
    val Devices = MotionGlyph { t ->
        val step = GlyphMotion.pulse(t)
        path("M12.5 16H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v2M8 16v4M5.5 20h6")
        move(1.1f * step, -1.1f * step) { path("M16 10h4a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1M17.5 18.5h1") }
    }
    /** 0 is search, 1 is close: the handle grows into one stroke of the cross as the lens gives way to the other. */
    val SearchClose = MotionGlyph { t ->
        val lens = 1f - GlyphMotion.window(t, 0f, .55f)
        val cross = GlyphMotion.window(t, .3f, 1f)
        line(15f - 8.5f * cross, 15f - 8.5f * cross, 20.5f - 3f * cross, 20.5f - 3f * cross)
        if (lens > 0f) scale(.4f + .6f * lens, 10f, 10f) { circle(10f, 10f, 6.5f, lens) }
        if (cross > 0f) line(17.5f, 6.5f, 17.5f - 11f * cross, 6.5f + 11f * cross, cross)
    }
    fun tab(tab: HomeTab): MotionGlyph = when (tab) { HomeTab.Chats -> Chat; HomeTab.Attention -> MotionGlyphs.Bell; HomeTab.Devices -> Devices }
}

/** Only the content is sampled: the floating controls never capture themselves. */
@Composable
internal fun PocketHomeFrame(s: PocketState, tab: HomeTab, attention: Int, compact: Boolean, hostExpanded: Boolean,
    onHost: () -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onTab: (HomeTab) -> Unit, onAssistant: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val background = Pocket.colors.bg
    val backdrop = rememberLayerBackdrop { drawRect(background); drawContent() }
    val search = remember { HomeSearchCoordinator() }
    CompositionLocalProvider(LocalHomeSearchCoordinator provides search) {
    SubcomposeLayout(Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding()) { constraints ->
        val loose = constraints.copy(minHeight = 0)
        val header = subcompose(HomeFrameSlot.Header) {
            CompositionLocalProvider(LocalPocketBackdrop provides backdrop, LocalPocketClearGlass provides true) {
                HomeHeader(s, tab, compact, hostExpanded, onHost, onNew, onSettings)
            }
        }.map { it.measure(loose) }
        val dock = subcompose(HomeFrameSlot.Dock) {
            CompositionLocalProvider(LocalPocketBackdrop provides backdrop) {
                PocketHomeNavigation(tab, attention,
                    canCreate = s.online && (s.supports(BrokerCapability.EnsureRuntime) || s.runtimes.any { it.online && s.supports("thread/start", it) }), onAssistant = onAssistant, onSelect = onTab)
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
    val motion = rememberPocketMotion()
    val arrow by animateFloatAsState(if (hostExpanded) 180f else 0f, PocketMotion.glyph<Float>().orSnap(motion), label = "host-chevron")
    val search = LocalHomeSearchCoordinator.current
    val context = LocalContext.current
    val restoring = s.restoringConnection
    val connection = if (restoring) "正在恢复连接" else context.stateLabel(if (s.online) "online" else "offline")
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = padding, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PocketGlassSurface(onClick = onHost, shape = PocketOval, modifier = Modifier.weight(1f).testTag("home-host")
                .semantics { contentDescription = "选择电脑：${s.computerName}，$connection"; stateDescription = if (hostExpanded) "已展开" else "已收起" }) {
                // One line: title, then the computer in gray with its connection dot. Less stacked text than before.
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PocketDestinationTitle(when(tab) { HomeTab.Chats -> "Codex"; HomeTab.Attention -> "待处理"; HomeTab.Devices -> "管理" },
                        Modifier.semantics { heading() })
                    Box(Modifier.width(26.dp).height(18.dp), contentAlignment = Alignment.Center) {
                        if (restoring) ActivityMotionIcon(ActivityMotion.Connection,true,Modifier.size(17.dp).testTag("home-reconnecting").semantics { contentDescription = "正在恢复连接" })
                        else Box(Modifier.size(6.dp).testTag("home-connectivity").background(if (s.online) Color(0xFF34C759) else Pocket.colors.inkFaint, CircleShape))
                    }
                    PText(s.computerName.ifBlank { stringResource(R.string.v03_home_computer) }, Modifier.weight(1f),
                        style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    PIcon(PocketIcons.ExpandMore, null, Modifier.size(14.dp).graphicsLayer { rotationZ = arrow }, tint = Pocket.colors.inkMuted)
                }
            }
            if (tab == HomeTab.Chats && search != null) PocketGlassSurface(onClick = search::toggle, shape = CircleShape, modifier = Modifier.size(48.dp).testTag("home-header-search")
                .semantics { contentDescription = context.getString(if (search.expanded) R.string.v03_home_hide_search else R.string.v03_home_search) }) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PToggleGlyph(HomeGlyphs.SearchClose, search.expanded, Modifier.size(21.dp), tint = Pocket.ink) }
            }
            val canCreate = s.online && (s.supports(BrokerCapability.EnsureRuntime) || s.runtimes.any { it.online && s.supports("thread/start", it) })
            PocketGlassSurface(onClick = onNew.takeIf { canCreate }, shape = CircleShape, modifier = Modifier.size(48.dp).testTag("home-new-chat")
                .semantics { contentDescription = context.getString(R.string.new_chat); if (!canCreate) disabled() }) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF222222)), contentAlignment = Alignment.Center) {
                    PIcon(PocketIcons.Edit, null, Modifier.size(21.dp), tint = Color.White.copy(alpha = if (canCreate) 1f else .4f))
                }
            }
        }
    }
}

/** Navigation holds its geometry while icons respond to selection; motion never changes hit targets. */
@Composable
internal fun PocketHomeNavigation(tab: HomeTab, attention: Int, onNew: (() -> Unit)? = null, canCreate: Boolean = true, onAssistant: (() -> Unit)? = null, onSelect: (HomeTab) -> Unit) {
    val motion = rememberPocketMotion()
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = if (onAssistant == null) 88.dp else 18.dp, top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    // Dense: list text scrolls right under the dock and showed through its rounded end as a gray patch.
    PocketGlassSurface(modifier = Modifier.weight(1f).testTag("home-navigation"), shape = PocketOval, dense = true) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(6.dp)) {
            val itemWidth = maxWidth / 3
            // The kit's sliding selection: the quick edge leads and the slow one follows, so the pill stretches as it
            // travels. A finger can pick the pill up anywhere on the bar and carry it to another tab.
            val tabs = HomeTab.entries
            val slide = rememberPSlide(tabs.size, tab.ordinal) { onSelect(tabs[it]) }
            Box(Modifier.pocketSlide(slide)) {
            Box(Modifier.matchParentSize()) {
                val left = slide.start.value.coerceIn(0f, tabs.size.toFloat())
                val right = slide.end.value.coerceIn(left, tabs.size.toFloat())
                // Carried, the pill lifts a little off the bar.
                Box(Modifier.offset(x = itemWidth * left).width(itemWidth * (right - left)).fillMaxHeight()
                    .graphicsLayer { val lifted = 1f + .05f * slide.lift.value; scaleX = lifted; scaleY = lifted }.clip(PocketOval)
                    // Opaque: a translucent pill let the refracted list text read as a gray smudge at its end.
                    .background(Pocket.colors.accentSoft.copy(alpha = .65f).compositeOver(Pocket.colors.surface)).testTag("home-tab-selection"))
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
                HomeTab.entries.forEach { item ->
                    val selected = tab == item
                    // The tab the pill is over looks chosen while it is carried; it becomes the selection on release.
                    val shown = slide.shown == item.ordinal
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (pressed) .96f else if (shown) 1.04f else 1f,
                        pocketPressSpring(), label = "nav-icon-size")
                    val offset by animateDpAsState(if (shown) (-1).dp else 0.dp, tween(pocketMotionMillis(220)), label = "nav-icon-lift")
                    val tint by animateColorAsState(if (shown) Pocket.colors.onAccentSoft else Pocket.colors.inkMuted,
                        tween(pocketMotionMillis(180)), label = "nav-icon-tint")
                    val label = when(item) { HomeTab.Chats -> "会话"; HomeTab.Attention -> "待处理"; HomeTab.Devices -> "管理" }
                    val play = rememberGlyphPulse(shown)
                    Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 58.dp).clip(PocketOval)
                        .selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = { onSelect(item) })
                        .semantics { if (item == HomeTab.Attention && attention > 0) stateDescription = "$attention 项待处理" }
                        .testTag("home-tab-${item.name.lowercase()}"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(Modifier.offset(y = offset).graphicsLayer { scaleX = scale; scaleY = scale }) {
                            // Only the tab being entered plays; the one left behind returns to rest at once.
                            MotionIcon(HomeGlyphs.tab(item), { if (shown) play.value else 1f }, Modifier.size(21.dp), tint)
                            if (item == HomeTab.Attention && attention > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-1).dp).size(6.dp).background(Pocket.colors.accent, CircleShape))
                        }
                        Spacer(Modifier.height(2.dp))
                        PocketTabLabel(label, shown, color = tint)
                    }
                }
            }
            }
        }
    }
    if (onAssistant != null) AssistantPocketButton(onAssistant)
    else if (onNew != null) {
        PocketGlassSurface(onClick = onNew.takeIf { canCreate }, shape = CircleShape, modifier = Modifier.size(60.dp).testTag("home-new-chat").semantics { if (!canCreate) disabled() }) {
            Box(Modifier.fillMaxSize().padding(5.dp).clip(CircleShape)
                .background(if (canCreate) Pocket.colors.solid else Pocket.colors.fillStrong), contentAlignment = Alignment.Center) {
                PIcon(PocketIcons.Edit, stringResource(R.string.new_chat), Modifier.size(24.dp), tint = if (canCreate) Pocket.colors.onSolid else Pocket.colors.inkMuted)
            }
        }
    }
    }
}
