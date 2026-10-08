package com.vasanth.vaultnote.ui.board

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.databinding.ItemBoardColumnBinding

class BoardColumnsAdapter(
    private val onAddCard: (ColumnUi) -> Unit,
    private val onColumnMenu: (View, ColumnUi, Int) -> Unit,
    private val onCardClick: (NoteEntity) -> Unit,
    private val onCardMenu: (View, NoteEntity) -> Unit,
    private val onReorder: (String, List<String>) -> Unit
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
        private val cards = BoardCardsAdapter(onCardClick, onCardMenu)

        init {
            b.cardsRecycler.layoutManager = LinearLayoutManager(b.root.context)
            b.cardsRecycler.adapter = cards
            b.cardsRecycler.itemAnimator = null
            ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
            ) {
                override fun onMove(
                    rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
                ) = cards.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)

                override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

                override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
                    super.onSelectedChanged(vh, state)
                    if (state == ItemTouchHelper.ACTION_STATE_DRAG) cards.dragging = true
                }

                override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                    super.clearView(rv, vh)
                    cards.dragging = false
                    data?.let { onReorder(it.column.id, cards.ids()) }
                }
            }).attachToRecyclerView(b.cardsRecycler)

            b.addCardButton.setOnClickListener { data?.let(onAddCard) }
            b.columnMenu.setOnClickListener { v ->
                data?.let { onColumnMenu(v, it, bindingAdapterPosition) }
            }
        }

        fun bind(c: ColumnUi) {
            data = c
            b.columnTitle.text = "${c.column.name} · ${c.cards.size}"
            cards.submit(c.cards)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ColumnUi>() {
            override fun areItemsTheSame(a: ColumnUi, b: ColumnUi) = a.column.id == b.column.id
            override fun areContentsTheSame(a: ColumnUi, b: ColumnUi) = a == b
        }
    }
}