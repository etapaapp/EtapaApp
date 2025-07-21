package com.marinov.colegioetapa

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class PlantaoDuvidasOnline : Fragment() {

    private lateinit var layoutSemInternet: LinearLayout
    private lateinit var btnTentarNovamente: MaterialButton
    private lateinit var loadingContainer: FrameLayout
    private lateinit var webViewContainer: FrameLayout
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_pages, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews(view)
        checkInternetAndLoadWebView()
    }

    private fun setupViews(view: View) {
        loadingContainer = view.findViewById(R.id.loadingContainer)
        layoutSemInternet = view.findViewById(R.id.layout_sem_internet)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)
        webViewContainer = view.findViewById(R.id.webViewContainer)

        btnTentarNovamente.setOnClickListener {
            navigateToHomeFragment()
        }
    }

    private fun checkInternetAndLoadWebView() {
        if (hasInternetConnection()) {
            loadingContainer.visibility = View.VISIBLE
            layoutSemInternet.visibility = View.GONE
            loadWebViewFragment()
        } else {
            loadingContainer.visibility = View.GONE
            layoutSemInternet.visibility = View.VISIBLE
        }
    }

    private fun loadWebViewFragment() {
        handler.postDelayed({
            if (isAdded) {
                val webViewFragment = WebViewFragment().apply {
                    arguments = WebViewFragment.createArgs("https://areaexclusiva.colegioetapa.com.br/horarios/plantao/online")
                }

                childFragmentManager.beginTransaction()
                    .replace(R.id.webViewContainer, webViewFragment)
                    .commit()

                loadingContainer.visibility = View.GONE
            }
        }, 500) // Simula um breve carregamento
    }

    private fun navigateToHomeFragment() {
        (activity as? MainActivity)?.navigateToHome()
    }

    private fun hasInternetConnection(): Boolean {
        val context = context ?: return false
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}