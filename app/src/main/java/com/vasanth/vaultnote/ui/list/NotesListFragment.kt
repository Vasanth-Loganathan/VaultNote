package com.vasanth.vaultnote.ui.list

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class NotesListFragment : Fragment(R.layout.fragment_notes_list) {

    private val vm: NotesListViewModel by viewModels()
    private var _b: FragmentNotesListBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: NotesAdapter
    private var grid = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentNotesListBinding.bind(view)

        val prefs = requireContext().getSharedPreferences("ui", Context.MODE_PRIVATE)
        grid = prefs.getBoolean("grid", true)
        val mode = vm.mode

        // ----- list -----
        adapter = NotesAdapter(onClick = ::onNoteClick, onLongClick = ::showNoteMenu)
        b.recycler.layoutManager =
            StaggeredGridLayoutManager(if (grid) 2 else 1, StaggeredGridLayoutManager.VERTICAL)
        b.recycler.adapter = adapter

        // ----- toolbar -----
        b.toolbar.title = when (mode) {
            ListMode.ARCHIVE -> "Archive"
            ListMode.TRASH -> "Trash"
            else -> getString(R.string.app_name)
        }
        if (mode != ListMode.ACTIVE) {
            b.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            b.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        }
        b.toolbar.inflateMenu(R.menu.menu_notes_list)
        val menu = b.toolbar.menu
        val searchItem = menu.findItem(R.id.action_search)
        searchItem.isVisible = mode == ListMode.ACTIVE
        menu.findItem(R.id.action_archive).isVisible = mode == ListMode.ACTIVE
        menu.findItem(R.id.action_trash).isVisible = mode == ListMode.ACTIVE
        menu.findItem(R.id.action_empty_trash).isVisible = mode == ListMode.TRASH
        applyGrid(menu.findItem(R.id.action_view_toggle))

        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = "Search notes"
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                vm.setQuery(newText.orEmpty()); return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                vm.setQuery(""); return true
            }
        })

        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_view_toggle -> {
                    grid = !grid
                    prefs.edit().putBoolean("grid", grid).apply()
                    applyGrid(item); true
                }
                R.id.action_archive -> { openList(ListMode.ARCHIVE); true }
                R.id.action_trash -> { openList(ListMode.TRASH); true }
                R.id.action_empty_trash -> { confirmEmptyTrash(); true }
                else -> false
            }
        }

        // ----- FAB -----
        b.fab.isVisible = mode == ListMode.ACTIVE
        b.fab.setOnClickListener { v ->
            val popup = PopupMenu(requireContext(), v)
            popup.menu.add(0, 1, 0, "New note")
            popup.menu.add(0, 2, 1, "New checklist")
            popup.setOnMenuItemClickListener {
                openEditor(null, if (it.itemId == 1) NoteType.NOTE else NoteType.CHECKLIST)
                true
            }
            popup.show()
        }

        // ----- observe -----
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.items.collect {
                        adapter.submitList(it)
                        b.emptyView.isVisible = it.isEmpty()
                    }
                }
                launch { vm.tags.collect { renderChips(it) } }
            }
        }
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

    // ----- navigation -----
    private fun openList(mode: String) =
        findNavController().navigate(R.id.action_list_to_list, bundleOf("mode" to mode))

    private fun openEditor(noteId: String?, type: String) =
        findNavController().navigate(
            R.id.action_list_to_editor, bundleOf("noteId" to noteId, "type" to type)
        )

    private fun onNoteClick(note: NoteEntity) {
        if (vm.mode == ListMode.TRASH) {
            view?.let { showNoteMenu(it, note) }
        } else {
            openEditor(note.id, note.type)
        }
    }

    // ----- long-press menu -----
    private fun showNoteMenu(anchor: View, note: NoteEntity) {
        val popup = PopupMenu(requireContext(), anchor)
        val m = popup.menu
        when (vm.mode) {
            ListMode.TRASH -> {
                m.add(0, 1, 0, "Restore")
                m.add(0, 2, 1, "Delete forever")
            }
            ListMode.ARCHIVE -> {
                m.add(0, 3, 0, "Unarchive")
                m.add(0, 4, 1, "Move to trash")
            }
            else -> {
                m.add(0, 5, 0, if (note.pinned) "Unpin" else "Pin")
                m.add(0, 6, 1, "Archive")
                m.add(0, 4, 2, "Move to trash")
            }
        }
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> vm.restore(note.id)
                2 -> confirmDeleteForever(note)
                3 -> vm.setArchived(note.id, false)
                4 -> {
                    vm.moveToTrash(note.id)
                    Snackbar.make(b.root, "Moved to trash", Snackbar.LENGTH_LONG)
                        .setAction("Undo") { vm.restore(note.id) }.show()
                }
                5 -> vm.setPinned(note.id, !note.pinned)
                6 -> vm.setArchived(note.id, true)
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