package com.vasanth.vaultnote.ui.board

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.databinding.ItemBoardColumnBinding

class BoardColumnsAdapter(
    private val onAddCard: (ColumnUi) -> Unit,
    private val onColumnMenu: (View, ColumnUi, Int) -> Unit,
    private val onCardClick: (NoteEntity) -> Unit,
    private val onCardMenu: (View, NoteEntity) -> Unit,
    private val onCardDrag: (View, NoteEntity) -> Unit
) : ListAdapter<ColumnUi, BoardColumnsAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemBoardColumnBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val dm = parent.resources.displayMetrics
        val lp = RecyclerView.LayoutParams((dm.widthPixels * 0.85f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        val m = (6 * dm.density).toInt()
        lp.setMargins(m, m, m, m)
        b.root.layoutParams = lp
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemBoardColumnBinding) : RecyclerView.ViewHolder(b.root) {
        private var data: ColumnUi? = null
        private val cards = BoardCardsAdapter(onCardClick, onCardMenu, onCardDrag)

        val columnId: String? get() = data?.column?.id
        val cardsRecycler: RecyclerView get() = b.cardsRecycler

        init {
            b.cardsRecycler.layoutManager = LinearLayoutManager(b.root.context)
            b.cardsRecycler.adapter = cards
            b.addCardButton.setOnClickListener { data?.let(onAddCard) }
            b.columnMenu.setOnClickListener { v ->
                data?.let { onColumnMenu(v, it, bindingAdapterPosition) }
            }
        }

        fun bind(c: ColumnUi) {
            data = c
            b.columnTitle.text = c.column.name
            cards.submitList(c.cards)
            setHighlight(false)
        }

        /** Outline shown while a card is dragged over this column. */
        fun setHighlight(on: Boolean) {
            val card = b.root
            card.strokeWidth = if (on) (2 * card.resources.displayMetrics.density).toInt() else 0
            if (on) card.strokeColor =
                MaterialColors.getColor(card, com.google.android.material.R.attr.colorPrimary)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ColumnUi>() {
            override fun areItemsTheSame(a: ColumnUi, b: ColumnUi) = a.column.id == b.column.id
            override fun areContentsTheSame(a: ColumnUi, b: ColumnUi) = a == b
        }
    }
}