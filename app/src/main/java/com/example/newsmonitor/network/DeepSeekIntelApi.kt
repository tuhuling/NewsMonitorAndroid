package com.example.newsmonitor.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GeneratedIntel(
    val title: String,
    val content: String
)

/**
 * DeepSeek 流式生成情报。
 *
 * 流式请求优先用于展示真实进度；若服务端拒绝 stream + JSON 模式、
 * 流为空或 JSON 解析失败，则自动回退到非流式请求。
 */
object DeepSeekIntelApi {

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MAX_TEMPLATE_CHARS = 4000
    private const val MAX_MATERIAL_CHARS = 12000

    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val targetRegex = Regex("(\\d{2,5})\\s*(?:个)?\\s*(?:字|字符)")

    private val client: OkHttpClient = Http.longClient.newBuilder()
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_PROMPT = """你是一名资深情报分析师。用户会提供新闻素材、写作要求，以及可选的情报模板。
请整合素材撰写一篇中文情报：不得编造素材中不存在的事实；如果提供了模板，必须严格仿照模板的结构、段落格式和文风。
只输出一个 json 对象，且只能包含 title 和 content 两个字段：
{"title":"情报标题（不超过30字）","content":"情报正文（纯文本，不要 markdown 标记）"}"""

    suspend fun generateIntel(
        apiKey: String,
        requirement: String,
        templateContent: String?,
        material: String,
        targetChars: Int,
        onStreamProgress: (charsSoFar: Int) -> Unit
    ): Result<GeneratedIntel> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("请先在设置中配置 DeepSeek API Key")
            )
        }
        if (material.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("没有可用的新闻素材"))
        }

        val messages = buildMessages(requirement, templateContent, material, targetChars)
        var lastError: String? = null

        try {
            var streamed: String? = null
            try {
                streamed = executeStream(apiKey, messages, targetChars, true, onStreamProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
            }

            if (streamed.isNullOrBlank()) {
                try {
                    streamed = executeStream(apiKey, messages, targetChars, false, onStreamProgress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message ?: lastError
                }
            }

            if (!streamed.isNullOrBlank()) {
                parseGenerated(streamed)?.let {
                    return@withContext Result.success(it)
                }
                lastError = "AI 返回格式异常，请重试"
            }

            // 流式不可用时回退到非流式请求。
            var raw: String? = null
            try {
                raw = executeNonStream(apiKey, messages, targetChars, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: lastError
            }
            if (raw.isNullOrBlank()) {
                try {
                    raw = executeNonStream(apiKey, messages, targetChars, false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message ?: lastError
                }
            }

            if (!raw.isNullOrBlank()) {
                parseGenerated(raw)?.let {
                    return@withContext Result.success(it)
                }
            }

            Result.failure(
                IllegalStateException(lastError ?: "AI 返回格式异常，请重试")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException(e.message ?: "AI 请求失败"))
        }
    }

    fun parseTargetChars(text: String): Int {
        return targetRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?.coerceIn(100, 8000)
            ?: 1000
    }

    private fun buildMessages(
        requirement: String,
        templateContent: String?,
        material: String,
        targetChars: Int
    ): JSONArray {
        val userPrompt = buildString {
            appendLine("【写作要求】")
            appendLine(requirement.ifBlank { "无特别要求，请写一篇信息量大、条理清晰的中文情报。" })
            appendLine()
            appendLine("【目标字数】约 $targetChars 字。")
            appendLine()
            if (!templateContent.isNullOrBlank()) {
                appendLine("【情报模板】请严格仿照以下模板的结构、段落格式和文风：")
                appendLine("<<<模板开始>>>")
                appendLine(templateContent.take(MAX_TEMPLATE_CHARS))
                appendLine("<<<模板结束>>>")
                appendLine()
            }
            appendLine("【新闻素材】")
            appendLine(material.take(MAX_MATERIAL_CHARS))
            appendLine()
            appendLine("请以 json 格式输出：{\"title\":\"...\",\"content\":\"...\"}")
        }

        return JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            put(JSONObject().put("role", "user").put("content", userPrompt))
        }
    }

    private fun buildPayload(
        messages: JSONArray,
        targetChars: Int,
        stream: Boolean,
        useJsonFormat: Boolean
    ): JSONObject {
        val maxTokens = (targetChars * 1.6).toInt().coerceIn(1024, 8192)
        return JSONObject().apply {
            put("model", "deepseek-chat")
            put("stream", stream)
            put("temperature", 0.7)
            put("max_tokens", maxTokens)
            if (useJsonFormat) {
                put("response_format", JSONObject().put("type", "json_object"))
            }
            put("messages", messages)
        }
    }

    private suspend fun executeStream(
        apiKey: String,
        messages: JSONArray,
        targetChars: Int,
        useJsonFormat: Boolean,
        onStreamProgress: (Int) -> Unit
    ): String? {
        val payload = buildPayload(messages, targetChars, stream = true, useJsonFormat = useJsonFormat)
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        val call = client.newCall(request)
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException(httpErrorMessage(response.code, response.body?.string()))
                }
                val source = response.body?.source()
                    ?: throw IllegalStateException("AI 响应为空")

                val builder = StringBuilder()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break

                    val delta = runCatching {
                        JSONObject(data)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                            .orEmpty()
                    }.getOrDefault("")

                    if (delta.isNotEmpty()) {
                        builder.append(delta)
                        onStreamProgress(builder.length)
                    }
                }
                return builder.toString().ifBlank { null }
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    private suspend fun executeNonStream(
        apiKey: String,
        messages: JSONArray,
        targetChars: Int,
        useJsonFormat: Boolean
    ): String? {
        val payload = buildPayload(messages, targetChars, stream = false, useJsonFormat = useJsonFormat)
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        val call = client.newCall(request)
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(httpErrorMessage(response.code, body))
                }
                return JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.ifBlank { null }
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    private fun httpErrorMessage(code: Int, body: String?): String {
        val apiMessage = runCatching {
            JSONObject(body.orEmpty()).optJSONObject("error")?.optString("message")
        }.getOrNull()
        val suffix = if (apiMessage.isNullOrBlank()) "" else "：$apiMessage"
        return "API 请求失败 (HTTP $code)$suffix"
    }

    private fun parseGenerated(raw: String): GeneratedIntel? {
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(text.substring(start, end + 1))
            val title = obj.optString("title").trim()
            val content = obj.optString("content").trim()
            if (title.isEmpty() || content.isEmpty()) null else GeneratedIntel(title, content)
        } catch (_: Exception) {
            null
        }
    }
}
