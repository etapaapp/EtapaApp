package com.marinov.colegioetapa

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.marinov.colegioetapa.WebViewFragment.Companion.createArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.Calendar

class HomeFragment : Fragment(), MainActivity.RefreshableFragment {

    // --- Views ---
    private var viewPager: ViewPager2? = null
    private var newsRecyclerView: RecyclerView? = null
    private var layoutSemInternet: LinearLayout? = null
    private var btnTentarNovamente: MaterialButton? = null
    private var loadingContainer: View? = null
    private var contentContainer: View? = null
    private var txtStuckHint: TextView? = null
    private var carouselLoadingIndicator: CircularProgressIndicator? = null
    private var newsSectionContainer: View? = null
    private var recentGradesSectionContainer: View? = null
    private var tableRecentGrades: TableLayout? = null


    // --- State ---
    private var shouldBlockNavigation = false
    private var isFragmentDestroyed = false
    private var hasBeenVisible = false
    private var isDataLoaded = false
    private var isCarouselLoading = false

    // --- Data Lists ---
    private val carouselItems: MutableList<CarouselItem> = mutableListOf()
    private val newsItems: MutableList<NewsItem> = mutableListOf()

    private val handler = Handler(Looper.getMainLooper())

    // --- Data Classes Internas ---
    data class ProvaCalendario(val data: Calendar, val codigo: String, val conjunto: Int)
    data class Nota(val codigo: String, val conjunto: Int, val valor: String)
    data class NotaRecente(val codigo: String, val conjunto: String, val nota: String, val data: Calendar)

    // --- Constantes ---
    private companion object {
        const val PREFS_NAME = "HomeFragmentCache"
        const val KEY_CAROUSEL_ITEMS = "carousel_items"
        const val KEY_NEWS_ITEMS = "news_items"
        const val KEY_CACHE_TIMESTAMP = "cache_timestamp"
        const val HOME_URL = "https://areaexclusiva.colegioetapa.com.br/home"
        const val NOTAS_URL = "https://areaexclusiva.colegioetapa.com.br/provas/notas"
        const val CALENDARIO_URL_BASE = "https://areaexclusiva.colegioetapa.com.br/provas/datas"
        const val OUT_URL = "https://areaexclusiva.colegioetapa.com.br"
        const val CAROUSEL_LOADING_DELAY = 250L
        const val MAX_RECENT_GRADES = 4 // Limite de notas a serem exibidas
    }

    // --- Lifecycle & Setup ---
    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context !is WebViewFragment.LoginSuccessListener) {
            throw RuntimeException("$context deve implementar LoginSuccessListener")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isFragmentDestroyed = false
        shouldBlockNavigation = false
        initializeViews(view)
        setupRecyclerView()
        setupListeners()
        configureCarouselHeight()
        checkInternetAndLoadData()
    }

    override fun onRefresh() {
        Log.d("HomeFragment", "Pull-to-Refresh acionado")
        buscarDadosAtualizados()
    }

    override fun onPause() {
        super.onPause()
        shouldBlockNavigation = true
    }

    override fun onResume() {
        super.onResume()
        shouldBlockNavigation = false
        if (hasBeenVisible && !isDataLoaded) {
            checkInternetAndLoadData()
        }
        hasBeenVisible = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configureCarouselHeight()
        if (carouselItems.isNotEmpty()) {
            setupCarouselWithLoading()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isFragmentDestroyed = true
        handler.removeCallbacksAndMessages(null)
    }

    private fun initializeViews(view: View) {
        loadingContainer = view.findViewById(R.id.loadingContainer)
        contentContainer = view.findViewById(R.id.contentContainer)
        layoutSemInternet = view.findViewById(R.id.layout_sem_internet)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)
        viewPager = view.findViewById(R.id.viewPager)
        newsRecyclerView = view.findViewById(R.id.newsRecyclerView)
        txtStuckHint = view.findViewById(R.id.txtStuckHint)
        carouselLoadingIndicator = view.findViewById(R.id.carouselLoadingIndicator)
        newsSectionContainer = view.findViewById(R.id.newsSectionContainer)
        recentGradesSectionContainer = view.findViewById(R.id.recentGradesSectionContainer)
        tableRecentGrades = view.findViewById(R.id.tableRecentGrades)
    }

    private fun setupRecyclerView() {
        newsRecyclerView?.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    }

    private fun setupListeners() {
        btnTentarNovamente?.setOnClickListener {
            isDataLoaded = false
            checkInternetAndLoadData()
        }
    }

    // --- Data Fetching & Processing ---

    private fun checkInternetAndLoadData() {
        if (hasInternetConnection()) {
            if (!isDataLoaded) {
                val hasCache = loadCache()
                if (hasCache) {
                    showContentState()
                    setupUI()
                    isDataLoaded = true
                } else {
                    showLoadingState()
                }
            }
            fetchDataInBackground()
        } else {
            showOfflineState()
        }
    }

    private fun buscarDadosAtualizados() {
        isDataLoaded = false
        checkInternetAndLoadData()
        (requireActivity() as? MainActivity)?.setRefreshing(false)
    }

    private fun fetchDataInBackground() {
        lifecycleScope.launch {
            try {
                // Executa todas as buscas de dados em paralelo
                val homeDocDeferred = async(Dispatchers.IO) { fetchPageData(HOME_URL) }
                val gradesDocDeferred = async(Dispatchers.IO) { fetchPageData(NOTAS_URL) }
                val calendarDocsDeferred = (1..12).map { mes ->
                    async(Dispatchers.IO) { fetchPageData("$CALENDARIO_URL_BASE?mes%5B%5D=$mes") }
                }

                val homeDoc = homeDocDeferred.await()
                val gradesDoc = gradesDocDeferred.await()
                val calendarDocs = awaitAll(*calendarDocsDeferred.toTypedArray())

                // Processa os dados obtidos
                val allExams = parseAllCalendarData(calendarDocs)
                val allGrades = parseAllGradesData(gradesDoc)
                val recentGrades = findRecentGrades(allExams, allGrades)

                withContext(Dispatchers.Main) {
                    if (isFragmentDestroyed) return@withContext

                    if (isValidSession(homeDoc)) {
                        processPageContent(homeDoc) // Processa carrossel e notícias
                        saveCache()
                        showContentState()
                        setupUI() // Configura carrossel e notícias
                        setupRecentGradesTable(recentGrades) // Configura a nova tabela de notas
                        isDataLoaded = true
                    } else {
                        handleInvalidSession()
                    }
                }
            } catch (e: Exception) { // Captura exceções genéricas de corrotinas ou IO
                withContext(Dispatchers.Main) {
                    if (!isFragmentDestroyed) handleDataFetchError(e)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun fetchPageData(url: String): Document? {
        return try {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            Jsoup.connect(url)
                .header("Cookie", cookies ?: "")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36")
                .timeout(20000)
                .get()
        } catch (e: IOException) {
            Log.e("HomeFragment", "Erro ao buscar dados de $url: ${e.message}")
            null // Retorna nulo em caso de erro de rede
        }
    }

    private fun parseAllCalendarData(docs: List<Document?>): List<ProvaCalendario> {
        val allExams = mutableListOf<ProvaCalendario>()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        docs.filterNotNull().forEach { doc ->
            val table = doc.selectFirst("table") ?: return@forEach
            val rows = table.select("tbody > tr")
            for (tr in rows) {
                val cells = tr.children()
                if (cells.size < 4) continue

                try {
                    val dataStr = cells[0].text().split(" ")[0] // "2/6"
                    val codigo = cells[1].ownText()
                    val conjuntoStr = cells[3].text().filter { it.isDigit() }

                    if (dataStr.contains('/') && conjuntoStr.isNotEmpty()) {
                        val dataParts = dataStr.split("/")
                        val day = dataParts[0].toInt()
                        val month = dataParts[1].toInt() - 1 // Calendar month is 0-indexed
                        val conjunto = conjuntoStr.toInt()

                        val calendar = Calendar.getInstance().apply {
                            set(currentYear, month, day, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        allExams.add(ProvaCalendario(calendar, codigo, conjunto))
                    }
                } catch (e: Exception) {
                    Log.e("HomeFragment", "Erro ao parsear linha do calendário: ${tr.text()}", e)
                }
            }
        }
        return allExams
    }

    private fun parseAllGradesData(doc: Document?): List<Nota> {
        if (doc == null) return emptyList()
        val allGrades = mutableListOf<Nota>()
        val table = doc.selectFirst("table") ?: return emptyList()

        val headers = table.select("thead th")
        val conjuntoMap = mutableMapOf<Int, Int>() // Map<columnIndex, conjuntoNumber>
        headers.forEachIndexed { index, th ->
            if (index > 1) { // Pular "Código" e "Matéria"
                val conjunto = th.text().filter { it.isDigit() }.toIntOrNull()
                if (conjunto != null) conjuntoMap[index] = conjunto
            }
        }

        val rows = table.select("tbody > tr")
        for (tr in rows) {
            val cols = tr.children()
            if (cols.size <= 1) continue
            val codigo = cols[1].text()

            cols.forEachIndexed { colIndex, td ->
                conjuntoMap[colIndex]?.let { conjunto ->
                    val nota = td.text()
                    if (nota.isNotEmpty()) {
                        allGrades.add(Nota(codigo, conjunto, nota))
                    }
                }
            }
        }
        return allGrades
    }

    private fun findRecentGrades(allExams: List<ProvaCalendario>, allGrades: List<Nota>): List<NotaRecente> {
        if (allExams.isEmpty() || allGrades.isEmpty()) return emptyList()

        val gradesMap = allGrades.associateBy { "${it.codigo}-${it.conjunto}" }
        val today = Calendar.getInstance()

        return allExams
            .filter { it.data.before(today) || it.data == today } // Apenas provas passadas
            .sortedByDescending { it.data } // Ordena pela mais recente
            .mapNotNull { exam ->
                val key = "${exam.codigo}-${exam.conjunto}"
                gradesMap[key]?.let { nota ->
                    if (nota.valor != "--") {
                        NotaRecente(exam.codigo, exam.conjunto.toString(), nota.valor, exam.data)
                    } else null
                }
            }
            .distinctBy { "${it.codigo}-${it.conjunto}" } // Pega apenas a ocorrência mais recente de cada prova
            .take(MAX_RECENT_GRADES) // Limita o número de notas
    }

    // --- UI & State Management ---

    private fun setupUI() {
        setupCarouselWithLoading()
        setupNews()
    }

    private fun setupNews() {
        if (isFragmentDestroyed) return
        if (newsItems.isNotEmpty()) {
            newsSectionContainer?.visibility = View.VISIBLE
            newsRecyclerView?.adapter = NewsAdapter()
        } else {
            newsSectionContainer?.visibility = View.GONE
        }
    }

    private fun setupRecentGradesTable(grades: List<NotaRecente>) {
        if (isFragmentDestroyed || tableRecentGrades == null) return
        val context = context ?: return

        tableRecentGrades?.removeAllViews()

        if (grades.isEmpty()) {
            recentGradesSectionContainer?.visibility = View.GONE
            return
        }

        recentGradesSectionContainer?.visibility = View.VISIBLE

        // Header Row
        val headerRow = TableRow(context)
        headerRow.setBackgroundColor(ContextCompat.getColor(context, R.color.header_bg))
        headerRow.addView(createGradeTableCell("Código", true, context))
        headerRow.addView(createGradeTableCell("Conjunto", true, context))
        headerRow.addView(createGradeTableCell("Nota", true, context))
        tableRecentGrades?.addView(headerRow)

        // Data Rows
        for (grade in grades) {
            val dataRow = TableRow(context)
            dataRow.addView(createGradeTableCell(grade.codigo, false, context))
            dataRow.addView(createGradeTableCell(grade.conjunto, false, context))
            dataRow.addView(createGradeTableCell(grade.nota, false, context))
            tableRecentGrades?.addView(dataRow)
        }
    }

    private fun createGradeTableCell(txt: String, isHeader: Boolean, context: Context): TextView {
        return TextView(context).apply {
            text = txt
            setTypeface(null, if (isHeader) Typeface.BOLD else Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isHeader) 13f else 12f)
            val h = (12 * resources.displayMetrics.density).toInt()
            val v = (8 * resources.displayMetrics.density).toInt()
            setPadding(h, v, h, v)
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
        }
    }

    fun onLoginSuccess() {
        Log.d("HomeFragment", "Login bem-sucedido - forçando recarregamento")
        clearCache()
        isDataLoaded = false
        checkInternetAndLoadData()
    }

    private fun configureCarouselHeight() {
        val viewPager = this.viewPager ?: return
        val context = context ?: return
        val isTablet = resources.configuration.screenWidthDp >= 600
        if (isTablet) return

        val screenWidth = resources.displayMetrics.widthPixels
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.carousel_margin) * 2 +
                context.resources.getDimensionPixelSize(R.dimen.activity_horizontal_margin)
        val availableWidth = screenWidth - horizontalPadding
        val calculatedHeight = (availableWidth * 300) / 800
        val minHeight = resources.getDimensionPixelSize(R.dimen.carousel_min_height)
        val maxHeight = resources.getDimensionPixelSize(R.dimen.carousel_max_height)
        val finalHeight = calculatedHeight.coerceIn(minHeight, maxHeight)

        val layoutParams = viewPager.layoutParams
        layoutParams.height = finalHeight
        viewPager.layoutParams = layoutParams
    }

    private fun isValidSession(doc: Document?): Boolean {
        return doc?.getElementById("home_banners_carousel") != null
    }

    private fun processPageContent(doc: Document?) {
        if (doc == null) return
        val newCarousel = mutableListOf<CarouselItem>()
        val newNews = mutableListOf<NewsItem>()
        processCarousel(doc, newCarousel)
        processNews(doc, newNews)
        carouselItems.clear(); carouselItems.addAll(newCarousel)
        newsItems.clear(); newsItems.addAll(newNews)
    }

    private fun processCarousel(doc: Document, carouselList: MutableList<CarouselItem>) {
        val carousel = doc.getElementById("home_banners_carousel") ?: return
        val items = carousel.select(".carousel-item")
        for (item in items) {
            val linkUrl = item.selectFirst("a")?.attr("href") ?: ""
            var imgUrl = item.select("img").attr("src")
            if (!imgUrl.startsWith("http")) imgUrl = "https://www.colegioetapa.com.br$imgUrl"
            carouselList.add(CarouselItem(imgUrl, linkUrl))
        }
    }

    private fun processNews(doc: Document, newsList: MutableList<NewsItem>) {
        val newsSection = doc.selectFirst("div.col-12.col-lg-8.mb-5") ?: return
        val cards = newsSection.select(".card.border-radius-card").not("#modal-avisos-importantes .card.border-radius-card")
        for (card in cards) {
            var iconUrl = card.select("img.aviso-icon").attr("src")
            val title = card.select("p.text-blue.aviso-text").text()
            val desc = card.select("p.m-0.aviso-text").text()
            val link = card.select("a[target=_blank]").attr("href")
            if (!iconUrl.startsWith("http")) iconUrl = "https://areaexclusiva.colegioetapa.com.br$iconUrl"
            if (newsList.none { it.title == title }) newsList.add(NewsItem(iconUrl, title, desc, link))
        }
    }

    private fun handleInvalidSession() {
        if (isFragmentDestroyed) return
        clearCache()
        isDataLoaded = false
        navigateToWebView(OUT_URL)
    }

    private fun handleDataFetchError(e: Exception) {
        if (isFragmentDestroyed) return
        Log.e("HomeFragment", "Erro ao buscar dados: ${e.message}", e)
        if (!isDataLoaded) {
            if (loadCache()) {
                showContentState()
                setupUI()
            } else {
                showOfflineState()
            }
        }
    }

    private fun saveCache() {
        if (isFragmentDestroyed) return
        val context = context ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            val gson = Gson()
            putString(KEY_CAROUSEL_ITEMS, gson.toJson(carouselItems))
            putString(KEY_NEWS_ITEMS, gson.toJson(newsItems))
            putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
        }
    }

    private fun loadCache(): Boolean {
        if (isFragmentDestroyed) return false
        val context = context ?: return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cacheTimestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0)
        if (System.currentTimeMillis() - cacheTimestamp > 24 * 60 * 60 * 1000L) {
            clearCache(); return false
        }
        val gson = Gson()
        val carouselJson = prefs.getString(KEY_CAROUSEL_ITEMS, null)
        val newsJson = prefs.getString(KEY_NEWS_ITEMS, null)
        if (carouselJson != null) {
            val carouselType = object : TypeToken<MutableList<CarouselItem>>() {}.type
            val newsType = object : TypeToken<MutableList<NewsItem>>() {}.type
            carouselItems.clear(); carouselItems.addAll(gson.fromJson(carouselJson, carouselType))
            if (newsJson != null) { newsItems.clear(); newsItems.addAll(gson.fromJson(newsJson, newsType)) }
            return true
        }
        return false
    }

    private fun clearCache() {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit { clear() }
    }

    private fun navigateToWebView(url: String) {
        if (shouldBlockNavigation || isFragmentDestroyed) return
        try {
            val fragment = WebViewFragment().apply { arguments = createArgs(url) }
            requireActivity().supportFragmentManager.beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.nav_host_fragment, fragment).addToBackStack(null).commitAllowingStateLoss()
        } catch (e: IllegalStateException) {
            Log.e("HomeFragment", "Navigation blocked: ${e.message}")
        }
    }

    private fun showLoadingState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.VISIBLE
        contentContainer?.visibility = View.GONE
        layoutSemInternet?.visibility = View.GONE
    }

    private fun showContentState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.GONE
        contentContainer?.visibility = View.VISIBLE
        layoutSemInternet?.visibility = View.GONE
    }

    private fun showOfflineState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.GONE
        contentContainer?.visibility = View.GONE
        layoutSemInternet?.visibility = View.VISIBLE
    }

    private fun hasInternetConnection(): Boolean {
        val connectivityManager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setupCarouselWithLoading() {
        if (carouselItems.isEmpty() || isFragmentDestroyed) return
        showCarouselLoading()
        configureCarouselHeight()
        handler.postDelayed({
            if (isFragmentDestroyed || !isCarouselLoading) return@postDelayed
            setupCarousel()
            hideCarouselLoading()
        }, CAROUSEL_LOADING_DELAY)
    }

    private fun setupCarousel() {
        if (carouselItems.isEmpty() || isFragmentDestroyed) return
        viewPager?.adapter = CarouselAdapter()
    }

    private fun showCarouselLoading() {
        if (isFragmentDestroyed) return
        isCarouselLoading = true
        carouselLoadingIndicator?.visibility = View.VISIBLE
        viewPager?.visibility = View.INVISIBLE
    }

    private fun hideCarouselLoading() {
        if (isFragmentDestroyed) return
        isCarouselLoading = false
        carouselLoadingIndicator?.visibility = View.GONE
        viewPager?.visibility = View.VISIBLE
    }

    // Adapters e ViewHolders
    private inner class CarouselAdapter : RecyclerView.Adapter<CarouselViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CarouselViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_carousel, parent, false))
        override fun onBindViewHolder(holder: CarouselViewHolder, position: Int) {
            val item = carouselItems[position]
            Glide.with(holder.itemView.context).load(item.imageUrl).centerCrop().into(holder.imageView)
            holder.itemView.setOnClickListener { item.linkUrl?.let { navigateToWebView(it) } }
        }
        override fun getItemCount() = carouselItems.size
    }

    private inner class NewsAdapter : RecyclerView.Adapter<NewsViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = NewsViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.news_item, parent, false))
        override fun onBindViewHolder(holder: NewsViewHolder, position: Int) {
            val item = newsItems[position]
            Glide.with(holder.itemView.context).load(item.iconUrl).into(holder.icon)
            holder.title.text = item.title
            holder.description.text = item.description
            holder.itemView.setOnClickListener { item.link?.let { navigateToWebView(it) } }
        }
        override fun getItemCount() = newsItems.size
    }

    internal class CarouselViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) { val imageView: ImageView = itemView.findViewById(R.id.imageView) }
    internal class NewsViewHolder(view: View) : RecyclerView.ViewHolder(view) { val icon: ImageView = view.findViewById(R.id.news_icon); val title: TextView = view.findViewById(R.id.news_title); val description: TextView = view.findViewById(R.id.news_description) }
    data class CarouselItem(val imageUrl: String?, val linkUrl: String?)
    data class NewsItem(val iconUrl: String?, val title: String?, val description: String?, val link: String?)
}
