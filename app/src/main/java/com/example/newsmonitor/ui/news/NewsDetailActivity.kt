package com.example.newsmonitor.ui.news

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.newsmonitor.R
import com.example.newsmonitor.data.AppDatabase
import com.example.newsmonitor.data.News
import com.example.newsmonitor.databinding.ActivityNewsDetailBinding
import com.example.newsmonitor.util.copyPlainText
import com.example.newsmonitor.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NewsDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewsDetailBinding
    private var currentNews: News? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewsDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val newsId = intent.getLongExtra(EXTRA_NEWS_ID, -1L)
        if (newsId <= 0L) {
            finish()
            return
        }

        lifecycleScope.launch {
            val news = withContext(Dispatchers.IO) {
                AppDatabase.get(applicationContext).newsDao().getById(newsId)
            }
            if (news == null) {
                toast("新闻不存在或已被删除")
                finish()
                return@launch
            }
            currentNews = news
            bind(news)
        }

        binding.btnCopy.setOnClickListener { copyCurrentContent() }
        binding.btnOpen.setOnClickListener { openOriginal() }
    }

    private fun bind(news: News) {
        val displayTitle = news.titleZh?.takeIf { it.isNotBlank() } ?: news.title
        val source = news.websiteName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.unknown_source)
        val time = news.publishTime?.take(16).orEmpty()
        val keywords = splitKeywords(news.matchKeyword)

        binding.tvTitle.text = displayTitle
        binding.tvMeta.text = "来源：$source    发布时间：$time（北京时间）"
        binding.tvKeywords.text = "匹配关键词：" + keywords.ifEmpty { listOf("无") }.joinToString(", ")

        val hasTranslation = !news.contentZh.isNullOrBlank()
        binding.tvTranslationNote.isVisible = !hasTranslation
        binding.tvContent.text = displayedContent(news)
    }

    private fun copyCurrentContent() {
        val news = currentNews ?: return
        val displayTitle = news.titleZh?.takeIf { it.isNotBlank() } ?: news.title
        val source = news.websiteName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.unknown_source)
        val time = news.publishTime?.take(16).orEmpty()
        val keywords = splitKeywords(news.matchKeyword).ifEmpty { listOf("无") }

        val text = buildString {
            append("【标题】").append(displayTitle).append("\n\n")
            append("【来源】").append(source).append('\n')
            append("【发布时间】").append(time).append("（北京时间）\n")
            append("【匹配关键词】").append(keywords.joinToString(", ")).append("\n\n")
            append("【正文】\n").append(displayedContent(news)).append("\n\n")
            append("【原文链接】").append(news.newsUrl.orEmpty())
        }

        copyPlainText("news", text)
        toast("已复制，可粘贴到 AI网站进行分析")
    }

    private fun openOriginal() {
        val url = currentNews?.newsUrl
        if (url.isNullOrBlank()) {
            toast("无有效原文链接")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("无法打开原文链接")
        }
    }

    private fun displayedContent(news: News): String {
        return news.contentZh?.takeIf { it.isNotBlank() }
            ?: news.content?.takeIf { it.isNotBlank() }
            ?: "（无正文）"
    }

    private fun splitKeywords(matchKeyword: String?): List<String> {
        return matchKeyword
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    companion object {
        private const val EXTRA_NEWS_ID = "extra_news_id"

        fun intent(context: Context, newsId: Long): Intent {
            return Intent(context, NewsDetailActivity::class.java)
                .putExtra(EXTRA_NEWS_ID, newsId)
        }
    }
}
