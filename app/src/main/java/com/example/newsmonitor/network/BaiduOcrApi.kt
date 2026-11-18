package com.example.newsmonitor.network

import android.util.Base64
import com.example.newsmonitor.data.BaiduOcrConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 百度通用文字识别（general_basic）。
 * 使用 API Key + Secret Key 换取 access_token，token 在内存中缓存。
 */
object BaiduOcrApi {

    private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
    private const val OCR_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic"

    private val client: OkHttpClient = Http.longClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val tokenMutex = Mutex()

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var tokenExpiresAt = 0L

    suspend fun recognize(config: BaiduOcrConfig, imageJpeg: ByteArray): String? =
        withContext(Dispatchers.IO) {
            if (imageJpeg.isEmpty()) return@withContext null
            val token = getToken(config) ?: return@withContext null
            val first = requestOcr(token, imageJpeg)
            if (first.text != null) return@withContext first.text
            if (first.tokenInvalid) {
                invalidateToken()
                val fresh = getToken(config) ?: return@withContext null
                return@withContext requestOcr(fresh, imageJpeg).text
            }
            null
        }

    private suspend fun getToken(config: BaiduOcrConfig): String? {
        val cached = accessToken
        if (cached != null && System.currentTimeMillis() < tokenExpiresAt) return cached

        return tokenMutex.withLock {
            val cachedInside = accessToken
            if (cachedInside != null && System.currentTimeMillis() < tokenExpiresAt) {
                return@withLock cachedInside
            }

            val url = TOKEN_URL.toHttpUrl().newBuilder()
                .addQueryParameter("grant_type", "client_credentials")
                .addQueryParameter("client_id", config.apiKey)
                .addQueryParameter("client_secret", config.secretKey)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", Http.USER_AGENT)
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withLock null
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val token = json.optString("access_token")
                    if (token.isBlank()) return@withLock null
                    val expiresIn = json.optLong("expires_in", 0L)
                    accessToken = token
                    tokenExpiresAt = System.currentTimeMillis() +
                        ((expiresIn - 300L).coerceAtLeast(60L) * 1000L)
                    token
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun invalidateToken() {
        accessToken = null
        tokenExpiresAt = 0L
    }

    private data class OcrOutcome(
        val text: String?,
        val tokenInvalid: Boolean
    )

    private fun requestOcr(token: String, imageJpeg: ByteArray): OcrOutcome {
        val form = FormBody.Builder()
            .add("image", Base64.encodeToString(imageJpeg, Base64.NO_WRAP))
            .add("language_type", "CHN_ENG")
            .add("detect_direction", "true")
            .build()

        val request = Request.Builder()
            .url("$OCR_URL?access_token=$token")
            .header("User-Agent", Http.USER_AGENT)
            .post(form)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return OcrOutcome(null, false)

                val json = JSONObject(body)
                val errorCode = json.optInt("error_code", 0)
                if (errorCode == 110 || errorCode == 111) {
                    return OcrOutcome(null, true)
                }
                if (errorCode != 0) {
                    return OcrOutcome(null, false)
                }

                val words = json.optJSONArray("words_result") ?: return OcrOutcome(null, false)
                val builder = StringBuilder()
                for (index in 0 until words.length()) {
                    val line = words.optJSONObject(index)?.optString("words").orEmpty()
                    if (line.isBlank()) continue
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(line)
                }
                OcrOutcome(builder.toString().ifBlank { null }, false)
            }
        } catch (_: Exception) {
            OcrOutcome(null, false)
        }
    }
}
