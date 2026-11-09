package com.example.newsmonitor.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RecommendedSite(
    val name: String,
    val url: String,
    val remark: String
)

object DeepSeekApi {

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"

    private const val SYSTEM_PROMPT = """你是一个新闻网站推荐助手。用户会描述需要的新闻网站类型和数量。
"前 N 名"一律理解为按国际知名度、影响力、行业地位排名前 N 的主流新闻网站。
请返回一个 JSON 数组，每个对象包含 name（网站简称）、url（首页完整URL）、
remark（一句话中文描述）。只返回 JSON，不要 markdown 代码块标记，不要其他文字。

示例：
[
  {"name":"CNN","url":"https://www.cnn.com","remark":"美国主流新闻网站"},
  {"name":"Bloomberg","url":"https://www.bloomberg.com","remark":"全球财经新闻权威来源"}
]"""

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = Http.longClient.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun recommend(
        apiKey: String,
        requirement: String
    ): Result<List<RecommendedSite>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("请先在设置中配置 DeepSeek API Key")
            )
        }
        if (requirement.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("请输入需求"))
        }

        try {
            val payload = JSONObject().apply {
                put("model", "deepseek-chat")
                put("stream", false)
                put("temperature", 0.7)
                put("response_format", JSONObject().put("type", "json_object"))
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        put(JSONObject().put("role", "user").put("content", requirement))
                    }
                )
            }

            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiMessage = runCatching {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                    }.getOrNull()
                    val suffix = if (apiMessage.isNullOrBlank()) "" else "：$apiMessage"
                    return@withContext Result.failure(
                        IllegalStateException("API 请求失败 (HTTP ${response.code})$suffix")
                    )
                }

                val content = JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()

                val array = extractJsonArray(content)
                    ?: return@withContext Result.failure(
                        IllegalStateException("AI 返回格式异常，请重试")
                    )

                val sites = ArrayList<RecommendedSite>()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val name = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    val remark = item.optString("remark").trim()
                    if (name.isEmpty() || url.isEmpty()) continue
                    sites.add(RecommendedSite(name, url, remark))
                }

                if (sites.isEmpty()) {
                    Result.failure(IllegalStateException("AI 返回格式异常，请重试"))
                } else {
                    Result.success(sites)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException(e.message ?: "AI 请求失败"))
        }
    }

    private fun extractJsonArray(content: String): JSONArray? {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try {
            JSONArray(content.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }
}
