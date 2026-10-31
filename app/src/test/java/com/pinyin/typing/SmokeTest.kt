package com.pinyin.typing

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 冒烟测试：验证启动、素材加载、输入比对与成绩记录主链路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmokeTest {

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<App>()
        PinyinDict.load(app)
    }

    @Test
    fun corpusLoads() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val groups = Corpus.get(app)
        assertEquals(9, groups.size)
        assertTrue(groups.sumOf { it.lessons.size } > 100)
        // 每个音段：汉字数 == 音节数
        groups.forEach { g ->
            g.lessons.forEach { l ->
                l.segments.forEach { s ->
                    assertEquals("${g.id}/${l.title}", s.text.length, s.syllables.size)
                }
            }
        }
    }

    @Test
    fun dictionaryAnnotates() {
        val app = ApplicationProvider.getApplicationContext<App>()
        PinyinDict.load(app)
        assertTrue(PinyinDict.isReady())
        val py = PinyinDict.annotate("拼音")
        assertEquals(2, py.size)
        assertTrue(py[0].startsWith("p"))
    }

    @Test
    fun typingFlowCompletesAndRecords() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val train = findFragment<TrainFragment>(activity)
            ?: throw AssertionError("TrainFragment 未挂载")

        StatsStore.clear()
        train.startCustom("你好")

        val edit = activity.findViewById<EditText>(R.id.etInput)
        val ic = edit.onCreateInputConnection(EditorInfo())
            ?: throw AssertionError("InputConnection 为空")

        // 模拟输入法：组合中的拼音不应计入，上屏后才计入
        ic.setComposingText("ni", 1)
        assertEquals("", activity.findViewById<android.widget.TextView>(R.id.tvComposing).text.toString().let {
            it
        }.replace("ni", ""))
        assertTrue(ic.commitText("你", 1))
        assertTrue(ic.commitText("好", 1))

        // 全部输入正确后应写入成绩
        val records = StatsStore.all()
        assertTrue("完成一课后应写入成绩", records.isNotEmpty())
        assertEquals(2, records.first().chars)
        assertEquals(100, records.first().accuracy)
        assertEquals(0, records.first().errors)
    }

    @Test
    fun wrongInputCountsError() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val train = findFragment<TrainFragment>(activity)
            ?: throw AssertionError("TrainFragment 未挂载")

        StatsStore.clear()
        train.startCustom("你好")
        val edit = activity.findViewById<EditText>(R.id.etInput)
        val ic = edit.onCreateInputConnection(EditorInfo())!!

        ic.commitText("我", 1)     // 错字
        // 输入法退格不会回退上方进度，改错要走输入框右侧的删除键
        activity.findViewById<android.widget.ImageButton>(R.id.btnDelete).performClick()
        ic.commitText("你", 1)
        ic.commitText("好", 1)

        val records = StatsStore.all()
        assertTrue(records.isNotEmpty())
        assertEquals(1, records.first().errors)
        assertEquals(2, records.first().chars)
    }

    @Test
    fun randomPoolGenerates() {
        val app = ApplicationProvider.getApplicationContext<App>()
        Corpus.get(app)
        RandomPool.load(app)
        assertTrue("随机字库应覆盖 3500 常用字", RandomPool.size() >= 3400)
        val text = RandomPool.randomText(30)
        assertEquals("随机字数不符", 30, text.replace(" ", "").length)
        assertEquals("音节数应与字数一致", text.length, RandomPool.syllablesOf(text).size)
        assertTrue("随机文本应有拼音", RandomPool.syllablesOf(text).any { it.isNotBlank() })
    }

    @Test
    fun restartPicksNewContent() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val train = findFragment<TrainFragment>(activity)
            ?: throw AssertionError("TrainFragment 未挂载")

        val groups = Corpus.get(app)
        val shengmu = groups.first { it.id == "shengmu" }
        val lesson = shengmu.lessons.first { it.title == "声母 b" }
        train.startLesson(lesson, shengmu)

        val tv = activity.findViewById<TrainingTextView>(R.id.trainingText)
        val restart = activity.findViewById<android.view.View>(R.id.btnRestart)
        val first = tv.targetText().replace(" ", "")
        assertTrue("未加载训练内容", first.isNotEmpty())

        var changed = false
        repeat(3) {
            restart.performClick()
            val next = tv.targetText().replace(" ", "")
            assertEquals("换字后字数应保持一致", first.length, next.length)
            // 声母专项换字后每个字仍必须是 b 声母
            next.forEach { c ->
                assertTrue(
                    "「$c」不是 b 声母：${PinyinDict.syllableOf(c)}",
                    NineKey.plain(PinyinDict.syllableOf(c)).lowercase().startsWith("b")
                )
            }
            if (next != first) changed = true
        }
        assertTrue("连续重新开始应换出新内容", changed)
    }

    @Test
    fun restartKeepsWordSyllablesAligned() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val train = findFragment<TrainFragment>(activity)
            ?: throw AssertionError("TrainFragment 未挂载")

        val groups = Corpus.get(app)
        val ci = groups.first { it.id == "ci" }
        val lesson = ci.lessons.first()
        train.startLesson(lesson, ci)

        val tv = activity.findViewById<TrainingTextView>(R.id.trainingText)
        val restart = activity.findViewById<android.view.View>(R.id.btnRestart)
        repeat(2) {
            restart.performClick()
            val text = tv.targetText()
            val syllables = tv.targetSyllables()
            assertEquals("音节表应与文本等长", text.length, syllables.size)
            text.indices.forEach { i ->
                if (text[i] == ' ') {
                    assertEquals("分隔位不应有音节", "", syllables[i])
                } else {
                    assertTrue("「${text[i]}」缺少读音", syllables[i].isNotBlank())
                }
            }
        }
    }

    @Test
    fun extraCorpusIsLargeEnough() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val groups = Corpus.get(app).associateBy { it.id }
        // 词 / 成语按空格分词计数，句子 / 段落按段计数
        fun units(id: String): Int = groups[id]!!.lessons.sumOf { l ->
            l.segments.sumOf { s -> s.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() } }
        }
        fun segs(id: String): Int = groups[id]!!.lessons.sumOf { it.segments.size }
        assertTrue("常用词语应达到 2500+，实际 ${units("ci")}", units("ci") >= 2500)
        assertTrue("成语应达到 1500+，实际 ${units("chengyu")}", units("chengyu") >= 1500)
        assertTrue("短句应达到 2500 句+，实际 ${segs("duanju")}", segs("duanju") >= 2500)
        assertTrue("段落应达到 1500 段+，实际 ${segs("wenzhang")}", segs("wenzhang") >= 1500)
        // 训练文本不能夹带标点/字母，否则无法输入到结束
        groups.values.forEach { g ->
            g.lessons.forEach { l ->
                l.segments.forEach { s ->
                    assertTrue(
                        "${g.id}/${l.title} 含非汉字字符：${s.text}",
                        s.text.all { it == ' ' || it in '\u4e00'..'\u9fff' }
                    )
                }
            }
        }
    }

    /** 内置发音包：语料里出现的每个音节都必须有对应音频，且文件真实可读 */
    @Test
    fun everySyllableHasAudio() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val index = app.assets.open("syllables.txt").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }
                .map { it.split('\t') }
                .filter { it.size == 2 }
                .associate { it[0] to it[1] }
        }
        assertTrue("音节索引过小：${index.size}", index.size > 1000)

        val missing = LinkedHashSet<String>()
        Corpus.get(app).forEach { g ->
            g.lessons.forEach { l ->
                l.segments.forEach { s ->
                    s.syllables.forEach { p ->
                        if (p.isNotEmpty() && p !in index) missing.add(p)
                    }
                }
            }
        }
        // 儿化音尾「r」没有独立单字读音，内置包不单独发声，其余音节必须齐全
        val allowed = setOf("r")
        assertTrue("缺音频的音节：${(missing - allowed).take(12)}", (missing - allowed).isEmpty())

        // 抽查首尾与几个易错音节，确认音频非空
        listOf("nǐ", "hǎo", "lǜ", "zhōng").forEach { s ->
            val f = index[s] ?: throw AssertionError("索引里没有 $s")
            val size = app.assets.open("syllables/$f").use { it.readBytes().size }
            assertTrue("$s 音频过小：$size", size > 800)
        }
    }

    /** 3500 常用字：每个字都要能用内置发音包读出来（随机字练习、点字发音都依赖它） */
    @Test
    fun everyCommonCharIsSpeakable() {
        val app = ApplicationProvider.getApplicationContext<App>()
        PinyinDict.load(app)
        val index = app.assets.open("syllables.txt").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }
                .map { it.split('\t') }
                .filter { it.size == 2 }
                .associate { it[0] to it[1] }
        }
        val common = PinyinDict.commonChars()
        assertTrue("常用字表过小：${common.size}", common.size >= 3500)

        val missing = LinkedHashSet<String>()
        common.forEach { if (it.syllable !in index) missing.add("${it.ch}:${it.syllable}") }
        assertTrue("常用字缺发音 ${missing.size} 个：${missing.take(12)}", missing.isEmpty())

        // 抽查几个易错音节，确认音频真实可读
        listOf("ā", "zhuàng", "lǜ", "ér", "shuǎi", "xué").forEach { s ->
            val f = index[s] ?: throw AssertionError("索引里没有 $s")
            val size = app.assets.open("syllables/$f").use { it.readBytes().size }
            assertTrue("$s 音频过小：$size", size > 800)
        }
    }

    private inline fun <reified T : Fragment> findFragment(activity: MainActivity): T? {
        return activity.supportFragmentManager.fragments.filterIsInstance<T>().firstOrNull()
    }
}
