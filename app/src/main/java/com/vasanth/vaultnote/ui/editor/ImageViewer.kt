package com.vasanth.vaultnote.ui.editor

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vasanth.vaultnote.data.Attachment
import com.vasanth.vaultnote.data.AttachmentStore
import com.vasanth.vaultnote.databinding.DialogImageViewerBinding
import com.vasanth.vaultnote.util.ScreenGuard
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Full-screen, swipeable image viewer with delete. Closes itself when the app goes to the background. */
object ImageViewer {

    fun show(
        ctx: Context,
        store: AttachmentStore,
        list: List<Attachment>,
        start: Int,
        owner: LifecycleOwner,
        onDelete: (String) -> Unit
    ) {
        if (list.isEmpty()) return
        val items = list.toMutableList()
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val b = DialogImageViewerBinding.inflate(LayoutInflater.from(ctx))
        dialog.setContentView(b.root)
        dialog.window?.let {
            ScreenGuard.set(it, PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("secure_screen", true))
        }

        val lm = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        val adapter = PageAdapter(store, owner.lifecycleScope, items)
        val snap = PagerSnapHelper()
        b.pager.layoutManager = lm
        b.pager.adapter = adapter
        snap.attachToRecyclerView(b.pager)
        b.pager.scrollToPosition(start.coerceIn(0, items.size - 1))

        fun current(): Int = snap.findSnapView(lm)?.let { lm.getPosition(it) } ?: start.coerceIn(0, items.size - 1)
        fun updateCounter() { b.counter.text = "${current() + 1} / ${items.size}" }

        b.counter.text = "${start + 1} / ${items.size}"
        b.pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) updateCounter()
            }
        })

        b.closeButton.setOnClickListener { dialog.dismiss() }
        b.deleteButton.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Delete image?")
                .setPositiveButton("Delete") { _, _ ->
                    val i = current()
                    if (i in items.indices) {
                        val att = items.removeAt(i)
                        adapter.notifyItemRemoved(i)
                        onDelete(att.id)
                    }
                    if (items.isEmpty()) dialog.dismiss() else b.pager.post { updateCounter() }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { dialog.dismiss() }
        }
        owner.lifecycle.addObserver(observer)
        dialog.setOnDismissListener { owner.lifecycle.removeObserver(observer) }
        dialog.show()
    }

    private class PageAdapter(
        private val store: AttachmentStore,
        private val scope: kotlinx.coroutines.CoroutineScope,
        private val items: List<Attachment>
    ) : RecyclerView.Adapter<PageAdapter.VH>() {

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            return VH(iv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.job?.cancel()
            holder.iv.setImageDrawable(null)
            val id = items[position].id
            holder.job = scope.launch {
                store.load(id, 1600)?.let { holder.iv.setImageBitmap(it) }
            }
        }

        override fun onViewRecycled(holder: VH) { holder.job?.cancel() }

        class VH(val iv: ImageView) : RecyclerView.ViewHolder(iv) {
            var job: Job? = null
        }
    }
}