package dev.codexpocket.app

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.serialization.json.JsonObject

/** Public acceptance facts only; release devices do not require run-as to retrieve reports. */
internal fun writeLiveReport(name: String, report: JsonObject) {
    require(name.matches(Regex("[a-z]+-report\\.json")))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = File(instrumentation.targetContext.filesDir, "integration")
    check(directory.isDirectory || directory.mkdirs())
    File(directory, name).writeText(report.toString())
    instrumentation.sendStatus(0, Bundle().apply { putString("pocketReport", report.toString()) })
}
