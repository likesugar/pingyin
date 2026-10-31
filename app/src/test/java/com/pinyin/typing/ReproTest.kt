package com.pinyin.typing

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReproTest {

    private fun boot(): MainActivity {
        val app = ApplicationProvider.getApplicationContext<App>()
        PinyinDict.load(app)
        // 每个用例从统一状态开始，避免 SharedPreferences 串状态
        Prefs.codeMode = Prefs.CODE_FULL
        Prefs.simpleInput = false
        return Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    private fun trainOf(a: MainActivity): TrainFragment =
        a.supportFragmentManager.fragments.filterIsInstance<TrainFragment>().first()

    @Test
    fun navigateToRandomDoesNotCrash() {
        val activity = boot()
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.selectedItemId = R.id.nav_random
        ShadowLooper.idleMainLooper()
        val frag = activity.supportFragmentManager.fragments
            .filterIsInstance<RandomFragment>().firstOrNull()
        assertNotNull("随机页未挂载", frag)
        assertNotNull("btnStart 未找到", activity.findViewById<Button>(R.id.btnStart))
    }

    @Test
    fun clickStartGeneratesLesson() {
        val activity = boot()
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.selectedItemId = R.id.nav_random
        ShadowLooper.idleMainLooper()
        activity.findViewById<Button>(R.id.btnStart).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue("应进入训练", trainOf(activity).isPracticing())
    }

    @Test
    fun deleteButtonRemovesLastChar() {
        val activity = boot()
        val train = trainOf(activity)
        train.startCustom("你好世界")
        ShadowLooper.idleMainLooper()

        val edit = activity.findViewById<EditText>(R.id.etInput)
        val ic = edit.onCreateInputConnection(EditorInfo())!!
        ic.commitText("你", 1)
        ic.commitText("好", 1)
        ShadowLooper.idleMainLooper()
        assertEquals("你好", edit.text.toString())

        activity.findViewById<ImageButton>(R.id.btnDelete).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals("只剩一个字", "你", edit.text.toString())
        assertEquals(
            "删除按钮应同步回退上方进度",
            "你",
            activity.findViewById<TrainingTextView>(R.id.trainingText).typedText()
        )
    }

    @Test
    fun inputBoxClearedWhenSegmentDone() {
        val activity = boot()
        val train = trainOf(activity)
        train.startCustom("你好")
        ShadowLooper.idleMainLooper()

        val edit = activity.findViewById<EditText>(R.id.etInput)
        val ic = edit.onCreateInputConnection(EditorInfo())!!
        ic.commitText("你", 1)
        ic.commitText("好", 1)
        ShadowLooper.idleMainLooper()

        assertEquals("本段输入完成后输入框应清空", "", edit.text.toString())
        assertEquals(
            "已打出的内容仍保留在上方",
            "你好",
            activity.findViewById<TrainingTextView>(R.id.trainingText).typedText()
        )
    }

    @Test
    fun simpleInputAcceptsFullPinyinCodes() {
        val activity = boot()
        val train = trainOf(activity)
        Prefs.simpleInput = true
        Prefs.codeMode = Prefs.CODE_FULL
        train.startCustom("你好")
        ShadowLooper.idleMainLooper()

        val edit = activity.findViewById<EditText>(R.id.etInput)
        val tv = activity.findViewById<TrainingTextView>(R.id.trainingText)

        edit.setText("n")
        ShadowLooper.idleMainLooper()
        assertEquals("第一字还没打完", 0, tv.progressFlags().count { it.toInt() == 1 })

        // 只敲编码、不选字，也应算完成
        edit.setText("nihao")
        ShadowLooper.idleMainLooper()
        assertEquals("两个字都应判定正确", 2, tv.progressFlags().count { it.toInt() == 1 })
        assertEquals("完成本段后输入框应清空", "", edit.text.toString())
        assertTrue("应写入成绩", StatsStore.all().isNotEmpty())
    }

    @Test
    fun simpleInputAcceptsNineKeyDigits() {
        val activity = boot()
        val train = trainOf(activity)
        Prefs.simpleInput = true
        Prefs.codeMode = Prefs.CODE_NINE
        train.startCustom("你好")
        ShadowLooper.idleMainLooper()

        val edit = activity.findViewById<EditText>(R.id.etInput)
        val tv = activity.findViewById<TrainingTextView>(R.id.trainingText)

        // ni -> 64, hao -> 426
        edit.setText("64426")
        ShadowLooper.idleMainLooper()
        assertEquals("九键数字也应判定正确", 2, tv.progressFlags().count { it.toInt() == 1 })
    }

    @Test
    fun simpleInputMarksWrongCode() {
        val activity = boot()
        val train = trainOf(activity)
        Prefs.simpleInput = true
        Prefs.codeMode = Prefs.CODE_FULL
        train.startCustom("你好")
        ShadowLooper.idleMainLooper()

        val edit = activity.findViewById<EditText>(R.id.etInput)
        val tv = activity.findViewById<TrainingTextView>(R.id.trainingText)

        // ni 正确，hao 敲成 hai
        edit.setText("nihai")
        ShadowLooper.idleMainLooper()
        assertEquals("第一字正确", 1, tv.progressFlags().count { it.toInt() == 1 })
        assertEquals("第二字错误", 1, tv.progressFlags().count { it.toInt() == 2 })
    }

    @Test
    fun simpleInputTrimsConsumedKeys() {
        val activity = boot()
        val train = trainOf(activity)
        Prefs.simpleInput = true
        Prefs.codeMode = Prefs.CODE_FULL
        train.startCustom("你好世界")
        ShadowLooper.idleMainLooper()

        val edit = activity.findViewById<EditText>(R.id.etInput)
        val tv = activity.findViewById<TrainingTextView>(R.id.trainingText)

        // 打完第一个字：已消费的 ni 应从输入框剪掉，不能留在框里累积
        edit.setText("ni")
        ShadowLooper.idleMainLooper()
        assertEquals("第一字应判定正确", 1, tv.progressFlags().count { it.toInt() == 1 })
        assertEquals("已消费的按键应被剪掉", "", edit.text.toString())

        edit.setText("hao")
        ShadowLooper.idleMainLooper()
        assertEquals("第二字应判定正确", 2, tv.progressFlags().count { it.toInt() == 1 })
        assertEquals("输入框不应越打越长", "", edit.text.toString())

        // 当前字只打一半：输入框只留这一半
        edit.setText("sh")
        ShadowLooper.idleMainLooper()
        assertEquals("未打完的字只显示当前输入", "sh", edit.text.toString())
    }

    @Test
    fun simpleInputAdvancesPastWrongCode() {
        val activity = boot()
        val train = trainOf(activity)
        Prefs.simpleInput = true
        Prefs.codeMode = Prefs.CODE_FULL
        Prefs.strictMode = false
        train.startCustom("你好世界")
        ShadowLooper.idleMainLooper()

        val edit = activity.findViewById<EditText>(R.id.etInput)
        val tv = activity.findViewById<TrainingTextView>(R.id.trainingText)

        // ni 对、xxx 错、shi 对：打错一个字不应把后面的输入全堵在框里
        edit.setText("nixxxshi")
        ShadowLooper.idleMainLooper()
        val flags = tv.progressFlags().map { it.toInt() }
        assertEquals("正确的字应判定正确 flags=$flags", 2, flags.count { it == 1 })
        assertEquals("错的字应判定错误 flags=$flags", 1, flags.count { it == 2 })
        assertEquals("打错后输入框仍应保持清空 flags=$flags", "", edit.text.toString())
    }

    @Test
    fun simpleStrictModeWaitsForCorrectCode() {
        val activity = boot()
        val train = trainOf(activity)
        Prefs.simpleInput = true
        Prefs.codeMode = Prefs.CODE_FULL
        Prefs.strictMode = true
        train.startCustom("你好世界")
        ShadowLooper.idleMainLooper()

        val edit = activity.findViewById<EditText>(R.id.etInput)
        val tv = activity.findViewById<TrainingTextView>(R.id.trainingText)

        // 第二字打错：严格模式下停在错字，丢弃这次输入等重打
        edit.setText("nihai")
        ShadowLooper.idleMainLooper()
        var flags = tv.progressFlags().map { it.toInt() }
        assertEquals("第一字正确 flags=$flags", 1, flags.count { it == 1 })
        assertEquals("第二字标红 flags=$flags", 1, flags.count { it == 2 })
        assertEquals("错字输入应被丢弃", "", edit.text.toString())

        // 重打正确的编码后继续推进，且输入框不累积
        edit.setText("haoshi")
        ShadowLooper.idleMainLooper()
        flags = tv.progressFlags().map { it.toInt() }
        assertEquals("重打后应推进到第三字 flags=$flags", 3, flags.count { it == 1 })
        assertEquals("输入框应保持清空 flags=$flags", "", edit.text.toString())
    }

    @Test
    fun imeDeleteKeepsProgressOnTop() {
        val activity = boot()
        val train = trainOf(activity)
        train.startCustom("你好世界")
        ShadowLooper.idleMainLooper()

        val edit = activity.findViewById<EditText>(R.id.etInput)
        val tv = activity.findViewById<TrainingTextView>(R.id.trainingText)
        val ic = edit.onCreateInputConnection(EditorInfo())!!
        ic.commitText("你", 1)
        ic.commitText("好", 1)
        ShadowLooper.idleMainLooper()
        assertEquals("你好", tv.typedText())

        // 输入法退格删掉一个字
        ic.deleteSurroundingText(1, 0)
        ShadowLooper.idleMainLooper()
        assertEquals("编辑框应少一个字", "你", edit.text.toString())
        assertEquals("输入法删除不应抹掉上方进度", "你好", tv.typedText())

        // 继续输入应继续向前推进，而不是从被删的位置重来
        ic.commitText("世", 1)
        ShadowLooper.idleMainLooper()
        assertEquals("继续输入应接在后面", "你好世", tv.typedText())
    }

    @Test
    fun backFromPracticeGoesHome() {        val activity = boot()
        val train = trainOf(activity)
        train.startCustom("你好")
        ShadowLooper.idleMainLooper()
        assertTrue(train.isPracticing())

        activity.onBackPressedDispatcher.onBackPressed()
        ShadowLooper.idleMainLooper()

        assertFalse("返回后应退出训练", train.isPracticing())
        assertEquals(
            "应回到首页课程列表",
            View.VISIBLE,
            activity.findViewById<View>(R.id.viewSelect).visibility
        )
    }

    @Test
    fun nineKeyEncodingIsCorrect() {
        assertEquals("64", NineKey.digits("nǐ"))
        assertEquals("426", NineKey.digits("hǎo"))
        assertEquals("948264", NineKey.digits("zhuāng"))
        assertEquals("8", NineKey.digits("ǚ"))
        assertEquals("v", NineKey.plain("ü"))
        assertEquals("64426", NineKey.digitsOfSyllables(listOf("nǐ", "hǎo")).replace(" ", ""))
        assertEquals("无声调也应正确", "426", NineKey.digits("hao"))
    }

    @Test
    fun codeModeSettingPersistsAndReflectsInSettings() {
        val activity = boot()
        Prefs.codeMode = Prefs.CODE_FULL
        val train = trainOf(activity)
        train.startCustom("你好")
        ShadowLooper.idleMainLooper()
        assertEquals("默认应为全键", Prefs.CODE_FULL, Prefs.codeMode)

        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.selectedItemId = R.id.nav_settings
        ShadowLooper.idleMainLooper()

        activity.findViewById<android.widget.RadioButton>(R.id.rbCodeNine).isChecked = true
        ShadowLooper.idleMainLooper()
        assertEquals("设置应保存为九键", Prefs.CODE_NINE, Prefs.codeMode)

        val hint = activity.findViewById<android.widget.TextView>(R.id.tvCodeHint).text.toString()
        assertTrue("提示应说明九键键位，实际：$hint", hint.contains("2 abc"))

        activity.findViewById<android.widget.RadioButton>(R.id.rbCodeFull).isChecked = true
        ShadowLooper.idleMainLooper()
        assertEquals("应能切回全键", Prefs.CODE_FULL, Prefs.codeMode)
    }

    @Test
    fun swipeSwitchesTabs() {
        val activity = boot()
        ShadowLooper.idleMainLooper()
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        assertEquals("起始应在训练页", R.id.nav_train, nav.selectedItemId)

        // 向左滑 -> 下一页
        swipe(activity, fromX = 300f, toX = 40f)
        ShadowLooper.idleMainLooper()
        assertEquals("左滑应进入随机字", R.id.nav_random, nav.selectedItemId)

        swipe(activity, fromX = 300f, toX = 40f)
        ShadowLooper.idleMainLooper()
        assertEquals("继续左滑应进入九键教学", R.id.nav_jiujian, nav.selectedItemId)

        swipe(activity, fromX = 300f, toX = 40f)
        ShadowLooper.idleMainLooper()
        assertEquals("继续左滑应进入设置", R.id.nav_settings, nav.selectedItemId)

        swipe(activity, fromX = 300f, toX = 40f)
        ShadowLooper.idleMainLooper()
        assertEquals("已在最后一页，左滑不应越界", R.id.nav_settings, nav.selectedItemId)

        // 向右滑 -> 上一页
        swipe(activity, fromX = 40f, toX = 300f)
        ShadowLooper.idleMainLooper()
        assertEquals("右滑应回到九键教学", R.id.nav_jiujian, nav.selectedItemId)
    }

    /** 九键教学页：键盘 12 格、对照非空，点「去九键练习」进入九键训练 */
    @Test
    fun jiujianTabShowsTeachingAndStartsPractice() {
        val activity = boot()
        ShadowLooper.idleMainLooper()
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.selectedItemId = R.id.nav_jiujian
        ShadowLooper.idleMainLooper()

        assertNotNull(
            "九键教学页未挂载",
            activity.supportFragmentManager.fragments.filterIsInstance<JiuJianFragment>().firstOrNull()
        )
        assertEquals(
            "九键键盘应有 12 格",
            12,
            activity.findViewById<GridLayout>(R.id.gridKeys).childCount
        )
        assertEquals(
            "九键游戏键盘应有 10 格（1-9 + 删除）",
            10,
            activity.findViewById<GridLayout>(R.id.gridGame).childCount
        )
        assertTrue(
            "九键游戏应已出题",
            activity.findViewById<TextView>(R.id.tvTarget).text.isNotEmpty()
        )

        activity.findViewById<Button>(R.id.btnPractice).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals("应切到训练页", R.id.nav_train, nav.selectedItemId)
        assertEquals("应切换为九键模式", Prefs.CODE_NINE, Prefs.codeMode)
        assertTrue("应已进入练习", trainOf(activity).isPracticing())
    }

    /** 九键游戏：按出正确编码应 +1 分并自动进入下一题 */
    @Test
    fun jiuJianGameScoresOnCorrectInput() {
        val activity = boot()
        ShadowLooper.idleMainLooper()
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.selectedItemId = R.id.nav_jiujian
        ShadowLooper.idleMainLooper()

        val grid = activity.findViewById<GridLayout>(R.id.gridGame)
        val target = activity.findViewById<TextView>(R.id.tvTarget).text.toString()
        assertTrue("游戏应已出题", target.isNotEmpty())
        // 每字一段编码，字间用空格分开（编码里不含 1），与 Fragment 逻辑一致
        val perChar = target.map { ch ->
            PinyinDict.annotate(ch.toString()).joinToString("") { NineKey.digits(it) }
        }
        val answer = perChar.joinToString("")
        assertTrue("题目应有九键编码", answer.isNotEmpty())
        assertTrue(
            "题目应显示数字编码",
            activity.findViewById<TextView>(R.id.tvCode).text.toString().isNotEmpty()
        )

        val dials = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
        for (d in answer) {
            val idx = dials.indexOf(d.toString())
            (grid.getChildAt(idx) as View).performClick()
            ShadowLooper.idleMainLooper()
        }
        ShadowLooper.idleMainLooper()
        val scoreText = activity.findViewById<TextView>(R.id.tvScore).text.toString()
        assertTrue("答对一题后得分应含 1：$scoreText", scoreText.contains("1"))
    }

    /** 九键游戏：按错一位会被判错并清空，分数不变 */
    @Test
    fun jiuJianGameWrongInputResets() {
        val activity = boot()
        ShadowLooper.idleMainLooper()
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.selectedItemId = R.id.nav_jiujian
        ShadowLooper.idleMainLooper()

        val grid = activity.findViewById<GridLayout>(R.id.gridGame)
        val target = activity.findViewById<TextView>(R.id.tvTarget).text.toString()
        val perChar = target.map { ch ->
            PinyinDict.annotate(ch.toString()).joinToString("") { NineKey.digits(it) }
        }
        val answer = perChar.joinToString("")
        assertTrue("题目应有九键编码", answer.isNotEmpty())

        val dials = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
        // 故意按一个与首字符不同的键
        val wrong = dials.first { it != "1" && it != answer[0].toString() }
        (grid.getChildAt(dials.indexOf(wrong)) as View).performClick()
        ShadowSystemClock.advanceBy(700, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper() // 推进 650ms 的清空延时
        val scoreText = activity.findViewById<TextView>(R.id.tvScore).text.toString()
        assertTrue("答错不应加分：$scoreText", !scoreText.contains("1"))
        val inputText = activity.findViewById<TextView>(R.id.tvInput).text.toString()
        assertTrue("错误后应清空输入（不再含错误数字）：$inputText", !inputText.contains(wrong))
    }

    /** 九键游戏：「缩小」按钮可折叠/展开游戏内容区，仅保留标题与得分 */
    @Test
    fun jiuJianGameShrinkToggle() {
        val activity = boot()
        ShadowLooper.idleMainLooper()
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.selectedItemId = R.id.nav_jiujian
        ShadowLooper.idleMainLooper()

        val body = activity.findViewById<View>(R.id.layoutGameBody)
        val btn = activity.findViewById<Button>(R.id.btnShrink)
        assertEquals("初始应展开", View.VISIBLE, body.visibility)
        assertEquals("初始按钮应为“缩小”", activity.getString(R.string.jiujian_game_shrink), btn.text)

        btn.performClick()
        ShadowLooper.idleMainLooper()
        assertEquals("点击后应折叠", View.GONE, body.visibility)
        assertEquals("折叠后按钮应为“展开”", activity.getString(R.string.jiujian_game_expand), btn.text)

        btn.performClick()
        ShadowLooper.idleMainLooper()
        assertEquals("再次点击应展开", View.VISIBLE, body.visibility)
        assertEquals("展开后按钮应为“缩小”", activity.getString(R.string.jiujian_game_shrink), btn.text)
    }

    /** 九键模式下：数字在上、完整拼音紧贴其下，行高相应撑开；全拼模式仍只有一行 */
    @Test
    fun nineKeyShowsFullPinyinUnderDigits() {
        val activity = boot()
        val tv = activity.findViewById<TrainingTextView>(R.id.trainingText)

        tv.setContent("你好", listOf("nǐ", "hǎo"))
        tv.setShowPinyin(true)
        tv.setCharSizeSp(24f)

        tv.setCodeMode(Prefs.CODE_FULL)
        measure(tv)
        val fullHeight = tv.rowHeight()
        assertEquals("全拼只标一行", 1, tv.annotationLines())

        tv.setCodeMode(Prefs.CODE_NINE)
        measure(tv)
        val nineHeight = tv.rowHeight()
        assertEquals("九键应标两行：数字 + 完整拼音", 2, tv.annotationLines())
        assertTrue(
            "九键行高应明显大于全拼（实际 $nineHeight vs $fullHeight）",
            nineHeight > fullHeight * 1.15f
        )

        tv.setShowPinyin(false)
        measure(tv)
        assertEquals("关闭拼音后不标注", 0, tv.annotationLines())
        assertTrue("关闭拼音后行高应回落", tv.rowHeight() < fullHeight)

        assertEquals("显示方式不影响训练文本", "你好", tv.targetText())
    }

    /** 触发一次测量，让行高/字格布局生效 */
    private fun measure(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
    }

    /** 点字朗读：读这个词而不是孤立单字，多音字才读得对 */
    @Test
    fun tappingCharSpeaksWord() {
        val activity = boot()
        val train = trainOf(activity)
        Prefs.speakOnTap = true
        train.startCustom("银行 取钱")
        ShadowLooper.idleMainLooper()

        val spoken = ArrayList<String>()
        Speech.speaker = { text, _ -> spoken.add(text) }
        try {
            val tv = activity.findViewById<TrainingTextView>(R.id.trainingText)
            val p = pointOf(tv, 1) // "行"，应连成「银行」一起读
            tap(tv, p.first, p.second)
            ShadowLooper.idleMainLooper()
            assertEquals("点字应读它所在的词", listOf("银行"), spoken)

            val q = pointOf(tv, 3) // "取"，应连成「取钱」
            tap(tv, q.first, q.second)
            ShadowLooper.idleMainLooper()
            assertEquals("词边界应重新取词", listOf("银行", "取钱"), spoken)
        } finally {
            Speech.speaker = null
        }
    }

    /** 关闭「点击汉字发音」后点字不再朗读 */
    @Test
    fun tappingCharSilentWhenDisabled() {
        val activity = boot()
        val train = trainOf(activity)
        Prefs.speakOnTap = false
        train.startCustom("你好")
        ShadowLooper.idleMainLooper()

        val spoken = ArrayList<String>()
        Speech.speaker = { text, _ -> spoken.add(text) }
        try {
            val tv = activity.findViewById<TrainingTextView>(R.id.trainingText)
            val p = pointOf(tv, 0)
            tap(tv, p.first, p.second)
            ShadowLooper.idleMainLooper()
            assertTrue("关闭开关后不应朗读", spoken.isEmpty())
        } finally {
            Speech.speaker = null
            Prefs.speakOnTap = true
        }
    }

    /** 喇叭按钮朗读整段，且不带分隔空格 */
    @Test
    fun speakButtonReadsWholeSegment() {
        val activity = boot()
        val train = trainOf(activity)
        train.startCustom("你好 世界")
        ShadowLooper.idleMainLooper()

        val spoken = ArrayList<String>()
        Speech.speaker = { text, _ -> spoken.add(text) }
        try {
            activity.findViewById<ImageButton>(R.id.btnSpeak).performClick()
            ShadowLooper.idleMainLooper()
            assertEquals("应朗读整段", listOf("你好世界"), spoken)
        } finally {
            Speech.speaker = null
        }
    }

    /** 找到某个目标下标在控件里的可点击坐标（先按 1080 宽测量布局） */
    private fun pointOf(tv: TrainingTextView, index: Int): Pair<Float, Float> {
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        tv.layout(0, 0, tv.measuredWidth, tv.measuredHeight)
        for (y in 4 until tv.height step 4) {
            for (x in 4 until tv.width step 4) {
                if (tv.charIndexAt(x.toFloat(), y.toFloat()) == index) {
                    return x.toFloat() to y.toFloat()
                }
            }
        }
        throw AssertionError("找不到下标 $index 对应的位置")
    }

    private fun tap(view: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
    }

    /** 训练区跟随滚动：光标顶到底部才滚，且每次按单行的整数倍上移 */
    @Test
    fun scrollTargetStepsOneRow() {
        val rowH = 60f
        val viewH = 300 // 可视 5 行
        assertEquals("光标还在可视区内不滚", -1, nextScrollTarget(240f, rowH, viewH, 0))
        assertEquals("刚溢出第 6 行：上移一行", 60, nextScrollTarget(300f, rowH, viewH, 0))
        assertEquals("溢出 1.5 行：上移两行（单行倍数）", 120, nextScrollTarget(330f, rowH, viewH, 0))
        assertEquals("溢出三行：取三行", 180, nextScrollTarget(420f, rowH, viewH, 0))
        assertEquals("已翻到更下面时不回拉", -1, nextScrollTarget(300f, rowH, viewH, 400))
        assertEquals("尺寸未就绪时不滚", -1, nextScrollTarget(300f, 0f, 0, 0))
        // 基于「已滚动位置」判定：光标刚进底部时，溢出一行只加一行（不跳过头）
        assertEquals("已滚到光标贴底：再溢出一行只加一行", 460, nextScrollTarget(650f, rowH, viewH, 400))
        // 连续两次溢出，各自只增一行，单调累加
        assertEquals("连续逐行：第二次仍只加一行", 520, nextScrollTarget(710f, rowH, viewH, 460))
    }

    /** 点字时把音节序列一并交给发音层，内置音节包才能按注音连读 */
    @Test
    fun tappingCharSendsSyllables() {
        val activity = boot()
        val train = trainOf(activity)
        train.startCustom("银行 取钱")
        ShadowLooper.idleMainLooper()

        var spokenText: String? = null
        var spokenSyllables: List<String>? = null
        Speech.speaker = { text, syl ->
            spokenText = text
            spokenSyllables = syl
        }
        try {
            val tv = activity.findViewById<TrainingTextView>(R.id.trainingText)
            val p = pointOf(tv, 1) // "行"
            tap(tv, p.first, p.second)
            ShadowLooper.idleMainLooper()
            assertEquals("应读整个词", "银行", spokenText)
            assertEquals("音节要与词对齐", PinyinDict.annotate("银行"), spokenSyllables)
        } finally {
            Speech.speaker = null
        }
    }

    /** 内置发音包在资源缺失/播放失败时不能崩，且能正常停止 */
    @Test
    fun syllableVoiceHandlesMissingAudio() {
        val app = ApplicationProvider.getApplicationContext<App>()
        assertTrue("内置发音包应可用", SyllableVoice.available(app))
        SyllableVoice.speak(app, listOf("nǐ", "hǎo"))
        SyllableVoice.awaitIdle()
        SyllableVoice.stop()
        SyllableVoice.awaitIdle()
        // 不存在的音节直接忽略，不抛异常
        SyllableVoice.speak(app, listOf("zzz9"))
        SyllableVoice.awaitIdle()
        SyllableVoice.stop()
        SyllableVoice.awaitIdle()
        assertFalse("停止后不应仍在播放", SyllableVoice.isSpeaking())
    }

    /** 模拟一次快速横向滑动（Robolectric 下走 Activity.dispatchTouchEvent 全链路） */
    private fun swipe(activity: MainActivity, fromX: Float, toX: Float, y: Float = 200f) {
        val t0 = SystemClock.uptimeMillis()
        fun e(action: Int, x: Float, dt: Long): MotionEvent =
            MotionEvent.obtain(t0, t0 + dt, action, x, y, 0)

        activity.dispatchTouchEvent(e(MotionEvent.ACTION_DOWN, fromX, 0))
        for (i in 1..5) {
            val x = fromX + (toX - fromX) * i / 5f
            activity.dispatchTouchEvent(e(MotionEvent.ACTION_MOVE, x, i * 10L))
        }
        activity.dispatchTouchEvent(e(MotionEvent.ACTION_UP, toX, 60))
    }

    /** 带空格文本点字：高亮下标(flat)与朗读回调(目标下标)必须一致，不能错一位 */
    @Test
    fun tapFlashAlignsWithFlatIndexForSpacedText() {
        val ctx = ApplicationProvider.getApplicationContext<App>()
        PinyinDict.load(ctx)
        val tv = TrainingTextView(ctx)
        tv.setShowPinyin(true)
        tv.setCodeMode(Prefs.CODE_NINE)
        tv.setCharSizeSp(40f)
        // 第 6 字前插空格：参商吹捧朋(0..5) 空格(6) 友(7)...
        val text = "参商吹捧朋 友人们是我们"
        tv.setContent(text, PinyinDict.annotate(text))

        val p = pointOf(tv, 7) // 「友」目标下标 7
        val tapped = mutableListOf<Int>()
        tv.onCharTap = { tapped.add(it) }
        tap(tv, p.first, p.second)

        assertEquals("朗读回调应收到目标下标", 7, tapped.single())
        val flatIndex = tv.javaClass.getDeclaredField("flatIndex").apply { isAccessible = true }
            .get(tv) as IntArray
        assertEquals("第7字符去空格下标应为6", 6, flatIndex[7])
        val flash = tv.javaClass.getDeclaredField("flashIndex").apply { isAccessible = true }
            .get(tv) as Int
        assertEquals("flashIndex 应等于该字去空格下标", 6, flash)
    }
}
