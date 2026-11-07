package dev.codexpocket.app.push

import com.huawei.hms.push.HmsMessageService
import dev.codexpocket.app.PocketApplication

/** Notification messages are rendered by HMS; the payload contains no private text. */
class PocketMessagingService : HmsMessageService() {
    override fun onNewToken(token: String?) {
        if (!token.isNullOrBlank()) (application as PocketApplication).repository.registerPush()
    }
}
