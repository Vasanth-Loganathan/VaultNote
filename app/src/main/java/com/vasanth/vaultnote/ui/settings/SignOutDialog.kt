package com.vasanth.vaultnote.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.content.DialogInterface
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.vasanth.vaultnote.sync.SyncManager
import kotlinx.coroutines.launch

object SignOutDialog {

    fun show(fragment: Fragment, sync: SyncManager, onDone: () -> Unit) {
        val ctx = fragment.requireContext()
        val items = arrayOf(
            "Sign out, keep cloud data",
            "Sign out and delete cloud data",
            "Erase everything here and in the cloud"
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Sign out of Google")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> confirmSimple(
                        fragment, sync, onDone, deleteCloud = false,
                        title = "Sign out and keep cloud data?",
                        message = "Notes stay on this phone. Encrypted copies stay in your Google Drive. " +
                                "You can turn sync on later with any Google account, and your notes will upload to it."
                    )
                    1 -> confirmSimple(
                        fragment, sync, onDone, deleteCloud = true,
                        title = "Sign out and delete cloud data?",
                        message = "Every VaultNote file in your Drive is deleted, including your keyring. " +
                                "Notes stay on this phone."
                    )
                    else -> confirmErase(fragment, sync)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmSimple(
        fragment: Fragment, sync: SyncManager, onDone: () -> Unit,
        deleteCloud: Boolean, title: String, message: String
    ) {
        val ctx = fragment.requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Sign out") { _, _ ->
                val app = ctx.applicationContext
                toast(app, if (deleteCloud) "Deleting cloud data…" else "Signing out…")
                fragment.requireActivity().lifecycleScope.launch {
                    try {
                        sync.signOut(deleteCloud)
                        toast(app, "Signed out")
                    } catch (e: Exception) {
                        toast(app, "Failed, nothing was changed: ${e.message}")
                    }
                    onDone()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmErase(fragment: Fragment, sync: SyncManager) {
        val ctx = fragment.requireContext()
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val layout = TextInputLayout(ctx).apply { hint = "Type DELETE to confirm" }
        val edit = TextInputEditText(layout.context)
        layout.addView(edit)
        val box = FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(layout)
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("Erase everything?")
            .setMessage(
                "This deletes all notes, the vault key and settings on this phone, and all VaultNote files " +
                        "in your Google Drive. Your recovery key will stop working. This cannot be undone. " +
                        "The app will close."
            )
            .setView(box)
            .setPositiveButton("Erase", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val btn = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            btn.isEnabled = false
            edit.doAfterTextChanged { btn.isEnabled = it.toString().trim() == "DELETE" }
            btn.setOnClickListener {
                btn.isEnabled = false
                dialog.dismiss()
                eraseAll(fragment, sync)
            }
        }
        dialog.show()
    }

    private fun eraseAll(fragment: Fragment, sync: SyncManager) {
        val app = fragment.requireContext().applicationContext
        toast(app, "Erasing…")
        fragment.requireActivity().lifecycleScope.launch {
            try {
                if (sync.isEnabled) sync.signOut(deleteCloud = true)   // cloud first; on failure nothing local is erased
            } catch (e: Exception) {
                toast(app, "Cloud delete failed, nothing was erased: ${e.message}")
                return@launch
            }
            // Wipes database, keyring, Keystore keys, prefs and jobs, then the app closes.
            (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
        }
    }

    private fun toast(app: Context, msg: String) =
        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
}