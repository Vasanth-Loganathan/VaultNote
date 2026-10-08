package com.vasanth.vaultnote.ui.settings

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vasanth.vaultnote.R
import com.vasanth.vaultnote.crypto.KeyringManager
import com.vasanth.vaultnote.crypto.PassphraseStrength
import com.vasanth.vaultnote.databinding.DialogChangePassphraseBinding
import com.vasanth.vaultnote.security.AppLockManager
import com.vasanth.vaultnote.sync.DriveSignIn
import com.vasanth.vaultnote.sync.EnableResult
import com.vasanth.vaultnote.sync.SyncManager
import com.vasanth.vaultnote.sync.SyncOutcome
import com.vasanth.vaultnote.util.BiometricHelper
import com.vasanth.vaultnote.util.ScreenGuard
import com.vasanth.vaultnote.util.ThemeHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment(R.layout.fragment_settings) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { findNavController().popBackStack() }

        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsPrefsFragment())
                .commit()
        }
    }
}

@AndroidEntryPoint
class SettingsPrefsFragment : PreferenceFragmentCompat() {

    @Inject lateinit var keyring: KeyringManager
    @Inject lateinit var appLock: AppLockManager
    @Inject lateinit var sync: SyncManager

    private var replaceNext = false
    private val signIn = DriveSignIn(this) { token -> onToken(token) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<ListPreference>("theme")?.setOnPreferenceChangeListener { _, newValue ->
            ThemeHelper.apply(newValue as String)
            true
        }

        findPreference<Preference>("notif_settings")?.setOnPreferenceClickListener {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            )
            true
        }

        findPreference<SwitchPreferenceCompat>("secure_screen")?.setOnPreferenceChangeListener { _, v ->
            ScreenGuard.set(requireActivity().window, v as Boolean)
            true
        }

        val bio = findPreference<SwitchPreferenceCompat>("biometric_unlock")
        bio?.isChecked = keyring.hasBiometricCache()
        bio?.setOnPreferenceChangeListener { pref, newValue ->
            if (newValue as Boolean) {
                if (!BiometricHelper.isAvailable(requireContext())) {
                    toast("Set a screen lock or fingerprint in phone settings first")
                    return@setOnPreferenceChangeListener false
                }
                BiometricHelper.authenticate(
                    this, "Enable biometric unlock",
                    onSuccess = {
                        if (keyring.enableBiometricCache()) (pref as SwitchPreferenceCompat).isChecked = true
                        else toast("Could not enable biometric unlock on this device")
                    }
                )
                false
            } else {
                keyring.disableBiometricCache()
                true
            }
        }

        findPreference<Preference>("change_passphrase")?.setOnPreferenceClickListener {
            showChangePassphrase(); true
        }
        findPreference<Preference>("lock_now")?.setOnPreferenceClickListener {
            appLock.lock(); true
        }

        // ---- cloud sync ----
        findPreference<Preference>("sync_account")?.setOnPreferenceClickListener {
            if (!sync.isEnabled) confirmEnable(); true
        }
        findPreference<Preference>("sync_now")?.setOnPreferenceClickListener { runSyncNow(); true }
        findPreference<Preference>("sync_storage")?.setOnPreferenceClickListener { checkStorage(); true }
        findPreference<Preference>("sync_off")?.setOnPreferenceClickListener {
            SignOutDialog.show(this, sync) { if (isAdded && view != null) refreshSyncUi() }
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sync.status.collect { refreshSyncUi() }
            }
        }
    }

    // ---------- cloud sync ----------
    private fun refreshSyncUi() {
        val on = sync.isEnabled
        val st = sync.status.value
        val synced = if (st.lastSyncAt > 0)
            " · synced " + DateUtils.getRelativeTimeSpanString(
                st.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            ) else ""
        findPreference<Preference>("sync_account")?.summary = when {
            !on -> "Off. Tap to turn on"
            st.syncing -> "Syncing…"
            st.error != null -> st.error
            else -> "On · ${sync.email.orEmpty()}$synced"
        }
        listOf("sync_now", "sync_storage", "sync_off").forEach {
            findPreference<Preference>(it)?.isVisible = on
        }
    }

    private fun confirmEnable() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Turn on Google Drive sync?")
            .setMessage(
                "Your notes are encrypted on this phone first. They are stored in a hidden app folder " +
                        "of your Google Drive that only VaultNote can open. Google only sees unreadable data."
            )
            .setPositiveButton("Continue") { _, _ -> replaceNext = false; signIn.start() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onToken(token: String?) {
        if (!isAdded || view == null) return
        if (token == null) { toast("Google sign-in was cancelled or failed"); return }
        viewLifecycleOwner.lifecycleScope.launch {
            toast("Setting up sync…")
            when (val r = sync.enable(token, replaceExisting = replaceNext)) {
                EnableResult.Ok -> { toast("Sync is on"); sync.syncNow() }
                EnableResult.VaultMismatch -> confirmReplaceCloud()
                is EnableResult.Error -> toast(r.message)
            }
            replaceNext = false
            refreshSyncUi()
        }
    }

    private fun confirmReplaceCloud() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Different vault found")
            .setMessage(
                "This Google account already holds a VaultNote vault protected by a different key. " +
                        "Replace it with the notes on this phone? The old cloud data will be deleted."
            )
            .setPositiveButton("Replace") { _, _ -> replaceNext = true; signIn.start() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runSyncNow() {
        viewLifecycleOwner.lifecycleScope.launch {
            when (sync.syncNow()) {
                SyncOutcome.DONE -> toast("Up to date")
                SyncOutcome.SKIPPED -> toast("Already syncing, or sync is not available right now")
                SyncOutcome.RETRY -> toast(sync.status.value.error ?: "Will retry soon")
            }
        }
    }

    private fun checkStorage() {
        val pref = findPreference<Preference>("sync_storage")
        pref?.summary = "Checking…"
        viewLifecycleOwner.lifecycleScope.launch {
            val info = sync.storageInfo()
            if (info == null) { pref?.summary = "Could not check. Tap to try again"; return@launch }
            val ctx = requireContext()
            val app = Formatter.formatShortFileSize(ctx, info.appBytes)
            val used = Formatter.formatShortFileSize(ctx, info.accountUsed)
            val limit = info.accountLimit?.let { Formatter.formatShortFileSize(ctx, it) } ?: "unlimited"
            val almostFull = info.accountLimit != null && info.accountUsed * 10 >= info.accountLimit * 9
            pref?.summary = "$app in ${info.fileCount} files · Google account: $used of $limit" +
                    if (almostFull) "\nYour Google storage is almost full" else ""
        }
    }

    // ---------- passphrase ----------
    private fun showChangePassphrase() {
        val d = DialogChangePassphraseBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Change passphrase")
            .setView(d.root)
            .setPositiveButton("Change", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val old = d.oldEdit.text.toString()
                val p = d.newEdit.text.toString()
                val c = d.confirmEdit.text.toString()
                d.oldLayout.error = null; d.newLayout.error = null; d.confirmLayout.error = null
                when {
                    old.isEmpty() -> d.oldLayout.error = "Enter your current passphrase"
                    PassphraseStrength.score(p) < 2 -> d.newLayout.error = "Too weak"
                    p != c -> d.confirmLayout.error = "Passphrases do not match"
                    else -> viewLifecycleOwner.lifecycleScope.launch {
                        if (keyring.changePassphrase(old.toCharArray(), p.toCharArray())) {
                            dialog.dismiss()
                            toast("Passphrase changed")
                        } else {
                            d.oldLayout.error = "Wrong passphrase"
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}