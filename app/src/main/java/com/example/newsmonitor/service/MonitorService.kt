package com.example.newsmonitor.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.newsmonitor.R
import com.example.newsmonitor.data.AppDatabase
import com.example.newsmonitor.data.ConfigRepository
import com.example.newsmonitor.data.LogEntry
import com.example.newsmonitor.data.News
import com.example.newsmonitor.data.Website
import com.example.newsmonitor.network.DingTalkPusher
import com.example.newsmonitor.network.FetchedNews
import com.example.newsmonitor.network.NewsFetcher
import com.example.newsmonitor.network.PushResult
import com.example.newsmonitor.network.Translator
import com.example.newsmonitor.ui.MainActivity
import com.example.newsmonitor.util.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.random.Random

/**
 * 监测前台服务。
 *
 * 设计约束：
 * - 仅使用 Foreground Service（dataSync）保证亮屏前台持续运行；
 * - 不做 JobScheduler / 双进程 / 心跳等任何保活手段；
 * - 所有网络与抓取在 Dispatchers.IO 执行；
 * - 只要关键词命中，无论翻译成功与否都入库。
 */
class MonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var finished = false
    private var logInsertCount = 0

    private lateinit var db: AppDatabase
    private lateinit var configRepository: ConfigRepository
    private lateinit var newsFetcher: NewsFetcher
    private val pusher = DingTalkPusher()

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.get(this)
        configRepository = ConfigRepository(db.configDao())
        newsFetcher = NewsFetcher { level, message -> writeLog(level, message) }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统重建 Service 时 intent 可能为 null：不要启动一条没有通知的“幽灵”巡查，
        // 直接收尾退出，等用户下次手动开始。
        if (intent?.action == null) {
            finishMonitoring()
            return START_NOT_STICKY
        }

        return when (intent.action) {
            ACTION_START -> {
                if (monitorJob?.isActive == true) return START_STICKY
                finished = false
                startForegroundNotification(0, 0)
                acquireWakeLock()
                MonitorState.onStarted()
                monitorJob = serviceScope.launch {
                    try {
                        runLoop()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        withContext(NonCancellable) {
                            writeLog("ERROR", "监测异常: ${e.message?.take(150)}")
                        }
                    } finally {
                        withContext(NonCancellable) {
                            finishMonitoring()
                        }
                    }
                }
                START_STICKY
            }

            ACTION_STOP -> {
                requestStop()
                START_NOT_STICKY
            }

            else -> START_NOT_STICKY
        }
    }

    private suspend fun runLoop() {
        if (db.keywordDao().count() == 0) {
            writeLog("WARN", "请先导入关键词")
            return
        }

        writeLog("INFO", "监测服务已启动")

        while (currentCoroutineContext().isActive && MonitorState.running.value) {
            val websites = db.websiteDao().getAll()
            MonitorState.setProgress(0, websites.size)
            updateNotification(0, websites.size)
            writeLog("INFO", "开始巡查，共 ${websites.size} 个网站")
            if (websites.isEmpty()) {
                writeLog("WARN", "没有已导入的网站源，请先在新闻源管理中添加")
            }

            for ((index, website) in websites.withIndex()) {
                if (!currentCoroutineContext().isActive || !MonitorState.running.value) break

                val items = try {
                    newsFetcher.fetchNews(website)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    writeLog("ERROR", "巡查 ${website.name} 失败: ${e.message?.take(120)}")
                    emptyList()
                }
                writeLog("INFO", "巡查 ${website.name}，发现 ${items.size} 条")

                for (item in items) {
                    if (!currentCoroutineContext().isActive || !MonitorState.running.value) break
                    try {
                        processItem(website, item)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        writeLog("ERROR", "处理新闻失败: ${e.message?.take(120)}")
                    }
                }

                MonitorState.setProgress(index + 1, websites.size)
                updateNotification(index + 1, websites.size)
                delay(2000)
            }

            writeLog("INFO", "本轮完成")
            cleanupLogs()

            val interval = configRepository.getPollInterval()
            val totalSeconds = interval * 60L
            var elapsed = 0L
            while (
                currentCoroutineContext().isActive &&
                elapsed < totalSeconds &&
                MonitorState.running.value
            ) {
                delay(1000)
                elapsed++
            }
        }
    }

    private suspend fun processItem(website: Website, item: FetchedNews) {
        val newsDao = db.newsDao()

        if (item.url.isNotBlank()) {
            val since = System.currentTimeMillis() - SEVEN_DAYS_MS
            if (newsDao.countRecentByUrl(item.url, since) > 0) {
                writeLog("INFO", "重复新闻，已跳过: ${item.title.take(50)}...")
                return
            }
        }

        val keywords = db.keywordDao().getAll().map { it.keyword }
        if (keywords.isEmpty()) return

        val titleZh = Translator.translate(item.title)
        val contentZh = Translator.translate(item.content)
        val translationFailed = titleZh.isNullOrBlank() || contentZh.isNullOrBlank()

        if (translationFailed) {
            writeLog("WARN", "翻译失败，已保留原文：${item.title.take(50)}")
        }

        // 双重匹配：翻译后的中文 + 原文，任一路命中即算命中。
        val translatedText = normalizeForMatch(
            (titleZh ?: "") + " " + (contentZh ?: "").take(500)
        )
        val originalText = normalizeForMatch(
            item.title + " " + item.content.take(500)
        )

        val matched = keywords.filter { keyword ->
            val normalized = normalizeForMatch(keyword)
            normalized.isNotEmpty() &&
                (translatedText.contains(normalized) || originalText.contains(normalized))
        }

        // 唯一不入库的情况：两路匹配都未命中。
        if (matched.isEmpty()) return

        val news = News(
            websiteId = website.id,
            websiteName = website.name,
            title = item.title,
            titleZh = titleZh?.takeIf { it.isNotBlank() },
            content = item.content,
            contentZh = contentZh?.takeIf { it.isNotBlank() },
            newsUrl = item.url,
            publishTime = item.publishTime,
            matchKeyword = matched.joinToString(","),
            isPushed = 0,
            createdAt = System.currentTimeMillis()
        )

        val newsId = newsDao.insert(news)
        val displayTitle = news.titleZh ?: news.title
        writeLog("INFO", "发现匹配新闻: ${displayTitle.take(50)} (关键词: ${news.matchKeyword})")

        val webhook = configRepository.getDingTalkWebhook()
        if (webhook.isNullOrBlank()) {
            writeLog("WARN", "未配置钉钉 Webhook，跳过推送")
            return
        }

        val summary = news.contentZh?.takeIf { it.isNotBlank() } ?: news.content.orEmpty()
        when (val result = pusher.send(webhook, news, displayTitle, summary, translationFailed)) {
            is PushResult.Success -> {
                newsDao.markPushed(newsId)
                writeLog("INFO", "推送钉钉成功: ${displayTitle.take(50)}...")
            }

            is PushResult.Failure -> writeLog("WARN", "推送钉钉失败: ${result.reason.take(150)}")

            PushResult.NotConfigured -> writeLog("WARN", "未配置钉钉 Webhook，跳过推送")
        }

        // 钉钉限流：每条推送之间间隔 2~3 秒。
        delay(2000L + Random.nextLong(1001))
    }

    private fun normalizeForMatch(text: String): String =
        text.lowercase(Locale.ROOT).replace(WHITESPACE, "")

    private suspend fun writeLog(level: String, message: String) {
        try {
            db.logDao().insert(
                LogEntry(
                    level = level,
                    message = message,
                    createdAt = TimeUtils.nowBeijingString()
                )
            )
            logInsertCount++
            if (logInsertCount % LOG_CLEANUP_EVERY == 0) {
                cleanupLogs()
            }
        } catch (_: Exception) {
            // 日志失败不能影响监测主流程
        }
    }

    private suspend fun cleanupLogs() {
        try {
            val logDao = db.logDao()
            logDao.deleteOlderThan(TimeUtils.cutoffDaysAgo(LOG_RETENTION_DAYS))
            logDao.trimTo(MAX_LOG_ROWS)
        } catch (_: Exception) {
            // 清理失败不中断监测
        }
    }

    private fun requestStop() {
        monitorJob?.cancel()
        finishMonitoring()
    }

    private fun finishMonitoring() {
        if (finished) return
        finished = true
        MonitorState.onStopped()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "外媒监测",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "外媒新闻监测前台服务"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(checked: Int, total: Int) {
        val notification = buildNotification(checked, total)
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
    }

    private fun updateNotification(checked: Int, total: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        try {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(checked, total))
        } catch (_: SecurityException) {
            // 用户未授予通知权限时忽略
        }
    }

    private fun buildNotification(checked: Int, total: Int): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle("外媒监测运行中")
            .setContentText("已监测 $checked/$total 个网站")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NewsMonitor::MonitorWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_SERVICE_RUNTIME_MS)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
            // ignore
        }
        wakeLock = null
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        releaseWakeLock()
        MonitorState.onStopped()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.newsmonitor.action.START_MONITOR"
        const val ACTION_STOP = "com.example.newsmonitor.action.STOP_MONITOR"

        private const val CHANNEL_ID = "monitor_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_LOG_ROWS = 10_000
        private const val LOG_RETENTION_DAYS = 7
        private const val LOG_CLEANUP_EVERY = 20
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_SERVICE_RUNTIME_MS = 6L * 60 * 60 * 1000

        private val WHITESPACE = Regex("\\s+")

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MonitorService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
