package dev.codexpocket.app

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in screenshots: adb instrumentation argument composerSuggestions=true. Local callback fixture only. */
@RunWith(AndroidJUnit4::class)
class ComposerSuggestionsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun slashAndAtStayInlineAndFocused() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("composerSuggestions") == "true")
        val directory = File(compose.activity.getExternalFilesDir(null), "composer-suggestions-proof/${System.currentTimeMillis()}")
        check(directory.mkdirs())
        var draft by mutableStateOf("")
        var state by mutableStateOf(ComposerSuggestionsState(online = true, actions = ComposerAction.entries.toSet()))
        var sends = 0
        var actions = 0
        val data = listOf(
            buildJsonObject { put("type", "skill"); put("name", "review"); put("path", "skills/review/SKILL.md") },
            buildJsonObject { put("type", "file"); put("name", "Composer.kt"); put("path", "src/Composer.kt") },
            buildJsonObject { put("type", "mention"); put("name", "Native tools"); put("path", "plugin://fixture/verified-mention") },
        )
        compose.activity.runOnUiThread {
            compose.activity.setContent { PocketTheme("light") { PocketBackdrop {
                Box(Modifier.fillMaxSize()) {
                    Text("输入 / 查看命令与技能，输入 @ 查看引用", Modifier.padding(24.dp))
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        PocketComposer(draft, "Fixture model", "high", false, ComposerAvailability(true, false, true, false),
                            { draft = it }, {}, {}, {}, { sends++ }, {}, {}, true,
                            suggestions = state, onQuery = { state = state.copy(trigger = it, data = data) },
                            onSelectAction = { actions++ }, onReference = { value, expected, start, end ->
                                val edit = referenceToken(value)?.let { replaceReferenceAt(draft, expected, start, end, it) }
                                if (edit == null) false else { draft = edit.text; true }
                            })
                    }
                }
            } } }
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("/")
        compose.onNodeWithText("/plan").assertExists()
        compose.onNodeWithTag("composer-input").assertIsFocused()
        screenshot(directory, "01-slash.png")
        compose.onNodeWithTag("composer-input").performTextReplacement("Inspect @ suffix")
        compose.onNodeWithTag("composer-input").performTextInputSelection(TextRange(9))
        compose.onNodeWithText("Composer.kt").assertExists()
        compose.onNodeWithText("Native tools").assertExists()
        compose.onNodeWithTag("composer-input").assertIsFocused()
        screenshot(directory, "02-at.png")
        compose.onNodeWithText("Composer.kt").performClick()
        assertEquals("Inspect @src/Composer.kt suffix", draft)
        compose.onNodeWithTag("composer-input").assertIsFocused()
        compose.onNodeWithTag("composer-suggestions").assertDoesNotExist()
        screenshot(directory, "03-at-selected.png")
        assertEquals(0, sends)
        assertEquals(0, actions)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("composerSuggestionsProof", directory.absolutePath) })
    }

    private fun screenshot(directory: File, name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
