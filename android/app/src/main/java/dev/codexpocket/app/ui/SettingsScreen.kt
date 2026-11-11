package dev.codexpocket.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
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
    val background = MaterialTheme.colorScheme.background
    val density = LocalDensity.current
    var segmentHeight by remember { mutableIntStateOf(with(density) { 82.dp.roundToPx() }) }
    Box(modifier.fillMaxSize().background(background).testTag("management-settings")) {
        AnimatedContent(
            targetState = page,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (motion) fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                else EnterTransition.None togetherWith ExitTransition.None
            },
            label = "settings-page",
        ) { selected ->
            pages.SaveableStateProvider(selected.name) {
                val padding = PaddingValues(start = 16.dp, end = 16.dp,
                    top = with(density) { segmentHeight.toDp() } + 4.dp, bottom = 24.dp)
                if (selected == SettingsPage.Extensions && extensionContent != null) {
                    extensionContent(Modifier.fillMaxSize().testTag("settings-page:" + selected.name), padding)
                } else {
                    val scroll = when (selected) {
                        SettingsPage.Connection -> connectionScroll
                        SettingsPage.Extensions -> extensionScroll
                        SettingsPage.App -> appScroll
                    }
                    Column(
                        Modifier.fillMaxSize().testTag("settings-page:" + selected.name).verticalScroll(scroll).padding(padding),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) { content(selected) }
                }
            }
        }
        // An opaque backing keeps moving page content out of the segmented control.
        SettingsPageSegments(page, { page = it }, Modifier.align(Alignment.TopCenter)
            .onSizeChanged { segmentHeight = it.height }
            .padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

@Composable
private fun SettingsPageSegments(page: SettingsPage, onSelect: (SettingsPage) -> Unit, modifier: Modifier) {
    val preferences = rememberPocketGlassPreferences()
    val motion = preferences.animatorsEnabled && !preferences.highContrast && !preferences.batterySaver
    val selection by animateFloatAsState(page.ordinal.toFloat(), if (motion) tween(200) else snap(), label = "settings-selected-thumb")
    CompositionLocalProvider(LocalPocketClearGlass provides true) {
        Surface(modifier.fillMaxWidth().testTag("settings-segments"), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(5.dp)) {
                val itemWidth = maxWidth / SettingsPage.entries.size
                Box {
                    Box(Modifier.matchParentSize()) {
                        Surface(Modifier.offset(x = itemWidth * selection).width(itemWidth).fillMaxHeight()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f), RoundedCornerShape(24.dp))
                            .testTag("settings-selected-thumb"), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) { }
                    }
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
                        SettingsPage.entries.forEach { item ->
                            val interaction = remember { MutableInteractionSource() }
                            Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                                .testTag("settings-tab:" + item.name).selectable(page == item, role = Role.Tab,
                                    interactionSource = interaction, indication = null, onClick = { onSelect(item) })
                                .padding(horizontal = 6.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Text(item.label, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = TextAlign.Center,
                                    color = if (page == item) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
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
    var forget by rememberSaveable { mutableStateOf(false) }
    var clear by rememberSaveable { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<String?>(null) }
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
            UsageScreen(if (wired) usageState!! else UsageScreenState(), onUsageRefresh ?: {}, onUsageFilter ?: {}, pageModifier, padding)
        }) { page ->
        when (page) {
            SettingsPage.Connection -> {
                SettingsPageTitle("电脑连接", "查看连接状态与已配对设备")
                if (!s.paired) {
                    SettingsSection(context.getString(R.string.no_pair)) {
                        Text(context.getString(R.string.return_pairing), Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (onPair != null) PocketActionRow("扫码配对电脑", PocketIcons.QrCodeScanner, onPair)
                    }
                } else {
                    SettingsSection("当前电脑") {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsCircleIcon(PocketIcons.Computer)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(s.computerName, style = MaterialTheme.typography.titleMedium)
                                Text(context.stateLabel(s.connection), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(context.getString(R.string.pinned_tls), Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.connectionDetail.isNotBlank()) Text(s.connectionDetail, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall)
                        PocketActionRow("连接诊断", PocketIcons.Search, { repo.inspectConnection() }, enabled = s.online)
                        PocketActionRow(context.getString(R.string.reconnect), PocketIcons.Refresh, repo::reconnect)
                        if (onPair != null) PocketActionRow("重新扫码配对", PocketIcons.QrCodeScanner, onPair,
                            subtitle = "更新此电脑的授权，保留会话缓存与草稿")
                    }
                    UpstreamVersionPanel(s, repo)
                    SettingsSection("新会话默认模式") {
                        SessionModeChoices(s.sessionPreferences.defaultMode, s.online && s.preferencesFresh &&
                            s.supports(BrokerCapability.PreferencesUpdate) && s.actionStatus["preferences"]?.loading != true, repo::setDefaultSessionMode)
                        Text("默认跟随桌面。独立 CLI 需要显式选择；已有任务保留当前归属。", Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall)
                        PocketTextButton({ repo.refreshSessionPreferences() }, enabled = s.online && s.supports(BrokerCapability.PreferencesRead)) { Text("刷新默认模式") }
                        s.actionStatus["preferences"]?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    if (s.runtimes.isNotEmpty()) SettingsSection("运行环境") {
                        s.runtimes.forEachIndexed { index, runtime ->
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                SettingsCircleIcon(if (runtime.source == "cli") PocketIcons.Terminal else PocketIcons.Computer)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(context.sourceLabel(runtime.source) + " · ${index + 1}", style = MaterialTheme.typography.bodyMedium)
                                    Text(runtimeVersionLabel(runtime.version), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (runtime.online) "已连接" else "离线", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    SettingsSection(context.getString(R.string.paired_devices)) {
                        PocketActionRow(context.getString(R.string.refresh_devices), PocketIcons.Refresh, { repo.devices() }, enabled = s.online)
                        if (s.devices.isEmpty()) Text(if (s.online) context.getString(R.string.refresh_devices_hint) else context.getString(R.string.offline_devices),
                            Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        s.devices.forEach { device ->
                            PocketActionRow(
                                title = device.string("name", context.getString(R.string.device)), icon = PocketIcons.PhoneAndroid,
                                onClick = { revoke = device.string("id") }, enabled = s.online && !s.busy,
                                trailing = { Text(context.getString(R.string.revoke), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) },
                            )
                        }
                    }
                    if (s.commands.isNotEmpty()) SettingsSection(context.getString(R.string.unconfirmed_actions)) {
                        Text(context.getString(R.string.unknown_explainer), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        s.commands.forEach { command ->
                            Text(context.actionLabel(command.method) + " · " + context.stateLabel(command.status),
                                Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
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
                    Text(context.getString(R.string.watch_limits), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PocketActionRow(context.getString(R.string.v02_push), PocketIcons.NotificationsNone, { repo.registerPush() },
                        subtitle = if (s.pushStatus == "unconfigured") context.getString(R.string.v02_push_unconfigured) else s.pushStatus,
                        enabled = s.pushConfigured && s.online && !s.busy,
                        trailing = { Text(context.getString(R.string.v02_push_enable), style = MaterialTheme.typography.labelMedium) })
                }
                SettingsSection(context.getString(R.string.on_device)) {
                    PocketActionRow(context.getString(R.string.clear_histories), PocketIcons.DeleteOutline, { clear = true })
                    PocketActionRow(context.getString(R.string.forget_pairing), PocketIcons.Unplug, { forget = true }, destructive = true)
                }
                Text(context.getString(R.string.app_about), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("阅读字体使用 MiSans，版权归小米所有。字体原文件及许可协议随应用提供。", Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (forget) PocketDialog(
        onDismissRequest = { forget = false }, title = { Text(context.getString(R.string.forget_title)) }, text = { Text(context.getString(R.string.forget_body)) },
        confirmButton = { PocketButton(context.getString(R.string.forget), { watch(false); repo.forget(); forget = false }, destructive = true) },
        dismissButton = { PocketButton(context.getString(R.string.cancel), { forget = false }, secondary = true) },
    )
    if (clear) PocketDialog(
        onDismissRequest = { clear = false }, title = { Text(context.getString(R.string.clear_title)) }, text = { Text(context.getString(R.string.clear_body)) },
        confirmButton = { PocketButton(context.getString(R.string.clear_cache), { repo.clearCache(); clear = false }, destructive = true) },
        dismissButton = { PocketButton(context.getString(R.string.cancel), { clear = false }, secondary = true) },
    )
    revoke?.let { id ->
        PocketDialog(
            onDismissRequest = { revoke = null }, title = { Text(context.getString(R.string.revoke_title)) }, text = { Text(context.getString(R.string.revoke_body)) },
            confirmButton = { PocketButton(context.getString(R.string.revoke), { repo.revoke(id); revoke = null }, destructive = true) },
            dismissButton = { PocketButton(context.getString(R.string.cancel), { revoke = null }, secondary = true) },
        )
    }
}

@Composable
private fun SettingsPageTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(horizontal = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
            Column(Modifier.padding(6.dp), content = content)
        }
    }
}

@Composable
internal fun SettingsCircleIcon(icon: ImageVector) {
    Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
