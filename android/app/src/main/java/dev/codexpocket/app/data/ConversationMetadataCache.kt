package dev.codexpocket.app.data

import android.content.SharedPreferences
import kotlinx.serialization.json.*

internal fun PermissionState.unverifiedDisplay() = copy(
    status = if (status == "ready") "saved" else status, revision = null,
    presets = presets.map { it.copy(enabled = false) }, loading = false, updating = false,
)

/** Display metadata survives cold starts, but never carries a write revision or owner proof. */
internal class ConversationMetadataCache(private val preferences: SharedPreferences) {
    private fun key(chat: ChatEntity) = JsonArray(listOf(chat.key.encoded, chat.runtimeId).map(::JsonPrimitive)).toString()
    private fun read(chat: ChatEntity): JsonObject = runCatching {
        pocketJson.parseToJsonElement(preferences.getString(key(chat), "{}")!!).asObject()
    }.getOrDefault(buildJsonObject {})

    @Synchronized private fun put(chat: ChatEntity, field: String, value: JsonElement) {
        val id = key(chat)
        val entry = JsonObject(read(chat) + mapOf(field to value, "at" to JsonPrimitive(System.currentTimeMillis())))
        val edit = preferences.edit().putString(id, entry.toString())
        val old = preferences.all.filterKeys { it != id }.entries.sortedBy {
            runCatching { pocketJson.parseToJsonElement(it.value as String).asObject().number("at") }.getOrDefault(0)
        }
        old.take((old.size - 23).coerceAtLeast(0)).forEach { edit.remove(it.key) }
        edit.apply()
    }

    @Synchronized fun permissions(chat: ChatEntity): PermissionState? =
        (read(chat)["permissions"] as? JsonObject)?.let { PermissionState.from(it) }

    fun savePermissions(chat: ChatEntity, state: PermissionState) {
        if (state.status !in setOf("ready", "saved")) return
        put(chat, "permissions", buildJsonObject {
            put("status", "saved")
            state.currentPresetId?.let { put("currentPresetId", it) }
            put("currentLabel", state.label)
            put("presets", buildJsonArray { state.presets.forEach { preset -> add(buildJsonObject {
                put("id", preset.id); put("label", preset.label); put("description", preset.description)
                put("enabled", false)
            }) } })
        })
    }

    @Synchronized fun models(chat: ChatEntity): List<NativeModel> =
        read(chat).array("models").map { NativeModel.from(it.asObject()) }

    fun saveModels(chat: ChatEntity, models: List<NativeModel>) {
        put(chat, "models", buildJsonArray { models.take(200).forEach { model -> add(buildJsonObject {
            put("id", model.id); put("model", model.model); put("displayName", model.name)
            put("description", model.description); put("defaultReasoningEffort", model.defaultEffort)
            put("isDefault", model.isDefault)
            put("supportedReasoningEfforts", buildJsonArray { model.efforts.forEach {
                add(buildJsonObject { put("reasoningEffort", it) })
            } })
            put("inputModalities", buildJsonArray { add("text"); if (model.images) add("image") })
            put("serviceTiers", JsonArray(model.serviceTiers.map(::JsonPrimitive)))
            put("additionalSpeedTiers", JsonArray(model.additionalSpeedTiers.map(::JsonPrimitive)))
        }) } })
    }
}

/** Only evidence that can change ownership invalidates a verified owner. */
internal fun ownershipInventoryChanged(chat: ChatEntity, before: List<RuntimeInfo>, after: List<RuntimeInfo>): Boolean {
    fun signature(rows: List<RuntimeInfo>) = rows.filter {
        it.configStoreId == chat.key.configStoreId && (it.id == chat.runtimeId || chat.key.nativeId in it.loadedThreadIds)
    }.map { listOf(it.id, it.source, it.online, it.role, it.version, chat.key.nativeId in it.loadedThreadIds) }.toSet()
    return signature(before) != signature(after)
}
