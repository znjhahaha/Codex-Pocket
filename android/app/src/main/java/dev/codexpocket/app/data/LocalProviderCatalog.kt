package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** A configured model. Null capabilities mean the service has not declared them. */
internal data class LocalProviderModel(
    val id: String,
    val efforts: List<String>? = null,
    val defaultEffort: String? = null,
    val images: Boolean? = null,
) {
    fun native(defaultModel: String) = NativeModel(id, id, id, "", efforts.orEmpty(), defaultEffort.orEmpty(), images == true, id == defaultModel)
    fun json() = buildJsonObject {
        put("id", id)
        efforts?.let { put("efforts", JsonArray(it.map(::JsonPrimitive))) }
        defaultEffort?.let { put("defaultEffort", it) }
        images?.let { put("images", it) }
    }
    /** New declarations replace old capabilities; absent fields leave manual configuration intact. */
    fun refreshed(declared: LocalProviderModel?) = if (declared == null) this else copy(
        efforts = declared.efforts ?: efforts,
        defaultEffort = declared.defaultEffort ?: defaultEffort?.takeIf { declared.efforts == null || it in declared.efforts },
        images = declared.images ?: images,
    )
    companion object {
        val EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        fun initial(model: String) = model.takeIf(String::isNotBlank)?.let { listOf(LocalProviderModel(it)) }.orEmpty()
        fun from(o: JsonObject): LocalProviderModel? {
            val id = o.string("id").takeIf { it.isNotEmpty() && LocalProviderRules.model(it) == it } ?: return null
            val efforts = (o["efforts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.filter { it in EFFORTS }?.distinct()
            return LocalProviderModel(id, efforts, o.string("defaultEffort").takeIf { it in efforts.orEmpty() },
                (o["images"] as? JsonPrimitive)?.booleanOrNull)
        }
        fun configured(o: JsonObject, defaultModel: String): List<LocalProviderModel> =
            (o["enabledModels"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::from) }
                ?.distinctBy { it.id } ?: initial(defaultModel)

        fun declared(id: String, o: JsonObject): LocalProviderModel {
            val raw = o["supportedReasoningEfforts"] ?: o["supported_reasoning_efforts"] ?: o["supported_reasoning_levels"] ?: o["reasoning_efforts"]
            val efforts = (raw as? JsonArray)?.mapNotNull { entry ->
                (entry as? JsonPrimitive)?.contentOrNull ?: (entry as? JsonObject)?.let {
                    it.string("reasoningEffort", it.string("reasoning_effort", it.string("effort"))).takeIf(String::isNotBlank)
                }
            }?.filter { it in EFFORTS }?.distinct()
            val default = o.string("defaultReasoningEffort", o.string("default_reasoning_effort", o.string("default_reasoning_level")))
            val modalities = (o["inputModalities"] ?: o["input_modalities"]) as? JsonArray
            return LocalProviderModel(id, efforts, default.takeIf { it in efforts.orEmpty() }, modalities?.any { it == JsonPrimitive("image") })
        }
    }
}
