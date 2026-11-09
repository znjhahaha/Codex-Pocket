package dev.codexpocket.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.codexpocket.wire.ConnectionConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.*

class PairedComputer(val config: ConnectionConfig, val deviceId: String, val deviceSecret: String, val computerId: String, val computerName: String) {
    override fun toString() = "PairedComputer([redacted])"
}

internal fun PairedComputer.persisted(): JsonObject = buildJsonObject {
    config.toJson().forEach { (name, value) -> put(name, value) }
    put("deviceId", deviceId); put("deviceSecret", deviceSecret); put("computerId", computerId); put("computerName", computerName)
}

internal fun restoredPair(o: JsonObject) = PairedComputer(ConnectionConfig.parse(o), o.string("deviceId"), o.string("deviceSecret"), o.string("computerId"), o.string("computerName"))

interface PairingStorage {
    fun save(pair: PairedComputer)
    fun load(): PairedComputer?
    fun forget()
}

class PairStore(context: Context) : PairingStorage {
    private val prefs = context.getSharedPreferences("encrypted_pair", Context.MODE_PRIVATE)
    private val alias = "codex-pocket-pair-v1"
    private val aad = "dev.codexpocket.app/pair/v1".toByteArray()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    override fun save(pair: PairedComputer) {
        val data = pair.persisted().toString().toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad) }
            val encrypted = cipher.doFinal(data)
            check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).putString("value", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "Could not securely save pairing" }
        } finally { data.fill(0) }
    }
    override fun load(): PairedComputer? {
        val value = prefs.getString("value", null) ?: return null
        val iv = Base64.decode(prefs.getString("iv", null) ?: error("Pairing storage is damaged"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); updateAAD(aad) }
        val plain = cipher.doFinal(Base64.decode(value, Base64.NO_WRAP))
        return try {
            val o = pocketJson.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
            restoredPair(o)
        } finally { plain.fill(0) }
    }
    override fun forget() { check(prefs.edit().clear().commit()); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
}
