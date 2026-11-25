package dev.codexpocket.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlin.math.abs

/** One dock entry and one retained role-aware panel. The dock places its ball above the IME. */
@Composable
internal fun AssistantModelControls(state: AssistantUiState, models: List<NativeModel>, connected: Boolean, visible: Boolean,
    act: (() -> AssistantAction) -> Unit) {
    val snapshot = state.snapshot
    val scopeId = snapshot?.scopeId ?: snapshot?.runtimeId.orEmpty()
    var opened by rememberSaveable(state.computerId, scopeId) { mutableStateOf(false) }
    var role by rememberSaveable(state.computerId, scopeId) { mutableIntStateOf(0) }
    var collapsed by rememberSaveable(state.computerId, scopeId) { mutableStateOf(false) }
    var chiefQuery by rememberSaveable(state.computerId, scopeId) { mutableStateOf("") }
    var workQuery by rememberSaveable(state.computerId, scopeId) { mutableStateOf("") }
    var browseChief by rememberSaveable(state.computerId, scopeId) { mutableStateOf(false) }
    var browseWork by rememberSaveable(state.computerId, scopeId) { mutableStateOf(false) }
    val chiefList = rememberLazyListState()
    val workList = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val anchor = rememberPAnchor()
    LaunchedEffect(opened, visible) {
        if (visible && opened && connected) act { AssistantAction("assistant/read", buildJsonObject { put("models", true) }) }
    }
    val enabled = connected && scopeId.isNotBlank() && !state.busy("assistant/settings/update") &&
        snapshot?.errorCode != "assistant_unavailable_on_device"
    Column(Modifier.fillMaxWidth()) {
        if (visible && collapsed && opened) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            PGlassIconButton(PocketIcons.Search, "恢复模型面板", { collapsed = false }, Modifier.testTag("assistant-model-ball").pocketBubblePop())
        }
        PTextAction("选择模型", { opened = true; collapsed = false },
            Modifier.pocketAnchor(anchor).testTag("assistant-model-open"), enabled = enabled)
    }
    if (!visible || !opened || collapsed) return
    val coordinator = role == 0
    val settings = snapshot?.settings
    val selected = if (coordinator) settings?.coordinatorModel else settings?.model
    val effective = selected ?: if (coordinator) settings?.model else null
    val model = models.firstOrNull { it.model == effective } ?: models.firstOrNull { effective == null && it.isDefault }
    val explicitEffort = if (coordinator) settings?.coordinatorEffort else settings?.effort
    val effectiveEffort = explicitEffort ?: if (coordinator) settings?.effort else null
    val query = if (coordinator) chiefQuery else workQuery
    val list = if (coordinator) chiefList else workList
    val browsing = (if (coordinator) browseChief else browseWork) || model == null
    fun browse(value: Boolean) { if (coordinator) browseChief = value else browseWork = value }
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    val listPull = remember(role, list, threshold) { object : NestedScrollConnection {
        var pull = 0f
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0f && !list.canScrollBackward) {
                pull += available.y
                if (pull >= threshold) { pull = 0f; collapsed = true }
                return Offset(0f, available.y)
            }
            pull = 0f
            return Offset.Zero
        }
        override suspend fun onPreFling(available: Velocity): Velocity { pull = 0f; return Velocity.Zero }
    } }
    val catalog = remember(models, selected) {
        if (selected == null || models.any { it.model == selected }) models
        else listOf(NativeModel(selected, selected, "$selected（已保存）", "", emptyList(), "", false, false)) + models
    }
    PocketModal("选择模型", { opened = false }, sheet = false, anchor = anchor.bounds, anchored = true, menuWidth = 320f, below = false) {
        Column(Modifier.weight(1f, fill = false).padding(6.dp).fillMaxWidth().testTag("assistant-model-panel")
            .onGloballyPositioned { panelOrigin = it.boundsInRoot().topLeft }
            .pointerInput(role, list, browsing, threshold) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // A list drag stays scrolling; only unconsumed pull at its top
                    // reaches listPull below and can collapse the panel.
                    val inList = browsing && listBounds.contains(down.position + panelOrigin)
                    val eligible = !inList || !list.canScrollBackward
                    var claimed = false
                    var directionChosen = false
                    var finished = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val travel = change.position - down.position
                        if (!directionChosen && maxOf(abs(travel.x), abs(travel.y)) > viewConfiguration.touchSlop) {
                            directionChosen = true
                            claimed = eligible && travel.y > abs(travel.x)
                        }
                        if (claimed) {
                            change.consume()
                            if (travel.y >= threshold && !finished) { finished = true; collapsed = true }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("assistant-model-handle"), verticalAlignment = Alignment.CenterVertically) {
                PSegmented(listOf("助理", "工作"), role, { role = it }, Modifier.weight(1f).testTag("assistant-model-role"), itemHeight = 32.dp)
                PIconButton(PocketIcons.ExpandMore, "收起为小球", { collapsed = true }, Modifier.testTag("assistant-model-collapse"))
            }
            if (!browsing) {
                PRow(model.name.ifBlank { model.model }, { browse(true) }, Modifier.testTag("assistant-model-choose"))
                val choices = supportedEffortChoices(model.efforts)
                val shown = effectiveEffort ?: model.defaultEffort.takeIf { it in choices }
                if (choices.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        PText("思考强度 · ${shown ?: "默认"}", Modifier.weight(1f).padding(start = 12.dp), style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted)
                        PTextAction(if (coordinator) "跟随工作" else "默认", { act { AssistantActions.effort(scopeId, coordinator, null) } },
                            Modifier.testTag("assistant-effort-inherit"), enabled = enabled)
                    }
                    EffortTrack(choices, shown, shown in choices, enabled, shown ?: "默认") { value ->
                        act { AssistantActions.effort(scopeId, coordinator, value) }
                    }
                }
            } else {
                ModelCatalogChoices(catalog, selected, query, { value ->
                    if (coordinator) chiefQuery = value else workQuery = value
                    scope.launch { list.scrollToItem(0) }
                }, list, onSelect = { chosen ->
                    if (enabled) { act {
                        val selection = AssistantActions.model(scopeId, coordinator, chosen.model)
                        if (explicitEffort == null || explicitEffort in chosen.efforts) selection
                        else selection.copy(params = JsonObject(selection.params + ("patch" to JsonObject(selection.params.obj("patch") +
                            ((if (coordinator) "coordinator_effort" else "effort") to JsonNull)))))
                    }; browse(false) }
                }, listModifier = Modifier.weight(1f, fill = false).nestedScroll(listPull).onGloballyPositioned { listBounds = it.boundsInRoot() }, leading = {
                    PRow(if (coordinator) "跟随工作设置" else "跟随运行入口默认", {
                        if (enabled) { act { AssistantActions.model(scopeId, coordinator, null) }; browse(false) }
                    }, Modifier.testTag("assistant-model-inherit"), selected = selected == null)
                })
            }
            state.modelError?.let { PText(it, Modifier.padding(horizontal = 12.dp), style = Pocket.type.bodySmall, color = Pocket.colors.warning) }
            PTextAction(if (state.busy("assistant/read")) "正在读取目录" else "重新读取目录", {
                act { AssistantAction("assistant/read", buildJsonObject { put("models", true) }) }
            }, Modifier.testTag("assistant-model-retry"), enabled = connected && !state.busy("assistant/read"), tint = Pocket.colors.inkMuted)
        }
    }
}
