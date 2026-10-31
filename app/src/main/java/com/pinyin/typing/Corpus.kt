package com.pinyin.typing

import org.json.JSONObject

/** 一段待输入的文本：汉字串 + 与之一一对应的音节表（空格位置为空串） */
data class Segment(val text: String, val syllables: List<String>) {
    val flatTarget: String get() = text.replace(" ", "")
}

data class Lesson(val title: String, val segments: List<Segment>) {
    val charCount: Int get() = segments.sumOf { it.flatTarget.length }
    val segmentCount: Int get() = segments.size
}

data class Group(val id: String, val name: String, val desc: String, val lessons: List<Lesson>)

object Corpus {

    @Volatile
    private var loaded: List<Group>? = null

    fun get(context: android.content.Context): List<Group> {
        loaded?.let { return it }
        val json = context.assets.open("corpus.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val arr = root.getJSONArray("groups")
        val result = ArrayList<Group>(arr.length())
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            val lessonsArr = g.getJSONArray("lessons")
            val lessons = ArrayList<Lesson>(lessonsArr.length())
            for (j in 0 until lessonsArr.length()) {
                val l = lessonsArr.getJSONObject(j)
                val segArr = l.getJSONArray("segments")
                val segments = ArrayList<Segment>(segArr.length())
                for (k in 0 until segArr.length()) {
                    val s = segArr.getJSONObject(k)
                    val text = s.getString("t")
                    val syllables = splitSyllables(s.getString("p"), text.length)
                    if (text.isNotEmpty()) segments.add(Segment(text, syllables))
                }
                if (segments.isNotEmpty()) lessons.add(Lesson(l.getString("title"), segments))
            }
            if (lessons.isNotEmpty()) {
                result.add(
                    Group(
                        g.getString("id"),
                        g.getString("name"),
                        g.optString("desc", ""),
                        lessons
                    )
                )
            }
        }
        loaded = result
        return result
    }

    /** 按字符数补齐音节表，避免尾部空格被 split 丢弃导致错位 */
    private fun splitSyllables(raw: String, charCount: Int): List<String> {
        val parts = ArrayList<String>(charCount)
        var start = 0
        while (parts.size < charCount) {
            val next = raw.indexOf(' ', start)
            if (next < 0) {
                parts.add(raw.substring(start))
                break
            }
            parts.add(raw.substring(start, next))
            start = next + 1
        }
        while (parts.size < charCount) parts.add("")
        return parts
    }
}
