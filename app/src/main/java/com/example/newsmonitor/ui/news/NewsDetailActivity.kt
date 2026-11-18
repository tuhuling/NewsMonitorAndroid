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
import com.example.newsmonitor.util.buildIntelShareText
import com.example.newsmonitor.util.copyPlainText
import com.example.newsmonitor.util.shareText
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
        binding.btnOpen.setOnClickListener {
            val news = currentNews ?: return@setOnClickListener
            if (news.isAiGenerated == 1) shareCurrent() else openOriginal()
        }
    }

    private fun bind(news: News) {
        val isAiGenerated = news.isAiGenerated == 1
        val displayTitle = news.titleZh?.takeIf { it.isNotBlank() } ?: news.title
        val source = news.websiteName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.unknown_source)
        val time = news.publishTime?.take(16).orEmpty()

        binding.tvTitle.text = displayTitle
        binding.tvContent.text = displayedContent(news)

        if (isAiGenerated) {
            binding.toolbar.title = getString(R.string.intel_detail)
            binding.tvMeta.text = "来源：AI生成情报    生成时间：$time（北京时间）"
            binding.tvKeywords.isVisible = false
            binding.tvTranslationNote.isVisible = false
            binding.btnOpen.text = getString(R.string.generate_intel_share)
            binding.btnOpen.setIconResource(R.drawable.ic_share)
        } else {
            val keywords = splitKeywords(news.matchKeyword)
            binding.toolbar.title = getString(R.string.news_detail)
            binding.tvMeta.text = "来源：$source    发布时间：$time（北京时间）"
            binding.tvKeywords.isVisible = true
            binding.tvKeywords.text =
                "匹配关键词：" + keywords.ifEmpty { listOf("无") }.joinToString(", ")

            val hasTranslation = !news.contentZh.isNullOrBlank()
            binding.tvTranslationNote.isVisible = !hasTranslation
            binding.btnOpen.text = getString(R.string.view_original)
            binding.btnOpen.setIconResource(R.drawable.ic_open)
        }
    }

    private fun copyCurrentContent() {
        val news = currentNews ?: return
        val displayTitle = news.titleZh?.takeIf { it.isNotBlank() } ?: news.title
        val time = news.publishTime?.take(16).orEmpty()

        val text = if (news.isAiGenerated == 1) {
            buildString {
                append("【标题】").append(displayTitle).append("\n\n")
                append("【类型】AI生成情报\n")
                append("【生成时间】").append(time).append("（北京时间）\n\n")
                append("【正文】\n").append(displayedContent(news))
            }
        } else {
            val source = news.websiteName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.unknown_source)
            val keywords = splitKeywords(news.matchKeyword).ifEmpty { listOf("无") }
            buildString {
                append("【标题】").append(displayTitle).append("\n\n")
                append("【来源】").append(source).append('\n')
                append("【发布时间】").append(time).append("（北京时间）\n")
                append("【匹配关键词】").append(keywords.joinToString(", ")).append("\n\n")
                append("【正文】\n").append(displayedContent(news)).append("\n\n")
                append("【原文链接】").append(news.newsUrl.orEmpty())
            }
        }

        copyPlainText("news", text)
        toast(
            if (news.isAiGenerated == 1) {
                "已复制情报内容"
            } else {
                "已复制，可粘贴到 AI网站进行分析"
            }
        )
    }

    private fun shareCurrent() {
        val news = currentNews ?: return
        val displayTitle = news.titleZh?.takeIf { it.isNotBlank() } ?: news.title
        shareText(
            getString(R.string.generate_intel_share),
            displayTitle,
            buildIntelShareText(displayTitle, displayedContent(news))
        )
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
