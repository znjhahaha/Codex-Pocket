package dev.codexpocket.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConversationMetadataCacheTest {
    @Test fun coldDisplayCacheKeepsLabelsWithoutRestoringAuthorityOrCrossingOwners() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("metadata-test", 0)
        prefs.edit().clear().commit()
        val chat = ChatEntity(ThreadKey("pc", "store", "thread"), "owner", "desktop", "", "", "", 1, "", false)
        val cache = ConversationMetadataCache(prefs)
        cache.savePermissions(chat, PermissionState(status = "ready", revision = JsonPrimitive("live-proof"), currentPresetId = "ask",
            presets = listOf(PermissionPreset("ask", "按需询问", enabled = true))))
        val model = NativeModel("id", "model", "工作模型", "", listOf("high"), "high", true, false, setOf("fast"))
        cache.saveModels(chat, listOf(model))
        val cold = ConversationMetadataCache(prefs)
        val permission = cold.permissions(chat)!!
        assertEquals("按需询问", permission.label)
        assertEquals("saved", permission.status)
        assertNull(permission.revision)
        assertTrue(permission.presets.none { it.enabled })
        assertEquals(listOf(model), cold.models(chat))
        assertNull(cold.permissions(chat.copy(runtimeId = "replacement")))
        assertTrue(cold.models(chat.copy(key = chat.key.copy(computerId = "another"))).isEmpty())
        assertFalse(prefs.all.values.any { it.toString().contains("live-proof") })
    }
}
