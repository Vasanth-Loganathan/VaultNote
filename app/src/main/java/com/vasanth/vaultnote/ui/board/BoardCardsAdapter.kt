package com.vasanth.vaultnote.ui.board

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.vasanth.vaultnote.data.ChecklistJson
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.db.NoteType
import com.vasanth.vaultnote.databinding.ItemBoardCardBinding
import com.vasanth.vaultnote.util.NoteColors

class BoardCardsAdapter(
    private val onClick: (NoteEntity) -> Unit,
    private val onMenu: (View, NoteEntity) -> Unit
) : RecyclerView.Adapter<BoardCardsAdapter.VH>() {

    private var items = mutableListOf<NoteEntity>()
    var dragging = false

    fun submit(list: List<NoteEntity>) {
        if (dragging) return
        items = list.toMutableList()
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int): Boolean {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return false
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
        return true
    }

    fun ids(): List<String> = items.map { it.id }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemBoardCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemBoardCardBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(items[p])
            }
            b.cardMenu.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onMenu(it, items[p])
            }
        }

        fun bind(n: NoteEntity) {
            if (n.locked) {
                b.cardTitle.text = "🔒 Locked note"
                b.cardPreview.isVisible = false
            } else {
                b.cardTitle.text = n.title.ifBlank { "Untitled" }
                val preview = if (n.type == NoteType.CHECKLIST) {
                    val list = ChecklistJson.decode(n.itemsJson).filter { it.text.isNotBlank() }
                    if (list.isEmpty()) "" else "☑ ${list.count { it.done }}/${list.size}"
                } else n.body.trim().take(120)
                b.cardPreview.text = preview
                b.cardPreview.isVisible = preview.isNotBlank()
            }
            b.root.setCardBackgroundColor(NoteColors.card(itemView.context, n.color))
        }
    }
}