package com.vasanth.vaultnote.ui.security

import androidx.lifecycle.ViewModel
import com.vasanth.vaultnote.crypto.KeyringManager
import com.vasanth.vaultnote.crypto.PendingSetup
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Shared by the three setup screens (scoped to the activity). */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val keyring: KeyringManager
) : ViewModel() {

    var pending: PendingSetup? = null
        private set

    suspend fun prepare(passphrase: CharArray) {
        pending = keyring.prepareSetup(passphrase)
    }

    /** Writes keyring.bin. Only called after the user confirms the recovery key is saved. */
    fun commit() {
        pending?.let { keyring.commitSetup(it) }
        pending = null
    }
}