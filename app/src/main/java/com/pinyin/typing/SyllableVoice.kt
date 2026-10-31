package com.pinyin.typing

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * 内置音节发音：assets/syllables 里是 1163 个带调音节的短音频（神经音色合成），
 * 覆盖《现代汉语常用字表》3500 字的全部读音，另含语料里的轻声等音节。
 * 按注音逐音节连播即可读出单字、词语乃至整段，完全离线，
 * 不依赖设备是否装了中文语音引擎。
 *
 * 所有音频操作（创建 / prepare / 播放调度 / 释放）都串行跑在专用后台线程上，
 * 主线程只投递请求：MediaPlayer 的 prepare、release 在部分机型与虚拟化
 * 环境里可能长时间阻塞，放在主线程会直接触发「应用无响应」。
 */
object SyllableVoice {

    private const val TAG = "SyllableVoice"
    private const val DIR = "syllables"

    /** 音节（带调拼音）-> 音频文件名 */
    private var index: Map<String, String>? = null

    private val thread = HandlerThread("syllable-voice").apply { start() }
    private val worker = Handler(thread.looper)

    private var queue: List<String> = emptyList()
    private var current: MediaPlayer? = null
    private var prefetched: MediaPlayer? = null

    /** 仅 worker 线程读写；主线程读它只用于按钮状态展示，允许近似值 */
    @Volatile
    private var speaking = false

    /** 内置发音包是否可用（assets 里有索引与音频） */
    fun available(context: Context): Boolean = loadIndex(context).isNotEmpty()

    fun isSpeaking(): Boolean = speaking

    fun stop() {
        // 立即置为非播放，主线程读到的按钮状态无需等后台线程
        speaking = false
        worker.post { stopInner() }
    }

    /** 仅测试用：等后台线程把已排队的播放/停止任务处理完，避免时序竞争 */
    @androidx.annotation.VisibleForTesting
    fun awaitIdle(timeoutMs: Long = 3000) {
        val latch = java.util.concurrent.CountDownLatch(1)
        worker.post { latch.countDown() }
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun stopInner() {
        speaking = false
        queue = emptyList()
        runCatching { current?.stop() }
        runCatching { current?.release() }
        runCatching { prefetched?.release() }
        current = null
        prefetched = null
    }

    /**
     * 依次朗读一串音节（带调拼音，如 ["yín", "háng"]）。
     * 索引里没有的音节会被跳过；整串都缺失时不发声。
     */
    fun speak(context: Context, syllables: List<String>) {
        val app = context.applicationContext
        worker.post {
            val map = loadIndex(app)
            val files = syllables.mapNotNull { map[it] }
            if (files.isEmpty()) return@post
            stopInner()
            queue = files
            speaking = true
            playAt(app, 0)
        }
    }

    /** 以下全部运行在 worker 线程 */
    private fun playAt(context: Context, position: Int) {
        if (position >= queue.size) {
            speaking = false
            releaseAll()
            return
        }
        val mp = prefetched ?: create(context, queue[position])
        prefetched = null
        if (mp == null) {
            // 这个音节的音频坏了就跳过
            playAt(context, position + 1)
            return
        }
        current = mp
        val idx = position
        mp.setOnCompletionListener {
            current = null
            runCatching { mp.release() }
            playAt(context, idx + 1)
        }
        try {
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "播放失败", e)
            current = null
            runCatching { mp.release() }
            playAt(context, idx + 1)
            return
        }
        // 预取下一个，缩短字与字之间的空隙
        if (idx + 1 < queue.size) prefetched = create(context, queue[idx + 1])
    }

    private fun releaseAll() {
        runCatching { current?.release() }
        runCatching { prefetched?.release() }
        current = null
        prefetched = null
    }

    private fun create(context: Context, file: String): MediaPlayer? {
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            context.assets.openFd("$DIR/$file").use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.prepare()
            mp
        } catch (e: Exception) {
            Log.w(TAG, "加载 $file 失败", e)
            null
        }
    }

    private fun loadIndex(context: Context): Map<String, String> {
        index?.let { return it }
        val map = HashMap<String, String>(1400)
        try {
            context.assets.open("syllables.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val p = line.split('\t')
                    if (p.size == 2 && p[0].isNotEmpty()) map[p[0]] = p[1]
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "音节索引缺失", e)
        }
        index = map
        return map
    }

    /** 这串音节里有多少个能在包内找到音频（用于判断值不值得走内置包） */
    fun hits(context: Context, syllables: List<String>): Int {
        val map = loadIndex(context)
        return syllables.count { map.containsKey(it) }
    }

    /** 仅测试用：确认某个音节的音频文件在包内 */
    fun fileOf(context: Context, syllable: String): String? = loadIndex(context)[syllable]

    /** 仅测试用：音频文件是否真实存在 */
    fun rawExists(context: Context, file: String): Boolean =
        runCatching { context.assets.open("$DIR/$file").use { it.read() } }.isSuccess
}
