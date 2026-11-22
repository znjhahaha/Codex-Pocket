package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test

class LocalProvidersTest {
    private class Memory(var value: String? = null, var failing: Boolean = false) : LocationBlob {
        override fun read() = value
        override fun write(value: String) { check(!failing) { "storage unavailable" }; this.value = value }
    }
    private fun draft(id: String? = null, name: String = "中转站", url: String = "https://relay.example/v1", key: String = "sk-relay-0123456789abcdef", model: String = "gpt-5.5") =
        LocalProviderDraft(id, name, url, key, model)
    private val provider = LocalProvider("p", "My \"relay\"", "https://relay.example/v1", "sk-secret-0123456789", "gpt-5.5")

    @Test fun addressesAreHttpsUnlessNumericAndNeverCarryCredentialsOrQueries() {
        assertEquals("https://relay.example/v1", LocalProviderRules.baseUrl(" https://relay.example/v1/ "))
        assertEquals("http://192.168.1.20:8080/v1", LocalProviderRules.baseUrl("http://192.168.1.20:8080/v1"))
        assertEquals("http://[::1]:8080/v1", LocalProviderRules.baseUrl("http://[::1]:8080/v1"))
        for (refused in listOf("http://relay.example/v1", "ftp://relay.example", "relay.example/v1", "https://", "https://user:pass@relay.example/v1",
            "https://relay.example/v1?key=1", "https://relay.example/v1#x", "https://relay.example/\"v1", "https://relay.example/v 1", "")) {
            assertNull(refused, LocalProviderRules.baseUrl(refused))
        }
    }

    @Test fun keysModelsAndNamesAreCheckedBeforeAnythingIsSaved() {
        assertEquals("sk-12345678", LocalProviderRules.apiKey("  sk-12345678\n"))
        assertNull(LocalProviderRules.apiKey("short"))
        assertNull(LocalProviderRules.apiKey("sk-1234 5678"))
        assertNull(LocalProviderRules.apiKey("sk-密钥-12345678"))
        assertEquals("", LocalProviderRules.model("  "))
        assertEquals("deepseek/chat-v3:free", LocalProviderRules.model("deepseek/chat-v3:free"))
        assertNull(LocalProviderRules.model("gpt \"5\""))
        assertEquals("我的中转站", LocalProviderRules.name(" 我的中转站 "))
        assertNull(LocalProviderRules.name(""))
        assertNull(LocalProviderRules.name("x".repeat(41)))
    }

    @Test fun savingPutsTheServiceInUseAndItIsThereAfterTheApplicationRestarts() {
        val blob = Memory()
        val store = LocalProviderStore(blob)
        assertEquals(LocalAccess(), store.access.value)
        val id = store.save(draft())!!
        assertEquals(id, store.access.value.activeId)
        assertEquals("sk-relay-0123456789abcdef", store.active()!!.apiKey)
        val shown = store.access.value.providers.single()
        assertEquals(LocalProviderSummary(id, "中转站", "https://relay.example/v1", "gpt-5.5", "sk-…cdef"), shown)
        // What a screen is given never carries the key.
        assertFalse(store.access.value.toString().contains("0123456789"))
        assertFalse(store.active().toString().contains("0123456789"))
        val reopened = LocalProviderStore(blob)
        assertEquals(store.access.value, reopened.access.value)
        assertEquals("sk-relay-0123456789abcdef", reopened.active()!!.apiKey)
    }

    @Test fun editingKeepsTheSavedKeyWhenNoneIsTypedAndReplacesItWhenOneIs() {
        val store = LocalProviderStore(Memory())
        val id = store.save(draft())!!
        assertEquals(id, store.save(draft(id, name = "改名", key = "", model = "")))
        assertEquals("sk-relay-0123456789abcdef", store.active()!!.apiKey)
        assertEquals("改名", store.active()!!.name)
        assertEquals("", store.active()!!.model)
        assertEquals(id, store.save(draft(id, key = "sk-other-0123456789")))
        assertEquals("sk-other-0123456789", store.active()!!.apiKey)
        assertEquals(1, store.access.value.providers.size)
    }

    @Test fun whatCannotBeUsedIsNotSavedAndAFailedWriteLeavesEverythingAsItWas() {
        val blob = Memory()
        val store = LocalProviderStore(blob)
        assertNull(store.save(draft(url = "http://relay.example/v1")))
        assertNull(store.save(draft(key = "")))
        assertNull(store.save(draft(id = "missing")))
        assertNull(blob.value)
        val id = store.save(draft())!!
        blob.failing = true
        assertTrue(runCatching { store.save(draft(name = "第二个")) }.isFailure)
        assertTrue(runCatching { store.use(null) }.isFailure)
        assertEquals(LocalAccess(listOf(store.active()!!.summary), id), store.access.value)
    }

    @Test fun switchingBackToOpenAiKeepsTheServicesAndRemovingTheOneInUseGoesBackToo() {
        val store = LocalProviderStore(Memory())
        val first = store.save(draft())!!
        val second = store.save(draft(name = "第二个", key = "sk-second-0123456789"))!!
        assertEquals(second, store.access.value.activeId)
        assertTrue(store.use(null))
        assertNull(store.active())
        assertEquals(2, store.access.value.providers.size)
        assertFalse(store.use("missing"))
        assertTrue(store.use(first))
        store.remove(second)
        assertEquals(first, store.access.value.activeId)
        store.remove(first)
        assertEquals(LocalAccess(), store.access.value)
    }

    @Test fun aRecordThatCannotBeReadStartsEmptyRatherThanBlockingThePhone() {
        assertEquals(LocalAccess(), LocalProviderStore(Memory("not json")).access.value)
        assertEquals(LocalAccess(), LocalProviderStore(object : LocationBlob {
            override fun read(): String? = error("key lost")
            override fun write(value: String) = Unit
        }).access.value)
    }

    @Test fun configNamesTheServiceAndWhereItsKeyIsReadFromButNeverTheKey() {
        val config = LocalCodexConfig.apply("sandbox_mode = \"danger-full-access\"\n", provider)
        assertEquals("""
            model_provider = "pocket" # pocket-provider
            model = "gpt-5.5" # pocket-provider
            sandbox_mode = "danger-full-access"

            [model_providers.pocket]
            name = "My \"relay\""
            base_url = "https://relay.example/v1"
            wire_api = "responses"
            env_key = "POCKET_PROVIDER_API_KEY"

            [shell_environment_policy]
            exclude = ["POCKET_PROVIDER_API_KEY"]

        """.trimIndent(), config)
        assertFalse(config.contains("sk-secret"))
    }

    @Test fun applyingTwiceChangesNothingAndGoingBackRestoresWhatWasChosenForOpenAi() {
        val original = "model = \"gpt-6.2-sol\"\nsandbox_mode = \"danger-full-access\"\n\n[projects.\"/data/p\"]\ntrust_level = \"trusted\"\n"
        val official = LocalCodexConfig.apply(original, null)
        assertEquals(original + "\n[shell_environment_policy]\nexclude = [\"POCKET_PROVIDER_API_KEY\"]\n", official)
        assertEquals(official, LocalCodexConfig.apply(official, null))
        val custom = LocalCodexConfig.apply(official, provider)
        assertEquals(custom, LocalCodexConfig.apply(custom, provider))
        // One model line is in force at a time; the person's own choice waits as a comment.
        assertEquals(1, custom.lines().count { it.startsWith("model =") })
        assertTrue(custom.contains("# pocket-saved: model = \"gpt-6.2-sol\""))
        assertTrue(custom.contains("[projects.\"/data/p\"]\ntrust_level = \"trusted\""))
        assertEquals(official, LocalCodexConfig.apply(custom, null))
        // Another service replaces the first rather than adding a second table of the same name.
        val other = LocalCodexConfig.apply(custom, LocalProvider("q", "Other", "http://10.0.0.2:8080/v1", "sk-other-0123456789", ""))
        assertEquals(1, Regex("""\[model_providers\.pocket]""").findAll(other).count())
        assertTrue(other.contains("base_url = \"http://10.0.0.2:8080/v1\""))
        // Without a model of its own the service is asked for what the person had chosen.
        assertTrue(other.lines().contains("model = \"gpt-6.2-sol\""))
        assertEquals(official, LocalCodexConfig.apply(other, null))
    }

    @Test fun aShellPolicyThePersonWroteIsLeftAloneAndTablesAfterOursSurvive() {
        val written = "[shell_environment_policy]\ninherit = \"core\"\n\n[model_providers.pocket]\nname = \"stale\"\n\n[model_providers.pocket.http_headers]\nx = \"1\"\n\n[tui]\ntheme = \"dark\"\n"
        val official = LocalCodexConfig.apply(written, null)
        assertEquals("[shell_environment_policy]\ninherit = \"core\"\n\n[tui]\ntheme = \"dark\"\n", official)
        val custom = LocalCodexConfig.apply(written, provider)
        assertEquals(1, Regex("""\[shell_environment_policy]""").findAll(custom).count())
        assertFalse(custom.contains("stale"))
        assertTrue(custom.contains("[tui]\ntheme = \"dark\""))
    }
}
