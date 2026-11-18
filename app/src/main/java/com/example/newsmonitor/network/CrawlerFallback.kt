package com.example.newsmonitor.network

import org.jsoup.nodes.Document

/**
 * 抓取失败时的兜底：浏览器渲染 DOM，必要时截图调用百度 OCR。
 */
interface CrawlerFallback {
    suspend fun fetchDocument(url: String): Document?
}
