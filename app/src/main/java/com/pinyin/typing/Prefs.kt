package com.pinyin.typing

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object Prefs {
    private const val FILE = "pinyin_prefs"
    private const val KEY_PINYIN = "show_pinyin"
    private const val KEY_STRICT = "strict"
    private const val KEY_FONT = "font_level"
    private const val KEY_THEME = "theme"
    private const val KEY_CODE_MODE = "code_mode"
    private const val KEY_SIMPLE_INPUT = "simple_input"
    private const val KEY_SPEAK_ON_TAP = "speak_on_tap"
    private const val KEY_SYSTEM_VOICE = "system_voice"

    const val THEME_DARK = 0
    const val THEME_LIGHT = 1
    const val THEME_SYSTEM = 2

    /** 编码方式：全键全拼 / 九键数字 */
    const val CODE_FULL = 0
    const val CODE_NINE = 1

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var showPinyin: Boolean
        get() = App.sp.getBoolean(KEY_PINYIN, true)
        set(v) = App.sp.edit().putBoolean(KEY_PINYIN, v).apply()

    var strictMode: Boolean
        get() = App.sp.getBoolean(KEY_STRICT, true)
        set(v) = App.sp.edit().putBoolean(KEY_STRICT, v).apply()

    var fontLevel: Int
        get() = App.sp.getInt(KEY_FONT, 1)
        set(v) = App.sp.edit().putInt(KEY_FONT, v.coerceIn(0, 2)).apply()

    var theme: Int
        get() = App.sp.getInt(KEY_THEME, THEME_DARK)
        set(v) = App.sp.edit().putInt(KEY_THEME, v).apply()

    var codeMode: Int
        get() = App.sp.getInt(KEY_CODE_MODE, CODE_FULL)
        set(v) = App.sp.edit().putInt(KEY_CODE_MODE, v).apply()

    /** 简易输入模式：直接敲拼音编码即可，无需选字上屏 */
    var simpleInput: Boolean
        get() = App.sp.getBoolean(KEY_SIMPLE_INPUT, false)
        set(v) = App.sp.edit().putBoolean(KEY_SIMPLE_INPUT, v).apply()

    /** 点击训练文本里的字即朗读发音 */
    var speakOnTap: Boolean
        get() = App.sp.getBoolean(KEY_SPEAK_ON_TAP, true)
        set(v) = App.sp.edit().putBoolean(KEY_SPEAK_ON_TAP, v).apply()

    /** 优先使用系统语音（更自然）；关闭后始终用内置音节发音 */
    var useSystemVoice: Boolean
        get() = App.sp.getBoolean(KEY_SYSTEM_VOICE, true)
        set(v) = App.sp.edit().putBoolean(KEY_SYSTEM_VOICE, v).apply()

    fun charSizeSp(level: Int = fontLevel): Float = when (level) {
        0 -> 30f
        1 -> 38f
        else -> 46f
    }

    fun load(context: Context) {
        // 触发 App.sp 初始化
        App.sp
    }

    fun applyTheme(mode: Int = theme) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }
}
