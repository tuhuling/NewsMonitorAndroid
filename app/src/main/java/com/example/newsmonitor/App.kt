package com.example.newsmonitor

import android.app.Application
import com.example.newsmonitor.data.AppDatabase

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.get(this)
    }

    companion object {
        lateinit var database: AppDatabase
            private set
    }
}
