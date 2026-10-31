package com.pinyin.typing

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 语音朗读：调用设备自带的中文语音引擎（Google TTS / 厂商 TTS / 第三方引擎）
 * 朗读单字、词语或整段文字，用于「听音辨字」。
 *
 * 引擎首次使用时才初始化，避免拖慢启动；没有中文音库时静默降级，
 * 由调用方通过 [unavailable] 决定是否提示用户安装语音引擎。
 * TextToSpeech 的构造放在后台线程：在部分机型与虚拟化环境里
 * 绑定语音服务可能长时间阻塞，放主线程会触发「应用无响应」。
 */
object Speech {

    private const val TAG = "Speech"

    private val thread = HandlerThread("speech-tts").apply { start() }
    private val worker = Handler(thread.looper)

    @Volatile
    private var engine: TextToSpeech? = null
    @Volatile
    private var initializing = false
    @Volatile
    private var configured = false
    @Volatile
    private var chineseOk = false
    @Volatile
    private var failed = false
    @Volatile
    private var pending: String? = null

    /** 测试注入点：非空时由它接管朗读请求（文本 + 音节），不接触真实引擎 */
    internal var speaker: ((String, List<String>) -> Unit)? = null

    /** 朗读语速，0.95 稍慢于常速，便于跟读 */
    var rate: Float = 0.95f
        set(v) {
            field = v.coerceIn(0.5f, 2.0f)
            if (configured) engine?.setSpeechRate(field)
        }

    /** 既没有系统中文音库、也没有内置发音包时才需要提示用户 */
    fun unavailable(context: Context): Boolean {
        if (SyllableVoice.available(context)) return false
        return failed || (configured && !chineseOk)
    }

    /** 系统语音是否已就绪可用（内置包之外的更自然选项） */
    fun systemReady(): Boolean = configured && chineseOk

    /** 预热引擎，进入训练页时调用可减少第一次点击的延迟 */
    fun prepare(context: Context) {
        if (engine != null || initializing) return
        initializing = true
        worker.post { init(context.applicationContext) }
    }

    /**
     * 朗读一段文本。
     *
     * 两条路：系统语音引擎（整段朗读更自然）与内置音节包（一定可用、完全离线）。
     * 默认优先系统引擎，未就绪或没有中文音库时立刻回退到内置包，用户不会点了没声音。
     *
     * @param syllables 与文本对应的带调音节，内置包按它逐音节连播
     */
    fun speak(context: Context, text: String, syllables: List<String> = emptyList()) {
        val s = text.trim()
        if (s.isEmpty()) return
        // 没给音节时按字典补算，保证任何文本都能走内置发音包
        val syl = syllables.ifEmpty { PinyinDict.annotate(s).filter { it.isNotEmpty() } }
        speaker?.let {
            it(s, syl)
            return
        }
        if (Prefs.useSystemVoice && configured && chineseOk) {
            engine?.let { speakNow(it, s); return }
        }
        // 回退到内置音节包；顺带把系统引擎准备好，下次可自动切到更自然的音色
        if (syl.isNotEmpty() && SyllableVoice.hits(context, syl) > 0) {
            SyllableVoice.stop()
            SyllableVoice.speak(context, syl)
            if (Prefs.useSystemVoice) prepare(context)
            return
        }
        val app = context.applicationContext
        val e = engine
        if (e == null) {
            pending = s
            prepare(app)
            return
        }
        if (!configured) {
            pending = s
            return
        }
        if (!chineseOk) return
        speakNow(e, s)
    }

    /** 立即停止当前朗读 */
    fun stop() {
        engine?.stop()
        SyllableVoice.stop()
        pending = null
    }

    /** 是否正在朗读（用于喇叭按钮的播放/停止切换） */
    fun isSpeaking(): Boolean =
        SyllableVoice.isSpeaking() || runCatching { engine?.isSpeaking == true }.getOrDefault(false)

    fun release() {
        SyllableVoice.stop()
        val e = engine
        engine = null
        configured = false
        chineseOk = false
        failed = false
        initializing = false
        pending = null
        // shutdown 在后台线程执行，避免退出时阻塞主线程
        worker.post {
            runCatching { e?.stop() }
            runCatching { e?.shutdown() }
        }
    }

    private fun speakNow(e: TextToSpeech, text: String) {
        // minSdk 24，直接使用带 utteranceId 的接口
        e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "py-${System.nanoTime()}")
    }

    @Synchronized
    private fun init(app: Context) {
        if (engine != null) return
        initializing = true
        var status = Int.MIN_VALUE
        val created = try {
            TextToSpeech(app) { st ->
                status = st
                // 部分实现会同步回调，此时 engine 字段还没赋值，交给下面的补调
                applyInit(st)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS 初始化失败", e)
            failed = true
            initializing = false
            pending = null
            return
        }
        engine = created
        if (status != Int.MIN_VALUE) applyInit(status)
    }

    @Synchronized
    private fun applyInit(status: Int) {
        if (configured) return
        val e = engine ?: return
        initializing = false
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS 引擎不可用: $status")
            failed = true
            return
        }
        // 作为辅助播报，不抢占媒体音频焦点，正在听的音乐不会被强行暂停
        e.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val r = e.setLanguage(Locale.SIMPLIFIED_CHINESE)
        chineseOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        e.setSpeechRate(rate)
        e.setPitch(1.0f)
        configured = true
        val t = pending
        pending = null
        if (t != null && chineseOk) speakNow(e, t)
    }
}
