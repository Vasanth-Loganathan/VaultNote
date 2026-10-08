package com.vasanth.vaultnote.ui.editor

import android.annotation.SuppressLint
import android.text.Editable
import android.view.GestureDetector
import android.view.MotionEvent
import android.text.Spanned
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
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
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.vasanth.vaultnote.R
import com.vasanth.vaultnote.data.ChecklistJson
import com.vasanth.vaultnote.data.db.NoteEntity
import com.vasanth.vaultnote.data.db.NoteType
import com.vasanth.vaultnote.databinding.DialogAddTagBinding
import com.vasanth.vaultnote.databinding.FragmentNoteEditorBinding
import com.vasanth.vaultnote.util.ColorPicker
import com.vasanth.vaultnote.util.NoteColors
import com.vasanth.vaultnote.util.ReminderFormat
import com.vasanth.vaultnote.util.BiometricHelper
import com.vasanth.vaultnote.util.SecureClipboard
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@AndroidEntryPoint
class NoteEditorFragment : Fragment(R.layout.fragment_note_editor) {

    private val vm: NoteEditorViewModel by viewModels()
    private var _b: FragmentNoteEditorBinding? = null
    private val b get() = _b!!

    private lateinit var checkAdapter: ChecklistAdapter

    /** false until the screen has been filled from the ViewModel; blocks accidental saves. */
    private var populated = false

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("Notifications are off, so reminders will not show")
        }

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
                R.id.action_lock -> { toggleLock(); true }
                R.id.action_copy -> { copyText(); true }
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
        b.bodyEdit.doAfterTextChanged { highlightLinks(it) }
        setupLinkTaps()

        // ----- bottom bar -----
        b.colorButton.setOnClickListener {
            ColorPicker.show(requireContext(), vm.state.value.color) { vm.setColor(it) }
        }
        b.tagButton.setOnClickListener { showAddTagDialog() }
        b.reminderButton.setOnClickListener { pickReminder() }
        b.reminderChip.setOnClickListener { pickReminder() }
        b.reminderChip.setOnCloseIconClickListener { vm.setReminder(null, null) }
        b.unlockNoteButton.setOnClickListener { gateIfNeeded() }

        // ----- observe -----
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.loaded.collect { if (it && !populated) populate() } }
                launch {
                    vm.state.map { listOf(it.pinned, it.archived, it.locked, it.color) }
                        .distinctUntilChanged()
                        .collect {
                            val s = vm.state.value
                            updateMenu(s.pinned, s.archived, s.locked)
                            applyColor(s.color)
                        }
                }
                launch {
                    vm.state.map { it.reminderAt to it.repeat }
                        .distinctUntilChanged()
                        .collect { (at, repeat) -> renderReminder(at, repeat) }
                }
                launch { vm.tags.collect { renderTags(it) } }
                launch { vm.backlinks.collect { renderBacklinks(it) } }
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
        gateIfNeeded()

        if (vm.isNew) {
            val target = if (n.title.isNotBlank() && n.type == NoteType.NOTE) b.bodyEdit else b.titleEdit
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

    // ---------- reminder ----------
    private fun renderReminder(at: Long?, repeat: String?) {
        b.reminderChip.isVisible = at != null
        if (at != null) b.reminderChip.text = ReminderFormat.text(requireContext(), at, repeat)
    }

    /** Step 1 of 3: date. */
    private fun pickReminder() {
        val current = vm.state.value
        val base = current.reminderAt?.takeIf { it > System.currentTimeMillis() }
            ?: (System.currentTimeMillis() + 60 * 60 * 1000)
        val zoned = Instant.ofEpochMilli(base).atZone(ZoneId.systemDefault())

        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Reminder date")
            .setSelection(zoned.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .setCalendarConstraints(
                CalendarConstraints.Builder().setValidator(DateValidatorPointForward.now()).build()
            )
            .build()
        datePicker.addOnPositiveButtonClickListener { selection ->
            val date = Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate()
            pickTime(date, zoned.hour, zoned.minute, current.repeat)
        }
        datePicker.show(childFragmentManager, "reminder_date")
    }

    /** Step 2 of 3: time. */
    private fun pickTime(date: LocalDate, hour: Int, minute: Int, repeat: String?) {
        val is24 = DateFormat.is24HourFormat(requireContext())
        val timePicker = MaterialTimePicker.Builder()
            .setTimeFormat(if (is24) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText("Reminder time")
            .build()
        timePicker.addOnPositiveButtonClickListener {
            val at = LocalDateTime.of(date, LocalTime.of(timePicker.hour, timePicker.minute))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (at <= System.currentTimeMillis()) {
                toast("Pick a time in the future")
            } else {
                pickRepeat(at, repeat)
            }
        }
        timePicker.show(childFragmentManager, "reminder_time")
    }

    /** Step 3 of 3: repeat. */
    private fun pickRepeat(at: Long, currentRepeat: String?) {
        val options = arrayOf("Does not repeat", "Daily", "Weekly")
        var choice = when (currentRepeat) { "daily" -> 1; "weekly" -> 2; else -> 0 }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Repeat")
            .setSingleChoiceItems(options, choice) { _, which -> choice = which }
            .setPositiveButton("Set") { _, _ ->
                vm.setReminder(at, when (choice) { 1 -> "daily"; 2 -> "weekly"; else -> null })
                ensureNotificationPermission()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------- [[links]] inside the note text ----------
    private val linkRegex = Regex("\\[\\[([^\\[\\]]+)\\]\\]")

    private fun highlightLinks(text: Editable?) {
        if (text == null || _b == null) return
        text.getSpans(0, text.length, NoteLinkSpan::class.java).forEach { text.removeSpan(it) }
        val color = MaterialColors.getColor(
            requireContext(), com.google.android.material.R.attr.colorPrimary, Color.BLUE
        )
        linkRegex.findAll(text).forEach { m ->
            text.setSpan(
                NoteLinkSpan(color), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupLinkTaps() {
        val detector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val et = b.bodyEdit
                val layout = et.layout ?: return false
                val x = e.x - et.totalPaddingLeft + et.scrollX
                val y = e.y - et.totalPaddingTop + et.scrollY
                val line = layout.getLineForVertical(y.toInt())
                if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return false

                val offset = layout.getOffsetForHorizontal(line, x)
                val text = et.text
                val span = text.getSpans(0, text.length, NoteLinkSpan::class.java).firstOrNull {
                    offset >= text.getSpanStart(it) && offset <= text.getSpanEnd(it)
                } ?: return false

                val title = text.substring(text.getSpanStart(span) + 2, text.getSpanEnd(span) - 2).trim()
                if (title.isNotEmpty()) openLinkedNote(title)
                return true
            }
        })
        b.bodyEdit.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); false }
    }

    private fun openLinkedNote(title: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val target = vm.findNoteByTitle(title)
            if (target != null) {
                if (target.id == vm.noteId) return@launch
                findNavController().navigate(
                    R.id.action_global_editor, bundleOf("noteId" to target.id, "type" to target.type)
                )
            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Create \"$title\"?")
                    .setMessage("No note with this title exists yet.")
                    .setPositiveButton("Create") { _, _ ->
                        findNavController().navigate(
                            R.id.action_global_editor,
                            bundleOf("noteId" to null, "type" to NoteType.NOTE, "title" to title)
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
    // ---------- backlinks ----------
    private fun renderBacklinks(list: List<NoteEntity>) {
        b.backlinksGroup.isVisible = list.isNotEmpty()
        b.backlinksChips.removeAllViews()
        list.forEach { n ->
            val chip = layoutInflater.inflate(R.layout.chip_link, b.backlinksChips, false) as Chip
            chip.text = if (n.locked) "🔒 Locked note" else n.title.ifBlank { "Untitled" }
            chip.setOnClickListener {
                findNavController().navigate(
                    R.id.action_global_editor, bundleOf("noteId" to n.id, "type" to n.type)
                )
            }
            b.backlinksChips.addView(chip)
        }
    }

    // ---------- menu / color / tags ----------
    private fun updateMenu(pinned: Boolean, archived: Boolean, locked: Boolean) {
        val menu = b.toolbar.menu
        menu.findItem(R.id.action_pin)?.apply {
            title = if (pinned) "Unpin" else "Pin"
            icon?.mutate()?.alpha = if (pinned) 255 else 110
        }
        menu.findItem(R.id.action_lock)?.apply {
            title = if (locked) "Unlock note" else "Lock note"
            icon?.mutate()?.alpha = if (locked) 255 else 110
        }
        menu.findItem(R.id.action_archive)?.title = if (archived) "Unarchive" else "Archive"
    }

    override fun onResume() {
        super.onResume()
        gateIfNeeded()
    }

    // ---------- locked notes ----------
    private var authInProgress = false

    private fun setGated(gated: Boolean) {
        b.contentScroll.isVisible = !gated
        b.bottomBar.isVisible = !gated
        b.lockedCover.isVisible = gated
        val menu = b.toolbar.menu
        for (i in 0 until menu.size()) menu.getItem(i).isVisible = !gated
    }

    /** Hides the note and asks for biometrics whenever a locked note is opened (or the app re-locked). */
    private fun gateIfNeeded() {
        if (!populated || _b == null) return
        if (!vm.needsAuth()) { setGated(false); return }
        setGated(true)
        if (authInProgress) return
        authInProgress = true
        BiometricHelper.authenticate(
            this, "Locked note", "Authenticate to open this note",
            onSuccess = {
                authInProgress = false
                if (_b != null) { vm.markAuthenticated(); setGated(false) }
            },
            onCancel = { authInProgress = false }
        )
    }

    private fun toggleLock() {
        if (!BiometricHelper.isAvailable(requireContext())) {
            toast("Set a screen lock or fingerprint in phone settings to use locked notes")
            return
        }
        if (vm.state.value.locked) {
            BiometricHelper.authenticate(
                this, "Remove lock", "Authenticate to unlock this note",
                onSuccess = { if (_b != null) { vm.toggleLocked(); toast("Note unlocked") } }
            )
        } else {
            vm.toggleLocked()
            toast("Note locked. Biometrics are needed to open it")
        }
    }

    private fun copyText() {
        val n = vm.state.value
        val text = if (n.type == NoteType.CHECKLIST) {
            (listOf(n.title) + ChecklistJson.decode(n.itemsJson)
                .map { (if (it.done) "[x] " else "[ ] ") + it.text })
                .filter { it.isNotBlank() }.joinToString("\n")
        } else {
            listOf(n.title, n.body).filter { it.isNotBlank() }.joinToString("\n\n")
        }
        if (text.isBlank()) return
        SecureClipboard.copy(requireContext(), text, autoClear = n.locked)
        toast(if (n.locked) "Copied. The clipboard clears in 30 seconds" else "Copied")
    }

    private fun applyColor(color: String) {
        val c = NoteColors.background(requireContext(), color)
        b.editorRoot.setBackgroundColor(c)
        b.toolbar.setBackgroundColor(c)
        b.bottomBar.setBackgroundColor(c)
        requireActivity().window.decorView.setBackgroundColor(c)
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
        val normal = MaterialColors.getColor(
            requireContext(), com.google.android.material.R.attr.colorSurface, Color.WHITE
        )
        requireActivity().window.decorView.setBackgroundColor(normal)
        _b = null
    }
}