package com.vasanth.vaultnote.util

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vasanth.vaultnote.crypto.KeyringManager

object BiometricHelper {

    // BIOMETRIC_STRONG | DEVICE_CREDENTIAL is not supported on API 28-29
    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT in 28..29) BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        else BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    fun isAvailable(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(
        fragment: Fragment,
        title: String,
        subtitle: String? = null,
        onSuccess: () -> Unit,
        onCancel: (CharSequence) -> Unit = {}
    ) {
        val prompt = BiometricPrompt(
            fragment,
            ContextCompat.getMainExecutor(fragment.requireContext()),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    onSuccess()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                    onCancel(errString)
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { subtitle?.let { setSubtitle(it) } }
            .setAllowedAuthenticators(authenticators())
            .build()
        prompt.authenticate(info)
    }
}

/** Asks once whether to use biometrics for unlocking. */
object BiometricOffer {
    fun maybeOffer(fragment: Fragment, keyring: KeyringManager, onDone: () -> Unit) {
        val ctx = fragment.requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (prefs.getBoolean("bio_offer_done", false) ||
            keyring.hasBiometricCache() ||
            !BiometricHelper.isAvailable(ctx)
        ) {
            onDone(); return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Unlock with biometrics?")
            .setMessage("Use your fingerprint, face or phone lock instead of typing the passphrase. You can change this in Settings.")
            .setCancelable(false)
            .setPositiveButton("Enable") { _, _ ->
                prefs.edit().putBoolean("bio_offer_done", true).apply()
                BiometricHelper.authenticate(
                    fragment, "Enable biometric unlock",
                    onSuccess = { keyring.enableBiometricCache(); onDone() },
                    onCancel = { onDone() }
                )
            }
            .setNegativeButton("Not now") { _, _ ->
                prefs.edit().putBoolean("bio_offer_done", true).apply()
                onDone()
            }
            .show()
    }
}