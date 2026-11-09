package com.example.newsmonitor.ui.news

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.newsmonitor.R
import com.example.newsmonitor.data.AppDatabase
import com.example.newsmonitor.data.News
import com.example.newsmonitor.databinding.FragmentNewsListBinding
import com.example.newsmonitor.ui.common.ExportUtils
import com.example.newsmonitor.util.TimeUtils
import com.example.newsmonitor.util.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NewsListFragment : Fragment() {

    private var _binding: FragmentNewsListBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { AppDatabase.get(requireContext().applicationContext) }
    private lateinit var adapter: NewsAdapter

    private val timeFilter = MutableStateFlow<TimeFilter>(TimeFilter.Days(3))
    private val sourceFilter = MutableStateFlow<String?>(null)
    private val keywordFilter = MutableStateFlow<String?>(null)

    private var websiteNames: List<String> = emptyList()
    private var keywordNames: List<String> = emptyList()
    private var allNews: List<News> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNewsListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = NewsAdapter(
            onOpen = { news ->
                startActivity(NewsDetailActivity.intent(requireContext(), news.id))
            },
            onSelectionChanged = { updateSelectionBar() }
        )
        binding.rvNews.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNews.adapter = adapter

        binding.chipLast3.isChecked = true
        binding.timeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.size != 1) return@setOnCheckedStateChangeListener
            val filter = when (checkedIds[0]) {
                R.id.chipAll -> TimeFilter.All
                R.id.chipToday -> TimeFilter.Today
                R.id.chipLast3 -> TimeFilter.Days(3)
                R.id.chipLast7 -> TimeFilter.Days(7)
                R.id.chipLast30 -> TimeFilter.Days(30)
                else -> return@setOnCheckedStateChangeListener
            }
            timeFilter.value = filter
            binding.btnHistory.text = getString(R.string.history_button)
        }

        binding.btnSource.setOnClickListener { pickSource() }
        binding.btnKeyword.setOnClickListener { pickKeyword() }
        binding.btnHistory.setOnClickListener { showHistoryMenu() }
        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        binding.btnExport.setOnClickListener { exportSelected() }

        updateSourceButton()
        updateKeywordButton()
        updateSelectionBar()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        timeFilter,
                        sourceFilter,
                        keywordFilter
                    ) { time, source, keyword -> Triple(time, source, keyword) }
                        .collectLatest { (time, source, keyword) ->
                            db.newsDao().observeFiltered(time.cutoff(), source, keyword)
                                .collect { list ->
                                    allNews = list
                                    adapter.submitList(list)
                                    binding.tvEmpty.isVisible = list.isEmpty()
                                    binding.tvCount.text = "共 ${list.size} 条新闻"
                                    updateSelectionBar()
                                }
                        }
                }

                launch {
                    db.websiteDao().observeAll().collect { list ->
                        websiteNames = list.map { it.name }
                        val current = sourceFilter.value
                        if (current != null && websiteNames.none { it == current }) {
                            sourceFilter.value = null
                        }
                        updateSourceButton()
                    }
                }

                launch {
                    db.keywordDao().observeAll().collect { list ->
                        keywordNames = list.map { it.keyword }
                        val current = keywordFilter.value
                        if (current != null && keywordNames.none { it == current }) {
                            keywordFilter.value = null
                        }
                        updateKeywordButton()
                    }
                }
            }
        }
    }

    private fun pickSource() {
        val all = getString(R.string.filter_all)
        val options = listOf(all) + websiteNames
        val selectedIndex = sourceFilter.value?.let { name ->
            websiteNames.indexOf(name).let { if (it >= 0) it + 1 else 0 }
        } ?: 0

        showSingleChoiceDialog("选择新闻源", options, selectedIndex) { index ->
            sourceFilter.value = if (index == 0) null else options[index]
            updateSourceButton()
        }
    }

    private fun pickKeyword() {
        val all = getString(R.string.filter_all)
        val options = listOf(all) + keywordNames
        val selectedIndex = keywordFilter.value?.let { keyword ->
            keywordNames.indexOf(keyword).let { if (it >= 0) it + 1 else 0 }
        } ?: 0

        showSingleChoiceDialog("选择关键词", options, selectedIndex) { index ->
            keywordFilter.value = if (index == 0) null else options[index]
            updateKeywordButton()
        }
    }

    private fun showSingleChoiceDialog(
        title: String,
        options: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        if (options.isEmpty()) return
        val dialog: AlertDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(
                options.toTypedArray(),
                selectedIndex
            ) { dialog, which ->
                onSelected(which)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun showHistoryMenu() {
        val popup = PopupMenu(requireContext(), binding.btnHistory)
        popup.menu.add(0, MENU_1_MONTH, 1, getString(R.string.history_1m))
        popup.menu.add(0, MENU_3_MONTHS, 2, getString(R.string.history_3m))
        popup.menu.add(0, MENU_6_MONTHS, 3, getString(R.string.history_6m))
        popup.menu.add(0, MENU_1_YEAR, 4, getString(R.string.history_1y))
        popup.setOnMenuItemClickListener { item ->
            val months = when (item.itemId) {
                MENU_1_MONTH -> 1
                MENU_3_MONTHS -> 3
                MENU_6_MONTHS -> 6
                else -> 12
            }
            binding.timeChipGroup.clearCheck()
            timeFilter.value = TimeFilter.Months(months)
            binding.btnHistory.text = item.title
            true
        }
        popup.show()
    }

    private fun updateSourceButton() {
        binding.btnSource.text = "新闻源: " + (sourceFilter.value ?: getString(R.string.filter_all))
    }

    private fun updateKeywordButton() {
        binding.btnKeyword.text = "关键词: " + (keywordFilter.value ?: getString(R.string.filter_all))
    }

    private fun toggleSelectAll() {
        val selected = adapter.selectedIds()
        val allSelected = allNews.isNotEmpty() && allNews.all { it.id in selected }
        if (allSelected) {
            adapter.clearSelection()
        } else {
            adapter.selectAll(allNews)
        }
        updateSelectionBar()
    }

    private fun updateSelectionBar() {
        if (_binding == null) return
        val selected = adapter.selectedIds()
        binding.selectionBar.isVisible = selected.isNotEmpty()
        val allSelected = allNews.isNotEmpty() && allNews.all { it.id in selected }
        binding.btnSelectAll.text = getString(
            if (allSelected) R.string.unselect_all else R.string.select_all
        )
    }

    private fun confirmDeleteSelected() {
        val ids = adapter.selectedIds().toList()
        if (ids.isEmpty()) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除新闻")
            .setMessage("确定删除选中的 ${ids.size} 条新闻？")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.newsDao().deleteByIds(ids) }
                    adapter.clearSelection()
                    updateSelectionBar()
                    context?.toast("已删除 ${ids.size} 条新闻")
                }
            }
            .show()
    }

    private fun exportSelected() {
        val ids = adapter.selectedIds().toList()
        if (ids.isEmpty()) return

        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val news = withContext(Dispatchers.IO) { db.newsDao().getByIds(ids) }
            if (news.isEmpty()) {
                context?.toast("没有可导出的新闻")
                return@launch
            }
            val file = withContext(Dispatchers.IO) {
                ExportUtils.exportNewsCsv(appContext, news)
            }
            context?.toast("已导出: ${file.absolutePath}")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private sealed interface TimeFilter {
        fun cutoff(): String?

        data object All : TimeFilter {
            override fun cutoff(): String? = null
        }

        data object Today : TimeFilter {
            override fun cutoff(): String? = TimeUtils.startOfToday()
        }

        data class Days(val days: Int) : TimeFilter {
            override fun cutoff(): String? = TimeUtils.cutoffDaysAgo(days)
        }

        data class Months(val months: Int) : TimeFilter {
            override fun cutoff(): String? = TimeUtils.cutoffMonthsAgo(months)
        }
    }

    companion object {
        private const val MENU_1_MONTH = 1
        private const val MENU_3_MONTHS = 2
        private const val MENU_6_MONTHS = 3
        private const val MENU_1_YEAR = 4
    }
}
