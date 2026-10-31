package com.pinyin.typing

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pinyin.typing.databinding.FragmentTrainBinding
import com.pinyin.typing.databinding.ItemLessonBinding

class TrainFragment : Fragment(R.layout.fragment_train) {

    private var _b: FragmentTrainBinding? = null
    private val b get() = _b!!

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false

    private var groups: List<Group> = emptyList()
    private var groupIndex = 0
    private var lesson: Lesson? = null
    /** 当前课所属的专项组，为空表示不在专项里（随机字 / 自定义文本） */
    private var currentGroup: Group? = null
    private var segmentIndex = 0
    private var segment: Segment? = null

    private val typed = StringBuilder()
    private var segCommitted = 0
    private var segErrors = 0
    private var doneChars = 0
    private var doneCommitted = 0
    private var doneErrors = 0

    /** 简易输入模式：直接敲编码，每个字对应一段编码 */
    private var codes: List<String> = emptyList()
    private var simpleFlags = ByteArray(0)
    /** 已判定完成的字数（下一个待打的字下标） */
    private var pendingScrollY = -1
    private var codePos = 0
    /** 本段累计错误次数（重打正确后不清零） */
    private var simpleErrorCount = 0
    /** 正在裁剪输入框，抑制 TextWatcher 回调，避免递归 */
    private var trimmingCode = false
    /** 当前训练已应用的编码方式，用于发现设置变更后重建编码 */
    private var appliedCodeMode = -1

    private var lessonStartMs = 0L
    private var segStartMs = 0L
    private var started = false
    private var finishing = false

    private val ticker = object : Runnable {
        override fun run() {
            updateStats()
            if (ticking) handler.postDelayed(this, 200)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentTrainBinding.bind(view)

        b.recyclerLessons.layoutManager = LinearLayoutManager(requireContext())
        b.btnBack.setOnClickListener { exitPractice() }
        b.btnRestart.setOnClickListener { restartWithRandom() }
        b.btnSkip.setOnClickListener { nextSegment(skipped = true) }
        // 输入框右侧删除按钮：点一下删一个字，长按清空
        b.btnDelete.setOnClickListener { deleteLast() }
        b.btnDelete.setOnLongClickListener {
            clearInput()
            true
        }
        // 朗读：喇叭读整段，点字读这个字（或包含它的词）
        b.btnSpeak.setOnClickListener { speakSegment() }
        b.trainingText.onCharTap = { speakAt(it) }

        setupInput()
        if (Corpus.get(requireContext()).isNotEmpty()) onDataReady()
    }

    fun onDataReady() {
        if (_b == null || groups.isNotEmpty()) return
        groups = Corpus.get(requireContext())
        buildChips()
        showLessons(groupIndex)
    }

    // ------------------------------------------------------------ 课程选择
    private fun buildChips() {
        val group = b.chipGroup
        group.removeAllViews()
        groups.forEachIndexed { index, g ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = g.name
                isCheckable = true
                isChecked = index == groupIndex
                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (index == groupIndex) R.color.brand else R.color.text_secondary
                    )
                )
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.surface_3)
                )
            }
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val idx = checkedIds.firstOrNull()?.let { id ->
                b.chipGroup.indexOfChild(b.chipGroup.findViewById(id))
            } ?: return@setOnCheckedStateChangeListener
            if (idx == groupIndex) return@setOnCheckedStateChangeListener
            groupIndex = idx
            for (i in 0 until b.chipGroup.childCount) {
                (b.chipGroup.getChildAt(i) as? Chip)?.setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (i == idx) R.color.brand else R.color.text_secondary
                    )
                )
            }
            showLessons(idx)
        }
    }

    private fun showLessons(index: Int) {
        if (index !in groups.indices) return
        b.recyclerLessons.adapter = LessonAdapter(groups[index].lessons) { startLesson(it) }
    }

    private inner class LessonAdapter(
        private val items: List<Lesson>,
        private val onClick: (Lesson) -> Unit
    ) : RecyclerView.Adapter<LessonAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemLessonBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val lesson = items[position]
            holder.title.text = lesson.title
            holder.desc.text = if (lesson.segmentCount > 1) {
                "共 ${lesson.segmentCount} 段"
            } else {
                groups[groupIndex].desc
            }
            holder.count.text = "${lesson.charCount} 字"
            holder.itemView.setOnClickListener { onClick(lesson) }
        }

        override fun getItemCount() = items.size

        inner class Holder(binding: ItemLessonBinding) :
            RecyclerView.ViewHolder(binding.root) {
            val title: TextView = binding.tvTitle
            val desc: TextView = binding.tvDesc
            val count: TextView = binding.tvCount
        }
    }

    // ------------------------------------------------------------ 训练流程
    // ------------------------------------------------------------ 随机换内容
    /** 一个训练单位：一个单字 / 一个词 / 一条成语 */
    private class TrainUnit(val text: String, val syllables: List<String>)

    /** 「重新开始 / 再来一次」= 在同一专项里换一批新内容（常用字 3500 池或组内词库） */
    private fun restartWithRandom() {
        val l = lesson ?: return
        val g = currentGroup
        if (g == null) {
            startLesson(l)
            return
        }
        // 字典尚未就绪（冷启动直接点重开）时先补齐，否则字池为空
        if (!PinyinDict.isReady()) runCatching { PinyinDict.load(requireContext()) }
        val next = runCatching { randomizeLesson(g, l) }.getOrNull() ?: l
        startLesson(next, g)
    }

    private fun randomizeLesson(g: Group, old: Lesson): Lesson {
        // 短句、段落必须整句换，逐字重拼会不通顺
        if (g.id == "duanju" || g.id == "wenzhang") {
            val others = g.lessons.filter { it.title != old.title }
            return others.randomOrNull() ?: old
        }
        if (g.id == "ci" || g.id == "chengyu") {
            val units = collectUnits(g)
            if (units.isEmpty()) return old
            val n = old.segments.sumOf { s -> s.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() } }
            return Lesson(old.title, listOf(buildSegment(pickN(units, n.coerceAtLeast(4)), spaced = true)))
        }
        val pool = charPool(g.id, old.title)
        if (pool.isEmpty()) return old
        val units = pickN(pool, old.charCount.coerceAtLeast(8)).map {
            TrainUnit(it.ch.toString(), listOf(it.syllable))
        }
        return Lesson(old.title, listOf(buildSegment(units, spaced = false)))
    }

    /** 课标题里的韵母写法 -> 字典里的韵母（pypinyin 严谨式，ü 记作 v） */
    private fun finalOf(title: String): String {
        val ym = title.substringAfter("韵母", "").trim()
        return when (ym) {
            "ü" -> "v"
            "üe" -> "ve"
            "üan" -> "van"
            "ün" -> "vn"
            "iu" -> "iou"
            "ui" -> "uei"
            "un" -> "uen"
            else -> ym
        }
    }

    /** 按专项特征从 3500 常用字里筛候选字 */
    private fun charPool(groupId: String, title: String): List<CommonChar> = when (groupId) {
        "shengmu" -> {
            val sm = title.substringAfter("声母", "").trim()
            when {
                sm.isEmpty() || sm.startsWith("零") -> {
                    // 零声母：a / o / e 起头（不含 y/w 改写的音节）
                    PinyinDict.commonWhere {
                        it.initial.isEmpty() &&
                            (it.final.startsWith("a") || it.final.startsWith("o") || it.final.startsWith("e"))
                    }
                }
                // y / w 在严谨拼音里算零声母，按整体拼音前缀筛
                sm == "y" || sm == "w" -> PinyinDict.commonWhere { NineKey.plain(it.syllable).startsWith(sm) }
                else -> PinyinDict.commonWhere { it.initial == sm }
            }
        }
        "yunmu" -> {
            val ym = finalOf(title)
            if (ym.isEmpty()) PinyinDict.commonChars() else PinyinDict.commonWhere { it.final == ym }
        }
        "zhengren" -> {
            val syl = title.substringAfter("整体认读", "").trim().replace("ü", "v")
            PinyinDict.commonWhere { NineKey.plain(it.syllable) == syl }
        }
        "bianyin" -> {
            val parts = title.removePrefix("辨音").trim().substringBefore(" ")
                .split('/').map { it.trim() }.filter { it.isNotEmpty() }
                .map { if (it == "ü") "v" else it }
            if (parts.isEmpty()) {
                PinyinDict.commonChars()
            } else {
                PinyinDict.commonWhere { c -> c.initial in parts || c.final in parts }
            }
        }
        else -> PinyinDict.commonChars()
    }

    /** 组内所有词 / 成语，连同原标注读音一起收集，避免多音字读错 */
    private fun collectUnits(g: Group): List<TrainUnit> {
        val out = ArrayList<TrainUnit>(256)
        for (l in g.lessons) for (s in l.segments) out.addAll(splitUnits(s))
        return out
    }

    private fun splitUnits(seg: Segment): List<TrainUnit> {
        val words = seg.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        // 去掉空格位后的音节表与汉字一一对应，按词长切片
        val flat = seg.syllables.filter { it.isNotEmpty() }
        val out = ArrayList<TrainUnit>(words.size)
        var pos = 0
        for (w in words) {
            val end = minOf(pos + w.length, flat.size)
            out.add(TrainUnit(w, if (pos < end) flat.subList(pos, end).toList() else emptyList()))
            pos += w.length
        }
        return out
    }

    private fun buildSegment(units: List<TrainUnit>, spaced: Boolean): Segment {
        val sb = StringBuilder()
        val syl = ArrayList<String>()
        for ((i, u) in units.withIndex()) {
            if (spaced && i > 0) {
                sb.append(' ')
                syl.add("")
            }
            sb.append(u.text)
            syl.addAll(u.syllables)
        }
        return Segment(sb.toString(), syl)
    }

    private fun <T> pickN(list: List<T>, n: Int): List<T> {
        if (list.isEmpty() || n <= 0) return emptyList()
        return if (list.size <= n) list.shuffled() else list.shuffled().subList(0, n)
    }

    fun startCustom(text: String) {
        startLesson(Lesson("自定义文本", listOf(Segment(text, PinyinDict.annotate(text)))))
    }

    fun startLesson(l: Lesson, owner: Group? = null) {
        // 记录所属专项，供「重新开始」时从同组素材里随机换内容
        currentGroup = owner ?: groups.firstOrNull { g -> g.lessons.any { it.title == l.title } }
        lesson = l
        segmentIndex = 0
        typed.clear()
        segCommitted = 0
        segErrors = 0
        doneChars = 0
        doneCommitted = 0
        doneErrors = 0
        started = false
        finishing = false
        lessonStartMs = System.currentTimeMillis()

        b.viewSelect.visibility = View.GONE
        b.viewPractice.visibility = View.VISIBLE
        b.tvLessonTitle.text = l.title
        (activity as? MainActivity)?.setBottomBarVisible(false)

        b.trainingText.setCharSizeSp(Prefs.charSizeSp())
        b.trainingText.setShowPinyin(Prefs.showPinyin)
        b.trainingText.setCodeMode(Prefs.codeMode)
        applyInputMode()

        loadSegment()
        Speech.prepare(requireContext())
        (activity as? MainActivity)?.requestInputFocus()
        b.etInput.postDelayed({
            if (_b != null) showKeyboard(true)
        }, 250)
    }

    private fun loadSegment() {
        val l = lesson ?: return
        val seg = l.segments.getOrNull(segmentIndex) ?: return
        Speech.stop()
        segment = seg
        typed.clear()
        segCommitted = 0
        segErrors = 0
        segStartMs = if (started) System.currentTimeMillis() else 0L
        finishing = false

        codePos = 0
        simpleErrorCount = 0
        buildCodes(seg)
        simpleFlags = ByteArray(codes.size)

        setInputQuiet("")
        b.tvComposing.text = ""
        b.trainingText.setContent(seg.text, seg.syllables)
        if (Prefs.simpleInput) {
            b.trainingText.setProgress(simpleFlags, 0)
        } else {
            b.trainingText.setTyped("")
        }
        b.tvSegment.text = if (l.segmentCount > 1) "第 ${segmentIndex + 1}/${l.segmentCount} 段" else ""
        b.btnSkip.visibility = if (l.segmentCount > 1) View.VISIBLE else View.GONE
        updateStats()
        b.scrollText.scrollTo(0, 0)
        pendingScrollY = -1
    }

    // ------------------------------------------------------------ 简易输入模式

    /** 按当前编码方式生成每个字的目标编码串 */
    private fun buildCodes(seg: Segment) {
        val list = ArrayList<String>(seg.text.length)
        for (i in seg.text.indices) {
            val ch = seg.text[i]
            if (ch == ' ') continue
            list.add(codeOf(seg.syllables.getOrNull(i).orEmpty()))
        }
        codes = list
    }

    private fun codeOf(syllable: String): String =
        if (Prefs.codeMode == Prefs.CODE_NINE) {
            NineKey.digits(syllable)
        } else {
            NineKey.plain(syllable).lowercase()
        }

    /** 把输入框里的字符归一化成可比对的编码（九键模式下字母也会转成数字） */
    private fun normalizeCodes(raw: String): String {
        val nine = Prefs.codeMode == Prefs.CODE_NINE
        val sb = StringBuilder(raw.length)
        for (c0 in raw) {
            val c = c0.lowercaseChar()
            when {
                c in 'a'..'z' -> sb.append(if (nine) NineKey.digitOf(c) else c)
                c in '0'..'9' -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 输入框内容变化（简易模式）：按字比对并刷新进度 */
    private fun onSimpleInput(raw: String) {
        if (finishing || _b == null || trimmingCode) return
        val input = normalizeCodes(raw)
        // 输入框被删空：保留已完成进度，当前字从头开始打
        if (input.isEmpty()) {
            // 当前字此前被判定为错时清掉红标，等待重打
            if (codePos < simpleFlags.size && simpleFlags[codePos].toInt() == 2) {
                simpleFlags[codePos] = 0
                segCommitted = simpleFlags.count { it != 0.toByte() }
                b.trainingText.setProgress(simpleFlags, codePos)
                updateStats()
            }
            return
        }
        if (!started) {
            started = true
            lessonStartMs = System.currentTimeMillis()
            segStartMs = lessonStartMs
            startTicker()
        }
        applySimpleProgress(input)
    }

    /**
     * 逐字消费编码：每判定完一个字，就把它对应的按键从输入框里剪掉。
     *
     * 这样输入框里永远只剩「当前正在拼的这一个字」（全拼 2~6 个字母、九键 2~6 个数字），
     * 不会越打越长；同时判定粒度是「一个字」而非「整段打满」，
     * 中间打错一位也不会把后面所有输入都堵在框里。
     */
    private fun applySimpleProgress(input: String) {
        if (codes.isEmpty()) return
        if (simpleFlags.size != codes.size) simpleFlags = ByteArray(codes.size)

        var idx = codePos
        var pos = 0
        var clearAll = false

        while (idx < codes.size) {
            val target = codes[idx]
            if (target.isEmpty()) {
                simpleFlags[idx] = 1
                idx++
                continue
            }
            // 还没打够这个字的长度，等用户继续
            if (input.length - pos < target.length) break
            val ok = input.regionMatches(pos, target, 0, target.length)
            pos += target.length
            if (ok) {
                simpleFlags[idx] = 1
                idx++
            } else {
                simpleFlags[idx] = 2
                simpleErrorCount++
                if (Prefs.strictMode) {
                    // 严格模式：停在错字处，丢弃这次输入，等用户重打这个字
                    clearAll = true
                    break
                }
                idx++
            }
        }
        codePos = idx

        val done = idx >= codes.size
        segCommitted = simpleFlags.count { it != 0.toByte() }
        segErrors = simpleErrorCount
        b.trainingText.setProgress(simpleFlags, if (done) codes.size else idx)
        followCursor()
        updateStats()

        if (done) {
            onSegmentFinished()
        } else {
            trimInput(if (clearAll) -1 else pos)
        }
    }

    /** 从输入框剪掉已判定的按键；consumed < 0 表示整条清空 */
    private fun trimInput(consumed: Int) {
        if (consumed == 0 || _b == null) return
        val et = b.etInput
        val e = et.text ?: return
        if (e.isEmpty()) return
        var end = e.length
        if (consumed > 0) {
            var valid = 0
            var i = 0
            while (i < e.length && valid < consumed) {
                val c = e[i].lowercaseChar()
                if (c in 'a'..'z' || c in '0'..'9') valid++
                i++
            }
            end = i
        }
        if (end <= 0) return
        trimmingCode = true
        try {
            runCatching {
                e.delete(0, end)
                et.setSelection(e.length)
            }
        } finally {
            trimmingCode = false
        }
    }

    /** 静默写入输入框（不触发简易模式判定） */
    private fun setInputQuiet(text: String) {
        if (_b == null) return
        trimmingCode = true
        try {
            runCatching {
                b.etInput.setText(text)
                b.etInput.setSelection(b.etInput.text?.length ?: 0)
            }
        } finally {
            trimmingCode = false
        }
    }

    /** 简易模式走直通英文输入，避免输入法组词拦下字母 */
    private fun applyInputMode() {
        if (_b == null) return
        val et = b.etInput
        val simple = Prefs.simpleInput
        val desired = if (simple) {
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        if (et.inputType != desired) {
            et.inputType = desired
            (context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.restartInput(et)
        }
        et.setHint(if (simple) R.string.hint_simple_input else R.string.hint_tap_to_start)
        appliedCodeMode = Prefs.codeMode
    }

    private fun setupInput() {
        // 点击或获得焦点时主动唤起输入法，避免部分机型唤不起键盘
        b.etInput.setOnClickListener { showKeyboard(true) }
        b.etInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showKeyboard(true) }
        b.etInput.listener = object : TypingEditText.Listener {
            override fun onCommit(text: String) {
                if (finishing) return
                // 简易模式：字母直接上屏，由 TextWatcher 统一按编码比对
                if (Prefs.simpleInput) return
                if (!started) {
                    started = true
                    lessonStartMs = System.currentTimeMillis()
                    segStartMs = lessonStartMs
                    startTicker()
                }
                val ft = segment?.flatTarget ?: return
                for (ch in text) {
                    // 打满后不再累加，避免内部状态越界（严格模式下需手动回退修正）
                    if (typed.length >= ft.length) break
                    val expect = ft.getOrNull(typed.length)
                    if (expect == null || ch != expect) segErrors++
                    typed.append(ch)
                    segCommitted++
                }
                afterInput()
            }

            override fun onComposing(text: String) {
                if (Prefs.simpleInput) return
                b.tvComposing.text = text
            }

            override fun onDelete(count: Int) {
                // 输入法退格（或候选词重排时的删除）不回退上方进度：
                // 已经打出来的字继续显示在上面，只有新的输入才会向前推进。
                // 简易模式下输入框就是编码流，删除由 TextWatcher 实时重算。
                if (finishing || Prefs.simpleInput) return
                b.tvComposing.text = ""
            }
        }

        // 简易输入模式：文本变化即重新比对（编码模式允许退格回退，便于纠正）
        b.etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!Prefs.simpleInput) return
                onSimpleInput(s?.toString().orEmpty())
            }
        })
    }

    /**
     * 训练区跟随：光标顶到可视区底部时，整体上移一行。
     * 滚动量按「单行」对齐，每打完一行就上移一次，正在打的那行始终可见；
     * 用户手动往上翻看后面的内容时不强行拉回。
     */
    /**
     * 训练区跟随：光标顶到可视区底部时，整体上移一行。
     * 滚动量按「单行」对齐，每打完一行就上移一次，正在打的那行始终可见；
     * 以 pendingScrollY 为基准（而非实时 scrollY），连续打字时目标单调累加。
     * 用户手动往上翻看后面的内容时不强行拉回。
     */
    private fun followCursor() {
        if (_b == null) return
        val base = if (pendingScrollY >= 0) pendingScrollY else b.scrollText.scrollY
        val target = nextScrollTarget(
            cursorTop = b.trainingText.cursorTop(),
            rowH = b.trainingText.rowHeight(),
            viewH = b.scrollText.height,
            baseScrollY = base
        )
        if (target < 0 || target == pendingScrollY) return
        pendingScrollY = target
        b.scrollText.smoothScrollTo(0, target)
    }

    private fun afterInput() {
        syncFromEditText()
        val seg = segment ?: return
        b.trainingText.setTyped(typed.toString())

        followCursor()

        updateStats()

        val ft = seg.flatTarget
        val done = if (Prefs.strictMode) {
            typed.toString() == ft
        } else {
            typed.length >= ft.length
        }
        if (done) onSegmentFinished()
    }

    /**
     * 兜底同步：只增不减。
     * 编辑框里已上屏的内容比内部记录更长时补齐差值；变短时（输入法退格）
     * 保留内部记录，这样上方已经显示出来的进度不会被输入法删掉。
     */
    private fun syncFromEditText() {
        val raw = b.etInput.text?.toString().orEmpty()
        // 去掉尾部未上屏的拼音字母（部分输入法会把组合串直接写进文本）
        var end = raw.length
        while (end > 0 && raw[end - 1].code < 128 && raw[end - 1].isLetter()) end--
        val committed = raw.substring(0, end)
        val cur = typed.toString()
        if (committed.length <= cur.length || !committed.startsWith(cur)) return
        val ft = segment?.flatTarget
        for (ch in committed.substring(cur.length)) {
            if (ft != null && typed.length >= ft.length) break
            typed.append(ch)
        }
    }

    private fun correctNow(): Int {
        if (Prefs.simpleInput) return simpleFlags.count { it.toInt() == 1 }
        val ft = segment?.flatTarget ?: return 0
        var n = 0
        while (n < typed.length && n < ft.length && typed[n] == ft[n]) n++
        return n
    }

    private fun updateStats() {
        val l = lesson ?: return
        val correct = doneChars + correctNow()
        val committed = doneCommitted + segCommitted
        val errors = doneErrors + segErrors
        val elapsedMs = if (started) System.currentTimeMillis() - lessonStartMs else 0L
        val minutes = elapsedMs / 60000.0
        val speed = if (minutes > 0.0001) (correct / minutes).toInt() else 0
        val acc = if (committed > 0) (correct * 100 / committed).coerceIn(0, 100) else 100

        b.tvTime.text = formatTime(elapsedMs)
        b.tvSpeed.text = speed.toString()
        b.tvAcc.text = "$acc%"
        b.tvErrors.text = errors.toString()
        val total = l.charCount.coerceAtLeast(1)
        b.progress.progress = (correct * 100 / total).coerceIn(0, 100)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun onSegmentFinished() {
        finishing = true
        // 本段打完立刻清空输入框（已打内容保留在上方训练区），避免残留上屏文字
        setInputQuiet("")
        b.tvComposing.text = ""
        val ft = segment?.flatTarget.orEmpty()
        doneChars += ft.length
        doneCommitted += segCommitted
        doneErrors += segErrors
        stopTicker()
        handler.removeCallbacks(ticker)

        val isLast = segmentIndex >= (lesson?.segmentCount ?: 1) - 1
        if (isLast) {
            showResult()
        } else {
            b.tvComposing.text = "✓"
            handler.postDelayed({
                if (_b == null || !isAdded) return@postDelayed
                segmentIndex++
                loadSegment()
            }, 350)
        }
    }

    private fun nextSegment(skipped: Boolean) {
        val l = lesson ?: return
        if (!skipped) return
        if (segmentIndex >= l.segmentCount - 1) {
            showResult()
        } else {
            segmentIndex++
            loadSegment()
        }
    }

    private fun showResult() {
        val l = lesson ?: return
        val elapsed = System.currentTimeMillis() - lessonStartMs
        val minutes = elapsed / 60000.0
        val speed = if (minutes > 0.0001) (doneChars / minutes).toInt() else 0
        val committed = doneCommitted.coerceAtLeast(doneChars)
        val acc = if (committed > 0) (doneChars * 100 / committed).coerceIn(0, 100) else 100

        StatsStore.add(
            Record(
                name = l.title,
                chars = doneChars,
                timeMs = elapsed,
                speed = speed,
                accuracy = acc,
                errors = doneErrors,
                ts = System.currentTimeMillis()
            )
        )

        val msg = buildString {
            append("字数：").append(doneChars).append(" 字\n")
            append("用时：").append(formatTime(elapsed)).append("\n")
            append("速度：").append(speed).append(" 字/分\n")
            append("准确率：").append(acc).append("%\n")
            append("错误：").append(doneErrors).append(" 次")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.result_lesson_done))
            .setMessage(msg)
            .setCancelable(false)
            .setNegativeButton(R.string.action_back) { _, _ -> if (_b != null) exitPractice() }
            .setPositiveButton(R.string.action_again) { _, _ -> if (_b != null) restartWithRandom() }
            .show()
    }

    /** 删除输入框最后一个字符（含组合中的拼音），并同步内部判定状态 */
    private fun deleteLast() {
        if (_b == null || finishing) return
        val t = b.etInput.text
        if (Prefs.simpleInput) {
            if (t != null && t.isNotEmpty()) {
                trimmingCode = true
                try {
                    t.delete(t.length - 1, t.length)
                    b.etInput.setSelection(t.length)
                } finally {
                    trimmingCode = false
                }
            } else if (codePos > 0) {
                // 输入框已空：回退上一个字，便于纠正
                codePos--
                simpleFlags[codePos] = 0
                segCommitted = simpleFlags.count { it != 0.toByte() }
                b.trainingText.setProgress(simpleFlags, codePos)
                updateStats()
            }
            return
        }
        if (t != null && t.isNotEmpty()) {
            t.delete(t.length - 1, t.length)
            b.etInput.setSelection(t.length)
        }
        // App 内删除按钮属于显式修正：同步回退上方进度
        if (typed.isNotEmpty()) typed.deleteAt(typed.length - 1)
        b.tvComposing.text = ""
        afterInput()
    }

    /** 清空输入 */
    private fun clearInput() {
        if (_b == null || finishing) return
        b.tvComposing.text = ""
        if (Prefs.simpleInput) {
            setInputQuiet("")
            // 整段进度归零，重新开始（已发生的错误次数保留）
            codePos = 0
            simpleFlags = ByteArray(codes.size)
            segCommitted = 0
            b.trainingText.setProgress(simpleFlags, 0)
            updateStats()
            return
        }
        b.etInput.setText("")
        typed.clear()
        afterInput()
    }

    /** 当前是否处于训练中 */
    fun isPracticing(): Boolean =
        _b != null && b.viewPractice.visibility == View.VISIBLE

    // ------------------------------------------------------------ 语音朗读

    /** 点字朗读：优先读它所在的词（多音字更准），词太长则只读这个字 */
    private fun speakAt(index: Int) {
        if (!Prefs.speakOnTap || _b == null) return
        val seg = segment ?: return
        if (index !in seg.text.indices) return
        val text = seg.text
        if (text[index] == ' ') return
        var l = index
        while (l > 0 && text[l - 1] != ' ') l--
        var r = index
        while (r < text.length - 1 && text[r + 1] != ' ') r++
        val word = text.substring(l, r + 1).replace(" ", "")
        val isWord = word.length in 2..4
        val say = if (isWord) word else text[index].toString()
        val range = if (isWord) l..r else index..index
        val syllables = range.mapNotNull { seg.syllables.getOrNull(it)?.takeIf { p -> p.isNotEmpty() } }
        speak(say, syllables)
    }

    /** 喇叭按钮：朗读当前整段（不含分隔空格），再点一次停止 */
    private fun speakSegment() {
        if (Speech.isSpeaking()) {
            Speech.stop()
            return
        }
        val seg = segment ?: return
        val s = seg.text.replace(" ", "")
        if (s.isEmpty()) return
        speak(s, seg.syllables.filter { it.isNotEmpty() })
    }

    private fun speak(text: String, syllables: List<String>) {
        if (_b == null) return
        Speech.speak(requireContext(), text, syllables)
        if (Speech.unavailable(requireContext())) {
            Toast.makeText(requireContext(), R.string.speech_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** 返回首页（课程列表）；未在训练时返回 false */
    fun goHome(): Boolean {
        if (!isPracticing()) return false
        exitPractice()
        return true
    }

    private fun exitPractice() {
        stopTicker()
        finishing = true
        showKeyboard(false)
        b.etInput.setText("")
        b.viewPractice.visibility = View.GONE
        b.viewSelect.visibility = View.VISIBLE
        (activity as? MainActivity)?.setBottomBarVisible(true)
        lesson = null
        segment = null
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        handler.postDelayed(ticker, 200)
    }

    private fun stopTicker() {
        ticking = false
        handler.removeCallbacks(ticker)
    }

    private fun showKeyboard(show: Boolean) {
        val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        if (show) {
            b.etInput.requestFocus()
            runCatching { imm.restartInput(b.etInput) }
            imm.showSoftInput(b.etInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(b.etInput.windowToken, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回时应用最新的字号 / 拼音显示开关 / 编码方式 / 输入模式
        if (b.viewPractice.visibility == View.VISIBLE) {
            b.trainingText.setCharSizeSp(Prefs.charSizeSp())
            b.trainingText.setShowPinyin(Prefs.showPinyin)
            b.trainingText.setCodeMode(Prefs.codeMode)
            val modeChanged = appliedCodeMode != Prefs.codeMode
            applyInputMode()
            if (modeChanged) {
                // 编码方式变了：目标编码长度不同，保留已完成的字数，当前字重打
                segment?.let { buildCodes(it) }
                if (Prefs.simpleInput) {
                    val keep = minOf(codePos, codes.size)
                    simpleFlags = ByteArray(codes.size)
                    for (i in 0 until keep) simpleFlags[i] = 1
                    codePos = keep
                    setInputQuiet("")
                    b.trainingText.setProgress(simpleFlags, if (keep >= codes.size) codes.size else keep)
                    segCommitted = simpleFlags.count { it != 0.toByte() }
                    updateStats()
                }
            }
        }
        if (b.viewPractice.visibility == View.VISIBLE && started) startTicker()
    }

    override fun onPause() {
        super.onPause()
        stopTicker()
    }

    override fun onDestroyView() {
        stopTicker()
        Speech.stop()
        _b?.trainingText?.onCharTap = null
        _b = null
        super.onDestroyView()
    }
}

    /**
     * 训练区跟随滚动的目标位置（绝对 scrollY）。
     *
     * 以「光标相对可视区」的位置判定：下一个待打的字底部进入可视区底部之下时，
     * 按单行整数倍把内容整体上移，让正在打的那行始终停在视野内。
     * 全程用相对位置计算，避免把已滚动量重复计入，导致有时不跳、有时跳过头。
     *
     * @param baseScrollY 当前「已经滚动到 / 正在滚动到的」位置。调用方传入
     *        pendingScrollY（而非动画过程中的实时 scrollY），这样连续快速打字时
     *        目标位置单调累加，不会因 smoothScroll 动画未完成而跳过头。
     * 返回 -1 表示不用滚动（光标已可见）。
     */
    internal fun nextScrollTarget(cursorTop: Float, rowH: Float, viewH: Int, baseScrollY: Int): Int {
        if (rowH <= 0f || viewH <= 0) return -1
        val rel = cursorTop - baseScrollY
        val bottom = rel + rowH
        if (bottom <= viewH) return -1
        val need = (bottom - viewH).toInt().coerceAtLeast(1)
        val step = rowH.toInt().coerceAtLeast(1)
        val add = ((need + step - 1) / step) * step
        val target = baseScrollY + add
        return if (target > baseScrollY) target else -1
    }
