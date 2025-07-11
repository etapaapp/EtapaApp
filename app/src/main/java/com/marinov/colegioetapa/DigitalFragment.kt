package com.marinov.colegioetapa

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class DigitalFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_fullscreen_placeholder, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        WebViewActivity.start(requireContext(), "https://areaexclusiva.colegioetapa.com.br/etapa-digital")
        view.post {
            if (isAdded && !parentFragmentManager.isDestroyed && !parentFragmentManager.isStateSaved) {
                try {
                    parentFragmentManager.popBackStackImmediate()
                } catch (_: IllegalStateException) {
                    parentFragmentManager.executePendingTransactions()
                    if (parentFragmentManager.backStackEntryCount > 0) {
                        parentFragmentManager.popBackStackImmediate()
                    }
                }
            }
        }
    }
}