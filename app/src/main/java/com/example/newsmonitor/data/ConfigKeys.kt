package com.example.newsmonitor.data

object ConfigKeys {
    const val BAIDU_OCR_APP_ID = "baidu_ocr_app_id"
    const val BAIDU_OCR_API_KEY = "baidu_ocr_api_key"
    const val BAIDU_OCR_SECRET_KEY = "baidu_ocr_secret_key"
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
}
