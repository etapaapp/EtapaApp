package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class EADFragment : Fragment() {

    private companion object {
        const val AUTH_CHECK_URL = "https://areaexclusiva.colegioetapa.com.br/provas/notas"
        const val PREFS_NAME = "app_prefs"
        const val KEY_SHOW_WARNING = "show_warning"
    }

    private lateinit var layoutSemInternet: LinearLayout
    private lateinit var btnTentarNovamente: MaterialButton
    private lateinit var loadingContainer: FrameLayout
    private lateinit var webViewContainer: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private val eadUrl = BuildConfig.EAD_URL

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
        setupBackPressHandler()
        checkInternetAndAuthentication()
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

    private fun setupBackPressHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateToHomeFragment()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    private fun checkInternetAndAuthentication() {
        if (!hasInternetConnection()) {
            showNoInternetUI()
            return
        }

        loadingContainer.visibility = View.VISIBLE
        performAuthCheck()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun performAuthCheck() {
        val authWebView = WebView(requireContext()).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }

        authWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    "(function() { " +
                            "return document.querySelector('#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > " +
                            "div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div:nth-child(2) > div.card-body > table') !== null; " +
                            "})();"
                ) { value ->
                    val isAuthenticated = value == "true"
                    if (isAuthenticated) {
                        checkAndShowWarningDialog()
                        loadEadWebView()
                    } else {
                        // Usuário não autenticado - mostrar tela offline
                        showNoInternetUI()
                    }
                    authWebView.destroy()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Erro de rede durante a verificação de autenticação
                showNoInternetUI()
                authWebView.destroy()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                // Erro HTTP durante a verificação de autenticação
                showNoInternetUI()
                authWebView.destroy()
            }
        }
        authWebView.loadUrl(AUTH_CHECK_URL)
    }

    private fun loadEadWebView() {
        handler.post {
            loadingContainer.visibility = View.GONE
            val webViewFragment = WebViewFragment().apply {
                arguments = WebViewFragment.createArgs(eadUrl)
            }
            childFragmentManager.beginTransaction()
                .replace(R.id.webViewContainer, webViewFragment)
                .commit()
        }
    }

    private fun checkAndShowWarningDialog() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val shouldShowWarning = prefs.getBoolean(KEY_SHOW_WARNING, true)

        if (shouldShowWarning) {
            AlertDialog.Builder(requireContext())
                .setTitle("ATENÇÃO!")
                .setMessage("Essa função é instável e pode não funcionar em todos os dispositivos! Essa página só exibe uma página com o compartilhamento de arquivos dos EADs antigos, caso tenha problemas em acessar mande um email para: etapa.app@outlook.com.br. Recomendado uso em tablets para melhor visibilidade.")
                .setPositiveButton("Entendi") { dialog, _ -> dialog.dismiss() }
                .setNegativeButton("Não mostrar novamente") { dialog, _ ->
                    prefs.edit {
                        putBoolean(KEY_SHOW_WARNING, false)
                    }
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()
        }

        // Carregar o WebView mesmo mostrando o aviso
        loadEadWebView()
    }

    private fun showNoInternetUI() {
        handler.post {
            loadingContainer.visibility = View.GONE
            layoutSemInternet.visibility = View.VISIBLE
        }
    }

    private fun hasInternetConnection(): Boolean {
        val context = context ?: return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun navigateToHomeFragment() {
        (activity as? MainActivity)?.navigateToHome()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}