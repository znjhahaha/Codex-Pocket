package dev.codexpocket.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.codexpocket.app.data.LocalAccess
import dev.codexpocket.app.data.LocalAccount
import dev.codexpocket.app.data.LocalProviderDraft
import dev.codexpocket.app.data.LocalProviderModelLoader
import dev.codexpocket.app.data.LocalProviderModelResult
import dev.codexpocket.app.data.LocalProviderModelProblem
import dev.codexpocket.app.data.LocalProjects
import dev.codexpocket.app.data.PhoneLinuxProgress
import dev.codexpocket.app.ui.kit.*

/** Running Codex on this phone: start it, choose the model service it uses, stop it. */
@Composable
internal fun ThisPhoneSheet(added: Boolean, account: LocalAccount, progress: String?, onDismiss: () -> Unit, onStart: () -> Unit,
    onChatGpt: () -> Unit, onRefresh: () -> Unit, onStop: () -> Unit,
    access: LocalAccess = LocalAccess(), applying: Boolean = false, onUseProvider: (String?) -> Unit = {},
    onSaveProvider: (LocalProviderDraft) -> Boolean = { false }, onRemoveProvider: (String) -> Unit = {}, onSignOut: () -> Unit = {},
    projects: LocalProjects = LocalProjects(), onImport: (Uri) -> Unit = {}, onExport: (String, Uri) -> Unit = { _, _ -> },
    onFetchModels: LocalProviderModelLoader = { LocalProviderModelResult.Failed(LocalProviderModelProblem.Network) },
    environment: PhoneLinuxProgress? = null) {
    val context = LocalContext.current
    var exporting by remember { mutableStateOf<String?>(null) }
    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onImport(uri) }
    val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val name = exporting; exporting = null
        if (uri != null && name != null) onExport(name, uri)
    }
    PSheet("此手机", onDismiss, subtitle = "Codex 直接在手机上运行，不需要电脑", maxHeightFraction = .9f) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PText("在应用私有目录中使用 Bash、Git、Python 和 Node。工具遵循会话中选择的权限；支持的隔离模式会在启动时验证，可在对话的权限菜单中查看。",
                style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
            environment?.let { PhoneEnvironmentStatus(it) }
            if (!added) {
                if (environment == null) progress?.let { PocketProgressStatus(it) }
                val label = when {
                    environment?.paused == true -> "继续下载"
                    environment?.failed == true -> "重试"
                    else -> "在此手机上运行"
                }
                PButton(label, onStart, Modifier.fillMaxWidth().testTag("this-phone-start"), enabled = progress == null && environment?.active != true)
                return@Column
            }
            if (environment?.failed == true || environment?.paused == true || environment?.pending == true) {
                PButton(if (environment.pending) "下载手机环境" else if (environment.paused) "继续下载" else "重试", onStart,
                    Modifier.fillMaxWidth().testTag("this-phone-environment-retry"), enabled = progress == null)
            }
            if (environment?.active == true) return@Column
            ThisPhoneProviders(access, applying, onUseProvider, onSaveProvider, onRemoveProvider, onFetchModels) {
                when (account) {
                    LocalAccount.Unknown, LocalAccount.Checking -> PocketProgressStatus("正在读取登录状态", Modifier.testTag("this-phone-checking"))
                    is LocalAccount.SignedIn -> {
                        PText("已登录 · ${account.label}", Modifier.testTag("this-phone-signed-in"), style = Pocket.type.titleSmall)
                        PButton("退出登录", onSignOut, Modifier.fillMaxWidth().testTag("this-phone-sign-out"), kind = PButtonKind.Secondary)
                    }
                    is LocalAccount.Code -> {
                        PText("在浏览器中打开登录页面，输入下面的一次性验证码。", style = Pocket.type.body)
                        PText(account.code, Modifier.testTag("this-phone-code"), style = Pocket.type.headline)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PButton("打开登录页面", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(account.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                                Modifier.weight(1f).testTag("this-phone-open-login"))
                            PButton("复制验证码", { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Codex", account.code)) },
                                Modifier.weight(1f), kind = PButtonKind.Secondary)
                        }
                        PocketProgressStatus("等待你在浏览器中完成登录")
                        PButton("取消登录", onRefresh, Modifier.fillMaxWidth().testTag("this-phone-cancel-login"), kind = PButtonKind.Secondary)
                    }
                    LocalAccount.SignedOut, is LocalAccount.Failed -> {
                        if (account is LocalAccount.Failed) PText(account.reason, Modifier.testTag("this-phone-failed"), style = Pocket.type.body, color = Pocket.colors.danger)
                        else PText("尚未登录。登录 OpenAI 账号，或在上面添加第三方服务后才能开始对话。", style = Pocket.type.body)
                        PButton("用 ChatGPT 账号登录", onChatGpt, Modifier.fillMaxWidth().testTag("this-phone-chatgpt"))
                        if (account is LocalAccount.Failed) PButton("重新读取登录状态", onRefresh, Modifier.fillMaxWidth(), kind = PButtonKind.Secondary)
                    }
                }
            }
            PText("项目文件", style = Pocket.type.titleSmall)
            PText("手机上的项目放在本应用的私有目录里。用压缩包把代码带进来，或把改好的项目带出去。", style = Pocket.type.caption, color = Pocket.colors.inkMuted)
            projects.names.forEach { name ->
                Row(Modifier.fillMaxWidth().testTag("this-phone-project:$name"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PText(name, Modifier.weight(1f), style = Pocket.type.body)
                    PButton("导出", { exporting = name; pickDestination.launch("$name.zip") }, Modifier.testTag("this-phone-export:$name"),
                        kind = PButtonKind.Secondary, enabled = !projects.busy)
                }
            }
            if (projects.busy) PocketProgressStatus("正在处理项目文件", Modifier.testTag("this-phone-transfer"))
            projects.notice?.let { PText(it, Modifier.testTag("this-phone-transfer-notice"), style = Pocket.type.bodySmall) }
            PButton("导入项目压缩包", { pickArchive.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                Modifier.fillMaxWidth().testTag("this-phone-import"), kind = PButtonKind.Secondary, enabled = !projects.busy)
            PButton("停止在此手机上运行", onStop, Modifier.fillMaxWidth().testTag("this-phone-stop"), kind = PButtonKind.Danger)
            PText("停止会结束手机上正在进行的任务；会话记录和登录信息会保留，再次选择“此手机”即可继续。", style = Pocket.type.caption, color = Pocket.colors.inkMuted)
        }
    }
}

@Composable
private fun PhoneEnvironmentStatus(environment: PhoneLinuxProgress) {
    Column(Modifier.fillMaxWidth().testTag("this-phone-environment"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PText(environment.message, style = Pocket.type.bodySmall,
            color = if (environment.failed) Pocket.colors.danger else Pocket.colors.inkMuted)
        environment.totalBytes?.let { total ->
            val bytes = environment.downloadedBytes
            PText(if (bytes == null) "下载大小 ${phoneEnvironmentBytes(total)}" else "${phoneEnvironmentBytes(bytes)} / ${phoneEnvironmentBytes(total)}",
                Modifier.testTag("this-phone-download-size"), style = Pocket.type.bodySmall)
        }
        if (environment.active) {
            PLinearProgress(environment.fraction, Modifier.fillMaxWidth().testTag("this-phone-download-progress"))
            environment.cancel?.let { cancel ->
                PTextAction("暂停下载", cancel, Modifier.testTag("this-phone-download-pause"))
            }
        }
    }
}

private fun phoneEnvironmentBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
}
