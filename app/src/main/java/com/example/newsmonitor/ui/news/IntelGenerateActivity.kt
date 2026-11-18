package com.example.newsmonitor.ui.news

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.newsmonitor.R
import com.example.newsmonitor.databinding.ActivityIntelGenerateBinding
import com.example.newsmonitor.util.buildIntelShareText
import com.example.newsmonitor.util.shareText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class IntelGenerateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIntelGenerateBinding
    private val viewModel: IntelGenerateViewModel by viewModels()
    private var rotationAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIntelGenerateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { confirmExit() }
        binding.btnCancelGenerate.setOnClickListener { confirmExit() }
        binding.btnBackResult.setOnClickListener { finish() }
        binding.btnCloseError.setOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { viewModel.retry() }
        binding.btnShareResult.setOnClickListener { shareResult() }
        binding.btnViewResult.setOnClickListener { openDetail() }

        onBackPressedDispatcher.addCallback(this) { confirmExit() }

        val requirement = intent.getStringExtra(EXTRA_REQUIREMENT).orEmpty()
        val templateId = intent.getLongExtra(EXTRA_TEMPLATE_ID, -1L)
        val newsIds = intent.getLongArrayExtra(EXTRA_NEWS_IDS) ?: longArrayOf()
        viewModel.start(newsIds, requirement, templateId)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: IntelUiState) {
        val running = state.stage == IntelStage.RUNNING || state.stage == IntelStage.IDLE
        binding.llLoading.isVisible = running
        binding.llResult.isVisible = state.stage == IntelStage.SUCCESS
        binding.llError.isVisible = state.stage == IntelStage.ERROR

        when (state.stage) {
            IntelStage.RUNNING, IntelStage.IDLE -> {
                binding.pbIntel.progress = state.percent
                binding.tvPercent.text =
                    getString(R.string.generate_intel_percent, state.percent)
                val totalSeconds = (state.elapsedMs / 1000).toInt()
                binding.tvElapsed.text = getString(
                    R.string.generate_intel_elapsed,
                    totalSeconds / 60,
                    totalSeconds % 60
                )
                startRotation()
            }

            IntelStage.SUCCESS -> {
                stopRotation()
                binding.tvResultTitle.text = state.title
                binding.tvResultMeta.text = getString(R.string.generate_intel_meta)
                binding.tvResultContent.text = state.content
            }

            IntelStage.ERROR -> {
                stopRotation()
                binding.tvGenerateError.text = state.error ?: "生成失败，请重试"
            }
        }
    }

    private fun startRotation() {
        if (rotationAnimator != null) return
        rotationAnimator = ObjectAnimator.ofFloat(
            binding.cardIcon,
            View.ROTATION,
            0f,
            360f
        ).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopRotation() {
        rotationAnimator?.cancel()
        rotationAnimator = null
        binding.cardIcon.rotation = 0f
    }

    private fun confirmExit() {
        if (viewModel.state.value.stage != IntelStage.RUNNING) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage("情报生成尚未完成，确定离开？")
            .setNegativeButton("继续生成", null)
            .setPositiveButton("离开") { _, _ -> finish() }
            .show()
    }

    private fun shareResult() {
        val state = viewModel.state.value
        if (state.stage != IntelStage.SUCCESS || state.content.isBlank()) return
        shareText(
            getString(R.string.generate_intel_share),
            state.title,
            buildIntelShareText(state.title, state.content)
        )
    }

    private fun openDetail() {
        val state = viewModel.state.value
        if (state.newsId <= 0L) return
        startActivity(NewsDetailActivity.intent(this, state.newsId))
    }

    override fun onStop() {
        stopRotation()
        super.onStop()
    }

    companion object {
        private const val EXTRA_NEWS_IDS = "extra_intel_news_ids"
        private const val EXTRA_REQUIREMENT = "extra_intel_requirement"
        private const val EXTRA_TEMPLATE_ID = "extra_intel_template_id"

        fun intent(
            context: Context,
            newsIds: LongArray,
            requirement: String,
            templateId: Long
        ): Intent {
            return Intent(context, IntelGenerateActivity::class.java)
                .putExtra(EXTRA_NEWS_IDS, newsIds)
                .putExtra(EXTRA_REQUIREMENT, requirement)
                .putExtra(EXTRA_TEMPLATE_ID, templateId)
        }
    }
}
