package com.vasanth.vaultnote.ui.list

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vasanth.vaultnote.R
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.db.NoteType
import com.vasanth.vaultnote.databinding.FragmentNotesListBinding
import com.vasanth.vaultnote.util.ColorPicker
import com.vasanth.vaultnote.util.TextPrompt
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import android.widget.Toast
import com.vasanth.vaultnote.ui.board.BoardPicker

@AndroidEntryPoint
class NotesListFragment : Fragment(R.layout.fragment_notes_list) {

    private val vm: NotesListViewModel by viewModels()
    private var _b: FragmentNotesListBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: NotesAdapter
    private lateinit var prefs: SharedPreferences
    private lateinit var backCallback: OnBackPressedCallback
    private var grid = true

    /** Which toolbar is currently shown: null = none yet, true = selection, false = normal. */
    private var toolbarSelecting: Boolean? = null
    /** True while the toolbar menu is being swapped, so the search view does not clear the query. */
    private var swapping = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentNotesListBinding.bind(view)
        toolbarSelecting = null

        prefs = requireContext().getSharedPreferences("ui", Context.MODE_PRIVATE)
        grid = prefs.getBoolean("grid", true)

        // ----- list -----
        adapter = NotesAdapter(onClick = ::onNoteClick, onLongClick = ::onNoteLongClick)
        b.recycler.layoutManager =
            StaggeredGridLayoutManager(if (grid) 2 else 1, StaggeredGridLayoutManager.VERTICAL)
        b.recycler.adapter = adapter

        // ----- toolbar (menu is set up in renderToolbar) -----
        b.toolbar.setOnMenuItemClickListener { onToolbarItem(it) }

        // ----- FAB -----
        b.fab.setOnClickListener { v ->
            val popup = PopupMenu(requireContext(), v)
            popup.menu.add(0, 1, 0, "New note")
            popup.menu.add(0, 2, 1, "New checklist")
            popup.menu.add(0, 3, 2, "New board")
            popup.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> openEditor(null, NoteType.NOTE)
                    2 -> openEditor(null, NoteType.CHECKLIST)
                    else -> TextPrompt.show(requireContext(), "New board", "Board name", positive = "Create") { name ->
                        vm.createBoard(name) { id -> if (isAdded && view != null) openBoard(id) }
                    }
                }
                true
            }
            popup.show()
        }

        // back leaves selection mode first
        backCallback = requireActivity().onBackPressedDispatcher
            .addCallback(viewLifecycleOwner, false) { vm.clearSelection() }

        // ----- observe -----
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.items.collect { list ->
                        adapter.submitList(list)
                        b.emptyView.isVisible = list.isEmpty()
                        vm.retainSelection(list.filterIsInstance<ListItem.Row>().map { it.note.id }.toSet())
                        renderToolbar(vm.selected.value)
                    }
                }
                launch { vm.tags.collect { renderChips(it) } }
                launch {
                    vm.selected.collect { sel ->
                        adapter.setSelection(sel)
                        backCallback.isEnabled = sel.isNotEmpty()
                        renderToolbar(sel)
                    }
                }
            }
        }
    }

    // =====================================================================
    //  Toolbar: normal and selection
    // =====================================================================
    private fun renderToolbar(sel: Set<String>) {
        if (_b == null) return
        val selecting = sel.isNotEmpty()
        if (toolbarSelecting != selecting) {
            toolbarSelecting = selecting
            if (selecting) setupSelectionToolbar() else setupNormalToolbar()
        }
        if (selecting) updateSelectionToolbar(sel)
        b.fab.isVisible = !selecting && vm.mode == ListMode.ACTIVE
    }

    private fun setupNormalToolbar() {
        swapping = true
        val mode = vm.mode
        val tb = b.toolbar
        tb.menu.clear()
        tb.inflateMenu(R.menu.menu_notes_list)

        tb.title = when (mode) {
            ListMode.ARCHIVE -> "Archive"
            ListMode.TRASH -> "Trash"
            else -> getString(R.string.app_name)
        }
        if (mode != ListMode.ACTIVE) {
            tb.setNavigationIcon(R.drawable.ic_arrow_back)
            tb.setNavigationOnClickListener { findNavController().popBackStack() }
        } else {
            tb.navigationIcon = null
            tb.setNavigationOnClickListener(null)
        }

        val menu = tb.menu
        val searchItem = menu.findItem(R.id.action_search)
        searchItem.isVisible = mode == ListMode.ACTIVE
        menu.findItem(R.id.action_archive).isVisible = mode == ListMode.ACTIVE
        menu.findItem(R.id.action_trash).isVisible = mode == ListMode.ACTIVE
        menu.findItem(R.id.action_empty_trash).isVisible = mode == ListMode.TRASH
        menu.findItem(R.id.action_today).isVisible = mode == ListMode.ACTIVE
        menu.findItem(R.id.action_settings).isVisible = mode == ListMode.ACTIVE
        applyGrid(menu.findItem(R.id.action_view_toggle))

        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = "Search notes"
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                if (!swapping) vm.setQuery(newText.orEmpty())
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (!swapping) vm.setQuery("")
                return true
            }
        })

        // keep an active search visible after leaving selection mode
        val q = vm.currentQuery
        if (q.isNotBlank() && mode == ListMode.ACTIVE) {
            searchItem.expandActionView()
            searchView.setQuery(q, false)
            searchView.clearFocus()
        }
        swapping = false
    }

    private fun setupSelectionToolbar() {
        swapping = true
        val tb = b.toolbar
        tb.menu.clear()
        tb.inflateMenu(R.menu.menu_notes_selection)
        tb.setNavigationIcon(R.drawable.ic_sel_close)
        tb.setNavigationOnClickListener { vm.clearSelection() }
        swapping = false
    }

    private fun updateSelectionToolbar(sel: Set<String>) {
        val mode = vm.mode
        val menu = b.toolbar.menu
        val notes = selectedNotes()
        b.toolbar.title = "${sel.size} selected"

        menu.findItem(R.id.action_sel_pin)?.apply {
            isVisible = mode == ListMode.ACTIVE
            title = if (notes.isNotEmpty() && notes.all { it.pinned }) "Unpin" else "Pin"
        }
        menu.findItem(R.id.action_sel_archive)?.apply {
            isVisible = mode != ListMode.TRASH
            title = if (mode == ListMode.ARCHIVE) "Unarchive" else "Archive"
        }
        menu.findItem(R.id.action_sel_color)?.isVisible = mode != ListMode.TRASH
        menu.findItem(R.id.action_sel_trash)?.isVisible = mode != ListMode.TRASH
        menu.findItem(R.id.action_sel_restore)?.isVisible = mode == ListMode.TRASH
        menu.findItem(R.id.action_sel_delete_forever)?.isVisible = mode == ListMode.TRASH
    }

    private fun onToolbarItem(item: MenuItem): Boolean {
        when (item.itemId) {
            // ----- normal -----
            R.id.action_view_toggle -> {
                grid = !grid
                prefs.edit().putBoolean("grid", grid).apply()
                applyGrid(item)
            }
            R.id.action_archive -> openList(ListMode.ARCHIVE)
            R.id.action_trash -> openList(ListMode.TRASH)
            R.id.action_empty_trash -> confirmEmptyTrash()
            R.id.action_today -> findNavController().navigate(R.id.action_list_to_today)
            R.id.action_settings -> findNavController().navigate(R.id.action_list_to_settings)

            // ----- selection -----
            R.id.action_sel_all -> vm.selectAll(rows().map { it.id })
            R.id.action_sel_pin -> vm.pinSelected(!selectedNotes().all { it.pinned })
            R.id.action_sel_archive -> {
                val archive = vm.mode != ListMode.ARCHIVE
                val ids = vm.archiveSelected(archive)
                Snackbar.make(b.root, if (archive) "${ids.size} archived" else "${ids.size} unarchived", Snackbar.LENGTH_LONG)
                    .setAction("Undo") { vm.setArchivedIds(ids, !archive) }.show()
            }
            R.id.action_sel_color ->
                ColorPicker.show(requireContext(), "default") { vm.colorSelected(it) }
            R.id.action_sel_trash -> {
                val ids = vm.trashSelected()
                Snackbar.make(b.root, "${ids.size} moved to trash", Snackbar.LENGTH_LONG)
                    .setAction("Undo") { vm.restoreIds(ids) }.show()
            }
            R.id.action_sel_move_board -> moveSelectedToBoard()
            R.id.action_sel_restore -> vm.restoreSelected()
            R.id.action_sel_delete_forever -> confirmDeleteSelected()
            else -> return false
        }
        return true
    }

    private fun moveSelectedToBoard() {
        val chosen = selectedNotes()
        if (chosen.isEmpty()) return
        if (chosen.all { it.type == NoteType.BOARD }) {
            Toast.makeText(requireContext(), "A board cannot go inside another board", Toast.LENGTH_LONG).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val boards = vm.boardChoices()
            BoardPicker.show(requireContext(), boards, null) { boardId, columnId ->
                vm.moveSelectedToBoard(boardId, columnId) { n ->
                    _b?.let { Snackbar.make(it.root, "$n moved to the board", Snackbar.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun rows(): List<NoteEntity> =
        vm.items.value.filterIsInstance<ListItem.Row>().map { it.note }

    private fun selectedNotes(): List<NoteEntity> {
        val s = vm.selected.value
        return rows().filter { it.id in s }
    }

    private fun applyGrid(item: MenuItem?) {
        (b.recycler.layoutManager as StaggeredGridLayoutManager).spanCount = if (grid) 2 else 1
        item?.setIcon(if (grid) R.drawable.ic_view_agenda else R.drawable.ic_grid_view)
    }

    private fun renderChips(tags: List<String>) {
        val selected = vm.selectedTag.value
        if (selected != null && selected !in tags) vm.selectTag(null)

        b.tagScroll.isVisible = vm.mode == ListMode.ACTIVE && tags.isNotEmpty()
        b.chipGroup.removeAllViews()

        fun addChip(label: String, tag: String?) {
            val chip = layoutInflater.inflate(R.layout.chip_filter, b.chipGroup, false) as Chip
            chip.id = View.generateViewId()
            chip.text = label
            chip.isChecked = vm.selectedTag.value == tag
            chip.setOnClickListener { vm.selectTag(tag) }
            b.chipGroup.addView(chip)
        }
        addChip("All", null)
        tags.forEach { addChip(it, it) }
    }

    // =====================================================================
    //  Navigation and clicks
    // =====================================================================
    private fun openList(mode: String) =
        findNavController().navigate(R.id.action_list_to_list, bundleOf("mode" to mode))

    private fun openEditor(noteId: String?, type: String) =
        findNavController().navigate(
            R.id.action_list_to_editor, bundleOf("noteId" to noteId, "type" to type)
        )

    private fun openBoard(id: String) =
        findNavController().navigate(R.id.action_list_to_board, bundleOf("boardId" to id))

    private fun onNoteClick(note: NoteEntity) {
        when {
            vm.selected.value.isNotEmpty() -> vm.toggleSelected(note.id)
            vm.mode == ListMode.TRASH -> view?.let { showTrashMenu(it, note) }
            note.type == NoteType.BOARD -> openBoard(note.id)
            else -> openEditor(note.id, note.type)
        }
    }

    private fun onNoteLongClick(anchor: View, note: NoteEntity) {
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        vm.toggleSelected(note.id)
    }

    // ----- trash tap menu -----
    private fun showTrashMenu(anchor: View, note: NoteEntity) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Restore")
        popup.menu.add(0, 2, 1, "Delete forever")
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> vm.restore(note.id)
                2 -> confirmDeleteForever(note)
            }
            true
        }
        popup.show()
    }

    private fun confirmDeleteForever(note: NoteEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete forever?")
            .setMessage("This note cannot be recovered.")
            .setPositiveButton("Delete") { _, _ -> vm.deleteForever(note.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSelected() {
        val n = vm.selected.value.size
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete $n forever?")
            .setMessage("They cannot be recovered.")
            .setPositiveButton("Delete") { _, _ -> vm.deleteSelectedForever() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmEmptyTrash() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Empty trash?")
            .setMessage("All notes in the trash will be deleted forever.")
            .setPositiveButton("Empty") { _, _ -> vm.emptyTrash() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}