package dev.codexpocket.app

import android.content.Context
import android.content.Intent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real system IME without Compose test input interception; existing history only. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class LiveV051KeyboardTest {
    @Test fun existingDraftOpensRealKeyboardAndReturns() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("v051Keyboard")=="true")
        val id=requireNotNull(args.getString("keyboardThreadId"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val repo=(context.applicationContext as PocketApplication).repository
        var opened=false; var closed=false; var stage="connect"; var failure:String?=null
        var draft:String?=null
        var activity:MainActivity?=null
        try {
            runBlocking { withTimeout(90_000) {
                repo.state.first { it.initialized }
                withContext(Dispatchers.Main) { repo.setForeground(true) }
                repo.state.first { it.online }; repo.refresh().join()
                val chat=repo.state.value.chats.first { it.key.nativeId==id }
                repo.select(chat).join(); draft=repo.state.value.draft?.text
            } }
            stage="activity"
            activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val active=activity
            val automation=instrumentation.uiAutomation
            val service=automation.serviceInfo
            service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo=service
            fun <T> ui(block:()->T):T { var value:T?=null;instrumentation.runOnMainSync { value=block() };@Suppress("UNCHECKED_CAST") return value as T }
            fun until(block:()->Boolean) { val end=System.nanoTime()+10_000_000_000L;while(!block()){check(System.nanoTime()<end){"Timeout at $stage"};Thread.sleep(100)} }
            fun input(node:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                node?:return null
                if(node.isEditable && node.isVisibleToUser)return node
                for(i in 0 until node.childCount)input(node.getChild(i))?.let { return it }
                return null
            }
            stage="focus"
            var editor:AccessibilityNodeInfo?=null
            until {
                editor=automation.windowsOnAllDisplays.get(ui { active.display!!.displayId })?.firstNotNullOfOrNull { input(it.root) }
                editor!=null
            }
            check(editor!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            editor!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ui { (active.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(active.currentFocus,InputMethodManager.SHOW_IMPLICIT) }
            stage="keyboard_open"
            until { ui { active.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true } }
            opened=true
            stage="keyboard_close"
            ui { active.window.insetsController?.hide(WindowInsets.Type.ime()) }
            until { ui { active.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())!=true } }
            closed=true
            check(repo.state.value.draft?.text==draft)
            stage="return"
            ui { active.onBackPressedDispatcher.onBackPressed() }
        } catch(e:Throwable) { failure=e.javaClass.simpleName;throw e }
        finally {
            writeLiveReport("keyboard-report.json",buildJsonObject {
                put("passed",failure==null&&opened&&closed);put("stage",stage);put("keyboardOpened",opened);put("keyboardClosed",closed)
                put("draftPreserved",draft==repo.state.value.draft?.text);put("modelRequests",0);put("failureType",failure)
            })
            runBlocking { withContext(Dispatchers.Main) { repo.setForeground(false) } }
        }
    }
}
