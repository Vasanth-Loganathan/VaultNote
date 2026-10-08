package com.vasanth.vaultnote.ui.editor

import android.text.TextPaint
import android.text.style.ForegroundColorSpan

/** Marks a [[Note title]] range in the editor: colored + underlined. */
class NoteLinkSpan(color: Int) : ForegroundColorSpan(color) {
    override fun updateDrawState(ds: TextPaint) {
        super.updateDrawState(ds)
        ds.isUnderlineText = true
    }
}