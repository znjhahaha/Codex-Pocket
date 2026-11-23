package dev.codexpocket.app.data

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** A model service the person brings their own key for, kept the way CC Switch keeps one: a name, an address, a key and a model. */
internal class LocalProvider(val id: String, val name: String, val baseUrl: String, val apiKey: String, val model: String) {
    /** Enough of the key to tell two apart, never enough to use. */
    val keyHint: String get() = if (apiKey.length < 12) "••••" else apiKey.take(3) + "…" + apiKey.takeLast(4)
    val summary get() = LocalProviderSummary(id, name, baseUrl, model, keyHint)
    // Not a data class: the key must never reach a log line through toString.
    override fun toString() = "LocalProvider($id)"
}

/** What a screen may show of a provider. */
internal data class LocalProviderSummary(val id: String, val name: String, val baseUrl: String, val model: String, val keyHint: String)

/** The saved services and which one Codex on this phone uses; none in use means OpenAI's own sign-in. */
internal data class LocalAccess(val providers: List<LocalProviderSummary> = emptyList(), val activeId: String? = null) {
    val active: LocalProviderSummary? get() = providers.firstOrNull { it.id == activeId }
}

/** What the person typed. A blank key on an existing provider keeps the saved one. */
internal class LocalProviderDraft(val id: String?, val name: String, val baseUrl: String, val apiKey: String, val model: String)

internal object LocalProviderRules {
    const val LIMIT = 32
    fun name(value: String): String? = value.trim().takeIf { it.length in 1..40 && it.none(Char::isISOControl) }

    /**
     * The address Codex appends `/responses` to. Codex on a phone cannot look names up itself: it reaches
     * them through the application's tunnel, which carries HTTPS only, so plain HTTP is for numeric addresses.
     */
    fun baseUrl(value: String): String? {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.length !in 8..512 || trimmed.any { it.code !in 0x21..0x7e || it == '"' || it == '\\' }) return null
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.takeIf(String::isNotEmpty) ?: return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        return when (uri.scheme?.lowercase()) {
            "https" -> trimmed
            "http" -> trimmed.takeIf { host.startsWith("[") || NUMERIC.matches(host) }
            else -> null
        }
    }
    fun apiKey(value: String): String? = value.trim().takeIf { it.length in 8..4096 && it.all { c -> c.code in 0x21..0x7e } }
    /** Blank asks for whatever model Codex would use by default. */
    fun model(value: String): String? = value.trim().takeIf { it.length <= 128 && it.all { c -> c.code in 0x21..0x7e && c != '"' && c != '\\' } }
    private val NUMERIC = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}

/** Providers and their keys commit together in one Keystore-encrypted record; nothing of them is written anywhere else. */
internal class LocalProviderStore(private val blob: LocationBlob) {
    private var providers: List<LocalProvider> = emptyList()
    private var activeId: String? = null
    private val changes = MutableStateFlow(LocalAccess())
    val access = changes.asStateFlow()

    init {
        // A record this phone can no longer decrypt holds keys nobody can recover; the person enters them again.
        runCatching { blob.read()?.let(Json::parseToJsonElement)?.jsonObject }.getOrNull()?.let { record ->
            providers = (record["providers"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val o = entry as? JsonObject ?: return@mapNotNull null
                fun text(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                LocalProvider(text("id") ?: return@mapNotNull null, text("name") ?: return@mapNotNull null,
                    text("baseUrl") ?: return@mapNotNull null, text("apiKey") ?: return@mapNotNull null, text("model").orEmpty())
            }.take(LocalProviderRules.LIMIT)
            activeId = (record["active"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { id -> providers.any { it.id == id } }
        }
        publish()
    }
    private fun publish() { changes.value = LocalAccess(providers.map { it.summary }, activeId) }
    private fun commit(next: List<LocalProvider>, active: String?) {
        blob.write(buildJsonObject {
            put("version", 1); put("active", active?.let(::JsonPrimitive) ?: JsonNull)
            putJsonArray("providers") { next.forEach { p -> addJsonObject {
                put("id", p.id); put("name", p.name); put("baseUrl", p.baseUrl); put("apiKey", p.apiKey); put("model", p.model)
            } } }
        }.toString())
        providers = next; activeId = active; publish()
    }

    @Synchronized fun active(): LocalProvider? = providers.firstOrNull { it.id == activeId }
    /** Data-layer lookups only; screens receive summaries, never this saved credential. */
    @Synchronized fun find(id: String): LocalProvider? = providers.firstOrNull { it.id == id }
    /** Saves what was typed and puts it in use. Null when something typed cannot be used as it is. */
    @Synchronized fun save(draft: LocalProviderDraft): String? {
        val existing = draft.id?.let { id -> providers.firstOrNull { it.id == id } ?: return null }
        val name = LocalProviderRules.name(draft.name) ?: return null
        val baseUrl = LocalProviderRules.baseUrl(draft.baseUrl) ?: return null
        val model = LocalProviderRules.model(draft.model) ?: return null
        val key = if (existing != null && draft.apiKey.isBlank()) existing.apiKey else LocalProviderRules.apiKey(draft.apiKey) ?: return null
        if (existing == null && providers.size >= LocalProviderRules.LIMIT) return null
        val saved = LocalProvider(existing?.id ?: UUID.randomUUID().toString(), name, baseUrl, key, model)
        commit(if (existing == null) providers + saved else providers.map { if (it.id == saved.id) saved else it }, saved.id)
        return saved.id
    }
    /** Null goes back to OpenAI's own sign-in; the saved services stay. */
    @Synchronized fun use(id: String?): Boolean {
        if (id != null && providers.none { it.id == id }) return false
        if (id != activeId) commit(providers, id)
        return true
    }
    @Synchronized fun remove(id: String) {
        if (providers.any { it.id == id }) commit(providers.filter { it.id != id }, activeId.takeIf { it != id })
    }
}

/**
 * Tells Codex on this phone which service to use by editing only what belongs to Pocket in its
 * `config.toml`. The key is not written there: Codex reads it from [KEY_VARIABLE] in its environment.
 */
internal object LocalCodexConfig {
    const val PROVIDER = "pocket"
    const val KEY_VARIABLE = "POCKET_PROVIDER_API_KEY"
    private const val MANAGED = "# pocket-provider"
    private const val SAVED = "# pocket-saved: "
    private val HEADER = Regex("""^\s*\[\[?\s*([^\[\],=]+?)\s*]]?\s*(#.*)?$""")
    private val MODEL = Regex("""^\s*model\s*=""")
    private val MODEL_PROVIDER = Regex("""^\s*model_provider\s*=""")
    private val SHELL_POLICY = Regex("""^\s*shell_environment_policy\s*[.=]""")

    fun apply(existing: String, provider: LocalProvider?): String {
        val top = mutableListOf<String>()
        val tables = mutableListOf<String>()
        val restored = sortedSetOf<Int>()
        var inTables = false; var skipping = false; var guarded = false
        for (line in existing.lineSequence()) {
            HEADER.matchEntire(line)?.groupValues?.get(1)?.filterNot { it == ' ' || it == '"' || it == '\'' }?.let { name ->
                inTables = true
                skipping = name == "model_providers.$PROVIDER" || name.startsWith("model_providers.$PROVIDER.")
                if (name == "shell_environment_policy" || name.startsWith("shell_environment_policy.")) guarded = true
            }
            when {
                skipping -> Unit
                inTables -> tables += line
                MODEL_PROVIDER.containsMatchIn(line) || line.trimEnd().endsWith(MANAGED) -> Unit
                line.startsWith(SAVED) -> { restored += top.size; top += line.removePrefix(SAVED) }
                else -> { if (SHELL_POLICY.containsMatchIn(line)) guarded = true; top += line }
            }
        }
        // What the person had chosen for OpenAI comes back unless Codex has since been given another.
        val chosen = top.indices.any { it !in restored && MODEL.containsMatchIn(top[it]) }
        val kept = top.filterIndexedTo(mutableListOf()) { index, _ -> index !in restored || (!chosen && index == restored.first()) }
        val head = mutableListOf<String>()
        if (provider != null) {
            head += "model_provider = \"$PROVIDER\" $MANAGED"
            if (provider.model.isNotEmpty()) {
                head += "model = ${quote(provider.model)} $MANAGED"
                kept.replaceAll { if (MODEL.containsMatchIn(it)) SAVED + it else it }
            }
            tables += listOf("", "[model_providers.$PROVIDER]", "name = ${quote(provider.name)}", "base_url = ${quote(provider.baseUrl)}",
                "wire_api = \"responses\"", "env_key = \"$KEY_VARIABLE\"")
        }
        // Commands Codex runs inherit its environment; the key is not theirs to read.
        if (!guarded) tables += listOf("", "[shell_environment_policy]", "exclude = [\"$KEY_VARIABLE\"]")
        val lines = (head + kept + listOf("") + tables).dropWhile(String::isBlank).dropLastWhile(String::isBlank)
        return buildString {
            var blank = false
            for (line in lines) {
                if (line.isBlank() && blank) continue
                blank = line.isBlank()
                append(line.trimEnd('\r')).append('\n')
            }
        }
    }

    private fun quote(value: String) = buildString {
        append('"')
        for (c in value) when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c.code < 0x20 || c.code == 0x7f -> append("\\u%04X".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
