package com.sufyan.harness.ai

import com.sufyan.harness.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenRouterProvider(
    private val secure: SecureStore,
    endpoint: String = DEFAULT_ENDPOINT,
) : AiProvider {

    companion object {
        const val DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1"
        private const val REFERER = "https://github.com/sufyan-vip/Softwere"
        private const val TITLE = "Sufyan Harness"
        private const val MODEL_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val MAX_RETRIES = 3
    }

    override val displayName = "OpenRouter"

    /** Mutable so the user can point at a compatible endpoint (Phase 5) without recreating the app graph. */
    var endpointValue: String = endpoint
    private fun endpoint(): String = endpointValue.ifBlank { DEFAULT_ENDPOINT }

    private var modelsCache: List<ModelInfo>? = null
    private var modelsCacheAt: Long = 0

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS) // long, because responses stream
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun requireKey(): String =
        secure.apiKey() ?: throw IllegalStateException("No OpenRouter API key configured.")

    private fun Request.Builder.auth(key: String) = this
        .header("Authorization", "Bearer $key")
        .header("HTTP-Referer", REFERER)
        .header("X-Title", TITLE)

    /** Verifies the stored key against OpenRouter. Never echoes the key back. */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val key = requireKey()
            val req = Request.Builder().url("${endpoint()}/auth/key").auth(key).get().build()
            client.newCall(req).execute().use { res ->
                when {
                    res.code == 401 -> throw IOException("Key rejected (401). Check the key is correct and active.")
                    !res.isSuccessful -> throw IOException("OpenRouter returned HTTP ${res.code}.")
                    else -> {
                        val body = res.body?.string().orEmpty()
                        val data = runCatching {
                            json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                        }.getOrNull()
                        val limit = data?.get("limit")?.jsonPrimitive?.contentOrNull ?: "unlimited"
                        val usage = data?.get("usage")?.jsonPrimitive?.contentOrNull ?: "0"
                        "Connected. Usage $usage, limit $limit."
                    }
                }
            }
        }
    }

    override suspend fun listModels(force: Boolean): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force) {
            modelsCache?.let { cached ->
                if (now - modelsCacheAt < MODEL_CACHE_TTL_MS) return@withContext Result.success(cached)
            }
        }
        runCatching {
            val req = Request.Builder().url("${endpoint()}/models").get()
                .header("HTTP-Referer", REFERER).header("X-Title", TITLE).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("Could not load models (HTTP ${res.code}).")
                val arr = json.parseToJsonElement(res.body!!.string()).jsonObject["data"]!!.jsonArray
                val models = arr.mapNotNull { el ->
                    runCatching {
                        val o = el.jsonObject
                        val pricing = o["pricing"]?.jsonObject
                        ModelInfo(
                            id = o["id"]!!.jsonPrimitive.content,
                            name = o["name"]?.jsonPrimitive?.contentOrNull ?: o["id"]!!.jsonPrimitive.content,
                            contextLength = o["context_length"]?.jsonPrimitive?.intOrNull,
                            promptPrice = pricing?.get("prompt")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                            completionPrice = pricing?.get("completion")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                        )
                    }.getOrNull()
                }.sortedBy { it.id }
                modelsCache = models
                modelsCacheAt = now
                models
            }
        }
    }

    private fun buildBody(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema>,
        temperature: Float,
    ): String = buildJsonObject {
        put("model", model)
        put("stream", true)
        put("temperature", temperature.toDouble())
        putJsonArray("messages") {
            messages.forEach { m ->
                addJsonObject {
                    put("role", m.role)
                    put("content", m.content)
                    m.toolCallId?.let { put("tool_call_id", it) }
                    m.name?.let { put("name", it) }
                    if (m.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            m.toolCalls.forEach { tc ->
                                addJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", tc.name)
                                        put("arguments", tc.argumentsJson)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { t ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parameters)
                        }
                    }
                }
            }
            put("tool_choice", "auto")
        }
    }.toString()

    override fun stream(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema>,
        temperature: Float,
    ): Flow<StreamEvent> = flow {
        val key = try {
            requireKey()
        } catch (e: IllegalStateException) {
            emit(StreamEvent.Failed(AiError("No API key", "Add your OpenRouter API key in Settings → AI.", false)))
            emit(StreamEvent.Done)
            return@flow
        }

        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            val request = Request.Builder()
                .url("${endpoint()}/chat/completions")
                .auth(key)
                .post(buildBody(model, messages, tools, temperature).toRequestBody("application/json".toMediaType()))
                .build()

            var retryAfterMs: Long? = null
            try {
                client.newCall(request).execute().use { res ->
                    // §5 — rate limit / server errors are retried with backoff (429 uses Retry-After).
                    if ((res.code == 429 || res.code in 500..599) && attempt < MAX_RETRIES) {
                        retryAfterMs = res.header("Retry-After")?.toLongOrNull()?.times(1000)
                            ?: (1000L * attempt)
                        return@use
                    }
                    if (!res.isSuccessful) {
                        emit(StreamEvent.Failed(httpError(res.code, res.body?.string().orEmpty())))
                        emit(StreamEvent.Done)
                        return@use
                    }
                    val source = res.body?.source()
                    if (source == null) {
                        emit(StreamEvent.Failed(AiError("Empty response", "OpenRouter returned no response body.", true)))
                        emit(StreamEvent.Done)
                        return@use
                    }

                    // tool call fragments accumulate by index across deltas
                    val toolNames = mutableMapOf<Int, String>()
                    val toolIds = mutableMapOf<Int, String>()
                    val toolArgs = mutableMapOf<Int, StringBuilder>()
                    var lastUsage: Usage? = null

                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty()) continue
                        if (payload == "[DONE]") break

                        // §50 — real usage may be reported on the final chunk.
                        runCatching {
                            json.parseToJsonElement(payload).jsonObject["usage"]?.jsonObject?.let { u ->
                                lastUsage = Usage(
                                    promptTokens = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                                    completionTokens = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                                    totalTokens = u["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                                )
                            }
                        }

                        val delta = runCatching {
                            json.parseToJsonElement(payload).jsonObject["choices"]
                                ?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                        }.getOrNull() ?: continue

                        delta["content"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { emit(StreamEvent.Text(it)) }

                        delta["tool_calls"]?.jsonArray?.forEach { tcEl ->
                            val tc = tcEl.jsonObject
                            val idx = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
                            tc["id"]?.jsonPrimitive?.contentOrNull?.let { toolIds[idx] = it }
                            tc["function"]?.jsonObject?.let { fn ->
                                fn["name"]?.jsonPrimitive?.contentOrNull?.let { toolNames[idx] = it }
                                fn["arguments"]?.jsonPrimitive?.contentOrNull?.let {
                                    toolArgs.getOrPut(idx) { StringBuilder() }.append(it)
                                }
                            }
                        }
                    }

                    if (toolNames.isNotEmpty()) {
                        val calls = toolNames.keys.sorted().map { i ->
                            ToolCall(
                                id = toolIds[i] ?: "call_$i",
                                name = toolNames[i]!!,
                                argumentsJson = toolArgs[i]?.toString().orEmpty().ifBlank { "{}" },
                            )
                        }
                        emit(StreamEvent.Tools(calls))
                    }
                    lastUsage?.takeIf { !it.isEmpty }?.let { emit(StreamEvent.Usage(it)) }
                    emit(StreamEvent.Done)
                }
            } catch (e: IOException) {
                if (attempt < MAX_RETRIES) {
                    retryAfterMs = 1000L * attempt
                } else {
                    emit(StreamEvent.Failed(networkError(e)))
                    emit(StreamEvent.Done)
                    return@flow
                }
            }

            // Read the value into a local first: `retryAfterMs` is captured by the `use` closure
            // above, so the compiler cannot smart-cast it to a non-null Long here.
            val wait = retryAfterMs
            if (wait != null) {
                delay(wait)
                continue
            }
            return@flow
        }

        emit(StreamEvent.Failed(AiError("Rate limited", "The request was repeatedly rate-limited. Wait a few seconds and retry.", true)))
        emit(StreamEvent.Done)
    }.flowOn(Dispatchers.IO)

    private fun httpError(code: Int, body: String): AiError {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return when (code) {
            401 -> AiError("Authentication failed", detail ?: "Your OpenRouter API key was rejected. Check it in Settings → AI.", false)
            402 -> AiError("Insufficient credits", detail ?: "This model requires credits on your OpenRouter account.", false)
            404 -> AiError("Model not found", detail ?: "The selected model is not available. Pick another in the model selector.", false)
            408, 504 -> AiError("Request timed out", detail ?: "The model took too long to respond.", true)
            429 -> AiError("Rate limited", detail ?: "Too many requests. Wait a few seconds and retry.", true)
            in 500..599 -> AiError("OpenRouter server error", detail ?: "The provider returned HTTP $code.", true)
            else -> AiError("Request failed (HTTP $code)", detail ?: "Unexpected response from OpenRouter.", true)
        }
    }

    private fun networkError(e: IOException): AiError = AiError(
        "OpenRouter connection failed",
        (e.message ?: "The server did not respond.") + " Check your internet connection and API key.",
        true,
    )
}
