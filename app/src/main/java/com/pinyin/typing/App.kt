package com.pinyin.typing

import android.app.Application
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        sp = getSharedPreferences("pinyin_prefs", MODE_PRIVATE)
        // 应用内固定简体中文，不跟随系统语言
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
        Prefs.applyTheme()
    }

    companion object {
        lateinit var instance: App
            private set
        lateinit var sp: SharedPreferences
            private set
    }
}
