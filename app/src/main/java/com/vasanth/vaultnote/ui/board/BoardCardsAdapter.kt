package com.vasanth.vaultnote.ui.board

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vasanth.vaultnote.data.AttachmentJson
import com.vasanth.vaultnote.data.ChecklistJson
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.db.NoteType
import com.vasanth.vaultnote.databinding.ItemBoardCardBinding
import com.vasanth.vaultnote.util.NoteColors
import com.vasanth.vaultnote.util.ReminderFormat

class BoardCardsAdapter(
    private val onClick: (NoteEntity) -> Unit,
    private val onMenu: (View, NoteEntity) -> Unit,
    private val onDrag: (View, NoteEntity) -> Unit
) : ListAdapter<CardUi, BoardCardsAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemBoardCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemBoardCardBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(getItem(p).note)
            }
            b.root.setOnLongClickListener { v ->
                val p = bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) false else { onDrag(v, getItem(p).note); true }
            }
            b.cardMenu.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onMenu(it, getItem(p).note)
            }
        }

        fun bind(c: CardUi) {
            val n = c.note
            val ctx = itemView.context
            b.root.alpha = 1f
            b.root.setCardBackgroundColor(NoteColors.card(ctx, n.color))

            if (n.locked) {
                b.cardTitle.text = "🔒 Locked note"
                b.cardPreview.isVisible = false
                b.cardMeta.isVisible = false
                b.cardTags.isVisible = false
                return
            }

            b.cardTitle.text = n.title.ifBlank { "Untitled" }

            val preview = if (n.type == NoteType.CHECKLIST) {
                val list = ChecklistJson.decode(n.itemsJson).filter { it.text.isNotBlank() }
                if (list.isEmpty()) "" else "☑ ${list.count { it.done }}/${list.size}"
            } else n.body.trim().take(120)
            b.cardPreview.text = preview
            b.cardPreview.isVisible = preview.isNotBlank()

            val meta = mutableListOf<String>()
            n.reminderAt?.let { meta += "⏰ " + ReminderFormat.text(ctx, it, n.repeat) }
            val images = AttachmentJson.decode(n.attachmentsJson).size
            if (images > 0) meta += "🖼 $images"
            b.cardMeta.text = meta.joinToString("  ·  ")
            b.cardMeta.isVisible = meta.isNotEmpty()

            b.cardTags.text = c.tags.joinToString("  ") { "#$it" }
            b.cardTags.isVisible = c.tags.isNotEmpty()
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CardUi>() {
            override fun areItemsTheSame(a: CardUi, b: CardUi) = a.note.id == b.note.id
            override fun areContentsTheSame(a: CardUi, b: CardUi) = a == b
        }
    }
}