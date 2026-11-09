package dev.codexpocket.app.data

import kotlinx.serialization.json.*

enum class ExtensionPhase { Idle, Loading, Ready, Offline, Unsupported, Failed }

/** Public inventory contains presentation metadata and opaque identities, never launch/config secrets. */
data class ManagedExtension(
    val id: String, val kind: String, val name: String, val description: String = "",
    val enabled: Boolean? = null, val status: String = "", val scope: String = "",
    val canToggle: Boolean = false, val reason: String = "", val version: String = "",
)

data class ExtensionManagementState(
    val computerId: String = "", val runtimeId: String = "", val phase: ExtensionPhase = ExtensionPhase.Idle,
    val entries: List<ManagedExtension> = emptyList(), val version: String = "", val updatedAt: Long = 0,
    val error: String? = null, val pendingIds: Set<String> = emptySet(), val uncertainIds: Set<String> = emptySet(),
    val scope: String = "", val reloadPending: Boolean = false, val status: String = "",
    val appInventoryStatus: String = "",
)

fun managedExtensions(value: JsonObject): List<ManagedExtension> = value.array("data").mapNotNull { raw ->
    val row = raw.asObject()
    val id = row.string("id")
    val kind = row.string("kind")
    if (id.isBlank() || kind !in setOf("mcp", "skill", "plugin", "app")) null
    else ManagedExtension(id, kind, row.string("name"), row.string("description"),
        (row["enabled"] as? JsonPrimitive)?.booleanOrNull, row.string("status"), row.string("scope"),
        row.obj("capabilities").flag("update"), row.string("reason"), row.string("version", value.string("version")))
}.distinctBy { it.id }
