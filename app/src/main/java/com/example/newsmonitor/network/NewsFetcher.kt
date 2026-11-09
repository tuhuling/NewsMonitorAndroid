package com.example.newsmonitor.network

import com.example.newsmonitor.data.Website
import com.example.newsmonitor.util.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.Charset
import kotlin.random.Random

data class FetchedNews(
    val title: String,
    val url: String,
    val content: String,
    val publishTime: String
)

/**
 * 基于 OkHttp + Jsoup 的新闻抓取器。
 * 每个网站首页最多取前 10 条详情，失败重试 1 次（间隔 3 秒）。
 */
class NewsFetcher(
    private val log: suspend (level: String, message: String) -> Unit
) {

    suspend fun fetchNews(website: Website): List<FetchedNews> = withContext(Dispatchers.IO) {
        val document = fetchPage(website.url, "首页(${website.name})")
            ?: return@withContext emptyList()

        val candidates = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        for (element in document.select(LINK_SELECTOR)) {
            if (candidates.size >= MAX_NEWS_PER_SITE) break
            val href = element.absUrl("href").ifBlank { element.attr("href") }
            val title = element.text().trim().replace(WHITESPACE, " ")
            if (href.isBlank() || title.length <= 10) continue
            val fullUrl = resolveUrl(website.url, href) ?: continue
            if (!fullUrl.startsWith("http://", true) && !fullUrl.startsWith("https://", true)) continue
            if (!seen.add(fullUrl)) continue
            candidates.add(title to fullUrl)
        }

        val result = ArrayList<FetchedNews>()
        for ((index, candidate) in candidates.withIndex()) {
            if (index > 0) {
                delay(300L + Random.nextLong(201))
            }
            val detail = fetchDetail(candidate.first, candidate.second) ?: continue
            result.add(detail)
        }
        result
    }

    private suspend fun fetchDetail(title: String, url: String): FetchedNews? {
        val document = fetchPage(url, "详情") ?: return null
        val rawTime = extractPublishTime(document)
        val extracted = extractContent(document)
        // 正文缺失或过短时用标题兜底；显式写成非空 String，避免可空类型推断问题。
        val content: String =
            if (extracted.isNullOrBlank() || extracted.length < 50) title else extracted
        return FetchedNews(
            title = title,
            url = url,
            content = content.take(MAX_CONTENT_LENGTH),
            publishTime = TimeUtils.parseToBeijing(rawTime)
        )
    }

    private suspend fun fetchPage(url: String, label: String): Document? {
        for (attempt in 0..1) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", Http.USER_AGENT)
                    .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                    .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8")
                    .build()

                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("响应为空")
                    val bytes = body.bytes()
                    if (bytes.isEmpty()) throw IOException("响应内容为空")
                    val charset = charsetFromContentType(response.header("Content-Type"))
                    return Jsoup.parse(ByteArrayInputStream(bytes), charset, url)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 0) {
                    delay(3000)
                } else {
                    log("ERROR", "抓取失败[$label] $url: ${e.message?.take(120)}")
                }
            }
        }
        return null
    }

    private fun charsetFromContentType(contentType: String?): String? {
        val charset = CHARSET_PATTERN.find(contentType ?: "")
            ?.groupValues
            ?.getOrNull(1)
            ?.trim('"', '\'', ' ')
            ?: return null
        if (charset.equals("ISO-8859-1", true) || charset.equals("ISO8859-1", true)) {
            return null
        }
        return if (Charset.isSupported(charset)) charset else null
    }

    private fun resolveUrl(baseUrl: String, href: String): String? {
        return try {
            when {
                href.startsWith("http://", true) || href.startsWith("https://", true) -> href
                href.startsWith("//") -> "https:$href"
                else -> URI(baseUrl).resolve(href).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractPublishTime(document: Document): String? {
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

    private fun extractContent(document: Document): String? {
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

    companion object {
        private const val MAX_NEWS_PER_SITE = 10
        private const val MAX_CONTENT_LENGTH = 3000
        private val WHITESPACE = Regex("\\s+")
        private val CHARSET_PATTERN = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)

        private const val LINK_SELECTOR =
            "a[href*=\"/news/\"], a[href*=\"/article/\"], a[href*=\"/story/\"], " +
                "a[href*=\"/post/\"], a[href*=\"/202\"], a[href*=\"/content/\"], " +
                ".news-item a, .article-item a, .post-title a, .entry-title a, " +
                "h2 a, h3 a, h4 a, .title a, .headline a"

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
}
