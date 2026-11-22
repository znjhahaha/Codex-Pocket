package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/** Renders the usage page in each of its states to build/reports/usage for review. Tall windows show a whole page at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsageScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var view: android.view.View
    private val padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
    // A week ending today, so the page reads as it does in daily use: "7 天" selected, fresh figures.
    private val week = UsageFilter.lastDays(7, "UTC")
    private val sampled = week.endDate.atStartOfDay(ZoneId.of("UTC")).toEpochSecond() + 81600

    private fun render(name: String, theme: String = "light", font: Float = 1f, block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
            view = LocalView.current
            val density = LocalDensity.current
            // Software rendering cannot replay the backdrop graph; the capsule shows its solid fallback.
            CompositionLocalProvider(LocalPocketGlass provides false, LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false),
                LocalDensity provides Density(density.density, font)) { PocketOverlayHost { block() } }
        } } }
        compose.waitForIdle()
        capture(name)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File("build/reports/usage/$name.png").also { it.parentFile.mkdirs() }.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test @Config(qualifiers = "w360dp-h2500dp-xxhdpi")
    fun dataLight() {
        render("data-light") { UsageScreen(UsageUiFixtures.rich(week, sampled), {}, {}, contentPadding = padding, onRequests = {}) }
        compose.onNodeWithTag("usage-metrics").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w360dp-h2500dp-xxhdpi")
    fun dataDark() {
        render("data-dark", "dark") { UsageScreen(UsageUiFixtures.rich(week, sampled), {}, {}, contentPadding = padding, onRequests = {}) }
        compose.onNodeWithTag("usage-metrics").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w320dp-h4200dp-xxhdpi")
    fun dataNarrowLargeFont() {
        render("data-320dp-large-font", font = 1.4f) {
            UsageScreen(UsageUiFixtures.rich(week.withStatus(UsageStatusFilter.Success).withModel("gpt-5.2-codex"), sampled), {}, {}, contentPadding = padding, onRequests = {})
        }
        compose.onNodeWithTag("usage-metrics").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w320dp-h6000dp-xxhdpi")
    fun dataNarrowLargestFont() {
        render("data-320dp-largest-font", "dark", 2f) {
            UsageScreen(UsageUiFixtures.rich(week.withStatus(UsageStatusFilter.Success).withModel("gpt-5.2-codex"), sampled), {}, {}, contentPadding = padding, onRequests = {})
        }
        compose.onNodeWithTag("usage-metrics").assertIsDisplayed()
    }

    @Test fun firstScreenLightAndDark() {
        render("data-first-screen-light") { UsageScreen(UsageUiFixtures.rich(week, sampled), {}, {}, contentPadding = padding, onRequests = {}) }
        render("data-first-screen-dark", "dark") { UsageScreen(UsageUiFixtures.rich(week, sampled), {}, {}, contentPadding = padding, onRequests = {}) }
        compose.onNodeWithTag("usage-metrics").assertIsDisplayed()
    }

    @Test fun trendModesAndFocus() {
        render("trend-cost") { UsageScreen(UsageUiFixtures.rich(week, sampled), {}, {}, contentPadding = padding) }
        val list = compose.onNodeWithTag("usage-list")
        list.performScrollToNode(hasTestTag("usage-trend"))
        compose.onNodeWithTag("usage-trend-chart").performTouchInput { swipe(Offset(width * .1f, centerY), Offset(width * .64f, centerY), durationMillis = 200) }
        capture("trend-cost-focused")
        listOf("Tokens", "Requests", "Latency").forEach { mode ->
            compose.onNodeWithTag("usage-trend:$mode").performClick()
            capture("trend-${mode.lowercase()}-focused")
        }
    }

    @Test fun sheets() {
        render("sheet-none", "dark") { UsageScreen(UsageUiFixtures.rich(week, sampled), {}, {}, contentPadding = padding, onRequests = {}) }
        compose.onNodeWithTag("usage-filters-button").performClick()
        capture("sheet-filter-dark")
        render("sheet-none") { UsageScreen(UsageUiFixtures.rich(week, sampled), {}, {}, contentPadding = padding, onRequests = {}) }
        compose.onNodeWithTag("usage-filters-button").performClick()
        capture("sheet-filter")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithTag("usage-metric:cost").performClick()
        capture("sheet-insight")
        compose.onNodeWithContentDescription("关闭").performClick()
        val list = compose.onNodeWithTag("usage-list")
        list.performScrollToNode(hasTestTag("usage-request:0"))
        compose.onNodeWithTag("usage-request:0").performClick()
        capture("sheet-request")
        compose.onNodeWithContentDescription("关闭").performClick()
        list.performScrollToNode(hasTestTag("usage-coverage-open"))
        compose.onNodeWithTag("usage-coverage-open").performClick()
        capture("sheet-coverage")
        java.io.File("build/reports/usage/sheet-none.png").delete()
    }

    @Test fun loading() {
        render("loading") { UsageScreen(UsageScreenState(week, phase = UsagePhase.Loading, refreshing = true, canQuery = true), {}, {}, contentPadding = padding) }
        compose.onNodeWithTag("usage-skeleton").assertIsDisplayed()
    }

    @Test fun empty() {
        render("empty") { UsageScreen(UsageUiFixtures.empty(week, sampled), {}, {}, contentPadding = padding) }
        compose.onNodeWithTag("usage-empty").assertIsDisplayed()
    }

    @Test fun errorWithoutCache() {
        render("error-no-cache") {
            UsageScreen(UsageScreenState(week, phase = UsagePhase.Failed, canQuery = true, failureCode = "backend_failed"), {}, {}, contentPadding = padding)
        }
        compose.onNodeWithText("用量读取失败").assertIsDisplayed()
        render("error-no-cache-dark", "dark") {
            UsageScreen(UsageScreenState(week, phase = UsagePhase.Offline), {}, {}, contentPadding = padding)
        }
    }

    @Test fun errorWithCache() {
        render("error-with-cache") {
            UsageScreen(UsageUiFixtures.rich(week, sampled).copy(phase = UsagePhase.Failed, cached = true, failureCode = "backend_failed"), {}, {}, contentPadding = padding)
        }
        compose.onNodeWithTag("usage-retry").assertIsDisplayed()
        compose.onNodeWithTag("usage-metrics").assertIsDisplayed()
    }
}
