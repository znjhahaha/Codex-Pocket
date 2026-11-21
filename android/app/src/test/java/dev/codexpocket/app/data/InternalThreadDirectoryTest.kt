package dev.codexpocket.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class InternalThreadDirectoryTest {
    private val prefs=ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("visibility-${UUID.randomUUID()}",Context.MODE_PRIVATE)
    private val runtime=RuntimeInfo("desktop","desktop","0.7.0","store",true,emptySet(),emptySet())
    private val key=ThreadKey("pc","store","coordinator")
    private fun metadata(authoritative:Boolean=true,runtime:String="desktop",store:String="store",role:String="assistant_coordinator")=buildJsonObject {
        put("hiddenThreadsAuthoritative",authoritative)
        put("hiddenThreads",buildJsonArray {add(buildJsonObject {put("runtimeId",runtime);put("configStoreId",store);put("threadId","coordinator");put("role",role)})})
    }
    @Test fun authoritativeRoleSurvivesOfflineReloadAndNeverLeaksAcrossComputerOrStore() {
        val directory=InternalThreadDirectory(prefs)
        assertTrue(directory.accept(metadata(),"pc",listOf(runtime),emptyList()))
        assertTrue(directory.contains(key))
        assertTrue(InternalThreadDirectory(prefs).contains(key))
        assertFalse(directory.contains(key.copy(computerId="other")))
        assertFalse(directory.contains(key.copy(configStoreId="other")))
        assertFalse(directory.accept(buildJsonObject {},"pc",listOf(runtime),emptyList()))
        assertFalse(directory.accept(metadata(authoritative=false),"pc",listOf(runtime),emptyList()))
        assertTrue(directory.contains(key))
    }
    @Test fun unknownRolesAndMismatchedRuntimeStoreCannotHideAnOrdinaryChat() {
        val directory=InternalThreadDirectory(prefs)
        assertFalse(directory.accept(metadata(role="ordinary"),"pc",listOf(runtime),emptyList()))
        assertFalse(directory.accept(metadata(store="other"),"pc",listOf(runtime),emptyList()))
        assertFalse(directory.accept(metadata(runtime="unknown"),"pc",listOf(runtime),emptyList()))
        assertFalse(directory.contains(key))
    }
    @Test fun retiredRuntimeMustMatchTheFullCachedIdentity() {
        val directory=InternalThreadDirectory(prefs)
        val chat=ChatEntity(key,"retired","desktop","Ordinary-looking title","", "",0,"idle",false)
        assertFalse(directory.accept(metadata(runtime="retired"),"pc",emptyList(),listOf(chat.copy(key=key.copy(configStoreId="other")))))
        assertTrue(directory.accept(metadata(runtime="retired"),"pc",emptyList(),listOf(chat)))
        assertTrue(directory.contains(chat.key))
        assertEquals("Ordinary-looking title",chat.title)
    }
}
