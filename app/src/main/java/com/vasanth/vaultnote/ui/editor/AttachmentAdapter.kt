package com.vasanth.vaultnote.ui.editor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.vasanth.vaultnote.data.Attachment
import com.vasanth.vaultnote.data.AttachmentStore
import com.vasanth.vaultnote.databinding.ItemAttachmentBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AttachmentAdapter(
    private val store: AttachmentStore,
    private val scope: CoroutineScope,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<AttachmentAdapter.VH>() {

    private var items = listOf<Attachment>()

    fun submit(list: List<Attachment>) {
        if (list == items) return
        items = list
        notifyDataSetChanged()
    }

    /** Called when a file arrived from Drive. */
    fun refresh() = notifyDataSetChanged()

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemAttachmentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun onViewRecycled(holder: VH) {
        holder.cancel()
    }

    inner class VH(private val b: ItemAttachmentBinding) : RecyclerView.ViewHolder(b.root) {
        private var job: Job? = null

        init {
            b.root.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(p)
            }
        }

        fun cancel() { job?.cancel() }

        fun bind(a: Attachment) {
            job?.cancel()
            b.thumb.setImageDrawable(null)
            b.placeholder.isVisible = true
            b.placeholder.text = if (store.exists(a.id)) "…" else "⬇"
            job = scope.launch {
                val bmp = store.load(a.id, 256)
                if (bmp != null) {
                    b.thumb.setImageBitmap(bmp)
                    b.placeholder.isVisible = false
                } else {
                    b.placeholder.text = if (store.exists(a.id)) "⚠" else "⬇"
                }
            }
        }
    }
}