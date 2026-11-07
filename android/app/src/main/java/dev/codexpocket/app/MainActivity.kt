package dev.codexpocket.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import dev.codexpocket.app.ui.PocketRoot
import dev.codexpocket.app.ui.PocketTheme

class MainActivity : ComponentActivity() {
    private val repository get() = (application as PocketApplication).repository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receiveShare(intent)
        setContent {
            val state by repository.state.collectAsStateWithLifecycle()
            PocketTheme(state.theme) { PocketRoot(state, repository) }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveShare(intent) }
    @Suppress("DEPRECATION")
    private fun receiveShare(intent: Intent?) {
        if (intent == null) return
        intent?.getStringExtra("pocketChat")?.let { encoded ->
            runCatching { dev.codexpocket.app.data.ThreadKey.decode(encoded) }.getOrNull()?.let { repository.openChat(it) }
            intent.removeExtra("pocketChat")
        }
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "codexpocket" && intent.data?.host == "notification") {
            intent.data?.lastPathSegment?.let(repository::openNotification); intent.data = null; return
        }
        if (intent?.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        intent?.getStringExtra(Intent.EXTRA_TEXT)?.take(1_000_000)?.takeIf(String::isNotBlank)?.let(repository::receiveSharedText)
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty() else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) repository.attach(uris.filter { it.scheme == "content" })
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.action = Intent.ACTION_MAIN
    }
}
