package com.pinyin.typing

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.pinyin.typing.databinding.FragmentJiujianBinding

/**
 * 九键教学页：键盘对应、打法要点，以及一个「九键游戏」小游戏——
 * 随机出题，玩家用九宫格拼出词的九键编码，逐位判定并计分，边玩边记键位。
 */
class JiuJianFragment : Fragment(R.layout.fragment_jiujian) {

    private var _b: FragmentJiujianBinding? = null
    private val b get() = _b!!

    private val handler = Handler(Looper.getMainLooper())

    private var target = ""
    private var answer = ""
    private var input = ""
    private var score = 0
    private var locked = false   // 答对/答错后的短暂锁定期
    private var howExpanded = false   // 「九键怎么打」是否展开，默认收起

    private val keys = listOf(
        "1" to "分词", "2" to "abc", "3" to "def",
        "4" to "ghi", "5" to "jkl", "6" to "mno",
        "7" to "pqrs", "8" to "tuv", "9" to "wxyz",
        "*" to "符号", "0" to "空格", "#" to "换行"
    )

    private val gameDial = listOf(
        "1" to "", "2" to "abc", "3" to "def",
        "4" to "ghi", "5" to "jkl", "6" to "mno",
        "7" to "pqrs", "8" to "tuv", "9" to "wxyz"
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentJiujianBinding.bind(view)
        buildKeys()
        buildGameKeys()
        nextQuestion()
        b.btnShrink.setOnClickListener { toggleGameBody() }
        b.btnHow.setOnClickListener { setHowExpanded(!howExpanded) }
        setHowExpanded(savedInstanceState?.getBoolean(KEY_HOW) == true)
        b.btnPractice.setOnClickListener {
            (activity as? MainActivity)?.startJiuJianPractice()
        }
    }

    /** 「九键怎么打」说明区：默认收起，仅显示标题行 */
    private fun setHowExpanded(expanded: Boolean) {
        howExpanded = expanded
        b.layoutHowBody.visibility = if (expanded) View.VISIBLE else View.GONE
        b.btnHow.text = getString(
            if (expanded) R.string.jiujian_how_collapse else R.string.jiujian_how_expand
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_HOW, howExpanded)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---- 顶部键盘示意（静态） ----
    private fun buildKeys() {
        val grid = b.gridKeys
        grid.removeAllViews()
        val ctx = requireContext()
        for ((digit, letters) in keys) {
            grid.addView(staticKeyCell(ctx, digit, letters))
        }
    }

    private fun staticKeyCell(ctx: Context, digit: String, letters: String): View {
        // 输入法风格：字母大写居中为主，数字小字标在右上角
        val isLetterKey = letters.matches(Regex("[a-z]+"))
        val cell = FrameLayout(ctx).apply {
            minimumHeight = dp(52)
            setBackgroundResource(R.drawable.bg_key)
        }
        cell.addView(
            TextView(ctx).apply {
                text = if (isLetterKey) letters.uppercase() else letters
                textSize = if (isLetterKey) 16f else 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        )
        cell.addView(
            TextView(ctx).apply {
                text = digit
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(ctx, R.color.brand))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.TOP
                ).apply { setMargins(0, dp(3), dp(7), 0) }
            }
        )
        cell.layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(3), dp(3), dp(3), dp(3))
        }
        return cell
    }

    // ---- 游戏键盘（可点击） ----
    private fun buildGameKeys() {
        val grid = b.gridGame
        grid.removeAllViews()
        val ctx = requireContext()
        for ((d, l) in gameDial) {
            grid.addView(gameKeyCell(ctx, d, l) { pressDigit(d[0]) })
        }
        grid.addView(gameKeyCell(ctx, "⌫", getString(R.string.jiujian_game_del)) { pressDelete() })
    }

    private fun gameKeyCell(ctx: Context, digit: String, letters: String, onClick: () -> Unit): View {
        // 游戏键盘：整体缩小，但数字和字母加大更醒目
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(38)
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_key)
            setOnClickListener { onClick() }
        }
        cell.addView(
            TextView(ctx).apply {
                text = digit
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            }
        )
        if (letters.isNotEmpty()) {
            cell.addView(
                TextView(ctx).apply {
                    text = letters.uppercase()
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_hint))
                }
            )
        }
        cell.layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
        return cell
    }

    // ---- 游戏逻辑 ----
    private fun nextQuestion() {
        locked = false
        input = ""
        if (!PinyinDict.isReady()) runCatching { PinyinDict.load(requireContext()) }
        val pool = PinyinDict.commonChars()
        if (pool.isEmpty()) {
            target = "字"
            answer = ""
            b.tvTarget.text = target
            b.tvPinyin.text = ""
            b.tvCode.text = ""
            updateInputView()
            return
        }
        // 随机 1~2 字，偏向单字让节奏更轻快
        val n = if (kotlin.random.Random.nextBoolean() && kotlin.random.Random.nextBoolean()) 2 else 1
        val picks = (1..n).map { pool.random() }
        target = picks.joinToString("") { it.ch.toString() }
        // 每字一段编码，多字词之间用空格分隔（不显示、也不打出分词符 1）
        val perChar = picks.map {
            runCatching { PinyinDict.annotate(it.ch.toString()) }.getOrDefault(emptyList())
                .joinToString("") { s -> NineKey.digits(s) }
        }
        answer = perChar.joinToString("")
        val sylls = picks.flatMap {
            runCatching { PinyinDict.annotate(it.ch.toString()) }.getOrDefault(emptyList())
        }
        b.tvTarget.text = target
        b.tvPinyin.text = sylls.joinToString(" ")
        b.tvCode.text = perChar.joinToString(" ")
        b.tvTip.text = getString(R.string.jiujian_game_tip)
        b.tvScore.text = getString(R.string.jiujian_score_fmt, score)
        updateInputView()
    }

    private fun pressDigit(d: Char) {
        if (locked || answer.isEmpty()) return
        // 1 键仅作九宫格展示（和输入法一致），编码里不含 1，按下不参与判定
        if (d == '1') return
        if (input.length >= answer.length) return
        val pos = input.length
        input += d
        if (input[pos] != answer[pos]) {
            // 按错：本位置红、提示正确编码，短暂锁定后清空重来
            locked = true
            b.tvTip.text = getString(R.string.jiujian_game_wrong, answer)
            updateInputView()
            handler.postDelayed({
                input = ""
                locked = false
                b.tvTip.text = getString(R.string.jiujian_game_tip)
                updateInputView()
            }, 650)
            return
        }
        updateInputView()
        if (input.length == answer.length) {
            locked = true
            score++
            b.tvScore.text = getString(R.string.jiujian_score_fmt, score)
            b.tvTip.text = getString(R.string.jiujian_game_right)
            handler.postDelayed({ nextQuestion() }, 700)
        }
    }

    private fun pressDelete() {
        if (locked || input.isEmpty()) return
        input = input.dropLast(1)
        updateInputView()
    }

    /** 折叠/展开九键游戏内容区，仅保留标题与得分 */
    private fun toggleGameBody() {
        val collapsed = b.layoutGameBody.visibility == View.GONE
        b.layoutGameBody.visibility = if (collapsed) View.VISIBLE else View.GONE
        b.btnShrink.text = getString(
            if (collapsed) R.string.jiujian_game_shrink else R.string.jiujian_game_expand
        )
    }

    /** 把已输入位逐位着色：对=绿、错=红、未输入=灰点占位 */
    private fun updateInputView() {
        val ctx = requireContext()
        val ok = ContextCompat.getColor(ctx, R.color.typing_correct)
        val bad = ContextCompat.getColor(ctx, R.color.typing_wrong)
        val hint = ContextCompat.getColor(ctx, R.color.text_hint)
        val sb = SpannableStringBuilder()
        for (i in answer.indices) {
            val ch: Char
            val color: Int
            if (i < input.length) {
                ch = input[i]
                color = if (input[i] == answer[i]) ok else bad
            } else {
                ch = '·'
                color = hint
            }
            val start = sb.length
            sb.append(ch)
            sb.setSpan(
                ForegroundColorSpan(color),
                start,
                start + 1,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        b.tvInput.text = sb
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        _b = null
        super.onDestroyView()
    }

    companion object {
        private const val KEY_HOW = "how_expanded"
    }
}
