package com.example.newsmonitor.network

import com.example.newsmonitor.data.BaiduTranslateConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 百度翻译通用 API（免费额度版本），作为 Google 翻译失败后的备用。
 * 免费版限制约 1 QPS，这里用 Mutex 串行并保证最小请求间隔。
 */
object BaiduTranslateApi {

    private const val ENDPOINT = "https://fanyi-api.baidu.com/api/trans/vip/translate"
    private const val MAX_QUERY_BYTES = 5800
    private const val MIN_INTERVAL_MS = 1100L
    private const val RATE_LIMIT_RETRY_DELAY_MS = 1200L

    private val mutex = Mutex()
    private var lastCallAt = 0L

    suspend fun translate(
        text: String?,
        config: BaiduTranslateConfig
    ): String? = withContext(Dispatchers.IO) {
        if (text.isNullOrBlank()) return@withContext null
        val query = truncateUtf8(text, MAX_QUERY_BYTES)
        if (query.isBlank()) return@withContext null

        mutex.withLock {
            val wait = lastCallAt + MIN_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                request(query, config, allowRateLimitRetry = true)
            } finally {
                lastCallAt = System.currentTimeMillis()
            }
        }
    }

    private fun request(
        query: String,
        config: BaiduTranslateConfig,
        allowRateLimitRetry: Boolean
    ): String? {
        val salt = System.currentTimeMillis().toString()
        val sign = md5(config.appId + query + salt + config.secretKey)
        val form = FormBody.Builder()
            .add("q", query)
            .add("from", "auto")
            .add("to", "zh")
            .add("appid", config.appId)
            .add("salt", salt)
            .add("sign", sign)
            .build()

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", Http.USER_AGENT)
            .post(form)
            .build()

        return try {
            Http.client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) return null

                val json = JSONObject(raw)
                val errorCode = json.optString("error_code")
                if (errorCode.isNotEmpty() && errorCode != "0") {
                    if (allowRateLimitRetry && errorCode == "54003") {
                        Thread.sleep(RATE_LIMIT_RETRY_DELAY_MS)
                        return request(query, config, allowRateLimitRetry = false)
                    }
                    return null
                }

                val results = json.optJSONArray("trans_result") ?: return null
                val builder = StringBuilder()
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val dst = item.optString("dst")
                    if (dst.isNotEmpty()) {
                        if (builder.isNotEmpty()) builder.append('\n')
                        builder.append(dst)
                    }
                }
                builder.toString().ifBlank { null }
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun md5(value: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val valueInt = byte.toInt() and 0xFF
            if (valueInt < 0x10) builder.append('0')
            builder.append(valueInt.toString(16))
        }
        return builder.toString()
    }

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        val builder = StringBuilder()
        var used = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val piece = String(Character.toChars(codePoint))
            val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
            if (used + pieceBytes > maxBytes) break
            builder.append(piece)
            used += pieceBytes
            index += charCount
        }
        return builder.toString().trimEnd()
    }
}
