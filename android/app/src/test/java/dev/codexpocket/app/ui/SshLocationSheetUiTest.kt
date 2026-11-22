package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.ui.kit.LocalPocketHaptics
import dev.codexpocket.app.ui.kit.PocketHaptics
import dev.codexpocket.wire.SshAuthentication
import dev.codexpocket.wire.SshTarget
import dev.codexpocket.wire.WireException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SshLocationSheetUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val fingerprint = "SHA256:" + "Zk3".repeat(14) + "Q"
    private val probes = mutableListOf<Pair<String, Int>>()
    private var probeFailure: Exception? = null
    private val connected = mutableListOf<Pair<String, SshTarget>>()
    private var progress by mutableStateOf<String?>(null)
    private var pickedKey: String? = "-----BEGIN OPENSSH PRIVATE KEY-----\nprivate-material\n-----END OPENSSH PRIVATE KEY-----\n"
    private var restored: Map<String, List<Any?>>? = null
    private lateinit var registry: SaveableStateRegistry

    /** Always a new composition: what is merely remembered must not survive from the one before. */
    private fun content(theme: String = "light", font: Float = 1f, width: Int = 360) {
        compose.activity.runOnUiThread { compose.activity.setContent { } }
        compose.waitForIdle()
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalPocketGlass provides false, LocalPocketHaptics provides PocketHaptics { },
                LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false),
                LocalDensity provides Density(density.density, font)) {
                registry = remember { SaveableStateRegistry(restored) { true } }
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    Box(Modifier.width(width.dp).fillMaxHeight()) { PocketOverlayHost {
                        SshLocationSheet(progress, {}, { label, target -> connected += label to target },
                            probe = { host, port -> probes += host to port; probeFailure?.let { throw it }; fingerprint },
                            keyPicker = { onKey, onFailure -> { pickedKey?.let(onKey) ?: onFailure() } })
                    } }
                }
            }
        } } }
        compose.waitForIdle()
    }
    private fun field(tag: String) = compose.onNodeWithTag(tag)
    private fun type(tag: String, text: String) { field(tag).performScrollTo().performTextReplacement(text); compose.waitForIdle() }
    private fun fillPassword(host: String = "dev.example.com", user: String = "alice", password: String = "correct horse") {
        type("ssh-host", host); type("ssh-user", user); type("ssh-password", password)
    }
    private fun editable(tag: String) = field(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
    /** Everything on screen that is drawn or read out; the untransformed input of a field is deliberately not part of it. */
    private fun spoken() = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().joinToString(" | ") { node ->
        listOf(SemanticsProperties.Text, SemanticsProperties.ContentDescription).flatMap { node.config.getOrNull(it).orEmpty() }.joinToString(" ") { it.toString() } +
            " " + node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty() + " " + node.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
    }
    private val connect get() = compose.onNodeWithTag("ssh-connect")
    private val dp get() = compose.activity.resources.displayMetrics.density

    @Test fun hostMustBeCheckedAndItsKeyConfirmedBeforeAnyCredentialIsSent() {
        content()
        compose.onNodeWithText("SSH 远程会话").assertIsDisplayed()
        connect.assertIsNotEnabled()
        compose.onNodeWithText("检查主机", useUnmergedTree = true).assertExists()
        fillPassword()
        type("ssh-port", "2222")
        connect.assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf("dev.example.com" to 2222), probes)
        assertTrue("nothing is sent before the key is confirmed", connected.isEmpty())
        field("ssh-host-key").performScrollTo()
        compose.onNodeWithText("确认主机身份").assertIsDisplayed()
        compose.onNodeWithTag("ssh-fingerprint", useUnmergedTree = true).assertTextEquals(fingerprint)
        compose.onNodeWithText("连接并准备", useUnmergedTree = true).assertExists()
        connect.assertIsNotEnabled()
        field("ssh-trust").assertIsOff().performClick()
        field("ssh-trust").assertIsOn()
        connect.assertIsEnabled().performClick()
        compose.waitForIdle()
        val (label, target) = connected.single()
        assertEquals("dev.example.com", label)
        assertEquals(listOf("dev.example.com", 2222, "alice", fingerprint, 43821), listOf(target.host, target.port, target.username, target.hostKey, target.backendPort))
        assertEquals("correct horse", (target.authentication as SshAuthentication.Password).value)
    }

    @Test fun anotherHostOrPortDiscardsTheConfirmationAndInvalidInputCannotBeSubmitted() {
        content()
        fillPassword()
        connect.performClick(); compose.waitForIdle()
        field("ssh-trust").performScrollTo().performClick()
        connect.assertIsEnabled()
        type("ssh-port", "2200")
        compose.onNodeWithTag("ssh-host-key").assertDoesNotExist()
        compose.onNodeWithText("检查主机", useUnmergedTree = true).assertExists()
        connect.performClick(); compose.waitForIdle()
        assertEquals(listOf("dev.example.com" to 22, "dev.example.com" to 2200), probes)
        field("ssh-trust").performScrollTo().assertIsOff()
        type("ssh-host", "other.example.com")
        compose.onNodeWithTag("ssh-host-key").assertDoesNotExist()
        for (port in listOf("0", "70000", "")) { type("ssh-port", port); connect.assertIsNotEnabled() }
        type("ssh-port", "22"); connect.assertIsEnabled()
        type("ssh-user", " "); connect.assertIsNotEnabled()
        type("ssh-user", "alice"); type("ssh-password", ""); connect.assertIsNotEnabled()
        assertTrue(connected.isEmpty())
    }

    @Test fun unreachableHostIsReportedAndCanBeTriedAgain() {
        probeFailure = WireException("ssh_unreachable", "SSH host key could not be read")
        content()
        fillPassword()
        connect.performClick(); compose.waitForIdle()
        field("ssh-error").performScrollTo().assertTextEquals("无法连接 SSH，请检查地址和端口。")
        compose.onNodeWithTag("ssh-host-key").assertDoesNotExist()
        probeFailure = null
        connect.assertIsEnabled().performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ssh-error").assertDoesNotExist()
        field("ssh-host-key").assertExists()
    }

    @Test fun privateKeyWithItsPassphraseBecomesTheAuthenticationAndAnUnreadableFileIsReported() {
        content()
        type("ssh-host", "dev.example.com"); type("ssh-user", "alice")
        field("ssh-auth:key").performScrollTo().performClick()
        compose.onNodeWithTag("ssh-password").assertDoesNotExist()
        connect.assertIsNotEnabled()
        val chosen = pickedKey
        pickedKey = null
        field("ssh-key").performScrollTo().performClick(); compose.waitForIdle()
        field("ssh-error").performScrollTo().assertTextEquals("私钥无法读取，请选择 PEM 或 OpenSSH 私钥文件。")
        connect.assertIsNotEnabled()
        pickedKey = chosen
        compose.onNodeWithText("选择私钥文件", useUnmergedTree = true).assertExists()
        field("ssh-key").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("已选择私钥 · 更换", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ssh-error").assertDoesNotExist()
        type("ssh-passphrase", "key passphrase")
        connect.performClick(); compose.waitForIdle()
        field("ssh-trust").performScrollTo().performClick()
        connect.performClick(); compose.waitForIdle()
        val authentication = connected.single().second.authentication as SshAuthentication.PrivateKey
        assertEquals(chosen, authentication.pem)
        assertEquals("key passphrase", authentication.passphrase)
    }

    @Test fun secretsAreMaskedOnScreenAndNeverReachSavedState() {
        content()
        fillPassword(password = "correct horse")
        assertTrue("the field is announced as a password", field("ssh-password").fetchSemanticsNode().config.contains(SemanticsProperties.Password))
        assertEquals("\u2022".repeat("correct horse".length), editable("ssh-password"))
        assertFalse("correct horse" in spoken())
        field("ssh-auth:key").performScrollTo().performClick()
        field("ssh-key").performScrollTo().performClick(); compose.waitForIdle()
        type("ssh-passphrase", "key passphrase")
        assertTrue(field("ssh-passphrase").fetchSemanticsNode().config.contains(SemanticsProperties.Password))
        assertEquals("\u2022".repeat("key passphrase".length), editable("ssh-passphrase"))
        assertFalse("private-material" in spoken() || "key passphrase" in spoken())
        connect.performClick(); compose.waitForIdle()
        field("ssh-trust").performScrollTo().performClick()
        // What an Activity would write to its instance state when the system reclaims the process.
        compose.runOnIdle { restored = registry.performSave() }
        content()
        val saved = restored.toString()
        for (secret in listOf("correct horse", "key passphrase", "private-material", fingerprint)) assertFalse(secret, secret in saved)
        assertTrue("dev.example.com" in saved && "alice" in saved)
        field("ssh-host").assertTextContains("dev.example.com")
        field("ssh-user").assertTextContains("alice")
        // The key, its passphrase and the trust decision are asked for again.
        compose.onNodeWithText("选择私钥文件", useUnmergedTree = true).assertExists()
        assertEquals("", editable("ssh-passphrase"))
        compose.onNodeWithTag("ssh-host-key").assertDoesNotExist()
        connect.assertIsNotEnabled()
        field("ssh-auth:password").performScrollTo().performClick()
        assertEquals("", editable("ssh-password"))
        assertTrue(connected.isEmpty())
    }

    @Test fun preparationProgressIsShownAndASecondAttemptCannotStart() {
        content()
        fillPassword()
        connect.performClick(); compose.waitForIdle()
        field("ssh-trust").performScrollTo().performClick()
        progress = "正在上传远程运行环境"
        compose.waitForIdle()
        field("ssh-progress").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("正在上传远程运行环境", useUnmergedTree = true).assertExists()
        connect.assertIsNotEnabled()
        field("ssh-host").assertIsNotEnabled()
        field("ssh-trust").assertIsNotEnabled()
        progress = "正在启动远程后台"
        compose.waitForIdle()
        compose.onNodeWithText("正在启动远程后台", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("正在上传远程运行环境", useUnmergedTree = true).assertDoesNotExist()
        progress = null
        compose.waitForIdle()
        compose.onNodeWithTag("ssh-progress").assertDoesNotExist()
        connect.assertIsEnabled()
    }

    @Test fun everyControlKeepsA48dpTargetInBothThemesAtNarrowWidthAndLargeText() {
        for (theme in listOf("light", "dark")) {
            probes.clear()
            content(theme, 1.6f, 320)
            fillPassword()
            connect.performClick(); compose.waitForIdle()
            field("ssh-host-key").performScrollTo()
            compose.onNodeWithTag("ssh-fingerprint", useUnmergedTree = true).assertTextEquals(fingerprint)
            field("ssh-auth:key").performScrollTo().performClick()
            val controls = compose.onAllNodes((hasClickAction() or hasSetTextAction()) and hasAnyAncestor(hasTestTag("ssh-sheet"))).fetchSemanticsNodes()
            assertTrue("${controls.size} controls", controls.size >= 9)
            controls.forEach { node ->
                val name = node.config.getOrNull(SemanticsProperties.TestTag) ?: node.config.toString()
                assertTrue("$theme $name is ${node.size.width / dp}x${node.size.height / dp}dp", node.size.height / dp >= 47.9f && node.size.width / dp >= 47.9f)
                assertTrue("$theme $name leaves the 320dp sheet", node.boundsInRoot.left >= -1f && node.boundsInRoot.right <= 320 * dp + 1f)
            }
            compose.onNodeWithContentDescription("关闭").assertIsDisplayed().assertHasClickAction()
            connect.assertIsDisplayed()
        }
    }
}
