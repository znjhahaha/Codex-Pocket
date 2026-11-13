package dev.codexpocket.app

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Test APK only. A real system IME window for deterministic inset/input acceptance.
 * MuMu's bundled nemu-vinput reports visible with a zero-height window.
 * The runner selects this service only for the opted-in fixture and restores the prior IME.
 * No network, storage, suggestions, logging or captured text.
 */
class FixtureKeyboardService : InputMethodService() {
    override fun onEvaluateInputViewShown() = true
    override fun onEvaluateFullscreenMode() = false
    override fun onCreateInputView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(222, 224, 232))
        val height = (280 * resources.displayMetrics.density).toInt()
        minimumHeight = height
        layoutParams = android.view.ViewGroup.LayoutParams(-1, height)
        addView(TextView(context).apply {
            text = "Pocket 测试输入法 · 系统键盘窗口"
            setTextColor(Color.BLACK); gravity = Gravity.CENTER; textSize = 16f
        }, LinearLayout.LayoutParams(-1, (48 * resources.displayMetrics.density).toInt()))
        addView(Button(context).apply {
            text = "输入测"; contentDescription = "Pocket fixture insert"
            setOnClickListener { currentInputConnection?.commitText("测", 1) }
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}
