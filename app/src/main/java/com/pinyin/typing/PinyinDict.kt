package com.pinyin.typing

import android.content.Context

/** 常用字的一条记录：汉字 + 带调拼音 + 声母 + 韵母（韵母中的 ü 记作 v） */
class CommonChar(val ch: Char, val syllable: String, val initial: String, val final: String)

/** 汉字 -> 带调拼音 字典，用于自定义文本的读音标注与随机抽字 */
object PinyinDict {

    private val map = HashMap<Char, String>(22000)

    /** 3500 常用字（现代汉语常用字表），用于随机练习与专项训练换字 */
    private val common = LinkedHashMap<Char, CommonChar>(3600)

    /** 常用字扁平缓存，避免每次抽字都重新建表 */
    @Volatile
    private var commonList: List<CommonChar> = emptyList()

    @Volatile
    private var ready = false

    fun isReady() = ready

    fun load(context: Context) {
        if (ready) return
        context.assets.open("pinyin.txt").bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val ch = line[0]
                val py = line.substring(tab + 1)
                if (py.isNotEmpty()) map[ch] = py
            }
        }
        // 常用字表：字 \t 拼音 \t 声母 \t 韵母，只保留字典里有读音的字
        runCatching {
            context.assets.open("common3500.txt").bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val p = line.split('\t')
                    if (p.size < 4 || p[0].isEmpty() || p[1].isEmpty()) continue
                    val ch = p[0][0]
                    if (!common.containsKey(ch)) {
                        common[ch] = CommonChar(ch, p[1], p[2], p[3])
                    }
                }
            }
        }
        commonList = if (common.isNotEmpty()) {
            common.values.toList()
        } else {
            map.entries.map { CommonChar(it.key, it.value, "", "") }
        }
        ready = true
    }

    /** 逐字标注，未收录的字符返回空串 */
    fun annotate(text: String): List<String> {
        if (!ready) return List(text.length) { "" }
        return List(text.length) { map[text[it]] ?: "" }
    }

    /** 单字读音（带调），未收录返回空串 */
    fun syllableOf(ch: Char): String = map[ch] ?: ""

    /** 常用字数量 */
    fun commonSize(): Int = commonList.size

    /** 3500 常用字 */
    fun commonChars(): List<CommonChar> = commonList

    /** 按声母 / 韵母筛选常用字，供专项训练随机换字 */
    fun commonWhere(predicate: (CommonChar) -> Boolean): List<CommonChar> =
        commonList.filter(predicate)

    /** 按去调拼音筛选字，供自定义规则使用（全字典范围） */
    fun charsWhere(predicate: (plain: String) -> Boolean): List<Pair<Char, String>> {
        if (!ready) return emptyList()
        val out = ArrayList<Pair<Char, String>>(256)
        for ((ch, py) in map) {
            if (predicate(NineKey.plain(py))) out.add(ch to py)
        }
        return out
    }
}
