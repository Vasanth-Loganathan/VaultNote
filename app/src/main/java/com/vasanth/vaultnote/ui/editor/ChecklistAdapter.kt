package com.vasanth.vaultnote.ui.editor

import android.content.Context
import android.graphics.Paint
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.vasanth.vaultnote.data.CheckItem
import com.vasanth.vaultnote.databinding.ItemCheckBinding

class ChecklistAdapter(
    private val onChanged: () -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<ChecklistAdapter.VH>() {

    val items = mutableListOf<CheckItem>()
    private var focusId: String? = null

    /** Unchecked items first, ticked items at the bottom. */
    fun setItems(list: List<CheckItem>) {
        items.clear()
        items.addAll(list.filter { !it.done } + list.filter { it.done })
        notifyDataSetChanged()
    }

    fun addItemAtEnd() = insert(items.count { !it.done })

    private fun insert(index: Int) {
        val item = CheckItem()
        items.add(index, item)
        focusId = item.id
        notifyItemInserted(index)
        onChanged()
    }

    private fun insertAfter(pos: Int) {
        if (pos == RecyclerView.NO_POSITION) return
        insert(if (items[pos].done) items.count { !it.done } else pos + 1)
    }

    private fun toggle(pos: Int) {
        if (pos == RecyclerView.NO_POSITION) return
        val updated = items[pos].copy(done = !items[pos].done)
        items.removeAt(pos)
        val target = if (updated.done) items.size else items.count { !it.done }
        items.add(target, updated)
        if (target != pos) notifyItemMoved(pos, target)
        notifyItemChanged(target)
        onChanged()
    }

    private fun remove(pos: Int) {
        if (pos == RecyclerView.NO_POSITION) return
        items.removeAt(pos)
        notifyItemRemoved(pos)
        onChanged()
    }

    /** Drag and drop; an item can only move among items with the same ticked state. */
    fun move(from: Int, to: Int): Boolean {
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
        if (items[from].done != items[to].done) return false
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
        return true
    }

    inner class VH(val b: ItemCheckBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.textEdit.imeOptions = EditorInfo.IME_ACTION_NEXT
            b.textEdit.setRawInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            )

            b.checkbox.setOnClickListener { toggle(bindingAdapterPosition) }
            b.removeButton.setOnClickListener { remove(bindingAdapterPosition) }

            b.dragHandle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(this@VH)
                false
            }
            b.textEdit.doAfterTextChanged { text ->
                val p = bindingAdapterPosition
                val s = text?.toString().orEmpty()
                if (p != RecyclerView.NO_POSITION && items[p].text != s) {
                    items[p] = items[p].copy(text = s)
                    onChanged()
                }
            }
            b.textEdit.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_NEXT) {
                    insertAfter(bindingAdapterPosition); true
                } else false
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCheckBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val edit = holder.b.textEdit

        edit.setText(item.text)
        holder.b.checkbox.isChecked = item.done
        edit.paintFlags = if (item.done) edit.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        else edit.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        edit.alpha = if (item.done) 0.5f else 1f
        holder.b.dragHandle.alpha = if (item.done) 0.2f else 1f

        if (item.id == focusId) {
            focusId = null
            edit.requestFocus()
            edit.post {
                val imm = edit.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}