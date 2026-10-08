package com.vasanth.vaultnote

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.vasanth.vaultnote.reminder.Notifier
import com.vasanth.vaultnote.security.AppLockManager
import com.vasanth.vaultnote.security.LockState
import com.vasanth.vaultnote.util.ScreenGuard
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var appLock: AppLockManager

    /** A note to open after unlocking (from a notification tap). */
    private var pendingNoteId: String? = null

    private val setupIds = setOf(R.id.onboardingFragment, R.id.setupFragment, R.id.recoveryKeyFragment)
    private val gateIds = setupIds + R.id.unlockFragment

    private val nav: NavController
        get() = (supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment).navController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ScreenGuard.apply(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nav_host)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            pendingNoteId = intent?.getStringExtra(Notifier.EXTRA_NOTE_ID)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appLock.state.collect { route(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ScreenGuard.apply(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNoteId = intent.getStringExtra(Notifier.EXTRA_NOTE_ID)
        route(appLock.state.value)
    }

    private fun route(state: LockState) {
        val dest = nav.currentDestination?.id ?: return
        when (state) {
            LockState.SETUP_REQUIRED ->
                if (dest !in setupIds) {
                    nav.navigate(
                        R.id.action_global_onboarding, null,
                        navOptions { popUpTo(R.id.nav_graph) { inclusive = true } }
                    )
                }

            LockState.LOCKED ->
                if (dest != R.id.unlockFragment) nav.navigate(R.id.action_global_unlock)

            LockState.UNLOCKED -> {
                if (dest in gateIds) leaveGate(dest)
                openPendingNote()
            }
        }
    }

    private fun leaveGate(dest: Int) {
        val previous = nav.previousBackStackEntry?.destination?.id
        if (dest == R.id.unlockFragment && previous != null && previous !in gateIds) {
            nav.popBackStack()    // re-lock while the user was inside the app: go back to where they were
        } else {
            nav.navigate(
                R.id.notesListFragment, null,
                navOptions { popUpTo(R.id.nav_graph) { inclusive = true } }
            )
        }
    }

    private fun openPendingNote() {
        val id = pendingNoteId ?: return
        pendingNoteId = null
        nav.navigate(R.id.action_global_editor, bundleOf("noteId" to id, "type" to "note"))
    }
}