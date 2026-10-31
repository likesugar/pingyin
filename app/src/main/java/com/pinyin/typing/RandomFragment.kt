package com.pinyin.typing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.pinyin.typing.databinding.FragmentRandomBinding
import com.pinyin.typing.databinding.ItemRecordBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RandomFragment : Fragment(R.layout.fragment_random) {

    private var _b: FragmentRandomBinding? = null
    private val b get() = _b!!
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private val counts = intArrayOf(30, 60, 120, 200)
    private var selected = 1

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentRandomBinding.bind(view)
        b.recyclerRecords.layoutManager = LinearLayoutManager(requireContext())

        RandomPool.load(requireContext())
        b.tvPoolSize.text = getString(R.string.random_pool_size, RandomPool.size())

        counts.forEachIndexed { index, n ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = "$n 字"
                isCheckable = true
                isChecked = index == selected
                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (index == selected) R.color.brand else R.color.text_secondary
                    )
                )
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.surface_3)
                )
            }
            b.chipCount.addView(chip)
        }
        b.chipCount.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val idx = b.chipCount.indexOfChild(b.chipCount.findViewById(id))
            if (idx < 0 || idx == selected) return@setOnCheckedStateChangeListener
            selected = idx
            for (i in 0 until b.chipCount.childCount) {
                (b.chipCount.getChildAt(i) as? Chip)?.setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (i == idx) R.color.brand else R.color.text_secondary
                    )
                )
            }
        }

        b.btnStart.setOnClickListener {
            val text = RandomPool.randomText(counts[selected])
            if (text.isBlank()) return@setOnClickListener
            val lesson = Lesson(
                "随机字 ${counts[selected]} 字",
                listOf(Segment(text, RandomPool.syllablesOf(text)))
            )
            (activity as? MainActivity)?.startLesson(lesson)
        }

        refresh()
    }

    fun refresh() {
        if (_b == null) return
        val records = StatsStore.all()
        b.tvBest.text = StatsStore.bestSpeed().toString()
        b.tvAvgAcc.text = if (records.isEmpty()) "--" else "${StatsStore.avgAccuracy()}%"
        b.tvTotalChars.text = StatsStore.totalChars().toString()
        b.tvSessions.text = records.size.toString()
        b.tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        b.recyclerRecords.adapter = RecordAdapter(records)
    }

    private inner class RecordAdapter(private val items: List<Record>) :
        RecyclerView.Adapter<RecordAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemRecordBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = items[position]
            holder.name.text = r.name
            holder.speed.text = "${r.speed} 字/分"
            holder.meta.text =
                "${r.chars} 字 · ${formatTime(r.timeMs)} · 准确率 ${r.accuracy}% · 错 ${r.errors}"
            holder.date.text = dateFmt.format(Date(r.ts))
        }

        override fun getItemCount() = items.size

        private fun formatTime(ms: Long): String {
            val s = (ms / 1000).toInt()
            return "%d:%02d".format(s / 60, s % 60)
        }

        inner class Holder(binding: ItemRecordBinding) : RecyclerView.ViewHolder(binding.root) {
            val name = binding.tvName
            val speed = binding.tvSpeed
            val meta = binding.tvMeta
            val date = binding.tvDate
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}
