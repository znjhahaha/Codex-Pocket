package dev.codexpocket.app.data

import android.os.Build
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Tests the official sandbox's file boundary, never the emulated success of unshare under PRoot. */
internal object PhoneSandbox {
    fun verify(layout: LocalRuntimeLayout): Boolean {
        val revision = File(layout.root, "linux/rootfs/.pocket-environment.json").lastModified()
        val identity = "${Build.FINGERPRINT}:${layout.libraries.path}:$revision:2"
        val cache = File(layout.root, "sandbox-capability.json")
        runCatching { Json.parseToJsonElement(cache.readText()).jsonObject }.getOrNull()?.let {
            if (it.string("identity") == identity) return it.flag("supported")
        }
        // Keep the denied path outside TMPDIR, which workspace-write legitimately
        // grants to child processes in addition to the selected workspace.
        val probe = File(layout.root, "sandbox-probes/${UUID.randomUUID()}").apply { mkdirs() }
        val workspace = File(probe, "workspace").apply { mkdirs() }
        val outside = File(probe, "outside").apply { mkdirs() }
        val config = File(probe, "config").apply { mkdirs() }
        File(config, "config.toml").writeText("sandbox_mode = \"workspace-write\"\napproval_policy = \"on-request\"\n")
        val log = File(probe, "probe.log")
        val allowed = File(workspace, "allowed")
        val denied = File(outside, "denied")
        val script = "printf allowed > '${allowed.path}' || exit 11; if printf forbidden > '${denied.path}'; then exit 12; fi; printf DENIED"
        val supported = runCatching {
            val command = phoneLinuxCommand(layout, layout.codex, listOf("-c", "sandbox_mode=\"workspace-write\"", "sandbox", "--", "/bin/bash", "-c", script)).toMutableList()
            command[command.indexOf("-w") + 1] = workspace.path
            val process = ProcessBuilder(command).directory(workspace).apply {
                environment().putAll(layout.environment("http://127.0.0.1:1"))
                environment()["CODEX_HOME"] = config.path
            }.redirectErrorStream(true).redirectOutput(log).start()
            try {
                process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                    allowed.readText() == "allowed" && !denied.exists() && log.readText().contains("DENIED")
            } finally { if (process.isAlive) process.destroyForcibly() }
        }.getOrDefault(false)
        cache.writeText(buildJsonObject { put("identity", identity); put("supported", supported) }.toString())
        return supported
    }
}
