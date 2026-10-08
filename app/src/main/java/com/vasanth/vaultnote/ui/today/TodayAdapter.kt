package com.vasanth.vaultnote.ui.today

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.vasanth.vaultnote.data.ChecklistJson
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.db.NoteType
import com.vasanth.vaultnote.databinding.ItemHeaderBinding
import com.vasanth.vaultnote.databinding.ItemReminderBinding
import com.vasanth.vaultnote.ui.list.ListItem
import com.vasanth.vaultnote.util.ReminderFormat

class TodayAdapter(
    private val onClick: (NoteEntity) -> Unit,
    private val onDone: (NoteEntity) -> Unit
) : ListAdapter<ListItem, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int) =
        if (getItem(position) is ListItem.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderVH(ItemHeaderBinding.inflate(inflater, parent, false))
        else RowVH(ItemReminderBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ListItem.Header -> (holder as HeaderVH).b.headerText.text = item.title
            is ListItem.Row -> (holder as RowVH).bind(item.note)
        }
    }

    class HeaderVH(val b: ItemHeaderBinding) : RecyclerView.ViewHolder(b.root)

    inner class RowVH(private val b: ItemReminderBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnClickListener { currentNote()?.let(onClick) }
            b.doneButton.setOnClickListener { currentNote()?.let(onDone) }
        }

        private fun currentNote(): NoteEntity? {
            val p = bindingAdapterPosition
            return if (p == RecyclerView.NO_POSITION) null else (getItem(p) as? ListItem.Row)?.note
        }

        fun bind(n: NoteEntity) {
            b.titleText.text = displayTitle(n)
            val at = n.reminderAt ?: 0L
            b.timeText.text = ReminderFormat.text(b.root.context, at, n.repeat)
            val overdue = at < System.currentTimeMillis()
            b.timeText.setTextColor(
                MaterialColors.getColor(
                    b.root,
                    if (overdue) com.google.android.material.R.attr.colorError
                    else com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )
        }
    }

    private fun displayTitle(n: NoteEntity): String {
        if (n.locked) return "🔒 Locked note"
        if (n.title.isNotBlank()) return n.title
        val first = if (n.type == NoteType.CHECKLIST)
            ChecklistJson.decode(n.itemsJson).firstOrNull { it.text.isNotBlank() }?.text
        else n.body.lineSequence().firstOrNull { it.isNotBlank() }
        return first ?: "Untitled"
    }

    companion object {
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