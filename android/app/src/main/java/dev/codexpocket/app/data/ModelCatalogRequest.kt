package dev.codexpocket.app.data

/** One captured model service. A changed service invalidates its outstanding catalog read. */
class ModelCatalogRequest(val isCurrent: () -> Boolean, val saved: List<NativeModel> = emptyList(), val load: suspend () -> List<NativeModel>)
