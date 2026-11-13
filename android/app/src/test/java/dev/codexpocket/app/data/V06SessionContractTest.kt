package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class V06SessionContractTest {
    private fun obj(raw: String) = pocketJson.parseToJsonElement(raw).jsonObject

    @Test fun ownershipFailuresDistinguishUncertainFromMultipleOwners() {
        val uncertain = PocketFailure.fromCode("owner_uncertain")
        val multiple = PocketFailure.fromCode("multiple_owners")
        assertNotNull(uncertain)
        assertNotNull(multiple)
        assertNotEquals(uncertain, multiple)
        assertFalse(uncertain!!.label.contains("多个"))
        assertTrue(multiple!!.label.contains("多个"))
    }

    @Test fun ownershipReasonsUseClosedCodesAndNeverGrantSendPermission() {
        val uncertain = ThreadAccess.from(obj("""{"mode":"desktop","phase":"blocked","canSend":false,"ownershipReason":"owner_uncertain"}"""))
        assertEquals(OwnershipReason.Uncertain, uncertain.ownershipReason)
        assertFalse(uncertain.canSendFrom("desktop"))
        assertEquals(OwnershipReason.Multiple, ThreadAccess.from(obj("""{"mode":"desktop","phase":"blocked","canSend":false,"reason":"multiple_owners"}""")).ownershipReason)
        val legacy = ThreadAccess.from(obj("""{"mode":"desktop","phase":"blocked","canSend":false,"reason":"thread_owned_elsewhere","ownershipReason":"future_reason"}"""))
        assertNull(legacy.ownershipReason)
        assertFalse(legacy.canSendFrom("desktop"))
    }

    @Test fun desktopDefaultNeverFallsBackToCli() {
        val cli = RuntimeInfo("cli", "cli", "v", "store", true, emptySet(), setOf("thread/start"))
        assertEquals(SessionMode.Desktop, SessionPreferences().defaultMode)
        assertTrue(sessionRuntimes(listOf(cli), SessionMode.Desktop).isEmpty())
        assertEquals(listOf(cli), sessionRuntimes(listOf(cli), SessionMode.Cli))
        assertNull(SessionMode.from("unknown"))
    }

    @Test fun accessNeverGrantsSendingDuringHandoffOrForAnotherOwner() {
        val ready = ThreadAccess.from(obj("""{"mode":"desktop","ownerRuntimeId":"desktop","source":"cli","phase":"ready","canSend":true}"""))
        assertTrue(ready.canSendFrom("desktop"))
        assertFalse(ready.canSendFrom("cli"))
        assertFalse(ready.copy(phase = AccessPhase.WaitingIdle).canSendFrom("desktop"))
        assertEquals("cli", ready.source)
        assertFalse(ready.copy(canSend = false).canSendFrom("desktop"))
    }

    @Test fun fastRequiresModelSpeedTierAndRuntimeFieldCapability() {
        val model = NativeModel.from(obj("""{"id":"m","model":"m","serviceTiers":[],"additionalSpeedTiers":[]}"""))
        assertFalse(model.supportsFast)
        assertTrue(NativeModel.from(obj("""{"serviceTiers":[{"id":"fast"}]}""")).supportsFast)
        assertTrue(NativeModel.from(obj("""{"additionalSpeedTiers":["fast"]}""")).supportsFast)
        val runtime = RuntimeInfo.from(obj("""{"id":"r","online":true,"capabilities":["thread/settings/update"],"capabilityFields":{"thread/settings/update":["threadId"]}}"""))
        assertFalse(runtime.supportsServiceTier)
        assertTrue(RuntimeInfo.from(obj("""{"id":"r","online":true,"capabilities":["thread/settings/update"],"capabilityFields":{"thread/settings/update":["threadId","serviceTier"]}}""")).supportsServiceTier)
    }

    @Test fun userContentReferencesPreserveEightImagesAndConnectionIdentity() {
        val raw = buildJsonObject {
            put("id", "user"); put("type", "userMessage")
            put("content", buildJsonArray { repeat(8) { i ->
                add(buildJsonObject { put("type", "text"); put("text", "part $i") })
                add(buildJsonObject {
                    put("type", "image"); put("runtimeId", "r"); put("connectionId", "c")
                    put("threadId", "t"); put("turnId", "turn"); put("itemId", "user")
                    put("mediaId", i.toString(16).padStart(32, '0')); put("status", "recoverable"); put("contentPart", i * 2 + 1)
                    put("mimeType", ""); put("byteLength", 0)
                })
            } })
        }
        val images = userMessageMedia(TimelineItem.from("turn", raw))
        assertEquals(8, images.size)
        assertEquals((0..7).map { it.toString(16).padStart(32, '0') }, images.map { it.mediaId })
        assertTrue(images.all { it.readable && it.connectionId == "c" })
        assertEquals((0..7).map { it * 2 + 1 }, images.map { it.contentPart })
        assertNotEquals(images.first().cacheKey, images.first().copy(connectionId = "other").cacheKey)
    }

    @Test fun selectedModeMustMatchActualRuntimeRatherThanHistoricalSource() {
        val key = ThreadKey("pc", "store", "t")
        val access = ThreadAccess(SessionMode.Desktop, "r", "cli", AccessPhase.Ready, true)
        val state = PocketState(selected = ChatEntity(key, "r", "cli", "", "", "", 0, "", false),
            runtimes = listOf(RuntimeInfo("r", "cli", "", "store", true, emptySet(), emptySet())),
            capabilities = setOf(BrokerCapability.AccessRead.method), threadAccess = mapOf(key to access), accessFresh = setOf(key))
        assertFalse(state.accessAllowsSending())
        assertTrue(state.copy(runtimes = state.runtimes.map { it.copy(source = "desktop") }).accessAllowsSending())
        val unloaded = state.copy(capabilities = state.capabilities + BrokerCapability.AccessUpdate.method,
            threadAccess = mapOf(key to access.copy(ownerRuntimeId = null)))
        assertFalse(unloaded.accessAllowsSending())
        assertTrue(unloaded.accessAllowsSendPreparation())
        assertFalse(unloaded.copy(threadAccess = mapOf(key to access.copy(phase = AccessPhase.Cancelled))).accessAllowsSendPreparation())
    }

    @Test fun brokerErrorsAreTypedWithoutRemoteErrorText() {
        assertEquals(PocketFailure.Ownership, PocketFailure.fromCode("thread_owned_elsewhere"))
        assertEquals(PocketFailure.Handoff, PocketFailure.fromCode("operation_in_progress"))
        assertEquals(PocketFailure.Runtime, PocketFailure.fromCode("runtime_offline"))
        assertEquals(PocketFailure.StaleRevision, PocketFailure.fromCode("version_conflict"))
        assertEquals(PocketFailure.Runtime, PocketFailure.fromCode("request_timeout"))
        assertEquals(AccessPhase.Cancelled, ThreadAccess.from(obj("""{"mode":"desktop","phase":"cancelled","canSend":false}""")).phase)
    }
}
