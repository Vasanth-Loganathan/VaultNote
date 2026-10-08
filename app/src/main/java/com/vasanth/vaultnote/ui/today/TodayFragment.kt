package com.vasanth.vaultnote.ui.today

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.vasanth.vaultnote.R
import com.vasanth.vaultnote.databinding.FragmentTodayBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TodayFragment : Fragment(R.layout.fragment_today) {

    private val vm: TodayViewModel by viewModels()
    private var _b: FragmentTodayBinding? = null
    private val b get() = _b!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentTodayBinding.bind(view)

        b.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        val adapter = TodayAdapter(
            onClick = { n ->
                findNavController().navigate(
                    R.id.action_global_editor, bundleOf("noteId" to n.id, "type" to n.type)
                )
            },
            onDone = { n -> vm.done(n.id) }
        )
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.items.collect {
                    adapter.submitList(it)
                    b.emptyView.isVisible = it.isEmpty()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}