package com.pinyin.typing

import android.content.Context
import kotlin.random.Random

/** 随机练习字库：取自 3500 常用字表，带准确读音，避免抽到生僻字 */
object RandomPool {

    private var pool: List<CommonChar> = emptyList()

    @Volatile
    private var ready = false

    fun size(): Int = pool.size

    fun load(context: Context) {
        if (ready) return
        if (!PinyinDict.isReady()) PinyinDict.load(context)
        pool = PinyinDict.commonChars()
        ready = true
    }

    /** 生成随机文本：每 5 个字插入一个空格作为分隔（空格不参与输入） */
    fun randomText(count: Int): String {
        if (pool.isEmpty()) return ""
        val n = count.coerceIn(1, 500)
        val sb = StringBuilder()
        for (i in 0 until n) {
            if (i > 0 && i % 5 == 0) sb.append(' ')
            sb.append(pool[Random.nextInt(pool.size)].ch)
        }
        return sb.toString()
    }

    fun syllablesOf(text: String): List<String> =
        List(text.length) { i ->
            val c = text[i]
            if (c == ' ') "" else PinyinDict.syllableOf(c)
        }
}
