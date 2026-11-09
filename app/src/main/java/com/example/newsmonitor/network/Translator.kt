package com.example.newsmonitor.network

import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Google 网页翻译（translate_a/single），带 1000 条 LRU 缓存。
 * 翻译失败返回 null，调用方必须保留原文新闻。
 */
object Translator {

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val CACHE_SIZE = 1000

    private val chinesePattern = Regex("[\\u4e00-\\u9fa5]")
    private val cache = LruCache<Int, String>(CACHE_SIZE)

    private val client: OkHttpClient = Http.client.newBuilder()
        .readTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun translate(text: String?): String? = withContext(Dispatchers.IO) {
        if (text.isNullOrBlank()) return@withContext null

        if (chinesePattern.containsMatchIn(text)) return@withContext text

        val key = text.take(200).hashCode()
        val cached = synchronized(cache) { cache.get(key) }
        if (cached != null) return@withContext cached

        try {
            val url = ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", "auto")
                .addQueryParameter("tl", "zh-CN")
                .addQueryParameter("dt", "t")
                .addQueryParameter("q", text.take(500))
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", Http.USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val root = JSONArray(body)
                val segments: JSONArray = root.optJSONArray(0) ?: return@withContext null

                val builder = StringBuilder()
                for (index in 0 until segments.length()) {
                    val segment = segments.optJSONArray(index) ?: continue
                    val part = segment.optString(0, "")
                    if (part.isNotEmpty()) builder.append(part)
                }

                val translated = builder.toString().trim()
                if (translated.isEmpty()) {
                    null
                } else {
                    synchronized(cache) { cache.put(key, translated) }
                    translated
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
