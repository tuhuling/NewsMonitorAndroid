package com.example.newsmonitor.ui.news

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.newsmonitor.data.AppDatabase
import com.example.newsmonitor.data.ConfigRepository
import com.example.newsmonitor.data.Keyword
import com.example.newsmonitor.data.News
import com.example.newsmonitor.network.DeepSeekIntelApi
import com.example.newsmonitor.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

enum class IntelStage { IDLE, RUNNING, SUCCESS, ERROR }

data class IntelUiState(
    val stage: IntelStage = IntelStage.IDLE,
    val percent: Int = 0,
    val elapsedMs: Long = 0L,
    val title: String = "",
    val content: String = "",
    val error: String? = null,
    val newsId: Long = 0L
)

class IntelGenerateViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val configRepository = ConfigRepository(db.configDao())

    private val _state = MutableStateFlow(IntelUiState())
    val state: StateFlow<IntelUiState> = _state.asStateFlow()

    private val streamedChars = AtomicInteger(0)
    private var newsIds: LongArray = longArrayOf()
    private var requirement = ""
    private var templateId = -1L
    private var targetChars = 1000
    private var expectedMs = 30_000L
    private var startedAtElapsed = 0L
    private var generationJob: Job? = null
    private var tickerJob: Job? = null

    fun start(newsIds: LongArray, requirement: String, templateId: Long) {
        if (_state.value.stage != IntelStage.IDLE) return
        this.newsIds = newsIds
        this.requirement = requirement
        this.templateId = templateId
        launchGeneration()
    }

    fun retry() {
        if (_state.value.stage != IntelStage.ERROR) return
        launchGeneration()
    }

    private fun launchGeneration() {
        generationJob?.cancel()
        tickerJob?.cancel()
        streamedChars.set(0)
        targetChars = DeepSeekIntelApi.parseTargetChars(requirement)
        expectedMs = (15_000L + targetChars * 12L).coerceAtMost(180_000L)
        startedAtElapsed = SystemClock.elapsedRealtime()
        _state.value = IntelUiState(stage = IntelStage.RUNNING)
        startTicker()

        generationJob = viewModelScope.launch {
            try {
                if (newsIds.isEmpty()) {
                    fail("请先选择新闻")
                    return@launch
                }

                val apiKey = configRepository.getDeepSeekApiKey()
                if (apiKey.isNullOrBlank()) {
                    fail("请先在设置中配置 DeepSeek API Key")
                    return@launch
                }

                val newsList = withContext(Dispatchers.IO) {
                    db.newsDao().getByIds(newsIds.toList())
                }
                if (newsList.isEmpty()) {
                    fail("所选新闻不存在或已被删除")
                    return@launch
                }

                val template = if (templateId > 0) {
                    withContext(Dispatchers.IO) {
                        db.intelTemplateDao().getById(templateId)
                    }
                } else {
                    null
                }

                val material = buildMaterial(newsList)
                val result = DeepSeekIntelApi.generateIntel(
                    apiKey = apiKey,
                    requirement = requirement,
                    templateContent = template?.content,
                    material = material,
                    targetChars = targetChars,
                    onStreamProgress = { chars -> streamedChars.set(chars) }
                )

                val intel = result.getOrNull()
                if (intel == null) {
                    fail(result.exceptionOrNull()?.message ?: "生成失败，请重试")
                    return@launch
                }

                val now = System.currentTimeMillis()
                val news = News(
                    websiteId = null,
                    websiteName = "AI生成情报",
                    title = intel.title,
                    titleZh = null,
                    content = intel.content,
                    contentZh = null,
                    newsUrl = null,
                    publishTime = TimeUtils.nowBeijingString(),
                    matchKeyword = AI_INTEL_TAG,
                    isPushed = 1,
                    createdAt = now,
                    isAiGenerated = 1
                )
                val savedId = withContext(Dispatchers.IO) {
                    // 将“AI生成情报”登记为关键词，便于在新闻列表中按关键词集中筛选。
                    if (db.keywordDao().countByKeyword(AI_INTEL_TAG) == 0) {
                        runCatching {
                            db.keywordDao().insert(
                                Keyword(
                                    keyword = AI_INTEL_TAG,
                                    createdAt = TimeUtils.nowBeijingString()
                                )
                            )
                        }
                    }
                    db.newsDao().insert(news)
                }
                tickerJob?.cancel()
                val elapsed = SystemClock.elapsedRealtime() - startedAtElapsed
                _state.update {
                    it.copy(
                        stage = IntelStage.SUCCESS,
                        percent = 100,
                        elapsedMs = elapsed,
                        title = intel.title,
                        content = intel.content,
                        newsId = savedId,
                        error = null
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "生成失败，请重试")
            }
        }
    }

    private fun startTicker() {
        tickerJob = viewModelScope.launch {
            while (isActive) {
                if (_state.value.stage != IntelStage.RUNNING) break
                delay(250)
                val elapsed = SystemClock.elapsedRealtime() - startedAtElapsed
                val timeFrac = (elapsed.toDouble() / expectedMs.toDouble()).coerceIn(0.0, 0.90)
                val streamFrac = (streamedChars.get().toDouble() /
                    (targetChars * 1.2 + 150.0)).coerceIn(0.0, 0.95)
                val computed = (max(timeFrac, streamFrac) * 100).toInt().coerceIn(0, 95)
                _state.update {
                    if (it.stage == IntelStage.RUNNING) {
                        it.copy(
                            percent = maxOf(it.percent, computed),
                            elapsedMs = elapsed
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun fail(message: String) {
        tickerJob?.cancel()
        val elapsed = if (startedAtElapsed > 0) {
            SystemClock.elapsedRealtime() - startedAtElapsed
        } else {
            0L
        }
        _state.update {
            it.copy(stage = IntelStage.ERROR, error = message, elapsedMs = elapsed)
        }
    }

    private fun buildMaterial(newsList: List<News>): String {
        val builder = StringBuilder()
        var remaining = 12_000
        newsList.take(20).forEachIndexed { index, news ->
            if (remaining <= 0) return@forEachIndexed
            val title = news.titleZh?.takeIf { it.isNotBlank() } ?: news.title
            val content = (news.contentZh?.takeIf { it.isNotBlank() }
                ?: news.content.orEmpty())
                .replace(WHITESPACE, " ")
                .take(800)
            val source = news.websiteName.orEmpty()
            val time = news.publishTime.orEmpty()

            builder.append("【素材").append(index + 1).append("】")
            if (source.isNotBlank()) builder.append("来源：").append(source).append(' ')
            if (time.isNotBlank()) builder.append("时间：").append(time)
            builder.append('\n')
            builder.append("标题：").append(title).append('\n')
            builder.append("正文：").append(content).append('\n')
            builder.append('\n')

            remaining -= title.length + content.length + 60
        }
        return builder.toString()
    }

    companion object {
        const val AI_INTEL_TAG = "AI生成情报"
        private val WHITESPACE = Regex("\\s+")
    }
}
