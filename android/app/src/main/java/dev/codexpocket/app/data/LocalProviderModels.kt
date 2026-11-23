package dev.codexpocket.app.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A form request, deliberately without a generated toString that could reveal its key. */
internal class LocalProviderModelRequest(val providerId: String?, val baseUrl: String, val apiKey: String)

internal enum class LocalProviderModelProblem(val message: String) {
    Address("请填写有效的接口地址；HTTP 地址只能使用数字 IP。"),
    Key("请填写完整的 API Key，或留空使用这个服务已保存的 Key。"),
    Authentication("鉴权失败，请检查 API Key 和该服务的访问权限。"),
    Redirect("服务重定向了请求，请填写最终接口地址后重试。"),
    Unsupported("这个地址没有模型列表接口，可以继续手动填写模型。"),
    RateLimited("请求过于频繁，请稍后重试。"),
    Service("服务暂时无法返回模型列表，请稍后重试。"),
    Response("服务返回的模型列表格式无法识别，可以手动填写模型。"),
    Network("无法连接模型服务，请检查地址和网络后重试。")
}

internal sealed interface LocalProviderModelResult {
    data class Models(val ids: List<String>) : LocalProviderModelResult
    data class Failed(val problem: LocalProviderModelProblem) : LocalProviderModelResult
}

internal typealias LocalProviderModelLoader = suspend (LocalProviderModelRequest) -> LocalProviderModelResult

/** Fetches only a model catalog; a listed model is not evidence of Responses support. */
internal class LocalProviderModelDiscovery(private val providers: LocalProviderStore, client: OkHttpClient = OkHttpClient()) {
    // Never forward a credential to a redirect target, including a change from HTTPS to HTTP.
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun fetch(input: LocalProviderModelRequest): LocalProviderModelResult {
        val base = LocalProviderRules.baseUrl(input.baseUrl)
            ?: return LocalProviderModelResult.Failed(LocalProviderModelProblem.Address)
        val secret = if (input.apiKey.isBlank() && input.providerId != null) providers.find(input.providerId)?.apiKey else input.apiKey
        val key = secret?.let(LocalProviderRules::apiKey)
            ?: return LocalProviderModelResult.Failed(LocalProviderModelProblem.Key)
        val endpoint = "$base/models".toHttpUrlOrNull()
            ?: return LocalProviderModelResult.Failed(LocalProviderModelProblem.Address)
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key")
            .header("Accept", "application/json").get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(LocalProviderModelResult.Failed(LocalProviderModelProblem.Network))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        when {
                            it.code == 401 || it.code == 403 -> LocalProviderModelResult.Failed(LocalProviderModelProblem.Authentication)
                            it.code in 300..399 -> LocalProviderModelResult.Failed(LocalProviderModelProblem.Redirect)
                            it.code == 404 || it.code == 405 -> LocalProviderModelResult.Failed(LocalProviderModelProblem.Unsupported)
                            it.code == 429 -> LocalProviderModelResult.Failed(LocalProviderModelProblem.RateLimited)
                            !it.isSuccessful -> LocalProviderModelResult.Failed(LocalProviderModelProblem.Service)
                            else -> try {
                                val source = it.body?.source()
                                // Catalogs can contain many models, but must not fill the phone's memory.
                                if (source == null || source.request(2L * 1024 * 1024 + 1)) LocalProviderModelResult.Failed(LocalProviderModelProblem.Response)
                                else parse(source.readUtf8())
                            } catch (_: IOException) { LocalProviderModelResult.Failed(LocalProviderModelProblem.Network) }
                        }
                    }
                    continuation.resume(result)
                }
            })
        }
    }

    companion object {
        internal fun parse(text: String): LocalProviderModelResult {
            val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return LocalProviderModelResult.Failed(LocalProviderModelProblem.Response)
            val standard = root["data"] as? JsonArray
            val ccs = root["models"] as? JsonArray
            val entries = standard ?: ccs ?: return LocalProviderModelResult.Failed(LocalProviderModelProblem.Response)
            val field = if (standard != null) "id" else "slug"
            val ids = entries.mapNotNull { entry ->
                val id = ((entry as? JsonObject)?.get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content
                id?.takeIf { it.isNotEmpty() && LocalProviderRules.model(it) == it }
            }.distinct()
            return if (entries.isNotEmpty() && ids.isEmpty()) LocalProviderModelResult.Failed(LocalProviderModelProblem.Response)
                else LocalProviderModelResult.Models(ids)
        }
    }
}

internal data class LocalProviderModelSearchState(val loading: Boolean = false, val result: LocalProviderModelResult? = null)

/** Owned by one form. Input edits and disposal revoke every outstanding result, even from a non-cancellable loader. */
internal class LocalProviderModelSearch(private val scope: CoroutineScope, private val load: LocalProviderModelLoader) {
    private var generation = 0L
    private var job: Job? = null
    private var closed = false
    private val changes = MutableStateFlow(LocalProviderModelSearchState())
    val state = changes.asStateFlow()

    fun clear() {
        generation++
        job?.cancel(); job = null
        changes.value = LocalProviderModelSearchState()
    }

    fun fetch(request: LocalProviderModelRequest) {
        if (closed) return
        clear()
        val current = generation
        changes.value = LocalProviderModelSearchState(loading = true)
        job = scope.launch {
            val result = try { load(request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalProviderModelResult.Failed(LocalProviderModelProblem.Network) }
            if (!closed && generation == current) changes.value = LocalProviderModelSearchState(result = result)
        }
    }

    fun close() { closed = true; clear() }
}
