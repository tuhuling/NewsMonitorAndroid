package com.example.newsmonitor.network

import com.example.newsmonitor.data.News
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class PushResult {
    data object Success : PushResult()
    data class Failure(val reason: String) : PushResult()
    data object NotConfigured : PushResult()
}

class DingTalkPusher {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun send(
        webhook: String?,
        news: News,
        displayTitle: String,
        summary: String,
        translationFailed: Boolean
    ): PushResult = withContext(Dispatchers.IO) {
        if (webhook.isNullOrBlank()) return@withContext PushResult.NotConfigured
        if (!webhook.startsWith("http://", true) && !webhook.startsWith("https://", true)) {
            return@withContext PushResult.Failure("Webhook 格式无效")
        }

        try {
            val markdown = buildString {
                append("<font size='4'><b>")
                append(escapeHtml(displayTitle))
                append("</b></font><br><br>")
                if (translationFailed) {
                    append("（翻译失败，以下为原文）<br>")
                }
                append("<b>来源</b>: ").append(escapeHtml(news.websiteName ?: "")).append("<br>")
                append("<b>发布时间</b>: ").append(escapeHtml(news.publishTime ?: "")).append("<br>")
                append("<b>关键词</b>: ").append(escapeHtml(news.matchKeyword ?: "")).append("<br>")
                append("<b>摘要</b>: ")
                    .append(escapeHtml(summary.take(300).ifBlank { "（无正文）" }))
                    .append("...<br><br>")
                append("<a href='").append(escapeHtml(news.newsUrl ?: "")).append("'>查看原文</a>")
            }

            val payload = JSONObject().apply {
                put("msgtype", "markdown")
                put(
                    "markdown",
                    JSONObject().apply {
                        put("title", "海外新闻提醒 - ${news.matchKeyword.orEmpty()}")
                        put("text", markdown)
                    }
                )
            }

            val request = Request.Builder()
                .url(webhook)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext PushResult.Failure("HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (JSONObject(body).optInt("errcode", -1) == 0) {
                    PushResult.Success
                } else {
                    PushResult.Failure(
                        runCatching { JSONObject(body).optString("errmsg", "errcode != 0") }
                            .getOrDefault("errcode != 0")
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PushResult.Failure(e.message?.take(150) ?: "推送异常")
        }
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
