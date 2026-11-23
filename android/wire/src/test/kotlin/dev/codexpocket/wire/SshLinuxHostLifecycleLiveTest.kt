package dev.codexpocket.wire

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** What happens to an installed host over time: dropped links, restarts, new versions. Same opt-in as [SshLinuxLiveTest]. */
class SshLinuxHostLifecycleLiveTest {
    private suspend fun failure(block: suspend () -> Unit): Throwable? = try { block(); null } catch (cancelled: CancellationException) { throw cancelled } catch (error: Throwable) { error }
    private suspend fun wireFailure(block: suspend () -> Unit): String = (failure(block) as? WireException)?.code ?: "none"

    /** The generation the running host reports about itself, and the process serving it. */
    private suspend fun running(target: SshTarget = LinuxQa.pocket): Pair<String, String> {
        val health = Json.parseToJsonElement(LinuxQa.sh("""
            root="${'$'}HOME/.local/share/codex-pocket"
            "${'$'}root/versions/${'$'}(cat "${'$'}root/active")/pocket-agent" health --state "${'$'}HOME/.local/state/codex-pocket/agent" --json </dev/null
        """.trimIndent(), target)).jsonObject.getValue("helperRuntime").jsonObject
        return health.string("generation") to health.getValue("pid").jsonPrimitive.content
    }
    private suspend fun active(target: SshTarget = LinuxQa.pocket) = LinuxQa.sh("cat \"${'$'}HOME/.local/share/codex-pocket/active\"\n", target).trim()
    private suspend fun nativeDaemon() = LinuxQa.sh("pgrep -u pocket -f 'codex app-server --listen unix://' || true\n").trim()

    private suspend fun threads(connection: PocketConnection, native: JsonObject) = connection.native(native, "thread/list") { put("limit", 10) }.getValue("data").jsonArray

    @Test fun droppedSshSurfacesTheUnknownOutcomeAndTheSavedIdentityReconnects() = runBlocking {
        LinuxQa.assume()
        val prepared = LinuxQa.prepare()
        val phone = LinuxQa.pair(LinuxQa.pocket, prepared)
        val host = running().second
        SshSession.open(LinuxQa.pocket).use { admin ->
            suspend fun sessions() = admin.execute("pgrep -u pocket -x sshd\n", 20, emptyMap()).lines().filter { it.isNotBlank() }.toSet()
            val others = sessions()
            val connection = SshPocketConnection(phone.target)
            try {
                connection.connect(phone.config)
                phone.hello(connection)
                assertEquals(LinkState.ONLINE, connection.state.value)
                val own = sessions() - others
                assertEquals("the phone's SSH session", 1, own.size)
                // A stopped host leaves both requests sent and unanswered when the link is cut.
                admin.execute("kill -STOP $host\n", 20, emptyMap())
                val command = UUID.randomUUID().toString()
                val mutation = async { failure { connection.request("runtime/ensure", buildJsonObject { put("source", "cli") }, command, 60_000) } }
                val read = async { failure { connection.request("runtimes/list", timeoutMillis = 60_000) } }
                delay(1_000)
                assertTrue(mutation.isActive && read.isActive)
                admin.execute("kill ${own.single()}\n", 20, emptyMap())
                val unknown = withTimeout(20_000) { mutation.await() }
                assertTrue("a sent mutation is reported as unknown, not repeated: $unknown", unknown is UnknownOutcomeException)
                assertEquals(command, (unknown as UnknownOutcomeException).commandId)
                assertEquals("disconnected", (withTimeout(20_000) { read.await() } as WireException).code)
                withTimeout(10_000) { while (connection.state.value != LinkState.OFFLINE) delay(100) }
                // The connection does not come back by itself; whoever owns it decides when to open another.
                assertEquals("offline", wireFailure { connection.request("runtimes/list") })
            } finally {
                admin.execute("kill -CONT $host\n", 20, emptyMap())
                connection.close()
            }
        }
        phone.online { connection, hello ->
            assertEquals(prepared.pairing.computerId, hello.string("computerId"))
            assertTrue(threads(connection, awaitNative(connection)).size >= 0)
        }
        assertEquals(host, running().second)
    }

    @Test fun ordinaryAccountWithoutLingeringRunsDetachedAndOutlivesThePhonesSession() = runBlocking {
        LinuxQa.assume()
        val target = LinuxQa.ordinary
        val prepared = LinuxQa.prepare(target)
        assertFalse("nobody let this account linger", prepared.persistent)
        assertFalse("this account has no Codex", prepared.nativeInstalled)
        assertEquals(43822, prepared.backendPort)
        val phone = LinuxQa.pair(target, prepared)
        val host = running(target).second
        // logind stops a non-lingering user's manager ten seconds after the last session; the host is not under it.
        delay(15_000)
        suspend fun survivors() = LinuxQa.sh("sudo pgrep -u pocketqa2 -f 'pocket-agent run' || true; sudo pgrep -u pocketqa2 -x sshd | wc -l\n").lines().filter { it.isNotBlank() }
        assertEquals(listOf(host, "0"), survivors())
        assertEquals("disabled or absent", "", LinuxQa.sh("systemctl --user is-enabled codex-pocket.service 2>/dev/null | grep -x enabled || true\n", target).trim())
        phone.online { connection, hello ->
            assertEquals(prepared.pairing.computerId, hello.string("computerId"))
            // Codex is not installed for this account: the phone is told so instead of waiting for a runtime.
            val refusal = failure { connection.request("runtime/ensure", buildJsonObject { put("source", "cli") }, UUID.randomUUID().toString(), 30_000) }
            assertEquals("runtime_unavailable", (refusal as WireException).code)
        }
        assertEquals(host, running(target).second)
        // Preparing again neither restarts the detached host nor installs a service for it.
        val again = LinuxQa.prepare(target)
        assertEquals(prepared.pairing.computerId, again.pairing.computerId)
        assertEquals(host, running(target).second)
        // A stand-in that only answers the login question: an installed, signed-in Codex is reported as such.
        try {
            LinuxQa.sh("mkdir -p \"${'$'}HOME/.local/bin\"; printf '#!/bin/sh\\n[ \"${'$'}1 ${'$'}2\" = \"login status\" ]\\n' > \"${'$'}HOME/.local/bin/codex\"; chmod 700 \"${'$'}HOME/.local/bin/codex\"", target)
            val signedIn = LinuxQa.prepare(target)
            assertTrue(signedIn.nativeInstalled && signedIn.nativeSignedIn)
        } finally { LinuxQa.sh("rm -f \"${'$'}HOME/.local/bin/codex\"", target) }
    }

    @Test fun hostRestartBringsTheServiceBackWithoutALoginAndSavedPairingsStillWork() = runBlocking {
        LinuxQa.assume()
        val prepared = LinuxQa.prepare()
        assertTrue(prepared.persistent)
        val phone = LinuxQa.pair(LinuxQa.pocket, prepared)
        val detached = LinuxQa.prepare(LinuxQa.ordinary)
        val ordinaryPhone = LinuxQa.pair(LinuxQa.ordinary, detached)
        phone.online { connection, _ -> awaitNative(connection) }
        val boot = LinuxQa.sh("cat /proc/sys/kernel/random/boot_id\n").trim()
        // POCKET_LINUX_QA_RESTART names a command that starts the VM again once the guest has powered off, for
        // hypervisors that cannot reset a guest (WHPX); without it the guest reboots itself.
        val restart = System.getenv("POCKET_LINUX_QA_RESTART")
        failure { LinuxQa.sh(if (restart == null) "sudo systemctl reboot" else "sudo systemctl poweroff") }
        // Only the host key is read while waiting: nobody logs in, so nothing starts the account's services but lingering.
        withTimeout(120_000) { while (failure { probeSshHostKey(LinuxQa.host, LinuxQa.pocket.port) } == null) delay(500) }
        if (restart != null) withContext(Dispatchers.IO) {
            assertEquals(0, ProcessBuilder(restart.split(' ')).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor())
        }
        withTimeout(600_000) { while (failure { probeSshHostKey(LinuxQa.host, LinuxQa.pocket.port) } != null) delay(2_000) }
        delay(30_000)
        LinuxQa.isolate()
        val evidence = LinuxQa.sh("""
            cat /proc/sys/kernel/random/boot_id
            loginctl show-user pocket --property=Linger --value
            systemctl --user is-active codex-pocket.service
            ps -o etimes= -p "${'$'}(systemctl --user show codex-pocket.service --property=MainPID --value)"
            sudo pgrep -u pocketqa2 -f 'pocket-agent run' | wc -l
        """.trimIndent()).lines().map { it.trim() }
        assertNotEquals("the host really restarted", boot, evidence[0])
        assertEquals("yes", evidence[1])
        assertEquals("active", evidence[2])
        assertTrue("the service was up before the first login: ${evidence[3]}s", evidence[3].toInt() >= 20)
        assertEquals("the detached host is down until its phone returns", "0", evidence[4])
        phone.online { connection, hello ->
            assertEquals(prepared.pairing.computerId, hello.string("computerId"))
            assertTrue(threads(connection, awaitNative(connection)).size >= 0)
        }
        // The account that may not linger: reconnecting starts its host again and the saved identity signs in.
        ordinaryPhone.online { _, hello -> assertEquals(detached.pairing.computerId, hello.string("computerId")) }
        assertEquals(active(LinuxQa.ordinary), running(LinuxQa.ordinary).first)
    }

    @Test fun newerBundleReplacesTheRunningVersionAndOneThatCannotStartOrArriveLeavesItRunning() = runBlocking {
        LinuxQa.assume()
        val first = LinuxQa.prepare()
        val phone = LinuxQa.pair(LinuxQa.pocket, first)
        phone.online { connection, _ -> awaitNative(connection) }
        val daemon = nativeDaemon()
        assertTrue(daemon.isNotEmpty())
        val before = running()
        assertEquals(TestRuntimeSigner.verified(LinuxQa.base).generation, before.first)

        val newer = TestRuntimeSigner.verified(LinuxQa.bundle("0.0.1-qa"))
        val upgraded = LinuxQa.prepare(directory = newer.directory)
        val after = running()
        assertEquals(newer.generation, after.first)
        assertEquals(newer.generation, active())
        assertNotEquals(before.second, after.second)
        assertEquals(first.pairing.computerId, upgraded.pairing.computerId)
        assertEquals("native Codex kept running under the replaced host", daemon, nativeDaemon())
        assertEquals("the previous version is kept whole", "", LinuxQa.sh("""
            cd "${'$'}HOME/.local/share/codex-pocket/versions/${before.first}"
            printf '%s  %s\n' ${TestRuntimeSigner.verified(LinuxQa.base).let { b -> b.files.joinToString(" ") { "${b.digests.getValue(it)} $it" } }} | sha256sum -c --quiet 2>&1
        """.trimIndent()).trim())
        phone.online { connection, hello ->
            assertEquals(first.pairing.computerId, hello.string("computerId"))
            assertTrue(threads(connection, awaitNative(connection)).size >= 0)
        }

        // Correctly signed, but its host exits at once.
        val broken = LinuxQa.bundle("0.0.2-qa", agent = "#!/bin/sh\nexit 1\n".toByteArray())
        assertEquals("ssh_upgrade_rolled_back", wireFailure { LinuxQa.prepare(directory = broken) })
        assertEquals(newer.generation, running().first)
        assertEquals(newer.generation, active())
        assertEquals(daemon, nativeDaemon())
        assertEquals(first.pairing.computerId, phone.online { _, hello -> hello.string("computerId") })

        // The link is cut while the executable is still arriving.
        val late = TestRuntimeSigner.verified(LinuxQa.bundle("0.0.3-qa-${UUID.randomUUID()}"))
        val settled = running()
        SshSession.open(LinuxQa.pocket).use { admin ->
            suspend fun sessions() = admin.execute("pgrep -u pocket -x sshd\n", 20, emptyMap()).lines().filter { it.isNotBlank() }.toSet()
            val others = sessions()
            val arriving = "\"${'$'}HOME/.local/share/codex-pocket/versions/${late.generation}/pocket-agent.upload\""
            val cut = CompletableDeferred<Long>()
            val uploading = CompletableDeferred<Unit>()
            // Watched and cut on the host itself: over a fast link the whole file can arrive between two looks from here.
            val cutter = launch {
                uploading.await()
                cut.complete(admin.execute("""
                    while [ "${'$'}(stat -c %s $arriving 2>/dev/null || echo 0)" -lt 1000000 ]; do sleep 0.01; done
                    arrived=${'$'}(stat -c %s $arriving 2>/dev/null || echo 0)
                    for pid in ${'$'}(pgrep -u pocket -x sshd); do
                        case " ${others.joinToString(" ")} " in *" ${'$'}pid "*) ;; *) kill "${'$'}pid" ;; esac
                    done
                    echo "${'$'}arrived"
                """.trimIndent(), 120, emptyMap()).trim().lines().last().toLong())
            }
            val interrupted = failure { LinuxQa.prepare(directory = late.directory, progress = { if ("上传" in it) uploading.complete(Unit) }) }
            cutter.cancelAndJoin()
            assertNotNull("the preparation reports that it did not finish", interrupted)
            assertTrue("cut in the middle of the upload", cut.isCompleted && cut.getCompleted() < late.directory.resolve("pocket-agent").length())
        }
        assertEquals(settled, running())
        assertEquals(newer.generation, active())
        assertEquals(first.pairing.computerId, phone.online { _, hello -> hello.string("computerId") })
        // Trying again finishes the same upgrade.
        LinuxQa.prepare(directory = late.directory)
        assertEquals(late.generation, running().first)
        assertEquals(first.pairing.computerId, phone.online { _, hello -> hello.string("computerId") })
        // Versions do not pile up: the running one, the one before it, and any that something still runs from.
        val kept = LinuxQa.sh("ls \"${'$'}HOME/.local/share/codex-pocket/versions\"").lines().filter { it.isNotBlank() }.toSet()
        val inUse = LinuxQa.sh("for entry in /proc/[0-9]*; do readlink \"${'$'}entry/exe\" 2>/dev/null; done | grep -o 'codex-pocket/versions/[0-9a-f]*' | sed 's#.*/##'").lines().filter { it.isNotBlank() }.toSet()
        assertTrue(late.generation in kept && newer.generation in kept)
        assertEquals(emptySet<String>(), kept - late.generation - newer.generation - inUse)
        assertFalse("the version that could not start is gone", TestRuntimeSigner.verified(broken).generation in kept)
    }
}
