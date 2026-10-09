package com.vasanth.vaultnote.ui.list

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.color.MaterialColors
import com.vasanth.vaultnote.data.BoardJson
import com.vasanth.vaultnote.data.ChecklistJson
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.db.NoteType
import com.vasanth.vaultnote.databinding.ItemHeaderBinding
import com.vasanth.vaultnote.databinding.ItemNoteBinding
import com.vasanth.vaultnote.util.NoteColors

sealed interface ListItem {
    data class Header(val title: String) : ListItem
    data class Row(val note: NoteEntity) : ListItem
}

class NotesAdapter(
    private val onClick: (NoteEntity) -> Unit,
    private val onLongClick: (View, NoteEntity) -> Unit
) : ListAdapter<ListItem, RecyclerView.ViewHolder>(DIFF) {

    private var selected: Set<String> = emptySet()

    fun setSelection(ids: Set<String>) {
        if (ids == selected) return
        selected = ids
        notifyItemRangeChanged(0, itemCount)
    }

    override fun getItemViewType(position: Int) =
        if (getItem(position) is ListItem.Header) TYPE_HEADER else TYPE_NOTE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) HeaderVH(ItemHeaderBinding.inflate(inflater, parent, false))
        else NoteVH(ItemNoteBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ListItem.Header -> (holder as HeaderVH).bind(item)
            is ListItem.Row -> (holder as NoteVH).bind(item.note)
        }
    }

    inner class HeaderVH(private val b: ItemHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(h: ListItem.Header) {
            b.headerText.text = h.title
            (itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan = true
        }
    }

    inner class NoteVH(private val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root) {
        private val baseStrokeWidth = b.root.strokeWidth
        private val baseStrokeColors: ColorStateList? = b.root.strokeColorStateList

        init {
            b.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) (getItem(pos) as? ListItem.Row)?.let { onClick(it.note) }
            }
            b.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) (getItem(pos) as? ListItem.Row)?.let { onLongClick(b.root, it.note) }
                true
            }
        }

        fun bind(note: NoteEntity) {
            val ctx = itemView.context
            val sel = note.id in selected
            val prefix = if (sel) "✓ " else ""

            if (note.locked) {
                b.titleText.text = prefix + "🔒 Locked note"
                b.titleText.isVisible = true
                b.previewText.isVisible = false
            } else {
                b.titleText.text = prefix + note.title
                b.titleText.isVisible = note.title.isNotBlank()
                val preview = previewOf(note)
                b.previewText.text = preview
                b.previewText.isVisible = preview.isNotBlank()
            }
            b.pinIcon.isVisible = note.pinned
            b.root.setCardBackgroundColor(NoteColors.card(ctx, note.color))

            if (sel) {
                b.root.strokeWidth = (3 * ctx.resources.displayMetrics.density).toInt()
                b.root.strokeColor = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
            } else {
                b.root.strokeWidth = baseStrokeWidth
                b.root.setStrokeColor(baseStrokeColors ?: ColorStateList.valueOf(Color.TRANSPARENT))
            }
        }
    }

    private fun previewOf(note: NoteEntity): String {
        if (note.type == NoteType.BOARD)
            return "▦ " + BoardJson.decode(note.itemsJson).joinToString(" · ") { it.name }
        if (note.type != NoteType.CHECKLIST) return note.body.take(300)
        val items = ChecklistJson.decode(note.itemsJson).filter { it.text.isNotBlank() }
        val lines = items.take(6).map { (if (it.done) "☑ " else "☐ ") + it.text }
        val more = if (items.size > 6) "\n+${items.size - 6} more" else ""
        return lines.joinToString("\n") + more
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_NOTE = 1

        private val DIFF = object : DiffUtil.ItemCallback<ListItem>() {
            override fun areItemsTheSame(a: ListItem, b: ListItem) = when {
                a is ListItem.Header && b is ListItem.Header -> a.title == b.title
                a is ListItem.Row && b is ListItem.Row -> a.note.id == b.note.id
                else -> false
            }

            override fun areContentsTheSame(a: ListItem, b: ListItem) = a == b
        }
    }
}