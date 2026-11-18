package com.example.newsmonitor.network

import org.jsoup.nodes.Document

/**
 * 从 HTML 文档中提取发布时间与正文。
 * 逻辑与原先 NewsFetcher 内部实现一致，供普通抓取与浏览器渲染兜底共用。
 */
object ContentExtractor {

    fun extractPublishTime(document: Document): String? {
        for (selector in TIME_SELECTORS) {
            val element = document.selectFirst(selector) ?: continue
            val value = if (element.tagName().equals("meta", true)) {
                element.attr("content")
            } else {
                element.attr("datetime").ifBlank { element.text() }
            }
            if (value.isNotBlank()) return value.trim()
        }
        return null
    }

    fun extractContent(document: Document): String? {
        document.select("script, style, nav, header, footer, aside").remove()

        for (selector in CONTENT_SELECTORS) {
            val element = document.selectFirst(selector) ?: continue
            val text = element.text().trim()
            if (text.length > 200) return text
        }

        val paragraphs = document.select("p")
            .map { it.text().trim() }
            .filter { it.length > 20 }
            .joinToString(" ")
        return if (paragraphs.isBlank()) null else paragraphs
    }

    private val TIME_SELECTORS = listOf(
        "time",
        ".publish-time",
        ".post-date",
        ".article-date",
        "meta[property=\"article:published_time\"]",
        "meta[name=\"publishdate\"]",
        ".date",
        ".timestamp",
        ".entry-date"
    )

    private val CONTENT_SELECTORS = listOf(
        "article",
        ".article-content",
        ".post-content",
        ".entry-content",
        ".content",
        ".body",
        ".story-body",
        ".main-content",
        "#content",
        ".news-content",
        ".detail-content"
    )
}
