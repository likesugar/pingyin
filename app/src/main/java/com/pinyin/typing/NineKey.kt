package com.pinyin.typing

import java.text.Normalizer

/**
 * 九键（T9）编码：把带调拼音音节转成手机九宫格按键序列。
 * 2 abc / 3 def / 4 ghi / 5 jkl / 6 mno / 7 pqrs / 8 tuv / 9 wxyz
 */
object NineKey {

    private const val KEY_MAP = "22233344455566677778889999" // a..z -> 键位

    /** 九键键盘行，用于设置页示意 */
    const val LAYOUT_HINT = "abc→2  def→3  ghi→4  jkl→5  mno→6  pqrs→7  tuv→8  wxyz→9"

    /** 带调拼音 -> 无调字母（ü 记作 v） */
    fun plain(syllable: String): String {
        // 先处理 ü：NFD 会把它拆成 u + 组合符号，那样就会丢掉 v 的信息
        val pre = syllable.replace('ü', 'v').replace('Ü', 'V')
        val nfd = Normalizer.normalize(pre, Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        for (c in nfd) {
            if (c.code < 0x300) sb.append(c)   // 0x300 以上是组合变音符号，丢弃
        }
        return sb.toString()
    }

    /** 音节 -> 九键数字串，如 nǐ -> 64、hǎo -> 426 */
    fun digits(syllable: String): String {
        if (syllable.isEmpty()) return ""
        val plain = plain(syllable).lowercase()
        val sb = StringBuilder(plain.length)
        for (c in plain) {
            if (c in 'a'..'z') sb.append(digitOf(c)) else sb.append(c)
        }
        return sb.toString()
    }

    /** 单个小写字母 -> 九键数字；非字母返回其自身 */
    fun digitOf(c: Char): Char =
        if (c in 'a'..'z') KEY_MAP[c - 'a'] else c

    /** 整句（空格分隔的音节串）转九键，用于示例/校验 */
    fun digitsOfSyllables(syllables: List<String>): String =
        syllables.joinToString(" ") { digits(it) }
}
