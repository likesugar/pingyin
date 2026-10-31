package com.pinyin.typing

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.pinyin.typing.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentSettingsBinding.bind(view)

        b.swPinyin.isChecked = Prefs.showPinyin
        b.swStrict.isChecked = Prefs.strictMode
        b.swSimple.isChecked = Prefs.simpleInput
        b.swSpeak.isChecked = Prefs.speakOnTap
        b.swSystemVoice.isChecked = Prefs.useSystemVoice
        b.seekFont.progress = Prefs.fontLevel

        b.swPinyin.setOnCheckedChangeListener { _, checked -> Prefs.showPinyin = checked }
        b.swStrict.setOnCheckedChangeListener { _, checked -> Prefs.strictMode = checked }
        b.swSimple.setOnCheckedChangeListener { _, checked -> Prefs.simpleInput = checked }
        b.swSpeak.setOnCheckedChangeListener { _, checked -> Prefs.speakOnTap = checked }
        b.swSystemVoice.setOnCheckedChangeListener { _, checked -> Prefs.useSystemVoice = checked }

        if (Prefs.codeMode == Prefs.CODE_NINE) b.rbCodeNine.isChecked = true
        else b.rbCodeFull.isChecked = true
        updateCodeHint(Prefs.codeMode)
        b.rgCodeMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == b.rbCodeNine.id) Prefs.CODE_NINE else Prefs.CODE_FULL
            Prefs.codeMode = mode
            updateCodeHint(mode)
        }

        b.seekFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) Prefs.fontLevel = progress
            }
            override fun onStartTrackingTouch(seek: SeekBar?) = Unit
            override fun onStopTrackingTouch(seek: SeekBar?) = Unit
        })

        when (Prefs.theme) {
            Prefs.THEME_LIGHT -> b.rbLight.isChecked = true
            Prefs.THEME_SYSTEM -> b.rbSystem.isChecked = true
            else -> b.rbDark.isChecked = true
        }
        b.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                b.rbLight.id -> Prefs.THEME_LIGHT
                b.rbSystem.id -> Prefs.THEME_SYSTEM
                else -> Prefs.THEME_DARK
            }
            if (mode != Prefs.theme) {
                Prefs.theme = mode
                Prefs.applyTheme(mode)
            }
        }

        b.btnCustomStart.setOnClickListener {
            val text = b.etCustom.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            if (!PinyinDict.isReady()) PinyinDict.load(requireContext())
            (activity as? MainActivity)?.startCustomLesson(text)
        }

        val groups = runCatching { Corpus.get(requireContext()) }.getOrElse { emptyList() }
        val lessons = groups.sumOf { it.lessons.size }
        b.tvAbout.text = "拼音打字训练 v${appVersion()}\n内置 $lessons 课 · 支持 Android 7.0 以上"
    }

    /** 版本名取自安装包信息，避免每次发版忘记同步文案 */
    private fun appVersion(): String = runCatching {
        val pm = requireContext().packageManager
        val name = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(requireContext().packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(requireContext().packageName, 0).versionName
        }
        name.orEmpty()
    }.getOrDefault("")

    private fun updateCodeHint(mode: Int) {
        b.tvCodeHint.setText(
            if (mode == Prefs.CODE_NINE) R.string.code_hint_nine else R.string.code_hint_full
        )
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}
