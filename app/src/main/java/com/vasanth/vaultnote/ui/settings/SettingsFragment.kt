package com.vasanth.vaultnote.ui.settings

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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

        // Biometric unlock: needs a fresh authentication before it can be enabled
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
                false      // the switch is turned on above, after authentication
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
    }

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