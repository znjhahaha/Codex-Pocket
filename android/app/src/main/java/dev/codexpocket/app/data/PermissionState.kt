package dev.codexpocket.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** A helper-advertised choice. Its ID is opaque; native policy/config remains on the computer. */
data class PermissionPreset(
    val id: String,
    val label: String,
    val description: String = "",
    val enabled: Boolean = false,
    val reason: String? = null,
)

/** Current check marks come only from authoritative reads, never a pending phone selection. */
data class PermissionState(
    val loading: Boolean = false,
    val updating: Boolean = false,
    val status: String = "unknown",
    val revision: JsonElement? = null,
    val currentPresetId: String? = null,
    val presets: List<PermissionPreset> = emptyList(),
    val error: String? = null,
    val currentLabel: String? = null,
) {
    val label: String
        get() = presets.singleOrNull { it.id == currentPresetId }?.label?.takeIf(String::isNotBlank)
            ?: currentLabel?.takeIf { status == "ready" && revision != null && currentPresetId in setOf(null, "custom") && it.isNotBlank() }
            ?: when (status) {
                "ready" -> if (revision != null && currentPresetId in setOf(null, "custom")) "自定义权限" else "会话权限未同步"
                "initializing" -> "正在同步权限"
                "update_required" -> "电脑桥接待更新"
                "unsupported" -> "权限选择不可用"
                else -> "会话权限未同步"
            }

    companion object {
        fun from(response: JsonObject): PermissionState {
            val parsed = (response["presets"] as? JsonArray).orEmpty().mapNotNull { value ->
                val row = value as? JsonObject ?: return@mapNotNull null
                val id = opaque(row["id"], 512) ?: return@mapNotNull null
                val label = text(row["label"], 160) ?: return@mapNotNull null
                PermissionPreset(id, label, text(row["description"], 600).orEmpty(),
                    (row["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true,
                    text(row["reason"], 600))
            }
            // Ambiguous IDs cannot be used for a mutation, even when one duplicate is enabled.
            val counts = parsed.groupingBy { it.id }.eachCount()
            val presets = parsed.filter { counts[it.id] == 1 }
            val status = text(response["status"], 32)?.takeIf {
                it in setOf("ready", "unknown", "unsupported", "error", "initializing", "update_required")
            } ?: "unknown"
            val failed = status == "error" || (response["error"] != null && response["error"] != JsonNull)
            return PermissionState(
                status = if (failed) "error" else status,
                revision = opaque(response["revision"], 1024)?.let(::JsonPrimitive),
                currentPresetId = opaque(response["currentPresetId"], 512),
                presets = presets,
                // Never preserve a generic upstream error object, credentials or config text.
                error = "读取会话权限失败，请重试".takeIf { failed },
                currentLabel = text(response["currentLabel"], 160),
            )
        }

        private fun opaque(value: JsonElement?, maxLength: Int): String? =
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf {
                it.isNotBlank() && it.length <= maxLength && it.none(Char::isISOControl)
            }

        private fun text(value: JsonElement?, maxLength: Int): String? =
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.take(maxLength)?.map { if (it.isISOControl()) ' ' else it }?.joinToString("")
                ?.trim()?.takeIf(String::isNotBlank)
    }
}
