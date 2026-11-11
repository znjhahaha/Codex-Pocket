package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.PocketState
import dev.codexpocket.app.data.PocketRepository
import kotlinx.serialization.json.*
import java.text.DateFormat
import java.util.Date

@Composable
internal fun UpstreamVersionPanel(s: PocketState, repo: PocketRepository) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("线路与上游版本", style = MaterialTheme.typography.titleMedium)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val actual = if (!s.online) "当前离线" else when (s.activeRouteKind) { "lan" -> "局域网直连"; "p2p" -> "公网直连"; "relay", "cloudflare" -> "服务器中转"; else -> "路线尚未确认" }
                Text(actual, style = MaterialTheme.typography.titleSmall)
                if (s.online) Text(if (s.routePhase == "direct_negotiating") "中转可用 · 正在尝试直连" else "身份验证通过 · 手机会话已建立", style = MaterialTheme.typography.bodySmall)
                s.routeFallback?.let { Text(when (it) { "direct_timeout" -> "直连尝试超时，继续使用可用线路"; "direct_not_stable" -> "直连尚未稳定，保留原线路"; else -> "暂未建立直连，继续使用可用线路" }, style = MaterialTheme.typography.bodySmall) }
                val versions = s.upstreamVersions
                for ((key, title) in listOf("installed" to "电脑已安装", "helper" to "电脑助手运行", "supervisor" to "后台监督报告")) {
                    val value = versions?.get(key) as? JsonObject
                    val version = (value?.get("version") as? JsonPrimitive)?.contentOrNull ?: "未知"
                    val generation = (value?.get("generation") as? JsonPrimitive)?.contentOrNull
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(title, style = MaterialTheme.typography.bodyMedium)
                        Text(version + (generation?.let { " · ${it.take(10)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    }
                }
                val bridges = versions?.get("bridges") as? JsonArray
                if (bridges.isNullOrEmpty()) {
                    Text("桌面桥接：尚未取得版本", style = MaterialTheme.typography.bodySmall)
                } else bridges.forEach { entry ->
                    val bridge = entry as? JsonObject
                    val id = (bridge?.get("runtime_id") as? JsonPrimitive)?.contentOrNull
                    val runtime = s.runtimes.firstOrNull { it.id == id }
                    val version = (bridge?.get("version") as? JsonPrimitive)?.contentOrNull ?: "未知"
                    val label = if (runtime?.source == "cli") "CLI 连接" else "桌面桥接"
                    Text("$label：$version" + (id?.let { " · ${it.take(12)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    if (runtime?.bridgeUpdatePending == true) Text("桥接版本与助手不同，等待电脑端更新生效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val host = versions?.get("host") as? JsonObject
                val hostVersion = (host?.get("version") as? JsonPrimitive)?.contentOrNull
                Text("独立宿主：${hostVersion ?: "尚未取得版本"}", style = MaterialTheme.typography.bodySmall)
                val relay = if (s.online) s.relayVersion else null
                Text("当前中继：" + when { !s.online -> "离线，未核验"; s.activeRouteKind in setOf("lan", "p2p") -> "未使用"; relay == null -> "上游未报告版本"; else -> "${relay.version} · 协议 ${relay.protocol}" + (relay.build?.let { " · ${it.take(10)}" } ?: "") }, style = MaterialTheme.typography.bodySmall)
                Text(if (!s.online && versions != null) "显示上次认证连接记录；不代表当前进程在线。" else "安装代次与运行代次分别显示；监督进程的报告需结合电脑端进程核验。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.versionsObservedAt > 0) Text("最近获取：" + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.versionsObservedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PocketTextButton({ repo.inspectConnection() }, enabled = s.online) { Text("刷新版本与诊断") }
            }
        }
        PocketActionRow("自动尝试公网直连", PocketIcons.Wifi, { repo.configureDirectConnection(!s.p2pEnabled, s.publicStunEnabled) },
            subtitle = "优先保留可用连接，直连验证通过后切换",
            trailing = { PocketToggle(s.p2pEnabled, { repo.configureDirectConnection(it, s.publicStunEnabled) }) })
        if (s.p2pEnabled) PocketActionRow("允许公共 STUN 辅助发现", PocketIcons.Info, { repo.configureDirectConnection(true, !s.publicStunEnabled) },
            subtitle = "Cloudflare 可见发现请求的 IP 与端口；关闭后仍可使用中转",
            trailing = { PocketToggle(s.publicStunEnabled, { repo.configureDirectConnection(true, it) }) })
    }
}
