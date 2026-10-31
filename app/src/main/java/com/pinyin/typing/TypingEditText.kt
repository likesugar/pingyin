package com.pinyin.typing

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * 通过包装 InputConnection 区分「拼音组合中」与「已上屏汉字」，
 * 这样输入法候选未上屏时的拼音字母不会被计入比对。
 */
class TypingEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    // 必须走主题的 editTextStyle，否则控件属性/编辑器信息异常，输入法可能唤不起
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : androidx.appcompat.widget.AppCompatEditText(context, attrs, defStyleAttr) {

    interface Listener {
        fun onCommit(text: String)
        fun onComposing(text: String)
        fun onDelete(count: Int)
    }

    var listener: Listener? = null

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(editorInfo) ?: return null
        return TypingInputConnection(ic)
    }

    private inner class TypingInputConnection(target: InputConnection) :
        InputConnectionWrapper(target, true) {

        private var composing: String = ""

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val ok = super.setComposingText(text, newCursorPosition)
            composing = text?.toString().orEmpty()
            listener?.onComposing(composing)
            return ok
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val ok = super.commitText(text, newCursorPosition)
            composing = ""
            val s = text?.toString().orEmpty()
            if (s.isNotEmpty()) listener?.onCommit(s)
            listener?.onComposing("")
            return ok
        }

        override fun finishComposingText(): Boolean {
            val ok = super.finishComposingText()
            if (composing.isNotEmpty()) listener?.onCommit(composing)
            composing = ""
            listener?.onComposing("")
            return ok
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val ok = super.deleteSurroundingText(beforeLength, afterLength)
            handleDelete(beforeLength)
            return ok
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            val ok = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            handleDelete(beforeLength)
            return ok
        }

        private fun handleDelete(beforeLength: Int) {
            if (composing.isNotEmpty()) {
                composing = ""
                listener?.onComposing("")
                return
            }
            if (beforeLength > 0) listener?.onDelete(beforeLength)
        }
    }
}
