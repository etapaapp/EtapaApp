package com.marinov.colegioetapa

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HorariosAula : Fragment() {

    private companion object {
        const val URL_HORARIOS = "https://areaexclusiva.colegioetapa.com.br/horarios/aulas"
        const val PREFS = "horarios_prefs"
        const val KEY_HTML = "cache_html_horarios"
        const val KEY_ALERT = "cache_alert_message" // Nova chave para mensagem
        const val ALERT_SELECTOR = "div.alert.alert-info.alert-font.text-center.m-0"
        const val TABLE_SELECTOR = "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > " +
                "div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > " +
                "div > div.card-body > table"
    }

    private lateinit var tableHorarios: TableLayout
    private lateinit var barOffline: LinearLayout
    private lateinit var messageContainer: LinearLayout
    private lateinit var tvMessage: TextView
    private lateinit var scrollContainer: androidx.core.widget.NestedScrollView
    private lateinit var cache: CacheHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_horarios, container, false)
        tableHorarios = root.findViewById(R.id.tableHorarios)
        barOffline = root.findViewById(R.id.barOffline)
        messageContainer = root.findViewById(R.id.messageContainer)
        tvMessage = root.findViewById(R.id.tvMessage)
        scrollContainer = root.findViewById(R.id.scrollContainer)

        val btnLogin: MaterialButton = root.findViewById(R.id.btnLogin)
        cache = CacheHelper(requireContext())

        btnLogin.setOnClickListener {
            (activity as? MainActivity)?.navigateToHome()
        }

        fetchHorarios()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                (activity as? MainActivity)?.navigateToHome()
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    private fun fetchHorarios() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val doc = withContext(Dispatchers.IO) {
                    try {
                        val cookieHeader = CookieManager.getInstance().getCookie(URL_HORARIOS)
                        Jsoup.connect(URL_HORARIOS)
                            .header("Cookie", cookieHeader)
                            .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15")
                            .timeout(15000)
                            .get()
                    } catch (e: Exception) {
                        Log.e("HorariosAula", "Erro ao conectar", e)
                        null
                    }
                }

                if (doc != null) {
                    // 1. Verificar se há mensagem de "Não há aulas"
                    val alert = doc.selectFirst(ALERT_SELECTOR)
                    if (alert != null) {
                        // Salva a mensagem no cache
                        cache.saveAlertMessage(alert.text())
                        showNoClassesMessage(alert.text())
                        hideOfflineBar()
                    } else {
                        // 2. Buscar tabela como no código original
                        val table = doc.selectFirst(TABLE_SELECTOR)

                        if (table != null) {
                            // Salva a tabela no cache (formato original)
                            cache.saveHtml(table.outerHtml())
                            parseAndBuildTable(table)
                            hideOfflineBar()
                        } else {
                            // Elementos não encontrados (usuário deslogado)
                            showOfflineBar()
                            Log.e("HorariosAula", "Elementos não encontrados no site")

                            // Tenta carregar cache
                            loadCachedData()
                        }
                    }
                } else {
                    // Sem conexão com a internet
                    showOfflineBar()
                    Log.e("HorariosAula", "Falha na conexão")

                    // Tenta carregar cache offline
                    loadCachedData()
                }
            } catch (e: Exception) {
                Log.e("HorariosAula", "Erro inesperado", e)
                showOfflineBar()
                loadCachedData()
            }
        }
    }

    private fun loadCachedData() {
        // 1. Tentar carregar mensagem de alerta do cache
        val alertMessage = cache.loadAlertMessage()
        if (!alertMessage.isNullOrEmpty()) {
            showNoClassesMessage(alertMessage)
            return
        }

        // 2. Tentar carregar tabela do cache (formato antigo)
        val html = cache.loadHtml()
        if (html != null) {
            try {
                val table = Jsoup.parse(html).selectFirst("table")
                if (table != null) {
                    parseAndBuildTable(table)
                }
            } catch (e: Exception) {
                Log.e("HorariosAula", "Erro ao processar cache", e)
            }
        }
    }

    private fun parseAndBuildTable(table: Element) {
        // Garantir que a mensagem esteja oculta
        hideMessage()
        scrollContainer.visibility = View.VISIBLE
        tableHorarios.removeAllViews()

        val headerBgColor = ContextCompat.getColor(requireContext(), R.color.colorPrimary)
        val textColor = ContextCompat.getColor(requireContext(), R.color.colorOnSurface)

        // Cabeçalho
        val headerRowHtml = table.selectFirst("thead > tr")
        if (headerRowHtml != null) {
            val headerRow = TableRow(requireContext())
            headerRow.setBackgroundColor(headerBgColor)
            for (th in headerRowHtml.select("th")) {
                val tv = createCell(th.text(), true)
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorOnPrimary))
                headerRow.addView(tv)
            }
            tableHorarios.addView(headerRow)
        }

        // Linhas de dados
        val rows = table.select("tbody > tr")
        for (tr in rows) {
            // Ignorar linhas com alerta de intervalo
            if (tr.select("div.alert-info").isNotEmpty()) continue

            val row = TableRow(requireContext())
            row.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.transparent))

            for (cell in tr.children()) {
                val isHeaderCell = cell.tagName() == "th"
                val tv = createCell(cell.text(), isHeaderCell)
                tv.setTextColor(textColor)

                if (cell.hasClass("bg-primary")) {
                    tv.setBackgroundResource(R.drawable.bg_primary_rounded)
                    tv.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                }
                row.addView(tv)
            }
            tableHorarios.addView(row)
        }
    }

    private fun showNoClassesMessage(message: String) {
        scrollContainer.visibility = View.GONE
        tvMessage.text = message
        messageContainer.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        messageContainer.visibility = View.GONE
    }

    private fun createCell(text: String, isHeader: Boolean): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isHeader) 14f else 13f)
            typeface = Typeface.defaultFromStyle(if (isHeader) Typeface.BOLD else Typeface.NORMAL)

            val padH = (12 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)

            val minWidth = (80 * resources.displayMetrics.density).toInt()
            setMinWidth(minWidth)

            layoutParams = TableRow.LayoutParams(
                0,
                TableRow.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(2, 2, 2, 2)
            }

            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
    }

    private fun showOfflineBar() {
        barOffline.visibility = View.VISIBLE
    }

    private fun hideOfflineBar() {
        barOffline.visibility = View.GONE
    }

    private inner class CacheHelper(context: Context) {
        private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Salva tabela no mesmo formato original
        fun saveHtml(html: String) {
            prefs.edit { putString(KEY_HTML, html) }
        }

        // Salva mensagem em uma chave separada
        fun saveAlertMessage(message: String) {
            prefs.edit { putString(KEY_ALERT, message) }
        }

        fun loadHtml(): String? {
            return prefs.getString(KEY_HTML, null)
        }

        fun loadAlertMessage(): String? {
            return prefs.getString(KEY_ALERT, null)
        }
    }
}