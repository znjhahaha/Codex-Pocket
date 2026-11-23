package dev.codexpocket.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import dev.codexpocket.wire.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real location stores and repositories; only a preference read is paused during activation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExecutionLocationIsolationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class Blob : LocationBlob {
        private var text: String? = null
        override fun read() = text
        override fun write(value: String) { text = value }
    }
    private object NoLegacy : PairingStorage {
        override fun load(): PairedComputer? = null
        override fun save(pair: PairedComputer) = Unit
        override fun forget() = Unit
    }
    private class ReadGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun awaitRead() = assertTrue("Activation must reach the appearance read", entered.await(5, TimeUnit.SECONDS))
    }
    private class DelayedPreferences(delegate: SharedPreferences) : SharedPreferences by delegate {
        private val base = delegate
        val nextThemeRead = AtomicReference<ReadGate?>()
        override fun getString(key: String?, default: String?): String? {
            if (key == "theme") nextThemeRead.getAndSet(null)?.let { gate ->
                gate.entered.countDown()
                check(gate.release.await(60, TimeUnit.SECONDS)) { "Test did not release the preference read" }
            }
            return base.getString(key, default)
        }
    }
    private class FixtureContext(base: Context, val settings: DelayedPreferences) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            if (name == "settings") settings else super.getSharedPreferences(name, mode)
    }

    private fun pair(endpoint: String, name: String) = PairedComputer(
        ConnectionConfig("wss://fixture.invalid", "room", "fixture-token", "00".repeat(32)),
        "phone", "fixture-secret", endpoint, name)

    private fun fixture(body: (ExecutionLocations, DelayedPreferences, Map<String, PocketRepository>) -> Unit) {
        compose.mainClock.autoAdvance = false
        val settings = DelayedPreferences(compose.activity.getSharedPreferences("location-isolation-test", Context.MODE_PRIVATE))
        settings.edit().clear().putString("theme", "light").putBoolean("glassEffects", false).commit()
        val context = FixtureContext(compose.activity.application, settings)
        val db = Room.inMemoryDatabaseBuilder(context, PocketDatabase::class.java).build()
        val store = ExecutionLocationStore(Blob(), NoLegacy).apply {
            register(ExecutionTarget("computer", ExecutionTargetKind.Computer, "工作电脑"), pair("pc", "工作电脑"))
            register(ExecutionTarget("phone", ExecutionTargetKind.ThisPhone, "此手机"), pair("local", "此手机"))
            register(ExecutionTarget("ssh", ExecutionTargetKind.LinuxSsh, "开发服务器"), pair("linux", "开发服务器"))
        }
        val locations = ExecutionLocations(context, store, db)
        val repositories = linkedMapOf<String, PocketRepository>()
        try {
            // Reuse initialized repositories, so the gate delays activation rather than constructor setup.
            runBlocking {
                for (id in listOf("computer", "phone", "ssh", "computer")) {
                    locations.select(id).join()
                    val repository = checkNotNull(locations.active.value)
                    withTimeout(5_000) { repository.state.first { it.initialized } }
                    repositories[id] = repository
                }
            }
            compose.activity.runOnUiThread { compose.activity.setContent { ExecutionLocationsRoot(locations) } }
            frames()
            body(locations, settings, repositories)
        } finally {
            settings.nextThemeRead.getAndSet(null)?.release?.countDown()
            compose.activity.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle()
            runBlocking {
                (listOf(locations) + repositories.values).forEach { owner ->
                    val scope = owner.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(owner) as CoroutineScope
                    scope.coroutineContext[Job]!!.cancelAndJoin()
                }
            }
            db.close()
        }
    }

    private fun frames(count: Int = 40) {
        repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }
    private fun screenshot(name: String) {
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also {
                decor.draw(android.graphics.Canvas(it))
            }
        }
        val file = File("build/reports/location-isolation/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun pendingPhoneActivationKeepsThePickerOnTheRepositoryStillBeingDisplayed() = fixture { locations, settings, repos ->
        compose.onNodeWithTag("home-host").performClick()
        frames()
        compose.onNodeWithTag("execution-location-picker").performClick()
        frames()
        val gate = ReadGate()
        settings.nextThemeRead.set(gate)
        val switching = locations.select("phone")
        try {
            gate.awaitRead()
            assertSame(repos.getValue("computer"), locations.active.value)
            frames(4)
            screenshot("pending-phone-activation")
            compose.onNodeWithTag("execution-location:computer", useUnmergedTree = true)
                .assert(hasAnyDescendant(hasText("当前")))
            compose.onNodeWithTag("execution-location:phone", useUnmergedTree = true)
                .assert(hasAnyDescendant(hasText("切换")))
        } finally {
            gate.release.countDown()
            runBlocking { switching.join() }
        }
        frames()
        compose.onNodeWithTag("execution-location:phone", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("当前")))
        screenshot("phone-activation-complete")
    }

    @Test fun locationSwitchesRestoreTheirOwnPageAndNeverReuseAnotherLocationsSearch() = fixture { locations, _, _ ->
        compose.onNodeWithTag("home-tab-devices").performClick()
        frames()
        compose.onNodeWithTag("settings-tab:App").performClick()
        frames()
        runBlocking { locations.select("phone").join() }
        frames()
        compose.onNodeWithTag("home-tab-chats").assertIsSelected()
        compose.onNodeWithTag("home-host", useUnmergedTree = true).assert(hasAnyDescendant(hasText("此手机")))
        compose.onNodeWithTag("home-header-search").performClick()
        frames()
        compose.onNodeWithContentDescription("搜索对话").performTextInput("手机项目")
        runBlocking { locations.select("ssh").join() }
        frames()
        compose.onNodeWithTag("home-host", useUnmergedTree = true).assert(hasAnyDescendant(hasText("开发服务器")))
        compose.onNodeWithContentDescription("搜索对话").assertDoesNotExist()
        screenshot("ssh-home-independent")
        runBlocking { locations.select("computer").join() }
        frames()
        compose.onNodeWithTag("home-tab-devices").assertIsSelected()
        compose.onNodeWithTag("settings-tab:App").assertIsSelected()
        screenshot("computer-page-restored")
        runBlocking { locations.select("phone").join() }
        frames()
        compose.onNodeWithContentDescription("搜索对话").assertTextContains("手机项目")
        compose.onNodeWithTag("home-host", useUnmergedTree = true).assert(hasAnyDescendant(hasText("此手机")))
        screenshot("phone-search-restored")
    }
}
