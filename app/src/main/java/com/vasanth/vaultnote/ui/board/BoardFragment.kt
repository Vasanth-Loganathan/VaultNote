package com.vasanth.vaultnote.ui.board

import android.content.ClipData
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioGroup
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.vasanth.vaultnote.data.db.NoteType
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vasanth.vaultnote.R
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.databinding.FragmentBoardBinding
import com.vasanth.vaultnote.util.ColorPicker
import com.vasanth.vaultnote.util.NoteColors
import com.vasanth.vaultnote.util.TextPrompt
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BoardFragment : Fragment(R.layout.fragment_board) {

    private val vm: BoardViewModel by viewModels()
    private var _b: FragmentBoardBinding? = null
    private val b get() = _b!!
    private var ui: BoardUi? = null
    private lateinit var adapter: BoardColumnsAdapter

    // ----- drag state -----
    private val handler = Handler(Looper.getMainLooper())
    private var dragCardId: String? = null
    private var dragView: View? = null
    private var lastX = 0f
    private var lastY = 0f
    private var hoverVh: BoardColumnsAdapter.VH? = null
    private var hoverIndex = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (dragCardId == null || _b == null) return
            autoScroll()
            updateHover()
            handler.postDelayed(this, 16)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentBoardBinding.bind(view)

        b.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        b.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        b.toolbar.inflateMenu(R.menu.menu_board)
        b.toolbar.setOnMenuItemClickListener { item ->
            val board = ui?.board
            when (item.itemId) {
                R.id.action_add_column -> { promptAddColumn(); true }
                R.id.action_rename_board -> {
                    if (board != null)
                        TextPrompt.show(requireContext(), "Rename board", "Board name", board.title) { vm.renameBoard(it) }
                    true
                }
                R.id.action_board_color -> {
                    if (board != null) ColorPicker.show(requireContext(), board.color) { vm.setColor(it) }
                    true
                }
                else -> false
            }
        }
        b.emptyAddColumn.setOnClickListener { promptAddColumn() }

        adapter = BoardColumnsAdapter(
            onAddCard = { col -> promptNewCard(col.column.id) },
            onColumnMenu = ::showColumnMenu,
            onCardClick = { card ->
                findNavController().navigate(
                    R.id.action_global_editor, bundleOf("noteId" to card.id, "type" to card.type)
                )
            },
            onCardMenu = ::showCardMenu,
            onCardDrag = ::startDrag
        )
        b.columnsRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        b.columnsRecycler.adapter = adapter
        b.columnsRecycler.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    val id = e.localState as? String ?: return@setOnDragListener false
                    dragCardId = id
                    handler.removeCallbacks(ticker)
                    handler.post(ticker)
                    true
                }
                DragEvent.ACTION_DRAG_LOCATION -> { lastX = e.x; lastY = e.y; true }
                DragEvent.ACTION_DROP -> {
                    lastX = e.x; lastY = e.y
                    updateHover()
                    val id = dragCardId
                    val col = hoverVh?.columnId
                    if (id != null && col != null) vm.dropCard(id, col, hoverIndex)
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> { endDrag(); true }
                else -> true
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { state ->
                    if (state == null) {
                        findNavController().popBackStack()
                    } else {
                        ui = state
                        b.toolbar.title = state.board.title.ifBlank { "Board" }
                        val c = NoteColors.background(requireContext(), state.board.color)
                        b.root.setBackgroundColor(c)
                        b.toolbar.setBackgroundColor(c)
                        b.emptyView.isVisible = state.columns.isEmpty()
                        adapter.submitList(state.columns)
                    }
                }
            }
        }
    }

    private fun promptNewCard(columnId: String) {
        val ctx = requireContext()
        val pad = dp(20)
        val layout = TextInputLayout(ctx).apply { hint = "Title" }
        val edit = TextInputEditText(layout.context).apply { setSingleLine() }
        layout.addView(edit)

        val rbNote = MaterialRadioButton(ctx).apply { id = View.generateViewId(); text = "Note"; isChecked = true }
        val rbList = MaterialRadioButton(ctx).apply { id = View.generateViewId(); text = "Checklist" }
        val group = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            addView(rbNote)
            addView(rbList)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(layout)
            addView(group)
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("New card")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) {
                    vm.addCard(columnId, t, if (rbList.isChecked) NoteType.CHECKLIST else NoteType.NOTE)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            edit.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    private fun promptAddColumn() =
        TextPrompt.show(requireContext(), "New column", "Column name", positive = "Add") { vm.addColumn(it) }

    // =====================================================================
    //  Drag and drop between columns
    // =====================================================================
    private fun startDrag(view: View, card: NoteEntity) {
        if (dragCardId != null) return
        val started = view.startDragAndDrop(
            ClipData.newPlainText("card", ""), View.DragShadowBuilder(view), card.id, 0
        )
        if (started) {
            dragView = view
            view.alpha = 0.35f
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun endDrag() {
        handler.removeCallbacks(ticker)
        dragCardId = null
        dragView?.alpha = 1f
        dragView = null
        hoverVh?.setHighlight(false)
        hoverVh = null
        _b?.insertLine?.isVisible = false
    }

    /** Bounds of v in the coordinate space of `relativeTo`. */
    private fun rectOf(v: View, relativeTo: View): Rect {
        val a = IntArray(2)
        val o = IntArray(2)
        v.getLocationInWindow(a)
        relativeTo.getLocationInWindow(o)
        val l = a[0] - o[0]
        val t = a[1] - o[1]
        return Rect(l, t, l + v.width, t + v.height)
    }

    private fun autoScroll() {
        val rv = _b?.columnsRecycler ?: return
        val edge = dp(56)
        val speed = dp(12)
        if (lastX < edge) rv.scrollBy(-speed, 0)
        else if (lastX > rv.width - edge) rv.scrollBy(speed, 0)

        hoverVh?.cardsRecycler?.let { cards ->
            val r = rectOf(cards, rv)
            if (lastX >= r.left && lastX <= r.right) {
                if (lastY < r.top + edge) cards.scrollBy(0, -speed)
                else if (lastY > r.bottom - edge) cards.scrollBy(0, speed)
            }
        }
    }

    /** Finds the column under the finger, the insertion index, and positions the blue line. */
    private fun updateHover() {
        val bd = _b ?: return
        val rv = bd.columnsRecycler

        var best: BoardColumnsAdapter.VH? = null
        var bestDist = Int.MAX_VALUE
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val vh = rv.getChildViewHolder(child) as? BoardColumnsAdapter.VH ?: continue
            val x = lastX.toInt()
            val d = when {
                x < child.left -> child.left - x
                x > child.right -> x - child.right
                else -> 0
            }
            if (d < bestDist) { bestDist = d; best = vh }
        }
        if (best !== hoverVh) {
            hoverVh?.setHighlight(false)
            best?.setHighlight(true)
            hoverVh = best
        }
        val vh = best
        if (vh == null) { bd.insertLine.isVisible = false; return }

        val cards = vh.cardsRecycler
        val cardsRect = rectOf(cards, rv)
        var index = 0
        var lineY = cardsRect.top + dp(4)
        for (i in 0 until cards.childCount) {
            val c = cards.getChildAt(i)
            val pos = cards.getChildAdapterPosition(c)
            if (pos == RecyclerView.NO_POSITION) continue
            val r = rectOf(c, rv)
            if (lastY < r.centerY()) { index = pos; lineY = r.top - dp(2); break }
            index = pos + 1
            lineY = r.bottom + dp(2)
        }
        hoverIndex = index

        val pad = dp(8)
        val line = bd.insertLine
        val w = (cardsRect.width() - 2 * pad).coerceAtLeast(dp(40))
        val lp = line.layoutParams
        if (lp.width != w) { lp.width = w; line.layoutParams = lp }
        line.translationX = (cardsRect.left + pad).toFloat()
        line.translationY = lineY.coerceIn(cardsRect.top, cardsRect.bottom).toFloat()
        line.isVisible = true
    }

    // =====================================================================
    //  Menus
    // =====================================================================
    private fun showColumnMenu(anchor: View, col: ColumnUi, index: Int) {
        val count = ui?.columns?.size ?: return
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Rename")
        popup.menu.add(0, 5, 1, "Color")
        if (index > 0) popup.menu.add(0, 2, 2, "Move left")
        if (index < count - 1) popup.menu.add(0, 3, 3, "Move right")
        popup.menu.add(0, 4, 4, "Delete column")
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> TextPrompt.show(requireContext(), "Rename column", "Column name", col.column.name) { n ->
                    vm.renameColumn(col.column.id, n)
                }
                5 -> ColorPicker.show(requireContext(), col.column.color) { c -> vm.setColumnColor(col.column.id, c) }
                2 -> vm.moveColumn(col.column.id, -1)
                3 -> vm.moveColumn(col.column.id, 1)
                4 -> confirmDeleteColumn(col)
            }
            true
        }
        popup.show()
    }

    private fun confirmDeleteColumn(col: ColumnUi) {
        val others = ui?.columns?.filter { it.column.id != col.column.id } ?: return
        if (col.cards.isEmpty()) { vm.deleteColumn(col.column.id, null); return }
        val labels = others.map { "Move cards to \"${it.column.name}\"" } + "Keep cards as normal notes"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete \"${col.column.name}\"?")
            .setItems(labels.toTypedArray()) { _, i ->
                vm.deleteColumn(col.column.id, others.getOrNull(i)?.column?.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCardMenu(anchor: View, card: NoteEntity) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Move to column…")
        popup.menu.add(0, 3, 1, "Move to another board…")
        popup.menu.add(0, 4, 2, "Move out of board")
        popup.menu.add(0, 2, 3, "Delete")
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> pickColumn(card)
                3 -> viewLifecycleOwner.lifecycleScope.launch {
                    val boards = vm.boardChoices()
                    BoardPicker.show(requireContext(), boards, vm.boardId) { boardId, columnId ->
                        vm.moveToBoard(card, boardId, columnId)
                        _b?.let { bd -> Snackbar.make(bd.root, "Moved to another board", Snackbar.LENGTH_SHORT).show() }
                    }
                }
                4 -> {
                    vm.moveOut(card)
                    Snackbar.make(b.root, "Moved out of board. It is now a normal note", Snackbar.LENGTH_LONG)
                        .setAction("Undo") { vm.putBack(card) }.show()
                }
                2 -> {
                    vm.trashCard(card)
                    Snackbar.make(b.root, "Card moved to trash", Snackbar.LENGTH_LONG)
                        .setAction("Undo") { vm.restoreCard(card) }.show()
                }
            }
            true
        }
        popup.show()
    }

    private fun pickColumn(card: NoteEntity) {
        val others = ui?.columns?.filter { it.column.id != card.columnId } ?: return
        if (others.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Move to")
            .setItems(others.map { it.column.name }.toTypedArray()) { _, i ->
                vm.moveCard(card, others[i].column.id)
            }
            .show()
    }

    override fun onDestroyView() {
        endDrag()
        super.onDestroyView()
        _b = null
    }
}