package dev.codexpocket.app.data

import kotlinx.serialization.json.JsonObject

data class SourcePreview(val path: String, val text: String, val truncated: Boolean, val workspaceRoot: String? = null)

/** Per-conversation secondary data; never used as authority for native write outcomes. */
data class AdvancedConversationState(
    val usage: JsonObject? = null,
    val goal: JsonObject? = null,
    val asyncQuestions: List<JsonObject> = emptyList(),
    val references: List<JsonObject> = emptyList(),
    val referencesLoading: Boolean = false,
    val referencesQuery: String = "",
    val referencesError: String? = null,
    val referencesUnsupported: Set<String> = emptySet(),
    val workspaceEntries: List<JsonObject> = emptyList(),
    val sourcePreview: SourcePreview? = null,
    val projectFiles: ProjectFileListing? = null,
    val projectFilesLoading: Boolean = false,
    val projectFilesError: String? = null,
    val fastMode: Boolean = false,
    val uploadProgress: Map<String, Float> = emptyMap(),
)
