package dev.codexpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import dev.codexpocket.wire.ExecutionTargetKind
import java.time.LocalDate
import java.time.ZoneId

internal fun usageAppLabel(app: String) = when (app) { "codex" -> "Codex"; "claude" -> "Claude"; "gemini" -> "Gemini"; else -> "Grok Build" }

/** Where the figures come from and the one way to read them again. Glass, because it floats over the list. */
@Composable
internal fun UsageSourceCapsule(busy: Boolean, canRefresh: Boolean, onRefresh: () -> Unit, sources: List<UsageSourceKind> = listOf(UsageSourceKind.CcSwitch),
    current: UsageSourceKind = UsageSourceKind.CcSwitch, onSource: (UsageSourceKind) -> Unit = {}, modifier: Modifier = Modifier) {
    val c = Pocket.colors
    val width = rememberUsageTextWidth()
    // The figures belong to the selected execution location; where the app can switch it, this is the switch.
    val target = LocalExecutionLocation.current
    val choose = LocalExecutionLocationPicker.current
    val place = target?.label ?: "这台电脑"
    val source = current.label
    // Two ledgers that are never added together: choosing one replaces the figures, it does not filter them.
    val choice = sources.size > 1
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // One row takes both labels, the refresh target, the hairlines and the gaps; the glyph and the wider gaps only when there is room for them too.
        val labels = width(place, Pocket.type.label) + width(source, Pocket.type.labelSmall) + if (choose != null) 20.dp else 0.dp
        val roomy = labels + 156.dp <= maxWidth
        // Text too large for one row puts the source under the location instead of cutting either short.
        val stacked = choice || labels + 110.dp > maxWidth
        val location = @Composable { part: Modifier ->
            val press = if (choose == null) Modifier else Modifier.heightIn(min = 48.dp).clip(PocketRadii.capsule)
                .pocketPressable(onClickLabel = "切换执行位置", haptic = PocketHaptic.Tap, onClick = choose).testTag("usage-location")
            Row(part.then(press).semantics(mergeDescendants = true) { contentDescription = "执行位置：$place" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (roomy) PIcon(if (target?.kind == ExecutionTargetKind.LinuxSsh) PocketIcons.Terminal else PocketIcons.Computer, null, Modifier.size(18.dp), tint = c.inkMuted)
                PText(place, Modifier.weight(1f, fill = false), style = Pocket.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (choose != null) PIcon(PocketIcons.ExpandMore, null, Modifier.size(16.dp), tint = c.inkMuted)
            }
        }
        // The only source this state offers: shown as the selected one, with nothing to switch to.
        val chosen = @Composable {
            if (choice) UsageSegmented(sources.map { UsageSegment(it.label, "usage-source:${it.wire}") }, sources.indexOf(current),
                { onSource(sources[it]) }, Modifier.padding(end = 14.dp).semantics { contentDescription = "数据来源" })
            else Box(Modifier.clip(PocketRadii.capsule).background(c.accentSoft).testTag("usage-source:${current.wire}")
                .semantics(mergeDescendants = true) { contentDescription = "数据来源 $source"; selected = true }
                .padding(horizontal = if (roomy) 11.dp else 9.dp, vertical = 5.dp)) {
                PText(source, style = Pocket.type.labelSmall, color = c.onAccentSoft, maxLines = 1, softWrap = false)
            }
        }
        val refresh = @Composable {
            PIconButton(PocketIcons.Refresh, "刷新", onRefresh, Modifier.testTag("usage-refresh").semantics { if (busy) stateDescription = "正在刷新" },
                enabled = canRefresh, haptic = PocketHaptic.Tap) {
                PLoopGlyph(MotionGlyphs.RefreshSpin, busy, tint = if (busy) c.accent else Color.Unspecified)
            }
        }
        PocketGlassSurface(Modifier.fillMaxWidth().testTag("usage-source"), if (stacked) PocketRadii.panel else PocketRadii.capsule, dense = true) {
            ProvidePInk(c.ink) {
                if (stacked) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 2.dp, bottom = 10.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        location(Modifier.weight(1f)); refresh()
                    }
                    chosen()
                } else Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (roomy) 10.dp else 6.dp)) {
                    location(Modifier.weight(1f))
                    PVerticalDivider(Modifier.height(20.dp))
                    chosen()
                    PVerticalDivider(Modifier.height(20.dp))
                    refresh()
                }
            }
        }
    }
}

/** The date range as one tap, everything else behind the filter sheet, and what is applied in words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UsageFilterBar(f: UsageFilter, onOpen: () -> Unit, onFilter: (UsageFilter) -> Unit) {
    val active = listOfNotNull(
        f.status.takeUnless { it == UsageStatusFilter.All }?.let { "状态：${it.label}" },
        f.providerId?.let { "上游：${usageShortId(it)}" },
        f.model?.let { "模型：$it" },
        f.sessionId?.let { "对话：${usageShortId(it)}" },
    )
    val presets = listOf(1 to "今天", 7 to "7 天", 30 to "30 天")
    val today = LocalDate.now(ZoneId.of(f.zoneId))
    val preset = presets.indexOfFirst { (days, _) -> f.endDate == today && f.startDate == today.minusDays(days - 1L) }
    val segments = presets.map { (days, label) -> UsageSegment(label, "usage-date:$days") } + UsageSegment("自定义", "usage-filter-open", haptic = null)
    val range = if (f.startDate == f.endDate) f.endDate.toString().takeLast(5) else "${f.startDate.toString().takeLast(5)} – ${f.endDate.toString().takeLast(5)}"
    val scope = "$range · ${usageAppLabel(f.appType)} · ${if (f.source == UsageSourceKind.NativeCodex) "原生会话" else f.dataSource.label}"
    val dates = @Composable { modifier: Modifier ->
        UsageSegmented(segments, if (preset >= 0) preset else presets.size, { index ->
            if (index == presets.size) onOpen()
            else LocalDate.now(ZoneId.of(f.zoneId)).let { now -> onFilter(f.withDates(now.minusDays(presets[index].first - 1L), now)) }
        }, modifier)
    }
    val filter = if (active.isEmpty()) "筛选" else "筛选 · ${active.size}"
    val filters = @Composable {
        UsageChipButton(filter, onOpen, Modifier.testTag("usage-filters-button"), active = active.isNotEmpty(), leading = PocketIcons.Tune)
    }
    val width = rememberUsageTextWidth()
    val label = Pocket.type.labelSmall
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Four dates and the filter share a row only while every date keeps a 48dp target and its whole label beside the filter's glyph, label and padding.
        val each = (maxWidth - 58.dp - width(filter, label)) / segments.size
        val oneRow = each >= PocketSpace.touch && segments.all { width(it.label, label) + 12.dp <= each }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The page's name and what it currently covers share one line under the controls.
            if (oneRow) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    dates(Modifier.weight(1f)); filters()
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    UsageHeading("调用统计")
                    UsageNote(scope, Modifier.weight(1f).testTag("usage-scope"), textAlign = TextAlign.End)
                }
            } else {
                dates(Modifier)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    UsageHeading("调用统计", Modifier.weight(1f).padding(horizontal = 4.dp))
                    filters()
                }
                UsageNote(scope, Modifier.padding(horizontal = 4.dp).testTag("usage-scope"))
            }
            if (active.isNotEmpty()) FlowRow(Modifier.testTag("usage-active-filters"), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) { active.forEach { PBadge(it, tone = PTone.Accent) } }
        }
    }
}

private fun usagePhaseCopy(phase: UsagePhase, hasData: Boolean): Pair<String, String> = when (phase) {
    UsagePhase.NotConnected -> "用量尚未接入" to "连接执行位置后，可读取该位置的用量。"
    UsagePhase.Idle -> "等待读取用量" to "刷新后读取当前筛选范围。"
    UsagePhase.Loading -> "正在读取用量" to "正在读取所选日期的记录。"
    UsagePhase.Ready -> if (hasData) "显示上次统计" to "本次未能更新，数值来自上次成功读取。" else "等待读取用量" to "刷新后读取当前筛选范围。"
    UsagePhase.Offline -> "电脑已离线" to "请切换到「电脑连接」重新连接，然后刷新。"
    UsagePhase.Missing -> "尚未找到 CC Switch 用量" to "当前执行位置没有 CC Switch 请求记录。"
    UsagePhase.Locked -> "用量数据库暂时被锁定" to "稍后刷新重试。"
    UsagePhase.UnsupportedSchema -> "暂不支持当前用量格式" to "CC Switch 的记录格式与当前读取器不兼容。原始记录仍保留。"
    UsagePhase.Corrupt -> "CC Switch 数据库无法打开" to "数据库文件损坏或不是有效的 SQLite 数据库，请在 CC Switch 中检查或恢复备份。"
    UsagePhase.UnsupportedHelper -> "电脑端尚不支持用量读取" to "更新电脑端 helper 后重新连接。"
    UsagePhase.InvalidPath -> "用量数据库路径不可安全读取" to "请在电脑端检查默认用量数据库文件。"
    UsagePhase.Rejected -> "统计请求被拒绝" to "缩小日期范围或检查筛选条件后刷新。"
    UsagePhase.Failed -> "用量读取失败" to "确认电脑端正常运行，然后刷新重试。"
}
private fun usagePhaseTone(phase: UsagePhase) = when (phase) {
    UsagePhase.NotConnected, UsagePhase.Idle, UsagePhase.Loading -> PTone.Neutral
    UsagePhase.Ready, UsagePhase.Offline, UsagePhase.Locked, UsagePhase.Missing -> PTone.Warning
    else -> PTone.Danger
}

@Composable
private fun UsageDiagnostics(state: UsageScreenState, modifier: Modifier = Modifier) {
    Column(modifier.testTag("usage-diagnostics-detail"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        state.observedSchemaVersion?.let { UsageNote("CC Switch 数据格式：$it") }
        state.failureCode?.let { UsageNote("读取状态：$it") }
    }
}

/**
 * The one place that says how current the figures are. Fresh and refreshing are a single quiet line;
 * figures kept from an earlier read say why, when they were taken, and offer the retry.
 */
@Composable
internal fun UsageStatusLine(state: UsageScreenState, snapshot: UsageSnapshot?, onRefresh: () -> Unit) {
    val c = Pocket.colors
    val zone = state.filter.zoneId
    val time = snapshot?.let { it.response.cache?.sampledAt ?: it.updatedAt }
    val stale = state.cached || snapshot?.response?.cache?.state == "stale"
    val quiet: Pair<String, Color>? = when {
        time == null -> "正在读取用量 · 正在读取所选日期的记录。" to c.accent
        state.refreshing || state.phase == UsagePhase.Loading -> "正在刷新 · 上次更新" to c.accent
        state.phase == UsagePhase.Ready && !stale -> "已更新" to c.positive
        else -> null
    }
    if (quiet != null) {
        val text = if (time == null) quiet.first else "${quiet.first} · ${usageTime(time, zone).substring(5, 16)}"
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("usage-status").semantics(mergeDescendants = true) {
            contentDescription = if (time == null) text else "${quiet.first} · ${usageTime(time, zone)} · $zone"
            liveRegion = LiveRegionMode.Polite
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageDot(quiet.second)
            UsageNote(text, Modifier.weight(1f))
        }
        return
    }
    var details by rememberSaveable(state.phase, state.observedSchemaVersion) { mutableStateOf(false) }
    val (title, hint) = usagePhaseCopy(state.phase, true)
    val diagnostics = state.observedSchemaVersion != null || state.failureCode != null
    Column(Modifier.fillMaxWidth().testTag("usage-status")) {
        Row(Modifier.fillMaxWidth().heightIn(min = PocketSpace.touch).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageDot(if (usagePhaseTone(state.phase) == PTone.Danger) c.danger else c.warning)
            PText(title, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = Pocket.type.labelSmall)
            if (diagnostics) PIconButton(PocketIcons.Info, if (details) "收起详情" else "读取详情", { details = !details }, Modifier.testTag("usage-diagnostics"),
                tint = c.inkMuted)
            if (state.canQuery) PTextAction("重试", onRefresh, Modifier.testTag("usage-retry"), tint = c.accent, haptic = PocketHaptic.Tap)
        }
        // Why the figures are old and when they were taken, under the line that carries the retry.
        Column(Modifier.padding(start = 19.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            UsageNote(hint)
            UsageNote("缓存快照 · ${usageTime(time!!, zone)} · $zone")
            if (diagnostics && details) UsageDiagnostics(state)
        }
    }
}

@Composable
private fun UsageStateMark(icon: ImageVector, tone: PTone) {
    val c = Pocket.colors
    val (fill, ink) = when (tone) {
        PTone.Warning -> c.warningSoft to c.warning
        PTone.Danger -> c.dangerSoft to c.onDangerSoft
        else -> c.fill to c.inkMuted
    }
    Box(Modifier.size(44.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) { PIcon(icon, null, Modifier.size(22.dp), tint = ink) }
}

/** Nothing to show and the reason why: not connected, not read yet, or a read that failed with no earlier figures. */
@Composable
internal fun UsageNotice(state: UsageScreenState, onRefresh: () -> Unit) {
    var details by rememberSaveable(state.phase, state.observedSchemaVersion) { mutableStateOf(false) }
    val (title, hint) = usagePhaseCopy(state.phase, false)
    val tone = if (state.phase == UsagePhase.Ready) PTone.Neutral else usagePhaseTone(state.phase)
    val waiting = tone == PTone.Neutral
    UsageCard(Modifier.testTag("usage-status"), PaddingValues(horizontal = 20.dp, vertical = 24.dp), spacing = 0.dp) {
        Column(Modifier.fillMaxWidth().testTag("usage-notice"), horizontalAlignment = Alignment.CenterHorizontally) {
            UsageStateMark(when (state.phase) {
                UsagePhase.NotConnected, UsagePhase.Offline -> PocketIcons.Computer
                UsagePhase.Idle, UsagePhase.Ready, UsagePhase.Missing -> PocketIcons.Info
                else -> PocketIcons.ErrorOutline
            }, tone)
            PText(title, Modifier.padding(top = 14.dp).semantics { heading() }, style = Pocket.type.title, textAlign = TextAlign.Center)
            PText(hint, Modifier.padding(top = 4.dp), style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted, textAlign = TextAlign.Center)
            if (state.canQuery) PButton(if (waiting) "读取用量" else "重试", onRefresh, Modifier.padding(top = 18.dp).widthIn(min = 160.dp).testTag("usage-retry"),
                kind = PButtonKind.Secondary, enabled = !state.refreshing, haptic = PocketHaptic.Tap)
            if (state.observedSchemaVersion != null || state.failureCode != null) {
                PTextAction(if (details) "收起详情" else "读取详情", { details = !details }, Modifier.padding(top = 6.dp).testTag("usage-diagnostics"),
                    tint = Pocket.colors.inkMuted)
                if (details) UsageDiagnostics(state)
            }
        }
    }
}

@Composable
internal fun UsageEmpty() {
    UsageCard(Modifier.testTag("usage-empty"), PaddingValues(horizontal = 20.dp, vertical = 24.dp), spacing = 0.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            UsageStateMark(PocketIcons.Search, PTone.Neutral)
            PText("所选范围没有调用记录", Modifier.padding(top = 14.dp).semantics { heading() }, style = Pocket.type.title, textAlign = TextAlign.Center)
            PText("可以更换日期或清除筛选条件。", Modifier.padding(top = 4.dp), style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted,
                textAlign = TextAlign.Center)
        }
    }
}

/** The page's outline while the first read is in flight. Still: the refresh glyph is the only thing that moves. */
@Composable
internal fun UsageSkeleton() {
    val c = Pocket.colors
    val bar = @Composable { modifier: Modifier -> Box(modifier.clip(PocketRadii.chip).background(c.fillStrong)) }
    val tile = @Composable { modifier: Modifier, tall: Boolean ->
        UsageCard(modifier, spacing = 10.dp) {
            bar(Modifier.size(52.dp, 12.dp)); bar(Modifier.size(96.dp, 24.dp)); bar(Modifier.fillMaxWidth(.7f).height(10.dp))
            if (tall) { bar(Modifier.fillMaxWidth().height(8.dp)); bar(Modifier.fillMaxWidth(.5f).height(10.dp)) }
        }
    }
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { testTag = "usage-skeleton"; contentDescription = "正在读取用量" },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { tile(Modifier.weight(1f), false); tile(Modifier.weight(1f), false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { tile(Modifier.weight(1.6f), true); tile(Modifier.weight(1f), true) }
        UsageCard(spacing = 12.dp) {
            bar(Modifier.size(44.dp, 14.dp))
            bar(Modifier.fillMaxWidth().height(40.dp))
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                listOf(.45f, .7f, .35f, .9f, .6f, .8f, .55f).forEach { bar(Modifier.weight(1f).fillMaxHeight(it)) }
            }
        }
        UsageCard(spacing = 14.dp) { repeat(3) { bar(Modifier.fillMaxWidth(if (it == 1) .6f else .85f).height(12.dp)) } }
    }
}
