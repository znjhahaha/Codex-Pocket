package dev.codexpocket.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** No activity, repository, DB connection or decryption: compare durable bytes only. */
@RunWith(AndroidJUnit4::class)
class StoredStatePreservationDeviceTest {
    @Test fun fingerprintExistingPairingAndHistoryWithoutOpeningThem() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mode = InstrumentationRegistry.getArguments().getString("preservationProbe")
        assumeTrue("Explicit preservation probe required", mode == "before" || mode == "after")
        val context = instrumentation.targetContext
        val root = File(context.applicationInfo.dataDir)
        val probe = InstrumentationRegistry.getArguments().getString("probeName") ?: "original"
        require(probe.matches(Regex("[a-zA-Z0-9_-]{1,32}")))
        val names = listOf("shared_prefs/encrypted_pair.xml", "databases/pocket.db", "databases/pocket.db-wal")
        val hashes = JSONObject()
        names.forEach { name ->
            val file = File(root, name)
            hashes.put(name, if (!file.exists()) "absent" else {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
                digest.digest().joinToString("") { "%02x".format(it) }
            })
        }
        val evidence = File(context.getExternalFilesDir(null), if (probe == "original") "pocket-preservation-before.json" else "pocket-preservation-$probe-before.json")
        if (mode == "before") {
            check(!evidence.exists()) { "Preserve the earlier evidence; choose a new probe name before repeating" }
            evidence.writeText(hashes.toString(2))
        } else {
            val baseline = JSONObject(evidence.readText())
            File(context.getExternalFilesDir(null), "pocket-preservation-$probe-after.json").writeText(hashes.toString(2))
            names.forEach { name -> assertEquals("Durable state changed: $name", baseline.getString(name), hashes.getString(name)) }
        }
        println("POCKET_PRESERVATION mode=$mode checked=${names.size} unchanged=${mode == "after"} noDecryption=true")
    }
}
