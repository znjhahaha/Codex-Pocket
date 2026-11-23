package dev.codexpocket.app.ui

import dev.codexpocket.app.ui.kit.*
import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.codexpocket.app.WatchService
import dev.codexpocket.app.data.*
import dev.codexpocket.app.R

@Composable
internal fun PairScreen(
    busy: Boolean,
    onPair: (String, String) -> Any,
    phase: PairingPhase = PairingPhase.Idle,
    error: String? = null,
    onDismiss: (() -> Unit)? = null,
) = EmbeddedPairingScreen(busy, onPair, phase, error, onDismiss)

internal enum class SettingsPage(val label: String) {
    // Keep the saveable page identity while replacing its displayed product content.
    Connection("电脑连接"), Extensions("用量"), App("应用设置"),
}

/** Scroll and saveable child state outlive each page's brief content transition. */
@Composable
internal fun SettingsPageLayout(
    initialPage: SettingsPage = SettingsPage.Connection,
    modifier: Modifier = Modifier,
    extensionContent: (@Composable (Modifier, PaddingValues) -> Unit)? = null,
    content: @Composable ColumnScope.(SettingsPage) -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    val connectionScroll = rememberScrollState()
    val extensionScroll = rememberScrollState()
    val appScroll = rememberScrollState()
    val pages = rememberSaveableStateHolder()
    val preferences = rememberPocketGlassPreferences()
    val motion = preferences.animatorsEnabled && !preferences.highContrast && !preferences.batterySaver
    val background = Pocket.colors.bg
    Column(modifier.fillMaxSize().background(background).testTag("management-settings")) {
        SettingsPageSegments(page, { page = it }, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        AnimatedContent(
            targetState = page,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                if (motion) fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                else EnterTransition.None togetherWith ExitTransition.None
            },
            label = "settings-page",
        ) { selected ->
            pages.SaveableStateProvider(selected.name) {
                val padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
                if (selected == SettingsPage.Extensions && extensionContent != null) {
                    extensionContent(Modifier.fillMaxSize().testTag("settings-page:" + selected.name), padding)
                } else {
                    val scroll = when (selected) {
                        SettingsPage.Connection -> connectionScroll
                        SettingsPage.Extensions -> extensionScroll
                        SettingsPage.App -> appScroll
                    }
                    Column(
                        Modifier.fillMaxSize().testTag("settings-page:" + selected.name)
                            .verticalScroll(scroll).padding(padding),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) { content(selected) }
                }
            }
        }
    }
}

@Composable
private fun SettingsPageSegments(page: SettingsPage, onSelect: (SettingsPage) -> Unit, modifier: Modifier) {
    val preferences = rememberPocketGlassPreferences()
    val motion = preferences.animatorsEnabled && !preferences.highContrast && !preferences.batterySaver
    // The kit's sliding selection: the quick edge leads, the slow one follows, and the thumb can be carried by a finger.
    val pages = SettingsPage.entries
    val slide = rememberPSlide(pages.size, page.ordinal, haptic = null) { onSelect(pages[it]) }
    CompositionLocalProvider(LocalPocketClearGlass provides true) {
        PSurface(modifier.fillMaxWidth().testTag("settings-segments"), shape = RoundedCornerShape(28.dp), color = Pocket.colors.fill) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(5.dp)) {
                val itemWidth = maxWidth / SettingsPage.entries.size
                Box(Modifier.pocketSlide(slide)) {
                    Box(Modifier.matchParentSize()) {
                        // Pulled past an end the thumb flattens against it; held, it presses in.
                        val left = slide.start.value.coerceIn(0f, pages.size.toFloat())
                        val right = slide.end.value.coerceIn(left, pages.size.toFloat())
                        PSurface(Modifier.offset(x = itemWidth * left).width(itemWidth * (right - left)).fillMaxHeight()
                            .graphicsLayer { val held = 1f - .05f * slide.lift.value; scaleX = held; scaleY = held }
                            .border(1.dp, Pocket.colors.line.copy(alpha = .45f), RoundedCornerShape(24.dp))
                            .testTag("settings-selected-thumb"), shape = RoundedCornerShape(24.dp), color = Pocket.colors.accentSoft) { }
                    }
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
                        SettingsPage.entries.forEach { item ->
                            val interaction = remember { MutableInteractionSource() }
                            Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                                .testTag("settings-tab:" + item.name).pocketSelectable(page == item, role = Role.Tab,
                                    interactionSource = interaction, pressedScale = .96f, haptic = null, onClick = { onSelect(item) })
                                .padding(horizontal = 6.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                                val shown = slide.shown == item.ordinal
                                PocketTabLabel(item.label, shown, style = Pocket.type.labelSmall,
                                    color = if (shown) Pocket.colors.onAccentSoft else Pocket.colors.inkMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(s: PocketState, repo: PocketRepository, onPair: (() -> Unit)? = null, modifier: Modifier = Modifier,
    management: Boolean = false, usageState: UsageScreenState? = null, onUsageRefresh: (() -> Unit)? = null,
    onUsageFilter: ((UsageFilter) -> Unit)? = null) {
    val context = LocalContext.current
    val chooseLocation = LocalExecutionLocationPicker.current
    var forget by rememberSaveable { mutableStateOf(false) }
    var clear by rememberSaveable { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<String?>(null) }
    var reconnects by remember { mutableIntStateOf(0) }
    var deviceRefreshes by remember { mutableIntStateOf(0) }
    fun watch(enable: Boolean) {
        if (enable) {
            try { ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java)) }
            catch (_: Exception) { repo.reportError(context.getString(R.string.watch_start_error)) }
        } else context.stopService(Intent(context, WatchService::class.java))
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) watch(true) else repo.reportError(context.getString(R.string.watch_permission_error))
    }
    fun changeWatch(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else watch(enabled)
    }
    SettingsPageLayout(if (management) SettingsPage.Connection else SettingsPage.App, modifier,
        extensionContent = { pageModifier, padding ->
            val wired = usageState != null && onUsageRefresh != null && onUsageFilter != null
            UsageScreen(if (wired) usageState!! else UsageScreenState(), onUsageRefresh ?: {}, onUsageFilter ?: {}, pageModifier, padding,
                onRequests = if (wired) { cursor -> repo.loadUsageRequests(cursor) } else null)
        }) { page ->
        when (page) {
            SettingsPage.Connection -> {
                SettingsPageTitle("电脑连接", "查看连接状态与已配对设备")
                chooseLocation?.let { choose -> PocketButton("执行位置", choose, Modifier.fillMaxWidth().testTag("settings-execution-locations"), secondary = true) }
                if (!s.paired) {
                    SettingsSection(context.getString(R.string.no_pair)) {
                        PText(context.getString(R.string.return_pairing), Modifier.padding(12.dp),
                            style = Pocket.type.body, color = Pocket.colors.inkMuted)
                        if (onPair != null) PocketActionRow("扫码配对电脑", PocketIcons.QrCodeScanner, onPair)
                    }
                } else {
                    SettingsSection("当前电脑") {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsCircleIcon(PocketIcons.Computer)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                PText(s.computerName, style = Pocket.type.title)
                                PText(context.stateLabel(s.connection), style = Pocket.type.bodySmall,
                                    color = Pocket.colors.inkMuted)
                            }
                        }
                        PText(context.getString(R.string.pinned_tls), Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
                        if (s.connectionDetail.isNotBlank()) PText(s.connectionDetail, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = Pocket.type.bodySmall)
                        PocketActionRow("连接诊断", PocketIcons.Search, { repo.inspectConnection() }, enabled = s.online)
                        PocketActionRow(context.getString(R.string.reconnect), PocketIcons.Refresh, { reconnects++; repo.reconnect() },
                            glyph = { PocketRefreshGlyph(reconnects) })
                        if (onPair != null) PocketActionRow("重新扫码配对", PocketIcons.QrCodeScanner, onPair,
                            subtitle = "更新此电脑的授权，保留会话缓存与草稿")
                    }
                    UpstreamVersionPanel(s, repo)
                    if (s.sessionModes.size > 1) SettingsSection("新会话默认模式") {
                        SessionModeChoices(s.sessionPreferences.defaultMode, s.online && s.preferencesFresh &&
                            s.supports(BrokerCapability.PreferencesUpdate) && s.actionStatus["preferences"]?.loading != true, repo::setDefaultSessionMode)
                        PText("默认跟随桌面。独立 CLI 需要显式选择；已有任务保留当前归属。", Modifier.padding(12.dp),
                            style = Pocket.type.bodySmall)
                        PocketTextButton({ repo.refreshSessionPreferences() }, enabled = s.online && s.supports(BrokerCapability.PreferencesRead)) { PText("刷新默认模式") }
                        s.actionStatus["preferences"]?.error?.let { PText(it, color = Pocket.colors.danger) }
                    }
                    if (s.runtimes.isNotEmpty()) SettingsSection("运行环境") {
                        s.runtimes.forEachIndexed { index, runtime ->
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                SettingsCircleIcon(if (runtime.source == "cli") PocketIcons.Terminal else PocketIcons.Computer)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    PText(context.sourceLabel(runtime.source) + " · ${index + 1}", style = Pocket.type.body)
                                    PText(runtimeVersionLabel(runtime.version), style = Pocket.type.caption,
                                        color = Pocket.colors.inkMuted)
                                }
                                PText(if (runtime.online) "已连接" else "离线", style = Pocket.type.caption)
                            }
                        }
                    }
                    SettingsSection(context.getString(R.string.paired_devices)) {
                        PocketActionRow(context.getString(R.string.refresh_devices), PocketIcons.Refresh, { deviceRefreshes++; repo.devices() }, enabled = s.online,
                            glyph = { PocketRefreshGlyph(deviceRefreshes) })
                        if (s.devices.isEmpty()) PText(if (s.online) context.getString(R.string.refresh_devices_hint) else context.getString(R.string.offline_devices),
                            Modifier.padding(12.dp), color = Pocket.colors.inkMuted, style = Pocket.type.bodySmall)
                        s.devices.forEach { device ->
                            PocketActionRow(
                                title = device.string("name", context.getString(R.string.device)), icon = PocketIcons.PhoneAndroid,
                                onClick = { revoke = device.string("id") }, enabled = s.online && !s.busy,
                                trailing = { PText(context.getString(R.string.revoke), style = Pocket.type.labelSmall, color = Pocket.colors.danger) },
                            )
                        }
                    }
                    if (s.commands.isNotEmpty()) SettingsSection(context.getString(R.string.unconfirmed_actions)) {
                        PText(context.getString(R.string.unknown_explainer), Modifier.padding(12.dp), style = Pocket.type.bodySmall,
                            color = Pocket.colors.inkMuted)
                        s.commands.forEach { command ->
                            PText(context.actionLabel(command.method) + " · " + context.stateLabel(command.status),
                                Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = Pocket.type.body)
                        }
                        PocketActionRow(context.getString(R.string.check_windows), PocketIcons.Search, { repo.checkCommands() }, enabled = s.online && !s.busy)
                    }
                }
            }
            SettingsPage.Extensions -> {
                Unit
            }
            SettingsPage.App -> {
                SettingsPageTitle("应用设置", "外观、后台通知与本机数据")
                SettingsSection(context.getString(R.string.appearance)) {
                    Row(Modifier.fillMaxWidth().selectableGroup().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("light", "dark", "system").forEach { theme ->
                            PocketPill(context.themeLabel(theme), s.theme == theme, { repo.theme(theme) }, modifier = Modifier.weight(1f))
                        }
                    }
                    PocketActionRow(stringResource(R.string.glass_effect_title), PocketIcons.AutoAwesome, { repo.glassEffects(!s.glassEffects) },
                        subtitle = stringResource(R.string.glass_effect_description),
                        trailing = { PocketToggle(s.glassEffects, repo::glassEffects, Modifier.semantics { contentDescription = context.getString(R.string.glass_effect_title) }) })
                }
                if (s.paired) SettingsSection("后台与通知") {
                    PocketActionRow(context.getString(R.string.remote_watch), PocketIcons.Wifi, { changeWatch(!s.watch) },
                        subtitle = context.getString(R.string.watch_hint),
                        trailing = { PocketToggle(s.watch, ::changeWatch, Modifier.semantics { contentDescription = context.getString(R.string.remote_watch) }) })
                    PText(context.getString(R.string.watch_limits), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
                    PocketActionRow(context.getString(R.string.v02_push), PocketIcons.NotificationsNone, { repo.registerPush() },
                        subtitle = if (s.pushStatus == "unconfigured") context.getString(R.string.v02_push_unconfigured) else s.pushStatus,
                        enabled = s.pushConfigured && s.online && !s.busy,
                        trailing = { PText(context.getString(R.string.v02_push_enable), style = Pocket.type.labelSmall) })
                }
                LocalPocketTutorial.current?.let { tutorial ->
                    SettingsSection("帮助") {
                        PocketActionRow("使用教程", PocketIcons.Lightbulb, tutorial::show, subtitle = "重新看一遍配对、对话、确认和助理的用法")
                    }
                }
                SettingsSection(context.getString(R.string.on_device)) {
                    PocketActionRow(context.getString(R.string.clear_histories), PocketIcons.DeleteOutline, { clear = true })
                    PocketActionRow(context.getString(R.string.forget_pairing), PocketIcons.Unplug, { forget = true }, destructive = true)
                }
                PText(context.getString(R.string.app_about), Modifier.padding(horizontal = 8.dp), style = Pocket.type.bodySmall,
                    color = Pocket.colors.inkMuted)
                PText("${dev.codexpocket.app.BuildConfig.VERSION_NAME} · ${dev.codexpocket.app.BuildConfig.POCKET_BUILD_ID}", Modifier.padding(horizontal = 8.dp),
                    style = Pocket.type.caption, color = Pocket.colors.inkMuted)
                PText("阅读字体使用 MiSans，版权归小米所有。字体原文件及许可协议随应用提供。", Modifier.padding(horizontal = 8.dp),
                    style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
            }
        }
    }
    if (forget) PocketDialog(
        onDismissRequest = { forget = false }, title = { PText(context.getString(R.string.forget_title)) }, text = { PText(context.getString(R.string.forget_body)) },
        confirmButton = { PocketButton(context.getString(R.string.forget), { watch(false); repo.forget(); forget = false }, destructive = true) },
        dismissButton = { PocketButton(context.getString(R.string.cancel), { forget = false }, secondary = true) },
    )
    if (clear) PocketDialog(
        onDismissRequest = { clear = false }, title = { PText(context.getString(R.string.clear_title)) }, text = { PText(context.getString(R.string.clear_body)) },
        confirmButton = { PocketButton(context.getString(R.string.clear_cache), { repo.clearCache(); clear = false }, destructive = true) },
        dismissButton = { PocketButton(context.getString(R.string.cancel), { clear = false }, secondary = true) },
    )
    revoke?.let { id ->
        PocketDialog(
            onDismissRequest = { revoke = null }, title = { PText(context.getString(R.string.revoke_title)) }, text = { PText(context.getString(R.string.revoke_body)) },
            confirmButton = { PocketButton(context.getString(R.string.revoke), { repo.revoke(id); revoke = null }, destructive = true) },
            dismissButton = { PocketButton(context.getString(R.string.cancel), { revoke = null }, secondary = true) },
        )
    }
}

@Composable
private fun SettingsPageTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PText(title, Modifier.semantics { heading() }, style = Pocket.type.titleLarge)
        PText(subtitle, style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) = PSection(title, content = content)

@Composable
internal fun SettingsCircleIcon(icon: ImageVector) {
    Box(Modifier.size(38.dp).background(Pocket.colors.sunken, CircleShape), contentAlignment = Alignment.Center) {
        PIcon(icon, null, Modifier.size(19.dp), tint = Pocket.colors.inkMuted)
    }
}
