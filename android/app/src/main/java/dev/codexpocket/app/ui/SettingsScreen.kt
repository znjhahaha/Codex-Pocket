package dev.codexpocket.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

@Composable
internal fun SettingsScreen(s: PocketState, repo: PocketRepository, onPair: (() -> Unit)? = null) {
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
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) watch(true) else repo.reportError(context.getString(R.string.watch_permission_error)) }
    fun changeWatch(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else watch(enabled)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (!s.paired) {
            Text(context.getString(R.string.no_pair), style = MaterialTheme.typography.headlineMedium)
            Text(context.getString(R.string.return_pairing), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SettingsSection(context.getString(R.string.v02_connection)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(PocketIcons.Computer, null, Modifier.size(28.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.computerName, style = MaterialTheme.typography.titleMedium)
                        Text(context.stateLabel(s.connection), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(context.getString(R.string.pinned_tls), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PocketActionRow(context.getString(R.string.reconnect), PocketIcons.Refresh, repo::reconnect)
                if (onPair != null) PocketActionRow("重新扫码配对", PocketIcons.QrCodeScanner, onPair,
                    subtitle = "更新此电脑的授权，保留会话缓存与草稿")
            }
            SettingsSection(context.getString(R.string.paired_devices)) {
                PocketActionRow(context.getString(R.string.refresh_devices), PocketIcons.Refresh, { repo.devices() }, enabled = s.online)
                if (s.devices.isEmpty()) Text(if (s.online) context.getString(R.string.refresh_devices_hint) else context.getString(R.string.offline_devices), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                s.devices.forEach { device ->
                    PocketActionRow(
                        title = device.string("name", context.getString(R.string.device)), icon = PocketIcons.PhoneAndroid,
                        onClick = { revoke = device.string("id") }, enabled = s.online && !s.busy,
                        trailing = { Text(context.getString(R.string.revoke), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) },
                    )
                }
            }
            SettingsSection(context.getString(R.string.remote_watch)) {
                PocketActionRow(
                    context.getString(R.string.remote_watch), PocketIcons.Wifi, { changeWatch(!s.watch) },
                    subtitle = context.getString(R.string.watch_hint),
                    trailing = { PocketToggle(s.watch, ::changeWatch, Modifier.semantics { contentDescription = context.getString(R.string.remote_watch) }) },
                )
                Text(context.getString(R.string.watch_limits), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PocketActionRow(
                    context.getString(R.string.v02_push), PocketIcons.NotificationsNone, { repo.registerPush() },
                    subtitle = if (s.pushStatus == "unconfigured") context.getString(R.string.v02_push_unconfigured) else s.pushStatus,
                    enabled = s.pushConfigured && s.online && !s.busy,
                    trailing = { Text(context.getString(R.string.v02_push_enable), style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        SettingsSection(context.getString(R.string.appearance)) {
            Row(Modifier.fillMaxWidth().selectableGroup().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("light", "dark", "system").forEach { theme ->
                    PocketPill(context.themeLabel(theme), s.theme == theme, { repo.theme(theme) }, modifier = Modifier.weight(1f))
                }
            }
            PocketActionRow(
                stringResource(R.string.glass_effect_title), PocketIcons.AutoAwesome, { repo.glassEffects(!s.glassEffects) },
                subtitle = stringResource(R.string.glass_effect_description),
                trailing = { PocketToggle(s.glassEffects, repo::glassEffects, Modifier.semantics { contentDescription = context.getString(R.string.glass_effect_title) }) },
            )
        }
        if (s.commands.isNotEmpty()) SettingsSection(context.getString(R.string.unconfirmed_actions)) {
            Text(context.getString(R.string.unknown_explainer), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.commands.forEach { command -> Text("${context.actionLabel(command.method)} · ${context.stateLabel(command.status)}", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium) }
            PocketActionRow(context.getString(R.string.check_windows), PocketIcons.Search, { repo.checkCommands() }, enabled = s.online && !s.busy)
        }
        SettingsSection(context.getString(R.string.on_device)) {
            PocketActionRow(context.getString(R.string.clear_histories), PocketIcons.DeleteOutline, { clear = true })
            PocketActionRow(context.getString(R.string.forget_pairing), PocketIcons.Unplug, { forget = true }, destructive = true)
        }
        Text(context.getString(R.string.app_about), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PocketSectionLabel(title)
        Column(Modifier.fillMaxWidth(), content = content)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
    }
}
