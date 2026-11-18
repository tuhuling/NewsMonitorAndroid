package com.example.newsmonitor.data

object ConfigKeys {
    const val BAIDU_OCR_API_KEY = "baidu_ocr_api_key"
    const val BAIDU_OCR_SECRET_KEY = "baidu_ocr_secret_key"
    const val BAIDU_TRANSLATE_APP_ID = "baidu_translate_app_id"
    const val BAIDU_TRANSLATE_SECRET_KEY = "baidu_translate_secret_key"
    const val DINGTALK_WEBHOOK = "dingtalk_webhook"
    const val DEEPSEEK_API_KEY = "deepseek_api_key"
    const val POLL_INTERVAL = "poll_interval"

    const val DEFAULT_POLL_INTERVAL = 60
    const val MIN_POLL_INTERVAL = 1
    const val MAX_POLL_INTERVAL = 1440
}

class ConfigRepository(private val dao: ConfigDao) {

    suspend fun get(key: String): String? = dao.get(key)

    suspend fun set(key: String, value: String?) = dao.put(ConfigEntry(key, value))

    suspend fun getInt(key: String, defaultValue: Int): Int =
        dao.get(key)?.trim()?.toIntOrNull() ?: defaultValue

    suspend fun getPollInterval(): Int =
        getInt(ConfigKeys.POLL_INTERVAL, ConfigKeys.DEFAULT_POLL_INTERVAL)
            .coerceIn(ConfigKeys.MIN_POLL_INTERVAL, ConfigKeys.MAX_POLL_INTERVAL)

    suspend fun getDingTalkWebhook(): String? =
        dao.get(ConfigKeys.DINGTALK_WEBHOOK)?.takeIf { it.isNotBlank() }

    suspend fun getDeepSeekApiKey(): String? =
        dao.get(ConfigKeys.DEEPSEEK_API_KEY)?.takeIf { it.isNotBlank() }

    suspend fun getBaiduTranslateConfig(): BaiduTranslateConfig? {
        val appId = dao.get(ConfigKeys.BAIDU_TRANSLATE_APP_ID)?.trim().orEmpty()
        val secretKey = dao.get(ConfigKeys.BAIDU_TRANSLATE_SECRET_KEY)?.trim().orEmpty()
        return if (appId.isNotEmpty() && secretKey.isNotEmpty()) {
            BaiduTranslateConfig(appId, secretKey)
        } else {
            null
        }
    }

    suspend fun getBaiduOcrConfig(): BaiduOcrConfig? {
        val apiKey = dao.get(ConfigKeys.BAIDU_OCR_API_KEY)?.trim().orEmpty()
        val secretKey = dao.get(ConfigKeys.BAIDU_OCR_SECRET_KEY)?.trim().orEmpty()
        return if (apiKey.isNotEmpty() && secretKey.isNotEmpty()) {
            BaiduOcrConfig(apiKey, secretKey)
        } else {
            null
        }
    }
}
