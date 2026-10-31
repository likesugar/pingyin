package com.pinyin.typing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Arrays
import kotlin.math.max

/**
 * 训练文本展示控件：在每个汉字上方标注拼音（或九键编码），按输入进度着色。
 * 目标文本中的空格作为分隔符，不参与输入比对。
 *
 * 进度统一用「每个字的状态 + 光标位置」表达：
 * 汉字模式由 setTyped 反推状态，简易编码模式由 setProgress 直接给定。
 */
class TrainingTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paintChar = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val paintPy = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    // 九键模式下数字下方的完整拼音，字号略小于数字
    private val paintPyFull = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var target: String = ""
    private var syllables: List<String> = emptyList()
    private var flatIndex = IntArray(0)      // 目标字符下标 -> 去空格后的下标（-1 表示空格）
    private var flatCount = 0

    /** 每个字的状态：0 未输入、1 正确、2 错误 */
    private var flags = ByteArray(0)
    private var cursorIndex = 0
    private var typed: String = ""

    private var showPinyin = true
    private var codeMode = Prefs.CODE_FULL
    private var charSizePx = 38f
    private var pySizePx = 14f

    /** 点击某个字时回调（参数为目标文本下标），未设置则不响应点击 */
    var onCharTap: ((Int) -> Unit)? = null

    /** 点击瞬时高亮的字下标，-1 表示无 */
    private var flashIndex = -1
    private val flashClear = Runnable {
        flashIndex = -1
        invalidate()
    }

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val hit = tapAt(e.x, e.y)
            return hit >= 0
        }
    })

    private val cells = ArrayList<Cell>()
    private var rowHeight = 0f
    private var contentRows = 0

    private class Cell(
        val index: Int,
        val x: Float,
        val y: Float,
        val width: Float
    )

    private val colorPending = context.getColor(R.color.typing_pending)
    private val colorCorrect = context.getColor(R.color.typing_correct)
    private val colorWrong = context.getColor(R.color.typing_wrong)
    private val colorBrand = context.getColor(R.color.brand)

    /** 当前训练文本（原样，含分隔空格），供测试与无障碍描述使用 */
    fun targetText(): String = target

    /** 与训练文本一一对应的音节表（空格位为空串） */
    fun targetSyllables(): List<String> = syllables

    fun setContent(text: String, syllables: List<String>) {
        this.target = text
        this.syllables = syllables
        flatIndex = IntArray(text.length)
        var flat = 0
        for (i in text.indices) {
            flatIndex[i] = if (text[i] == ' ') -1 else flat++
        }
        flatCount = flat
        flags = ByteArray(flatCount)
        cursorIndex = 0
        typed = ""
        requestLayout()
        invalidate()
    }

    /** 汉字模式：按已上屏的汉字串推进 */
    fun setTyped(s: String) {
        if (s == typed) return
        typed = s
        rebuildFlagsFromTyped()
        invalidate()
    }

    /** 编码模式：直接给定每个字的状态与光标 */
    fun setProgress(states: ByteArray, cursor: Int) {
        flags = states
        cursorIndex = cursor.coerceIn(0, flatCount)
        typed = ""
        invalidate()
    }

    fun setShowPinyin(show: Boolean) {
        if (show == showPinyin) return
        showPinyin = show
        requestLayout()
        invalidate()
    }

    fun setCharSizeSp(sp: Float) {
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics
        )
        if (px == charSizePx) return
        charSizePx = px
        pySizePx = px * 0.36f
        requestLayout()
        invalidate()
    }

    /** 编码方式：Prefs.CODE_FULL 显示全拼，Prefs.CODE_NINE 显示九键数字 */
    fun setCodeMode(mode: Int) {
        if (mode == codeMode) return
        codeMode = mode
        requestLayout()
        invalidate()
    }

    /** 当前已打出（显示在上方）的内容，供逻辑/测试读取 */
    fun typedText(): String = typed

    /** 每个字上方标注的行数：0 关闭、1 全拼、2 九键数字 + 完整拼音 */
    fun annotationLines(): Int = labelLines()

    /** 每个字的状态副本，供逻辑/测试读取 */
    fun progressFlags(): ByteArray = flags.copyOf()

    fun cursor(): Int = cursorIndex

    private fun displayOf(syllable: String): String =
        if (codeMode == Prefs.CODE_NINE) NineKey.digits(syllable) else syllable

    /** 标注行数：0 不显示、1 全拼、2 九键（数字 + 完整拼音） */
    private fun labelLines(): Int = when {
        !showPinyin -> 0
        codeMode == Prefs.CODE_NINE -> 2
        else -> 1
    }

    private fun shadowSizePx(): Float = pySizePx * 0.98f

    /** 标注区总高度（不含汉字本身） */
    private fun labelHeight(): Float = when (labelLines()) {
        2 -> pySizePx * 3.5f
        1 -> pySizePx * 1.9f
        else -> 0f
    }

    /** 汉字模式：把已输入汉字与目标逐字比对，得到状态表 */
    private fun rebuildFlagsFromTyped() {
        if (flags.size != flatCount) flags = ByteArray(flatCount)
        Arrays.fill(flags, 0)
        val n = typed.length
        var flat = 0
        for (i in target.indices) {
            if (target[i] == ' ') continue
            if (flat < n) flags[flat] = if (typed[flat] == target[i]) 1 else 2
            flat++
        }
        cursorIndex = n.coerceIn(0, flatCount)
    }

    /** 当前光标所在行的顶部 Y（用于自动滚动）；光标在末尾时取最后一行的位置 */
    fun cursorTop(): Float {
        val cell = cells.firstOrNull { flatIndex[it.index] == cursorIndex } ?: cells.lastOrNull()
        return cell?.y ?: 0f
    }

    /** 命中测试：返回坐标所在字在目标文本里的下标（含空格占位），未命中返回 -1 */
    fun charIndexAt(x: Float, y: Float): Int = cellAt(x, y)?.index ?: -1

    /** 返回坐标命中的单元格（含空格占位），未命中返回 null */
    private fun cellAt(x: Float, y: Float): Cell? {
        for (cell in cells) {
            if (x >= cell.x && x <= cell.x + cell.width && y >= cell.y && y <= cell.y + rowHeight) {
                return cell
            }
        }
        return null
    }

    /**
     * 点字反馈：点亮命中字并触发朗读回调。
     * 高亮用「去空格下标」(flat)，与 onDraw 的着色判定一致；
     * 朗读回调传「目标文本下标」，供上层按原文本取字。
     * 返回目标文本下标，未命中返回 -1。
     */
    fun tapAt(x: Float, y: Float): Int {
        val cell = cellAt(x, y) ?: return -1
        flashIndex = flatIndex[cell.index]
        invalidate()
        removeCallbacks(flashClear)
        postDelayed(flashClear, 180L)
        onCharTap?.invoke(cell.index)
        return cell.index
    }

    /**
     * 点击即定位到字（用于点字发音）。DOWN 时被消费但父容器仍可在滑动超过阈值时
     * 拦截并接管，滚动不受影响；滑动过程中会收到 CANCEL，不会误触发点击。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onCharTap == null) return super.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        return true
    }

    fun rowHeight(): Float = rowHeight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        layoutCells(contentWidth)
        val height = (paddingTop + paddingBottom + rowHeight * contentRows).toInt()
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutCells((w - paddingLeft - paddingRight).coerceAtLeast(0))
    }

    private fun layoutCells(contentWidth: Int) {
        cells.clear()
        paintChar.textSize = charSizePx
        paintPy.textSize = pySizePx
        paintPyFull.textSize = shadowSizePx()
        rowHeight = charSizePx * 1.18f + labelHeight()
        if (target.isEmpty() || contentWidth <= 0) {
            contentRows = 0
            return
        }
        val right = paddingLeft + contentWidth
        var x = paddingLeft.toFloat()
        var y = paddingTop.toFloat()
        var row = 0
        for (i in target.indices) {
            if (target[i] == ' ') {
                x += charSizePx * 0.45f
                continue
            }
            val w = max(charSizePx, labelWidthOf(syllables.getOrNull(i).orEmpty()) + 6f) + 4f
            if (x + w > right && x > paddingLeft) {
                x = paddingLeft.toFloat()
                y += rowHeight
                row++
            }
            cells.add(Cell(i, x, y, w))
            x += w
        }
        contentRows = row + 1
    }

    /** 标注行里最宽的一行（九键时数字与完整拼音取较宽者），用于计算字格宽度 */
    private fun labelWidthOf(syllable: String): Float {
        if (!showPinyin) return 0f
        return when (labelLines()) {
            2 -> {
                val digits = NineKey.digits(syllable)
                val full = syllable
                if (digits.isEmpty()) paintPyFull.measureText(full)
                else max(paintPy.measureText(digits), paintPyFull.measureText(full))
            }
            else -> paintPy.measureText(displayOf(syllable))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cells.isEmpty()) return
        paintChar.textSize = charSizePx
        paintPy.textSize = pySizePx
        paintPyFull.textSize = shadowSizePx()
        val lines = labelLines()
        val charBaselineOffset = labelHeight() + charSizePx * 0.92f
        val pyBaselineOffset = pySizePx * 1.05f
        // 第二行（完整拼音）基线：紧贴第一行数字下方
        val fullBaselineOffset = pySizePx * 1.95f + shadowSizePx() * 1.0f

        for (cell in cells) {
            val i = cell.index
            val ch = target[i]
            val fi = flatIndex[i]
            val state = when {
                fi < 0 -> State.SPACER
                fi < flags.size && flags[fi].toInt() == 1 -> State.CORRECT
                fi < flags.size && flags[fi].toInt() == 2 -> State.WRONG
                fi == cursorIndex -> State.CURSOR
                else -> State.PENDING
            }
            val cx = cell.x + cell.width / 2f
            val top = cell.y

            // 背景（点击反馈 / 当前光标 / 错误）
            if (fi >= 0 && fi == flashIndex) {
                paintBg.color = colorBrand
                paintBg.alpha = 60
                rect.set(cell.x, top + 2f, cell.x + cell.width, top + rowHeight - 4f)
                canvas.drawRoundRect(rect, 6f, 6f, paintBg)
            }
            when (state) {
                State.CURSOR -> {
                    paintBg.color = colorBrand
                    paintBg.alpha = 70
                    rect.set(cell.x, top + 2f, cell.x + cell.width, top + rowHeight - 4f)
                    canvas.drawRoundRect(rect, 6f, 6f, paintBg)
                }
                State.WRONG -> {
                    paintBg.color = colorWrong
                    paintBg.alpha = 45
                    rect.set(cell.x, top + 2f, cell.x + cell.width, top + rowHeight - 4f)
                    canvas.drawRoundRect(rect, 6f, 6f, paintBg)
                }
                else -> Unit
            }

            val color = when (state) {
                State.CORRECT -> colorCorrect
                State.WRONG -> colorWrong
                State.PENDING -> colorPending
                else -> colorCorrect
            }

            if (showPinyin) {
                val syl = syllables.getOrNull(i).orEmpty()
                if (lines == 2) {
                    // 第一行：九键数字（主要输入目标）
                    val digits = NineKey.digits(syl)
                    if (digits.isNotEmpty()) {
                        paintPy.color = color
                        paintPy.alpha = if (state == State.PENDING) 190 else 235
                        canvas.drawText(digits, cx, top + pyBaselineOffset, paintPy)
                    }
                    // 第二行：完整拼音（辅助提示）
                    if (syl.isNotEmpty()) {
                        paintPyFull.color = color
                        paintPyFull.alpha = if (state == State.PENDING) 140 else 190
                        canvas.drawText(syl, cx, top + fullBaselineOffset, paintPyFull)
                    }
                } else {
                    val py = displayOf(syl)
                    if (py.isNotEmpty()) {
                        paintPy.color = color
                        paintPy.alpha = if (state == State.PENDING) 190 else 235
                        canvas.drawText(py, cx, top + pyBaselineOffset, paintPy)
                    }
                }
            }

            paintChar.color = color
            if (state == State.PENDING) paintChar.alpha = 255
            canvas.drawText(ch.toString(), cx, top + charBaselineOffset, paintChar)
        }
    }

    private enum class State { PENDING, CORRECT, WRONG, CURSOR, SPACER }
}
