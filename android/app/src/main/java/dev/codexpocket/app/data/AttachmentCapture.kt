package dev.codexpocket.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

fun captureUri(context: Context): Uri {
    val folder = File(context.cacheDir, "capture").apply { mkdirs() }
    return FileProvider.getUriForFile(context, "${context.packageName}.files", File(folder, "${UUID.randomUUID()}.jpg"))
}
