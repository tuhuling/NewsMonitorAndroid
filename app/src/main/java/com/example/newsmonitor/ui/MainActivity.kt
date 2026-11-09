package com.example.newsmonitor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.newsmonitor.R
import com.example.newsmonitor.databinding.ActivityMainBinding
import com.example.newsmonitor.ui.keyword.KeywordFragment
import com.example.newsmonitor.ui.monitor.MonitorFragment
import com.example.newsmonitor.ui.news.NewsListFragment
import com.example.newsmonitor.ui.settings.SettingsFragment
import com.example.newsmonitor.ui.website.WebsiteFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTab = R.id.nav_news

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentTab = savedInstanceState?.getInt(KEY_CURRENT_TAB, R.id.nav_news) ?: R.id.nav_news

        binding.bottomNav.setOnItemSelectedListener { item ->
            currentTab = item.itemId
            showFragment(item.itemId)
            true
        }

        if (savedInstanceState == null) {
            showFragment(R.id.nav_news)
            binding.bottomNav.selectedItemId = R.id.nav_news
        } else {
            binding.bottomNav.selectedItemId = currentTab
        }

        requestNotificationPermissionIfNeeded()
    }

    private fun showFragment(itemId: Int) {
        val fragment = when (itemId) {
            R.id.nav_news -> NewsListFragment()
            R.id.nav_websites -> WebsiteFragment()
            R.id.nav_keywords -> KeywordFragment()
            R.id.nav_monitor -> MonitorFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> return
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_CURRENT_TAB, currentTab)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val KEY_CURRENT_TAB = "current_tab"
    }
}
