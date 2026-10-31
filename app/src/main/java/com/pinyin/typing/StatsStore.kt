package com.pinyin.typing

import org.json.JSONArray
import org.json.JSONObject

/** 一次完整的课程练习记录 */
data class Record(
    val name: String,
    val chars: Int,
    val timeMs: Long,
    val speed: Int,      // 字/分钟
    val accuracy: Int,   // 百分比
    val errors: Int,
    val ts: Long
)

object StatsStore {
    private const val KEY = "records"
    private const val MAX = 100

    fun all(): List<Record> {
        val raw = App.sp.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Record>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Record(
                        o.optString("n", ""),
                        o.optInt("c", 0),
                        o.optLong("t", 0L),
                        o.optInt("s", 0),
                        o.optInt("a", 100),
                        o.optInt("e", 0),
                        o.optLong("ts", 0L)
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(r: Record) {
        val list = all().toMutableList()
        list.add(0, r)
        val trimmed = list.take(MAX)
        val arr = JSONArray()
        trimmed.forEach {
            arr.put(
                JSONObject().apply {
                    put("n", it.name)
                    put("c", it.chars)
                    put("t", it.timeMs)
                    put("s", it.speed)
                    put("a", it.accuracy)
                    put("e", it.errors)
                    put("ts", it.ts)
                }
            )
        }
        App.sp.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() = App.sp.edit().remove(KEY).apply()

    fun bestSpeed(): Int = all().maxOfOrNull { it.speed } ?: 0

    fun avgAccuracy(): Int {
        val list = all()
        if (list.isEmpty()) return 0
        return list.sumOf { it.accuracy } / list.size
    }

    fun totalChars(): Int = all().sumOf { it.chars }
}
