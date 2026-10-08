package com.vasanth.vaultnote.ui.editor

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vasanth.vaultnote.R
import com.vasanth.vaultnote.data.ChecklistJson
import com.vasanth.vaultnote.data.db.NoteType
import com.vasanth.vaultnote.databinding.DialogAddTagBinding
import com.vasanth.vaultnote.databinding.FragmentNoteEditorBinding
import com.vasanth.vaultnote.util.ColorPicker
import com.vasanth.vaultnote.util.NoteColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class NoteEditorFragment : Fragment(R.layout.fragment_note_editor) {

    private val vm: NoteEditorViewModel by viewModels()
    private var _b: FragmentNoteEditorBinding? = null
    private val b get() = _b!!

    private lateinit var checkAdapter: ChecklistAdapter

    /** false until the screen has been filled from the ViewModel; blocks accidental saves. */
    private var populated = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentNoteEditorBinding.bind(view)
        populated = false

        // ----- toolbar -----
        b.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        b.toolbar.inflateMenu(R.menu.menu_note_editor)
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_pin -> { vm.togglePinned(); true }
                R.id.action_archive -> {
                    vm.toggleArchived()
                    toast(if (vm.state.value.archived) "Archived" else "Unarchived")
                    findNavController().popBackStack(); true
                }
                R.id.action_delete -> {
                    vm.deleteNote()
                    toast("Moved to trash")
                    findNavController().popBackStack(); true
                }
                else -> false
            }
        }

        // ----- checklist -----
        lateinit var helper: ItemTouchHelper
        checkAdapter = ChecklistAdapter(
            onChanged = ::push,
            onStartDrag = { helper.startDrag(it) }
        )
        helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ) = checkAdapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh); push()
            }
        })
        b.checklistRecycler.layoutManager = LinearLayoutManager(requireContext())
        b.checklistRecycler.adapter = checkAdapter
        b.checklistRecycler.itemAnimator = null
        helper.attachToRecyclerView(b.checklistRecycler)
        b.addItemButton.setOnClickListener { checkAdapter.addItemAtEnd() }

        // ----- text -----
        b.titleEdit.doAfterTextChanged { push() }
        b.bodyEdit.doAfterTextChanged { push() }

        // ----- bottom bar -----
        b.colorButton.setOnClickListener {
            ColorPicker.show(requireContext(), vm.state.value.color) { vm.setColor(it) }
        }
        b.tagButton.setOnClickListener { showAddTagDialog() }

        // ----- observe -----
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.loaded.collect { if (it && !populated) populate() } }
                launch {
                    vm.state.map { Triple(it.pinned, it.archived, it.color) }
                        .distinctUntilChanged()
                        .collect { (pinned, archived, color) ->
                            updateMenu(pinned, archived)
                            applyColor(color)
                        }
                }
                launch { vm.tags.collect { renderTags(it) } }
            }
        }
    }

    private fun populate() {
        val n = vm.state.value

        b.titleEdit.setText(n.title)
        if (n.type == NoteType.CHECKLIST) {
            b.bodyEdit.isVisible = false
            b.checklistGroup.isVisible = true
            checkAdapter.setItems(ChecklistJson.decode(n.itemsJson))
        } else {
            b.bodyEdit.isVisible = true
            b.checklistGroup.isVisible = false
            b.bodyEdit.setText(n.body)
        }

        b.editedText.text = if (vm.isNew) "" else "Edited " + DateUtils.getRelativeTimeSpanString(
            n.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )

        populated = true

        if (vm.isNew) {
            val target = if (n.type == NoteType.CHECKLIST) b.titleEdit else b.titleEdit
            target.requestFocus()
            target.post {
                val imm = target.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    /** Send what is on screen to the ViewModel (which auto-saves after a short pause). */
    private fun push() {
        if (!populated || _b == null) return
        val isChecklist = vm.state.value.type == NoteType.CHECKLIST
        vm.updateContent(
            title = b.titleEdit.text.toString(),
            body = b.bodyEdit.text.toString(),
            items = if (isChecklist) checkAdapter.items.toList() else null
        )
    }

    private fun updateMenu(pinned: Boolean, archived: Boolean) {
        val menu = b.toolbar.menu
        menu.findItem(R.id.action_pin)?.apply {
            title = if (pinned) "Unpin" else "Pin"
            icon?.mutate()?.alpha = if (pinned) 255 else 110
        }
        menu.findItem(R.id.action_archive)?.title = if (archived) "Unarchive" else "Archive"
    }

    private fun applyColor(color: String) {
        val c = NoteColors.background(requireContext(), color)
        b.editorRoot.setBackgroundColor(c)
        b.toolbar.setBackgroundColor(c)
        b.bottomBar.setBackgroundColor(c)
        requireActivity().window.decorView.setBackgroundColor(c)   // status/nav bar area
    }

    private fun renderTags(tags: List<String>) {
        b.tagGroup.isVisible = tags.isNotEmpty()
        b.tagGroup.removeAllViews()
        tags.forEach { tag ->
            val chip = layoutInflater.inflate(R.layout.chip_tag, b.tagGroup, false) as Chip
            chip.text = tag
            chip.setOnCloseIconClickListener { vm.removeTag(tag) }
            b.tagGroup.addView(chip)
        }
    }

    private fun showAddTagDialog() {
        val d = DialogAddTagBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add tag")
            .setView(d.root)
            .setPositiveButton("Add") { _, _ -> vm.addTag(d.tagInput.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        vm.flush()
        WindowCompat.getInsetsController(requireActivity().window, requireView())
            .hide(WindowInsetsCompat.Type.ime())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // restore the normal window background for the list screen
        val normal = MaterialColors.getColor(
            requireContext(), com.google.android.material.R.attr.colorSurface, Color.WHITE
        )
        requireActivity().window.decorView.setBackgroundColor(normal)
        _b = null
    }
}