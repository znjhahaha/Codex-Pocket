package dev.codexpocket.app.data

import android.content.SharedPreferences
import kotlinx.serialization.json.*

/** Positive server role evidence survives offline cache reloads; chat/history rows stay intact. */
internal class InternalThreadDirectory(private val preferences: SharedPreferences) {
    @Volatile private var hidden = preferences.getStringSet("assistantInternalThreads.v1", emptySet()).orEmpty()
        .mapNotNull {runCatching {ThreadKey.decode(it)}.getOrNull()}.toSet()

    fun contains(key: ThreadKey) = key in hidden

    @Synchronized fun accept(raw: JsonObject, computer: String, runtimes: List<RuntimeInfo>, cached: List<ChatEntity>): Boolean {
        if(computer.isBlank() || (raw["hiddenThreadsAuthoritative"] as? JsonPrimitive)?.let {!it.isString && it.booleanOrNull==true}!=true) return false
        val rows=raw["hiddenThreads"] as? JsonArray ?: return false
        val additions=rows.mapNotNull {value ->
            val row=value as? JsonObject ?: return@mapNotNull null
            fun text(name:String)=(row[name] as? JsonPrimitive)?.takeIf {it.isString}?.content?.takeIf {it.isNotBlank() && it.length<=256 && '\u0000' !in it}
            if(text("role")!="assistant_coordinator") return@mapNotNull null
            val runtime=text("runtimeId") ?: return@mapNotNull null
            val store=text("configStoreId") ?: return@mapNotNull null
            val thread=text("threadId") ?: return@mapNotNull null
            val key=ThreadKey(computer,store,thread)
            val live=runtimes.firstOrNull {it.id==runtime}
            if(if(live!=null) live.configStoreId==store else cached.any {it.key==key && it.runtimeId==runtime}) key else null
        }.toSet()
        val next=hidden+additions
        if(next==hidden) return false
        hidden=next
        preferences.edit().putStringSet("assistantInternalThreads.v1",next.map {it.encoded}.toSet()).apply()
        return true
    }
}
