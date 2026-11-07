package dev.codexpocket.app.ui

/** Null means native Default; unsupported values must never be presented as a supported level. */
internal fun supportedEffortChoices(efforts: List<String>): List<String?> {
    return listOf("low", "medium", "high", "xhigh", "max").filter { it in efforts }
}

/** A missing or ambiguous selection has no check mark, including ambiguous advertised defaults. */
internal fun <T> modelMenuSelection(models: List<T>, requested: String?, modelKey: (T) -> String,
    isDefault: (T) -> Boolean): T? =
    if (requested == null) models.singleOrNull(isDefault) else models.singleOrNull { modelKey(it) == requested }
