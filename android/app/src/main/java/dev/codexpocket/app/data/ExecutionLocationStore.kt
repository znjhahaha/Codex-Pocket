package dev.codexpocket.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.codexpocket.wire.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

internal interface LocationBlob {
    fun read(): String?
    fun write(value: String)
}

/** The catalogue and its credentials commit together in one Keystore-encrypted record. */
internal class EncryptedLocationBlob(context: Context, name: String = "execution_locations",
    private val alias: String = "codex-pocket-locations-v1", purpose: String = "dev.codexpocket.app/locations/v1") : LocationBlob {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val aad = purpose.toByteArray()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    override fun read(): String? {
        val encrypted = prefs.getString("value", null) ?: return null
        require(encrypted.length <= 6 * 1024 * 1024) { "Execution locations record is too large" }
        val iv = Base64.decode(prefs.getString("iv", null) ?: error("Execution locations record is damaged"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); updateAAD(aad) }
        val bytes = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
        return try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
    }
    override fun write(value: String) {
        val bytes = value.toByteArray()
        try {
            require(bytes.size <= 4 * 1024 * 1024)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad) }
            val encrypted = cipher.doFinal(bytes)
            check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("value", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "Could not save execution locations" }
        } finally { bytes.fill(0) }
    }
}

internal class ExecutionLocationStore(private val blob: LocationBlob, private val legacy: PairingStorage) {
    constructor(context: Context) : this(EncryptedLocationBlob(context), PairStore(context))
    private data class Record(val catalog: ExecutionTargetDirectory, val pairs: Map<String, PairedComputer>, val connectors: Map<String, JsonObject>)
    private var current: Record? = null
    /** Why the saved record could not be read. Pairing again replaces it, as it always replaced an unreadable pairing. */
    private var unreadable: Exception? = null
    /** A phone with nothing saved still has one place to pair; it is written only once it holds a pairing. */
    private val unsaved = ExecutionTarget("default", ExecutionTargetKind.Computer, "电脑")
    private fun shown(catalog: ExecutionTargetDirectory) = if (catalog.targets.isEmpty()) catalog.put(unsaved) else catalog
    private val changes = MutableStateFlow(shown(ExecutionTargetDirectory()))
    val directoryChanges = changes.asStateFlow()

    private fun record(): Record {
        current?.let { return it }
        val result = try { restore() } catch (failure: Exception) {
            unreadable = failure
            return Record(ExecutionTargetDirectory(), emptyMap(), emptyMap())
        }
        unreadable = null
        current = result
        changes.value = shown(result.catalog)
        return result
    }

    private fun restore(): Record {
        val saved = blob.read()
        if (saved != null) {
            val json = pocketJson.parseToJsonElement(saved).jsonObject
            require(json["version"]?.jsonPrimitive?.int == 1) { "Unsupported execution locations format" }
            val catalog = ExecutionTargetDirectory.parse(json.getValue("catalog").jsonObject)
            val pairs = json.getValue("pairs").jsonObject.mapValues { restoredPair(it.value.jsonObject) }
            require(pairs.all { (id, pair) -> catalog.targets.any { it.id == id && it.endpointId == pair.computerId } })
            val connectors = json.getValue("connectors").jsonObject.mapValues { it.value.jsonObject }
            require(connectors.keys.all { id -> catalog.targets.any { it.id == id } })
            return Record(catalog, pairs, connectors)
        }
        val pair = legacy.load() ?: return Record(ExecutionTargetDirectory(), emptyMap(), emptyMap())
        return Record(ExecutionTargetDirectory().withComputer(pair.computerId, pair.computerName.ifBlank { "电脑" }), mapOf(pair.computerId to pair), emptyMap())
            .also(::commit)
    }

    private fun commit(value: Record) {
        blob.write(buildJsonObject {
            put("version", 1); put("catalog", value.catalog.toJson())
            put("pairs", JsonObject(value.pairs.mapValues { it.value.persisted() }))
            put("connectors", JsonObject(value.connectors))
        }.toString())
        unreadable = null
        current = value
        changes.value = shown(value.catalog)
    }

    @Synchronized fun directory(): ExecutionTargetDirectory = shown(record().catalog)
    @Synchronized fun connector(id: String): JsonObject? = record().connectors[id]
    @Synchronized fun select(id: String) {
        val old = record()
        if (shown(old.catalog).selectedId != id) commit(old.copy(catalog = old.catalog.select(id)))
    }
    @Synchronized fun add(target: ExecutionTarget) { val old = record(); commit(old.copy(catalog = old.catalog.put(target))) }
    @Synchronized fun register(target: ExecutionTarget, pair: PairedComputer, connector: JsonObject? = null) {
        val old = record()
        val catalog = old.catalog.put(target).bind(target.id, pair.computerId)
        commit(Record(catalog, old.pairs + (target.id to pair),
            if (connector == null) old.connectors else old.connectors + (target.id to connector)))
    }
    @Synchronized fun remove(id: String) {
        val old = record()
        if (old.catalog.targets.any { it.id == id }) commit(Record(old.catalog.remove(id), old.pairs - id, old.connectors - id))
    }
    @Synchronized private fun pair(id: String): PairedComputer? {
        val saved = record().pairs[id]
        unreadable?.let { throw it }
        return saved
    }
    @Synchronized private fun updatePair(id: String, pair: PairedComputer) {
        val target = shown(record().catalog).targets.firstOrNull { it.id == id } ?: error("Execution location was removed")
        register(if (target.kind == ExecutionTargetKind.Computer) target.copy(label = pair.computerName.ifBlank { "电脑" }) else target, pair)
    }

    /** A reconnect always saves to its own target even if the user has switched elsewhere. */
    fun pairing(id: String): PairingStorage = object : PairingStorage {
        override fun load() = pair(id)
        override fun save(pair: PairedComputer) = updatePair(id, pair)
        override fun forget() = remove(id)
    }
}
