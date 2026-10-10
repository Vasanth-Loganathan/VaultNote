package com.vasanth.vaultnote.ui.board

import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vasanth.vaultnote.data.BoardJson
import com.vasanth.vaultnote.data.db.NoteEntity

/** Two steps: choose a board, then a column. */
object BoardPicker {

    fun show(ctx: Context, boards: List<NoteEntity>, excludeId: String?, onPick: (boardId: String, columnId: String) -> Unit) {
        val list = boards.filter { it.id != excludeId }
        if (list.isEmpty()) {
            Toast.makeText(ctx, "No other boards yet. Create a board first", Toast.LENGTH_LONG).show()
            return
        }
        val names = list.map { if (it.locked) "🔒 Locked board" else it.title.ifBlank { "Untitled board" } }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Move to board")
            .setItems(names.toTypedArray()) { _, i -> pickColumn(ctx, list, excludeId, list[i], onPick) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickColumn(
        ctx: Context, all: List<NoteEntity>, excludeId: String?, board: NoteEntity,
        onPick: (String, String) -> Unit
    ) {
        val cols = BoardJson.decode(board.itemsJson)
        if (cols.isEmpty()) {
            Toast.makeText(ctx, "This board has no columns. Add a column first", Toast.LENGTH_LONG).show()
            return
        }
        if (cols.size == 1) { onPick(board.id, cols[0].id); return }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (board.locked) "Choose column" else board.title.ifBlank { "Choose column" })
            .setItems(cols.map { it.name }.toTypedArray()) { _, j -> onPick(board.id, cols[j].id) }
            .setNegativeButton("Back") { _, _ -> show(ctx, all, excludeId, onPick) }
            .show()
    }
}