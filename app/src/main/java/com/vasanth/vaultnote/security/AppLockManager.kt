package com.vasanth.vaultnote.security

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import com.vasanth.vaultnote.crypto.KeyringManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class LockState { SETUP_REQUIRED, LOCKED, UNLOCKED }

@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext ctx: Context,
    private val keyring: KeyringManager
) : DefaultLifecycleObserver {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

    private val _state = MutableStateFlow(
        if (keyring.isSetUp()) LockState.LOCKED else LockState.SETUP_REQUIRED
    )
    val state: StateFlow<LockState> = _state.asStateFlow()

    /** Increases on every lock. Locked notes use it to know they must ask again. */
    @Volatile
    var lockEpoch = 0
        private set

    private var backgroundedAt = 0L

    // Called by ProcessLifecycleOwner (whole app, not one screen)
    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (_state.value != LockState.UNLOCKED || backgroundedAt == 0L) return
        val seconds = prefs.getString("auto_lock", "30")?.toLongOrNull() ?: 30L
        if (SystemClock.elapsedRealtime() - backgroundedAt >= seconds * 1000) lock()
    }

    fun onUnlocked() {
        backgroundedAt = 0L
        _state.value = LockState.UNLOCKED
    }

    fun lock() {
        if (!keyring.isSetUp()) return
        keyring.lock()
        lockEpoch++
        _state.value = LockState.LOCKED
    }
}