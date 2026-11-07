package dev.codexpocket.app.push

import android.content.Context
import dev.codexpocket.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The default build has no HMS runtime requirement. The enabled build supplies its service. */
object PushRegistration {
    val configured: Boolean get() = BuildConfig.HUAWEI_APP_ID.isNotBlank()
    suspend fun token(context: Context): String? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val type = Class.forName("com.huawei.hms.aaid.HmsInstanceId")
        val instance = type.getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext)
        (type.getMethod("getToken", String::class.java, String::class.java).invoke(instance, BuildConfig.HUAWEI_APP_ID, "HCM") as? String)?.takeIf(String::isNotBlank)
    }
}
