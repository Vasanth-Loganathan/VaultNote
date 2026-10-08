package com.vasanth.vaultnote.ui.security

import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vasanth.vaultnote.R
import com.vasanth.vaultnote.crypto.KeyringManager
import com.vasanth.vaultnote.crypto.PassphraseStrength
import com.vasanth.vaultnote.databinding.DialogChangePassphraseBinding
import com.vasanth.vaultnote.databinding.DialogTextInputBinding
import com.vasanth.vaultnote.databinding.FragmentUnlockBinding
import com.vasanth.vaultnote.security.AppLockManager
import com.vasanth.vaultnote.util.BiometricHelper
import com.vasanth.vaultnote.util.BiometricOffer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class UnlockFragment : Fragment(R.layout.fragment_unlock) {

    @Inject lateinit var keyring: KeyringManager
    @Inject lateinit var appLock: AppLockManager

    private var _b: FragmentUnlockBinding? = null
    private val b get() = _b!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentUnlockBinding.bind(view)

        // Back must never reveal the screen below the lock
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            requireActivity().moveTaskToBack(true)
        }

        val bioReady = BiometricHelper.isAvailable(requireContext()) && keyring.hasBiometricCache()
        b.biometricButton.isVisible = bioReady
        b.biometricButton.setOnClickListener { promptBiometric() }
        b.unlockButton.setOnClickListener { unlockWithPassphrase() }
        b.recoveryButton.setOnClickListener { showRecoveryDialog() }
        b.passEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { unlockWithPassphrase(); true } else false
        }
        b.passEdit.setOnKeyListener { _, _, _ -> b.passLayout.error = null; false }

        if (bioReady && savedInstanceState == null) b.root.post { if (_b != null) promptBiometric() }
    }

    // ---------- biometric ----------
    private fun promptBiometric() {
        BiometricHelper.authenticate(
            this, "Unlock VaultNote",
            onSuccess = {
                if (_b == null) return@authenticate
                if (keyring.unlockWithBiometricCache()) {
                    appLock.onUnlocked()
                } else {
                    // Key invalidated (e.g. screen lock changed): fall back to the passphrase
                    keyring.disableBiometricCache()
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit().putBoolean("bio_offer_done", false).apply()
                    b.biometricButton.isVisible = false
                    toast("Biometric unlock expired. Enter your passphrase")
                }
            }
        )
    }

    // ---------- passphrase ----------
    private fun unlockWithPassphrase() {
        val text = b.passEdit.text.toString()
        if (text.isEmpty()) return
        busy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = keyring.unlockWithPassphrase(text.toCharArray())
            if (_b == null) return@launch
            busy(false)
            if (ok) {
                b.passEdit.setText("")
                BiometricOffer.maybeOffer(this@UnlockFragment, keyring) { appLock.onUnlocked() }
            } else {
                b.passLayout.error = "Wrong passphrase"
            }
        }
    }

    // ---------- recovery key ----------
    private fun showRecoveryDialog() {
        val d = DialogTextInputBinding.inflate(layoutInflater)
        d.inputLayout.hint = "Recovery key"
        d.inputEdit.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Use recovery key")
            .setMessage("Enter the recovery key you saved during setup.")
            .setView(d.root)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val text = d.inputEdit.text.toString()
                viewLifecycleOwner.lifecycleScope.launch {
                    if (keyring.unlockWithRecoveryKey(text)) {
                        dialog.dismiss()
                        promptNewPassphrase()
                    } else {
                        d.inputLayout.error = "Invalid recovery key"
                    }
                }
            }
        }
        dialog.show()
    }

    /** After a recovery-key unlock the user must choose a new passphrase. */
    private fun promptNewPassphrase() {
        val d = DialogChangePassphraseBinding.inflate(layoutInflater)
        d.oldLayout.isVisible = false

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Set a new passphrase")
            .setMessage("Your recovery key stays valid.")
            .setView(d.root)
            .setCancelable(false)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val p = d.newEdit.text.toString()
                val c = d.confirmEdit.text.toString()
                when {
                    PassphraseStrength.score(p) < 2 -> d.newLayout.error = "Too weak"
                    p != c -> d.confirmLayout.error = "Passphrases do not match"
                    else -> viewLifecycleOwner.lifecycleScope.launch {
                        if (keyring.resetPassphrase(p.toCharArray())) {
                            dialog.dismiss()
                            appLock.onUnlocked()
                        } else {
                            toast("Could not save the new passphrase")
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun busy(on: Boolean) {
        b.progress.isVisible = on
        b.unlockButton.isEnabled = !on
        b.passEdit.isEnabled = !on
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}