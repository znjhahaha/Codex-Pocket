package dev.codexpocket.wire

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal fun shellWord(value: String): String {
    require(value.isNotEmpty() && value.none(Char::isISOControl))
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

/** [persistent] is false when the host runs but was not allowed to start again by itself after a reboot. */
data class SshPreparation(val pairing: PairingCode, val backendPort: Int, val nativeInstalled: Boolean, val persistent: Boolean = true)

/** Exit statuses the remote scripts reserve; anything else stays an unexplained preparation failure. */
internal val sshRemoteFailures = mapOf(61 to "ssh_platform_unsupported", 62 to "ssh_remote_path_unsafe",
    63 to "ssh_host_start_failed", 64 to "ssh_upgrade_rolled_back", 65 to "ssh_host_missing", 66 to "ssh_upload_failed")

/**
 * Brings the already installed host up when it is not running, without a bundle: a host that could not be
 * registered as a lingering service is down after a reboot until its phone comes back.
 */
internal val sshResumeScript = """
    set -eu
    [ "${'$'}(uname -s)" = Linux ] || exit 61
    home=${'$'}(cd -P -- "${'$'}HOME" && pwd -P)
    root="${'$'}home/.local/share/codex-pocket"
    state="${'$'}home/.local/state/codex-pocket/agent"
    generation=${'$'}(head -c 64 "${'$'}root/active" 2>/dev/null) || exit 65
    case "${'$'}generation" in *[!0-9a-f]*) exit 65 ;; esac
    agent="${'$'}root/versions/${'$'}generation/pocket-agent"
    [ "${'$'}{#generation}" -eq 64 ] && [ -x "${'$'}agent" ] && [ -x "${'$'}root/launch" ] || exit 65
    alive() { "${'$'}agent" health --state "${'$'}state" --json </dev/null >/dev/null 2>&1; }
    if alive; then exit 0; fi
    if command -v systemctl >/dev/null 2>&1 && systemctl --user is-enabled --quiet codex-pocket.service 2>/dev/null; then
      systemctl --user start codex-pocket.service
    else
      umask 077
      nohup "${'$'}root/launch" </dev/null >>"${'$'}state/host.log" 2>&1 &
    fi
    n=0
    until alive; do n=${'$'}((n+1)); [ "${'$'}n" -lt 20 ] || exit 63; sleep 1; done
""".trimIndent() + "\n"

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
        if (discovery.size != 2 || discovery[0] !in setOf("x86_64", "aarch64"))
            throw WireException("ssh_runtime_missing", "Unsupported Linux architecture")
        val home = discovery[1]
        require(home.startsWith('/') && home.none(Char::isISOControl))
        val bundle = bundleFor(discovery[0])
        if (bundle.architecture != discovery[0]) throw WireException("ssh_runtime_invalid", "Linux runtime is for another architecture")
        val root = "$home/.local/share/codex-pocket"
        val state = "$home/.local/state/codex-pocket/agent"
        val bin = "$root/versions/${bundle.generation}"
        progress("正在准备远程运行环境")
        // A file that is already what the signed manifest names is neither uploaded nor replaced again.
        val missing = ssh.execute("""
            set -eu
            umask 077
            for folder in ${shellWord("$home/.local")} ${shellWord("$home/.local/share")} ${shellWord(root)} ${shellWord("$root/versions")} ${shellWord(bin)}; do
              [ ! -L "${'$'}folder" ] || exit 62
              mkdir -p -- "${'$'}folder"
            done
            cd -- ${shellWord(bin)}
            for name in ${bundle.files.joinToString(" ")}; do
              case "${'$'}name" in ${bundle.files.joinToString(" ") { "$it) digest=${bundle.digests.getValue(it)} ;;" }} esac
              printf '%s  %s\n' "${'$'}digest" "${'$'}name" | sha256sum -c >/dev/null 2>&1 || printf '%s\n' "${'$'}name"
            done
        """.trimIndent()).lines().filter { it in bundle.files }
        if (missing.isNotEmpty()) {
            progress("正在上传远程运行环境")
            for (name in missing) ssh.upload(java.io.File(bundle.directory, name), "$bin/$name.upload")
            // What arrived is compared with the signed manifest before it can take the place of a version.
            ssh.execute("set -eu\ncd -- ${shellWord(bin)}\n" +
                "printf '%s  %s\\n' ${missing.joinToString(" ") { "${bundle.digests.getValue(it)} $it.upload" }} | sha256sum -c >/dev/null 2>&1 || exit 66\n" +
                missing.joinToString("") { "mv -f -- $it.upload $it\n" } + "chmod 700 pocket-agent pocket-runtime-host\n")
        }
        progress("正在启动远程后台")
        fun unitPath(path: String) = path.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%")
        val startup = """
            set -eu
            umask 077
            root=${shellWord(root)}
            state=${shellWord(state)}
            want=${bundle.generation}
            bin="${'$'}root/versions/${'$'}want"
            export PATH="${'$'}HOME/.local/bin:${'$'}HOME/.npm-global/bin:${'$'}PATH"
            mkdir -p -- "${'$'}state" "${'$'}HOME/.codex"
            for path in "${'$'}state" "${'$'}state/config.json" "${'$'}root/active" "${'$'}root/launch"; do [ ! -L "${'$'}path" ] || exit 62; done
            if [ ! -f "${'$'}state/config.json" ]; then
              "${'$'}bin/pocket-agent" init --state "${'$'}state" --lan-bind 127.0.0.1:${target.backendPort} --policy lan --json </dev/null >/dev/null
            fi
            previous=
            if [ -f "${'$'}root/active" ]; then
              previous=${'$'}(head -c 64 "${'$'}root/active")
              case "${'$'}previous" in *[!0-9a-f]*) previous= ;; esac
              [ "${'$'}{#previous}" -eq 64 ] && [ -x "${'$'}root/versions/${'$'}previous/pocket-agent" ] || previous=
            fi
            healthy() {
              "${'$'}root/versions/${'$'}1/pocket-agent" health --state "${'$'}state" --json </dev/null 2>/dev/null | grep -q "\"generation\":\"${'$'}1\""
            }
            # Without lingering the user's services stop when the last session closes, so the host is detached instead.
            mode=detached
            systemd=no
            if command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
              systemd=yes
              user=${'$'}(id -un)
              lingering() { [ "${'$'}(loginctl show-user "${'$'}user" --property=Linger --value 2>/dev/null)" = yes ]; }
              lingering || loginctl enable-linger >/dev/null 2>&1 || true
              if lingering; then mode=service; fi
            fi
            unit_active() { [ "${'$'}systemd" = yes ] && systemctl --user is-active --quiet codex-pocket.service; }
            hosts() {
              for entry in /proc/[0-9]*; do
                case "${'$'}(readlink "${'$'}entry/exe" 2>/dev/null || true)" in
                  "${'$'}root"/versions/*/pocket-agent|"${'$'}root"/versions/*/pocket-agent" (deleted)")
                    if tr '\000' '\n' < "${'$'}entry/cmdline" 2>/dev/null | grep -qx run; then printf '%s\n' "${'$'}{entry#/proc/}"; fi ;;
                esac
              done
            }
            stop_host() {
              [ "${'$'}systemd" = no ] || systemctl --user stop codex-pocket.service >/dev/null 2>&1 || true
              n=0
              while pids=${'$'}(hosts) && [ -n "${'$'}pids" ]; do
                kill ${'$'}pids 2>/dev/null || true
                n=${'$'}((n+1)); [ "${'$'}n" -lt 15 ] || exit 63
                sleep 1
              done
            }
            start_host() {
              printf '%s\n' "${'$'}1" > "${'$'}root/active.new"
              mv -f -- "${'$'}root/active.new" "${'$'}root/active"
              if [ "${'$'}mode" = service ]; then
                systemctl --user daemon-reload
                systemctl --user enable codex-pocket.service >/dev/null 2>&1
                systemctl --user restart codex-pocket.service
              else
                nohup "${'$'}root/launch" </dev/null >>"${'$'}state/host.log" 2>&1 &
              fi
              n=0
              until healthy "${'$'}1"; do n=${'$'}((n+1)); [ "${'$'}n" -lt 30 ] || return 1; sleep 1; done
            }
            launcher=${'$'}(cat <<'POCKET_LAUNCH'
            #!/bin/sh
            # Codex Pocket: runs the host version named by ./active.
            generation=${'$'}(head -c 64 ${shellWord("$root/active")}) || exit 78
            case "${'$'}generation" in *[!0-9a-f]*) exit 78 ;; esac
            [ "${'$'}{#generation}" -eq 64 ] || exit 78
            exec ${shellWord("$root/versions")}/"${'$'}generation"/pocket-agent run --state ${shellWord(state)}
            POCKET_LAUNCH
            )
            if [ "${'$'}(cat "${'$'}root/launch" 2>/dev/null || true)" != "${'$'}launcher" ]; then
              printf '%s\n' "${'$'}launcher" > "${'$'}root/launch.new"
              chmod 700 "${'$'}root/launch.new"
              mv -f -- "${'$'}root/launch.new" "${'$'}root/launch"
            fi
            settled=no
            if healthy "${'$'}want" && [ "${'$'}previous" = "${'$'}want" ]; then
              if [ "${'$'}mode" = service ]; then unit_active && settled=yes; else unit_active || settled=yes; fi
            fi
            if [ "${'$'}settled" = no ]; then
              stop_host
              if [ "${'$'}mode" = service ]; then
                mkdir -p -- "${'$'}HOME/.config/systemd/user"
                cat > "${'$'}HOME/.config/systemd/user/codex-pocket.service" <<'POCKET_UNIT'
            [Unit]
            Description=Codex Pocket
            [Service]
            ExecStart="${unitPath(root)}/launch"
            Restart=on-failure
            RestartSec=2
            # Native Codex started by the host keeps its tasks while the host itself is replaced.
            KillMode=process
            [Install]
            WantedBy=default.target
            POCKET_UNIT
              elif [ "${'$'}systemd" = yes ]; then
                systemctl --user disable codex-pocket.service >/dev/null 2>&1 || true
              fi
              if ! start_host "${'$'}want"; then
                stop_host
                [ -n "${'$'}previous" ] && [ "${'$'}previous" != "${'$'}want" ] || exit 63
                start_host "${'$'}previous" || exit 63
                exit 64
              fi
            fi
            printf '%s\n' "${'$'}mode"
            "${'$'}bin/pocket-agent" pair --state "${'$'}state" --json </dev/null >/dev/null
            cat "${'$'}state/pairing-qr.json"
        """.trimIndent()
        val raw = ssh.execute(startup, 150)
        val code = PairingCode.parse(raw.substringAfter('\n'))
        val route = code.routes.firstOrNull { it.kind == "lan" && java.net.URI(it.url).host == "127.0.0.1" }
            ?: throw WireException("ssh_backend_route_missing", "Remote host has no private loopback route")
        progress("正在检查 Codex")
        val native = ssh.execute("export PATH=\"${'$'}HOME/.local/bin:${'$'}HOME/.npm-global/bin:${'$'}PATH\"\nif command -v codex >/dev/null 2>&1; then printf yes; else printf no; fi\n").trim() == "yes"
        return SshPreparation(code, java.net.URI(route.url).port, native, raw.substringBefore('\n') == "service")
    }
}
