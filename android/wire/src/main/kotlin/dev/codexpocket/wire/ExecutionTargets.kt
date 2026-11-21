package dev.codexpocket.wire

import kotlinx.serialization.json.*

enum class ExecutionTargetKind(val wire: String) {
    Computer("computer"), LinuxSsh("linux_ssh"), ThisPhone("android_local");

    companion object {
        fun parse(value: String) = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unsupported execution location")
    }
}

/** A saved location is not a claim of connectivity or native control capability. */
data class ExecutionTarget(
    val id: String,
    val kind: ExecutionTargetKind,
    val label: String,
    val endpointId: String? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= 256 && id.none(Char::isISOControl))
        require(label.isNotBlank() && label.length <= 120 && label.none(Char::isISOControl))
        require(endpointId == null || endpointId.isNotBlank() && endpointId.length <= 256 && endpointId.none(Char::isISOControl))
    }

    fun toJson() = buildJsonObject {
        put("id", id); put("kind", kind.wire); put("label", label)
        endpointId?.let { put("endpointId", it) }
    }

    companion object {
        fun parse(value: JsonObject) = ExecutionTarget(value.string("id"),
            ExecutionTargetKind.parse(value.string("kind")), value.string("label"),
            value["endpointId"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
    }
}

/** Credential-free catalogue. Endpoint IDs remain the computerId part of existing ThreadKeys. */
data class ExecutionTargetDirectory(
    val targets: List<ExecutionTarget> = emptyList(),
    val selectedId: String? = null,
) {
    init {
        require(targets.size <= 64 && targets.map { it.id }.distinct().size == targets.size)
        val endpoints = targets.mapNotNull { it.endpointId }
        require(endpoints.distinct().size == endpoints.size) { "An endpoint already belongs to another location" }
        require(selectedId == null || targets.any { it.id == selectedId })
    }
    val selected: ExecutionTarget? get() = targets.firstOrNull { it.id == selectedId }

    fun withComputer(endpointId: String, label: String): ExecutionTargetDirectory {
        val existing = targets.firstOrNull { it.endpointId == endpointId }
        require(existing == null || existing.kind == ExecutionTargetKind.Computer)
        return put(ExecutionTarget(existing?.id ?: endpointId, ExecutionTargetKind.Computer, label, endpointId))
    }

    fun put(target: ExecutionTarget): ExecutionTargetDirectory {
        val old = targets.firstOrNull { it.id == target.id }
        require(old == null || old.kind == target.kind && (old.endpointId == null || old.endpointId == target.endpointId)) {
            "Execution location identity changed"
        }
        return copy(targets = if (old == null) targets + target else targets.map { if (it.id == target.id) target else it },
            selectedId = selectedId ?: target.id)
    }

    fun bind(id: String, endpointId: String): ExecutionTargetDirectory =
        put(targets.firstOrNull { it.id == id }?.copy(endpointId = endpointId)
            ?: throw IllegalArgumentException("Unknown execution location"))

    fun select(id: String): ExecutionTargetDirectory {
        require(targets.any { it.id == id }) { "Unknown execution location" }
        return copy(selectedId = id)
    }

    fun remove(id: String): ExecutionTargetDirectory {
        val remaining = targets.filterNot { it.id == id }
        return copy(targets = remaining, selectedId = if (selectedId == id) remaining.firstOrNull()?.id else selectedId)
    }

    fun toJson() = buildJsonObject {
        put("version", 1); put("targets", JsonArray(targets.map { it.toJson() }))
        selectedId?.let { put("selectedId", it) }
    }

    companion object {
        fun parse(value: JsonObject): ExecutionTargetDirectory {
            require(value.integer("version") == 1L) { "Unsupported execution locations format" }
            return ExecutionTargetDirectory(value.getValue("targets").jsonArray.map { ExecutionTarget.parse(it.jsonObject) },
                value["selectedId"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
        }
    }
}
