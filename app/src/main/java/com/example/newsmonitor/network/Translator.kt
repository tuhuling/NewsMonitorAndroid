package com.example.newsmonitor.network

import android.util.LruCache
import com.example.newsmonitor.data.BaiduTranslateConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * 翻译服务：优先 Google 网页翻译，失败时回退到百度翻译（需在设置中配置）。
 * 翻译失败返回 null，调用方必须保留原文新闻。
 */
object Translator {

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val CACHE_SIZE = 1000
    private const val MAX_QUERY_LENGTH = 1000
    private const val SMART_CUT_MIN = 900
    private const val GOOGLE_FAILURE_LIMIT = 3
    private const val GOOGLE_COOLDOWN_MS = 5 * 60 * 1000L

    private val chinesePattern = Regex("[\\u4e00-\\u9fa5]")
    private val cache = LruCache<Int, String>(CACHE_SIZE)

    @Volatile
    private var googleFailures = 0

    @Volatile
    private var googleSkipUntil = 0L

    private val client: OkHttpClient = Http.client.newBuilder()
        .readTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun translate(text: String?): String? = translate(text, null)

    suspend fun translate(
        text: String?,
        fallback: BaiduTranslateConfig?
    ): String? = withContext(Dispatchers.IO) {
        if (text.isNullOrBlank()) return@withContext null

        if (chinesePattern.containsMatchIn(text)) return@withContext text

        val query = truncateForTranslation(text)
        val key = query.hashCode()
        val cached = synchronized(cache) { cache.get(key) }
        if (cached != null) return@withContext cached

        if (System.currentTimeMillis() >= googleSkipUntil) {
            val translated = tryGoogle(query)
            if (!translated.isNullOrBlank()) {
                googleFailures = 0
                synchronized(cache) { cache.put(key, translated) }
                return@withContext translated
            }
            googleFailures++
            if (googleFailures >= GOOGLE_FAILURE_LIMIT) {
                googleSkipUntil = System.currentTimeMillis() + GOOGLE_COOLDOWN_MS
            }
        }

        if (fallback != null) {
            val translated = BaiduTranslateApi.translate(query, fallback)
            if (!translated.isNullOrBlank()) {
                synchronized(cache) { cache.put(key, translated) }
                return@withContext translated
            }
        }

        null
    }

    private fun tryGoogle(query: String): String? {
        return try {
            val url = ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", "auto")
                .addQueryParameter("tl", "zh-CN")
                .addQueryParameter("dt", "t")
                .addQueryParameter("q", query)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", Http.USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = JSONArray(body)
                val segments: JSONArray = root.optJSONArray(0) ?: return null

                val builder = StringBuilder()
                for (index in 0 until segments.length()) {
                    val segment = segments.optJSONArray(index) ?: continue
                    val part = segment.optString(0, "")
                    if (part.isNotEmpty()) builder.append(part)
                }

                builder.toString().trim().ifBlank { null }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 翻译前截断：最多 1000 个字符。
     * 超长时在 900~1000 字符之间回退到最近的句号/问号/感叹号/换行，
     * 保证最后一句完整，译文大约能到四五百个汉字。
     */
    private fun truncateForTranslation(text: String): String {
        if (text.length <= MAX_QUERY_LENGTH) return text
        val head = text.substring(0, MAX_QUERY_LENGTH)
        for (index in head.lastIndex downTo SMART_CUT_MIN) {
            when (head[index]) {
                '.', '!', '?', '\n', '。' -> return head.substring(0, index + 1).trimEnd()
            }
        }
        return head
    }
}
