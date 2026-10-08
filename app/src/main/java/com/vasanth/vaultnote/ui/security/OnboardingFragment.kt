package com.vasanth.vaultnote.ui.security

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.vasanth.vaultnote.R
import com.vasanth.vaultnote.databinding.FragmentOnboardingBinding
import com.vasanth.vaultnote.security.AppLockManager
import com.vasanth.vaultnote.sync.DriveSignIn
import com.vasanth.vaultnote.sync.RestoreResult
import com.vasanth.vaultnote.sync.SyncManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {

    @Inject lateinit var sync: SyncManager
    @Inject lateinit var appLock: AppLockManager

    private var _b: FragmentOnboardingBinding? = null
    private val signIn = DriveSignIn(this) { token -> onToken(token) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentOnboardingBinding.bind(view)
        _b!!.startButton.setOnClickListener {
            findNavController().navigate(R.id.action_onboarding_to_setup)
        }
        _b!!.restoreButton.setOnClickListener { signIn.start() }
    }

    private fun onToken(token: String?) {
        if (_b == null) return
        if (token == null) { toast("Google sign-in was cancelled or failed"); return }
        viewLifecycleOwner.lifecycleScope.launch {
            when (val r = sync.restoreKeyring(token)) {
                RestoreResult.Found -> appLock.onKeyringInstalled()   // MainActivity then shows the unlock screen
                RestoreResult.NotFound -> toast("No VaultNote vault was found in this Google account")
                is RestoreResult.Error -> toast(r.message)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}