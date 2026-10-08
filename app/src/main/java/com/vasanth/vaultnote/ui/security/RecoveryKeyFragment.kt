package com.vasanth.vaultnote.ui.security

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.vasanth.vaultnote.R
import com.vasanth.vaultnote.crypto.KeyringManager
import com.vasanth.vaultnote.databinding.FragmentRecoveryKeyBinding
import com.vasanth.vaultnote.security.AppLockManager
import com.vasanth.vaultnote.util.BiometricOffer
import com.vasanth.vaultnote.util.SecureClipboard
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class RecoveryKeyFragment : Fragment(R.layout.fragment_recovery_key) {

    @Inject lateinit var keyring: KeyringManager
    @Inject lateinit var appLock: AppLockManager

    private val vm: SetupViewModel by activityViewModels()
    private var _b: FragmentRecoveryKeyBinding? = null
    private val b get() = _b!!

    private val saveFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeKeyFile(uri)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentRecoveryKeyBinding.bind(view)

        val key = vm.pending?.recoveryKey
        if (key == null) {   // e.g. the app was restarted mid-setup
            findNavController().popBackStack(R.id.setupFragment, false)
            return
        }

        b.keyText.text = key.split("-").chunked(4).joinToString("\n") { it.joinToString("-") }

        b.copyButton.setOnClickListener {
            SecureClipboard.copy(requireContext(), key, autoClear = true)
            toast("Copied. The clipboard clears in 30 seconds")
        }
        b.saveButton.setOnClickListener { saveFile.launch("vaultnote-recovery-key.txt") }
        b.savedCheck.setOnCheckedChangeListener { _, checked -> b.finishButton.isEnabled = checked }

        b.finishButton.setOnClickListener {
            vm.commit()
            BiometricOffer.maybeOffer(this, keyring) { appLock.onUnlocked() }
        }
    }

    private fun writeKeyFile(uri: Uri) {
        val key = vm.pending?.recoveryKey ?: return
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use {
                it.write(
                    ("VaultNote recovery key\n\n$key\n\n" +
                            "Keep this file private. Anyone with it and your Drive data can read your notes.\n")
                        .toByteArray()
                )
            }
            toast("Saved")
        } catch (e: Exception) {
            toast("Could not save the file")
        }
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}