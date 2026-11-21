package dev.codexpocket.wire

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal fun shellWord(value: String): String {
    require(value.isNotEmpty() && value.none(Char::isISOControl))
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

data class SshPreparation(val pairing: PairingCode, val backendPort: Int, val nativeInstalled: Boolean)

/** Installs only the signed, user-level host. Existing identities and native tasks survive retries. */
suspend fun prepareSshHost(target: SshTarget, bundleFor: suspend (String) -> LinuxRuntimeBundle,
    progress: (String) -> Unit = {}): SshPreparation {
    progress("正在验证 SSH 身份")
    SshSession.open(target).use { ssh ->
        val discovery = ssh.execute("""
            set -eu
            [ "${'$'}(uname -s)" = Linux ] || exit 61
            printf '%s\n' "${'$'}(uname -m)"
            cd -P -- "${'$'}HOME"
            pwd -P
        """.trimIndent()).trim().lines()
        require(discovery.size == 2 && discovery[0] in setOf("x86_64", "aarch64")) { "Unsupported Linux architecture" }
        val home = discovery[1]
        require(home.startsWith('/') && home.none(Char::isISOControl))
        val bundle = bundleFor(discovery[0])
        require(bundle.architecture == discovery[0])
        val root = "$home/.local/share/codex-pocket"
        val state = "$home/.local/state/codex-pocket/agent"
        val bin = "$root/versions/${bundle.generation}"
        fun unitPath(path: String) = path.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%")
        val unit = "[Unit]\nDescription=Codex Pocket\n[Service]\nExecStart=\"${unitPath(bin)}/pocket-agent\" run --state \"${unitPath(state)}\"\nRestart=on-failure\nRestartSec=2\n[Install]\nWantedBy=default.target\n"
        val quotedUnit = "'" + unit.replace("'", "'\"'\"'") + "'"
        progress("正在准备远程运行环境")
        ssh.execute("""
            set -eu
            umask 077
            for folder in ${shellWord("$home/.local")} ${shellWord("$home/.local/share")} ${shellWord(root)} ${shellWord("$root/versions")} ${shellWord(bin)}; do
              [ ! -L "${'$'}folder" ] || exit 62
              mkdir -p -- "${'$'}folder"
            done
        """.trimIndent())
        for (name in bundle.files) ssh.upload(java.io.File(bundle.directory, name), "$bin/$name.upload")
        val finalize = bundle.files.joinToString("\n") { name ->
            "mv -f -- ${shellWord("$bin/$name.upload")} ${shellWord("$bin/$name")}"
        }
        ssh.execute("set -eu\n$finalize\nchmod 700 -- ${shellWord("$bin/pocket-agent")} ${shellWord("$bin/pocket-runtime-host")}\n")
        progress("正在启动远程后台")
        val port = target.backendPort
        val startup = """
            set -eu
            umask 077
            state=${shellWord(state)}
            bin=${shellWord(bin)}
            root=${shellWord(root)}
            export PATH="${'$'}HOME/.local/bin:${'$'}HOME/.npm-global/bin:${'$'}PATH"
            mkdir -p -- "${'$'}state" "${'$'}HOME/.codex"
            [ ! -L "${'$'}state" ] && [ ! -L "${'$'}state/config.json" ] || exit 62
            if [ ! -f "${'$'}state/config.json" ]; then
              "${'$'}bin/pocket-agent" init --state "${'$'}state" --lan-bind 127.0.0.1:$port --policy lan --json >/dev/null
            fi
            if "${'$'}bin/pocket-agent" health --state "${'$'}state" --json >/dev/null 2>&1; then
              :
            elif command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
              mkdir -p "${'$'}HOME/.config/systemd/user"
              printf '%s' $quotedUnit > "${'$'}HOME/.config/systemd/user/codex-pocket.service"
              systemctl --user daemon-reload
              systemctl --user enable --now codex-pocket.service >/dev/null
            else
              nohup "${'$'}bin/pocket-agent" run --state "${'$'}state" </dev/null >>"${'$'}state/host.log" 2>&1 &
            fi
            n=0
            until "${'$'}bin/pocket-agent" health --state "${'$'}state" --json >/dev/null 2>&1; do
              n=${'$'}((n+1)); [ "${'$'}n" -lt 30 ] || exit 63; sleep 1
            done
            "${'$'}bin/pocket-agent" pair --state "${'$'}state" --json >/dev/null
            cat "${'$'}state/pairing-qr.json"
        """.trimIndent()
        val raw = ssh.execute(startup, 60)
        val code = PairingCode.parse(raw)
        val route = code.routes.firstOrNull { it.kind == "lan" && java.net.URI(it.url).host == "127.0.0.1" }
            ?: throw WireException("ssh_backend_route_missing", "Remote host has no private loopback route")
        progress("正在检查 Codex")
        val installed = ssh.execute("export PATH=\"${'$'}HOME/.local/bin:${'$'}HOME/.npm-global/bin:${'$'}PATH\"\nif command -v codex >/dev/null 2>&1; then printf yes; else printf no; fi\n").trim() == "yes"
        return SshPreparation(code, java.net.URI(route.url).port, installed)
    }
}
