package dev.codexpocket.app.push

import com.huawei.hms.push.HmsMessageService
import dev.codexpocket.app.PocketApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** Notification messages are rendered by HMS; the payload contains no private text. */
class PocketMessagingService : HmsMessageService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onNewToken(token: String?) {
        if (!token.isNullOrBlank()) scope.launch {
            (application as PocketApplication).locations.active.filterNotNull().first().registerPush().join()
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
