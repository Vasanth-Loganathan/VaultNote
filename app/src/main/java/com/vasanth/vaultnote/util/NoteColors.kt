package com.vasanth.vaultnote.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.GridLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vasanth.vaultnote.R

object NoteColors {
    val keys = listOf("default", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink")

    private fun tint(ctx: Context, key: String): Int? {
        val res = when (key) {
            "red" -> R.color.note_red
            "orange" -> R.color.note_orange
            "yellow" -> R.color.note_yellow
            "green" -> R.color.note_green
            "teal" -> R.color.note_teal
            "blue" -> R.color.note_blue
            "purple" -> R.color.note_purple
            "pink" -> R.color.note_pink
            else -> return null
        }
        return ContextCompat.getColor(ctx, res)
    }

    /** Color used for cards in the list. */
    fun card(ctx: Context, key: String): Int =
        tint(ctx, key) ?: MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY
        )

    /** Color used for the editor background. */
    fun background(ctx: Context, key: String): Int =
        tint(ctx, key) ?: MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorSurface, Color.WHITE
        )
}

object ColorPicker {
    fun show(ctx: Context, current: String, onPick: (String) -> Unit) {
        val density = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()

        val grid = GridLayout(ctx).apply {
            columnCount = 5
            setPadding(px(16), px(16), px(16), px(8))
        }
        var dialog: AlertDialog? = null
        val outline = MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorOnSurface, Color.GRAY
        )

        NoteColors.keys.forEach { key ->
            val dot = View(ctx)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(NoteColors.card(ctx, key))
                setStroke(px(if (key == current) 3 else 1), outline)
            }
            dot.layoutParams = GridLayout.LayoutParams().apply {
                width = px(44); height = px(44)
                setMargins(px(6), px(6), px(6), px(6))
            }
            dot.setOnClickListener { onPick(key); dialog?.dismiss() }
            grid.addView(dot)
        }
        dialog = MaterialAlertDialogBuilder(ctx).setTitle("Note color").setView(grid).show()
    }
}