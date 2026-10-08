package com.vasanth.vaultnote.ui.board

import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentBoardBinding.bind(view)

        b.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        b.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        b.toolbar.inflateMenu(R.menu.menu_board)
        b.toolbar.setOnMenuItemClickListener { item ->
            val board = ui?.board
            when (item.itemId) {
                R.id.action_add_column -> {
                    TextPrompt.show(requireContext(), "New column", "Column name", positive = "Add") { vm.addColumn(it) }
                    true
                }
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

        adapter = BoardColumnsAdapter(
            onAddCard = { col ->
                TextPrompt.show(requireContext(), "New card", "Title", positive = "Add") {
                    vm.addCard(col.column.id, it)
                }
            },
            onColumnMenu = ::showColumnMenu,
            onCardClick = { card ->
                findNavController().navigate(
                    R.id.action_global_editor, bundleOf("noteId" to card.id, "type" to card.type)
                )
            },
            onCardMenu = ::showCardMenu,
            onReorder = { colId, ids -> vm.reorder(colId, ids) }
        )
        b.columnsRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        b.columnsRecycler.adapter = adapter

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
                        adapter.submitList(state.columns)
                    }
                }
            }
        }
    }

    // ----- columns -----
    private fun showColumnMenu(anchor: View, col: ColumnUi, index: Int) {
        val count = ui?.columns?.size ?: return
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Rename")
        if (index > 0) popup.menu.add(0, 2, 1, "Move left")
        if (index < count - 1) popup.menu.add(0, 3, 2, "Move right")
        if (count > 1) popup.menu.add(0, 4, 3, "Delete column")
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> TextPrompt.show(requireContext(), "Rename column", "Column name", col.column.name) { n ->
                    vm.renameColumn(col.column.id, n)
                }
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

    // ----- cards -----
    private fun showCardMenu(anchor: View, card: NoteEntity) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Move to column…")
        popup.menu.add(0, 2, 1, "Delete")
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> pickColumn(card)
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
        super.onDestroyView()
        _b = null
    }
}