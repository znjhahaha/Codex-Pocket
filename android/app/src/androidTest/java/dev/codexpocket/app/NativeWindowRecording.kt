package dev.codexpocket.app

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in sampled window recording. Never enabled during performance measurements. */
internal class NativeWindowRecording(private val activity: Activity, directory: File) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val frames = File(directory, "window-frames").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Thread({
        var bitmap: Bitmap? = null
        val started = SystemClock.elapsedRealtime()
        var index = 0
        try {
            while (running.get() && index < 360) {
                val latch = CountDownLatch(1)
                var result = -1
                main.post {
                    val view = activity.window.decorView
                    if (view.width <= 0 || view.height <= 0) { latch.countDown(); return@post }
                    val frame = bitmap ?: Bitmap.createBitmap(540, (540f * view.height / view.width).toInt(), Bitmap.Config.ARGB_8888).also { bitmap = it }
                    try { PixelCopy.request(activity.window, frame, { result = it; latch.countDown() }, main) }
                    catch (_: Exception) { latch.countDown() }
                }
                if (!latch.await(3, TimeUnit.SECONDS)) break
                if (result == PixelCopy.SUCCESS) {
                    val name = "frame-%04d.jpg".format(index++)
                    File(frames, name).outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    File(frames, "frames.tsv").appendText("$name\t${SystemClock.elapsedRealtime() - started}\n")
                }
                SystemClock.sleep(150)
            }
        } finally { bitmap?.recycle() }
    }, "pocket-window-recording").apply { isDaemon = true; start() }

    override fun close() { running.set(false); worker.join(4_000) }
}
