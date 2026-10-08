package com.vasanth.vaultnote.ui.security

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.vasanth.vaultnote.R
import com.vasanth.vaultnote.databinding.FragmentOnboardingBinding

class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        FragmentOnboardingBinding.bind(view).startButton.setOnClickListener {
            findNavController().navigate(R.id.action_onboarding_to_setup)
        }
    }
}