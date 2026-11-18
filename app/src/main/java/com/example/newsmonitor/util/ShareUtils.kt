package com.example.newsmonitor.util

import android.content.Context
import android.content.Intent

fun Context.shareText(chooserTitle: String, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        startActivity(Intent.createChooser(send, chooserTitle))
    } catch (_: Exception) {
        toast("无法唤起分享")
    }
}

fun buildIntelShareText(title: String, content: String): String {
    return "$title\n\n$content\n\n—— AI生成情报"
}
