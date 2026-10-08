package com.vasanth.vaultnote.ui.security

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.vasanth.vaultnote.R
import com.vasanth.vaultnote.crypto.PassphraseStrength
import com.vasanth.vaultnote.databinding.FragmentSetupBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SetupFragment : Fragment(R.layout.fragment_setup) {

    private val vm: SetupViewModel by activityViewModels()
    private var _b: FragmentSetupBinding? = null
    private val b get() = _b!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentSetupBinding.bind(view)

        b.passEdit.doAfterTextChanged {
            b.passLayout.error = null
            updateMeter(it?.toString().orEmpty())
        }
        b.confirmEdit.doAfterTextChanged { b.confirmLayout.error = null }
        b.continueButton.setOnClickListener { submit() }
    }

    private fun updateMeter(p: String) {
        val score = PassphraseStrength.score(p)
        b.strengthBar.setProgressCompat(if (p.isEmpty()) 0 else (score + 1) * 20, true)
        val attr = if (score < 2) com.google.android.material.R.attr.colorError
        else com.google.android.material.R.attr.colorPrimary
        b.strengthBar.setIndicatorColor(MaterialColors.getColor(b.root, attr))
        b.strengthText.text = if (p.isEmpty()) "" else PassphraseStrength.label(score)
    }

    private fun submit() {
        val p = b.passEdit.text.toString()
        val c = b.confirmEdit.text.toString()
        when {
            PassphraseStrength.score(p) < 2 ->
                b.passLayout.error = "Too weak. Use 8+ characters, ideally 12+ or a mix of letters, numbers and symbols"
            p != c -> b.confirmLayout.error = "Passphrases do not match"
            else -> {
                busy(true)
                viewLifecycleOwner.lifecycleScope.launch {
                    vm.prepare(p.toCharArray())
                    if (_b == null) return@launch
                    busy(false)
                    findNavController().navigate(R.id.action_setup_to_recovery)
                }
            }
        }
    }

    private fun busy(on: Boolean) {
        b.progress.isVisible = on
        b.continueButton.isEnabled = !on
        b.passEdit.isEnabled = !on
        b.confirmEdit.isEnabled = !on
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}