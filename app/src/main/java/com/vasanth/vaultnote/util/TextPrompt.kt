package com.vasanth.vaultnote.util

import android.content.Context
import android.view.WindowManager
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

object TextPrompt {
    fun show(
        ctx: Context, title: String, hint: String, initial: String = "",
        positive: String = "Save", onOk: (String) -> Unit
    ) {
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val layout = TextInputLayout(ctx).apply { this.hint = hint }
        val edit = TextInputEditText(layout.context).apply {
            setText(initial)
            setSelection(initial.length)
            setSingleLine()
        }
        layout.addView(edit)
        val box = FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(layout)
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(positive) { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) onOk(t)
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            edit.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }
}