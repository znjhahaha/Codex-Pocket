package dev.codexpocket.app.ui

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.codexpocket.app.R

/** Bundled MiSans for both CJK and Latin, shared by Compose and native text layout. */
internal val PocketReadingFont = FontFamily(
    Font(R.font.misans_regular, FontWeight.Normal),
    Font(R.font.misans_medium, FontWeight.Medium),
    Font(R.font.misans_semibold, FontWeight.SemiBold),
    Font(R.font.misans_bold, FontWeight.Bold),
)
internal object ReadingTypography {
    const val bodySp = 16f
    const val lineHeightSp = 26f
    val headingMultipliers get() = floatArrayOf(22f / 16, 20f / 16, 18f / 16, 1f, 1f, 1f)
    fun typeface(context: Context, heading: Boolean = false): Typeface =
        ResourcesCompat.getFont(context, if (heading) R.font.misans_semibold else R.font.misans_family) ?: Typeface.DEFAULT
}
