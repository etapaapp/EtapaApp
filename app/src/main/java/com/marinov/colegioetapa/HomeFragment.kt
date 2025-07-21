package com.marinov.colegioetapa

import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

class HomeFragment : Fragment(), MainActivity.RefreshableFragment {

    private var viewPager: ViewPager2? = null
    private var newsRecyclerView: RecyclerView? = null
    private var layoutSemInternet: LinearLayout? = null
    private var btnTentarNovamente: MaterialButton? = null
    private var loadingContainer: View? = null
    private var contentContainer: View? = null
    private var txtStuckHint: TextView? = null
    private var carouselLoadingIndicator: CircularProgressIndicator? = null

    private var shouldBlockNavigation = false
    private var isFragmentDestroyed = false
    private var hasBeenVisible = false
    private var isDataLoaded = false
    private var isCarouselLoading = false

    private val carouselItems: MutableList<CarouselItem> = mutableListOf()
    private val newsItems: MutableList<NewsItem> = mutableListOf()

    private val handler = Handler(Looper.getMainLooper())

    // Constantes para proporção das imagens do carrossel (800x300 = 8:3)
    private companion object {
        const val PREFS_NAME = "HomeFragmentCache"
        const val KEY_CAROUSEL_ITEMS = "carousel_items"
        const val KEY_NEWS_ITEMS = "news_items"
        const val CAROUSEL_ASPECT_RATIO = 8f / 3f
        const val KEY_CACHE_TIMESTAMP = "cache_timestamp"
        const val HOME_URL = "https://areaexclusiva.colegioetapa.com.br/home"
        const val OUT_URL = "https://areaexclusiva.colegioetapa.com.br"
        const val CAROUSEL_LOADING_DELAY = 250L // 250ms
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context !is WebViewFragment.LoginSuccessListener) {
            throw RuntimeException("$context deve implementar LoginSuccessListener")
        }
    }

    // Função para ser chamada pela Activity quando login for bem-sucedido
    fun onLoginSuccess() {
        Log.d("HomeFragment", "Login bem-sucedido - forçando recarregamento")
        // Limpar cache para garantir dados frescos
        clearCache()
        // Resetar estado
        isDataLoaded = false
        // Forçar recarregamento
        checkInternetAndLoadData()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        shouldBlockNavigation = false
        initializeViews(view)
        setupRecyclerView()
        setupListeners()
        configureCarouselHeight()
        checkInternetAndLoadData()
    }

    // Implementação da interface RefreshableFragment
    override fun onRefresh() {
        Log.d("HomeFragment", "Pull-to-Refresh acionado")
        // Nova abordagem: sempre tentar recarregar, mesmo no estado offline
        buscarDadosAtualizados()
    }

    private fun buscarDadosAtualizados() {
        // Forçar recarregamento dos dados independente do estado atual
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val doc = fetchHomePageData()
                withContext(Dispatchers.Main) {
                    if (isFragmentDestroyed) return@withContext

                    if (isValidSession(doc)) {
                        processPageContent(doc)
                        saveCache()
                        setupUI()
                        // Mostrar conteúdo (pode estar substituindo o estado offline)
                        showContentState()
                        isDataLoaded = true
                    } else {
                        handleInvalidSession()
                    }
                    // Parar o refresh
                    (requireActivity() as MainActivity).setRefreshing(false)
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    if (!isFragmentDestroyed) {
                        Log.e("HomeFragment", "Erro ao buscar dados: ${e.message}")
                        // Se não temos dados carregados, mostramos o estado offline
                        if (!isDataLoaded) {
                            showOfflineState()
                        }
                        (requireActivity() as MainActivity).setRefreshing(false)
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        shouldBlockNavigation = true
    }

    override fun onResume() {
        super.onResume()
        shouldBlockNavigation = false

        // Se o fragment já foi visível antes, verifica se precisa recarregar
        if (hasBeenVisible && !isDataLoaded) {
            Log.d("HomeFragment", "Retornando do WebView - verificando necessidade de recarregamento")
            checkInternetAndLoadData()
        }
        hasBeenVisible = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Reconfigurar altura do carrossel na mudança de orientação
        configureCarouselHeight()
        // Forçar recriação do adapter para aplicar nova altura
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
    }

    private fun setupRecyclerView() {
        newsRecyclerView?.layoutManager = LinearLayoutManager(
            context,
            LinearLayoutManager.HORIZONTAL,
            false
        )
    }

    private fun setupListeners() {
        btnTentarNovamente?.setOnClickListener {
            isDataLoaded = false
            checkInternetAndLoadData()
        }
    }

    private fun configureCarouselHeight() {
        val viewPager = this.viewPager ?: return
        val context = context ?: return

        // Verificar se é tablet - se for, manter comportamento atual
        val isTablet = resources.configuration.screenWidthDp >= 600
        if (isTablet) {
            // Para tablet, não fazer nada - a altura já está definida no XML
            return
        }

        // Para celular, usar regra de 3 baseada nas dimensões originais 800x300
        val screenWidth = resources.displayMetrics.widthPixels
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.carousel_margin) * 2 +
                context.resources.getDimensionPixelSize(R.dimen.activity_horizontal_margin)

        val availableWidth = screenWidth - horizontalPadding

        // Regra de 3: 800px -> 300px | availableWidth -> altura
        // altura = (availableWidth * 300) / 800
        val calculatedHeight = (availableWidth * 300) / 800

        // Limites para celular
        val minHeight = resources.getDimensionPixelSize(R.dimen.carousel_min_height)
        val maxHeight = resources.getDimensionPixelSize(R.dimen.carousel_max_height)
        val finalHeight = calculatedHeight.coerceIn(minHeight, maxHeight)

        // Aplicar altura INSTANTANEAMENTE
        val layoutParams = viewPager.layoutParams
        layoutParams.height = finalHeight
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        viewPager.layoutParams = layoutParams

        // Configurar propriedades do ViewPager
        viewPager.apply {
            clipToPadding = false
            clipChildren = false
            offscreenPageLimit = 1
            setPageTransformer(null)
        }

        Log.d("HomeFragment", "Altura do carrossel configurada: $finalHeight px (largura: $availableWidth px) - mobile")
    }

    private fun checkInternetAndLoadData() {
        if (hasInternetConnection()) {
            // Se já tem dados carregados, não precisa mostrar loading
            if (!isDataLoaded) {
                // Primeiro tenta carregar cache
                val hasCache = loadCache()

                if (hasCache) {
                    showContentState()
                    setupUI()
                    isDataLoaded = true
                } else {
                    // Se não tem cache, mostra loading
                    showLoadingState()
                }

                // Sempre busca dados frescos
                fetchDataInBackground()
            } else {
                // Se dados já estão carregados, mas queremos forçar recarregamento
                // (caso do retorno do login), mostra loading brevemente
                showLoadingState()
                fetchDataInBackground()
            }
        } else {
            showOfflineState()
        }
    }

    private fun fetchDataInBackground() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val doc = fetchHomePageData()

                withContext(Dispatchers.Main) {
                    if (isFragmentDestroyed) return@withContext

                    if (isValidSession(doc)) {
                        processPageContent(doc)
                        saveCache()
                        showContentState()
                        setupUI()
                        isDataLoaded = true
                    } else {
                        handleInvalidSession()
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    if (!isFragmentDestroyed) {
                        handleDataFetchError(e)
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun fetchHomePageData(): Document {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(HOME_URL)

        return Jsoup.connect(HOME_URL)
            .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15")
            .header("Cookie", cookies ?: "")
            .timeout(15000)
            .get()
    }

    private fun isValidSession(doc: Document): Boolean {
        return doc.getElementById("home_banners_carousel") != null &&
                doc.selectFirst("div.col-12.col-lg-8.mb-5") != null
    }

    private fun processPageContent(doc: Document?) {
        val newCarousel = mutableListOf<CarouselItem>()
        val newNews = mutableListOf<NewsItem>()

        processCarousel(doc, newCarousel)
        processNews(doc, newNews)

        carouselItems.clear()
        carouselItems.addAll(newCarousel)

        newsItems.clear()
        newsItems.addAll(newNews)
    }

    private fun handleInvalidSession() {
        if (isFragmentDestroyed) return
        clearCache()
        isDataLoaded = false
        navigateToWebView(OUT_URL)
    }

    private fun handleDataFetchError(e: IOException) {
        if (isFragmentDestroyed) return
        Log.e("HomeFragment", "Erro ao buscar dados: ${e.message}")

        // Se não tem dados carregados, vai para login
        if (!isDataLoaded) {
            navigateToWebView(OUT_URL)
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

        // Verifica se o cache não está muito antigo (24 horas)
        val cacheTimestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0)
        val currentTime = System.currentTimeMillis()
        val cacheAge = currentTime - cacheTimestamp
        val maxCacheAge = 24 * 60 * 60 * 1000L // 24 horas

        if (cacheAge > maxCacheAge) {
            clearCache()
            return false
        }

        val gson = Gson()
        val carouselJson = prefs.getString(KEY_CAROUSEL_ITEMS, null)
        val newsJson = prefs.getString(KEY_NEWS_ITEMS, null)

        if (carouselJson != null && newsJson != null) {
            val carouselType = object : TypeToken<MutableList<CarouselItem>>() {}.type
            val newsType = object : TypeToken<MutableList<NewsItem>>() {}.type

            val cachedCarousel = gson.fromJson<MutableList<CarouselItem>>(carouselJson, carouselType)
            val cachedNews = gson.fromJson<MutableList<NewsItem>>(newsJson, newsType)

            if (cachedCarousel?.isNotEmpty() == true && cachedNews?.isNotEmpty() == true) {
                carouselItems.clear()
                carouselItems.addAll(cachedCarousel)

                newsItems.clear()
                newsItems.addAll(cachedNews)

                return true
            }
        }
        return false
    }

    private fun clearCache() {
        if (isFragmentDestroyed) return
        val context = context ?: return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            remove(KEY_CAROUSEL_ITEMS)
            remove(KEY_NEWS_ITEMS)
            remove(KEY_CACHE_TIMESTAMP)
        }
    }

    private fun processCarousel(doc: Document?, carouselList: MutableList<CarouselItem>) {
        if (doc == null) return

        val carousel = doc.getElementById("home_banners_carousel") ?: return
        val items = carousel.select(".carousel-item")

        for (item in items) {
            if (isFragmentDestroyed) return

            val linkElem = item.selectFirst("a")
            val linkUrl = linkElem?.attr("href") ?: ""
            var imgUrl = item.select("img").attr("src")

            if (!imgUrl.startsWith("http")) {
                imgUrl = "https://www.colegioetapa.com.br$imgUrl"
            }

            carouselList.add(CarouselItem(imgUrl, linkUrl))
        }
    }

    private fun processNews(doc: Document?, newsList: MutableList<NewsItem>) {
        if (doc == null) return

        val newsSection = doc.selectFirst("div.col-12.col-lg-8.mb-5") ?: return
        val cards = newsSection.select(".card.border-radius-card")
        cards.removeAll(newsSection.select("#modal-avisos-importantes .card.border-radius-card"))

        for (card in cards) {
            var iconUrl = card.select("img.aviso-icon").attr("src")
            val title = card.select("p.text-blue.aviso-text").text()
            val desc = card.select("p.m-0.aviso-text").text()
            val link = card.select("a[target=_blank]").attr("href")

            if (!iconUrl.startsWith("http")) {
                iconUrl = "https://areaexclusiva.colegioetapa.com.br$iconUrl"
            }

            if (!isDuplicateNews(title, newsList)) {
                newsList.add(NewsItem(iconUrl, title, desc, link))
            }
        }
    }

    private fun isDuplicateNews(title: String?, newsList: MutableList<NewsItem>): Boolean {
        return newsList.any { it.title == title }
    }

    private fun navigateToWebView(url: String) {
        if (shouldBlockNavigation || isFragmentDestroyed) return

        try {
            val fragment = WebViewFragment().apply {
                arguments = createArgs(url)
            }
            requireActivity().supportFragmentManager
                .beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.nav_host_fragment, fragment)
                .addToBackStack(null)
                .commitAllowingStateLoss()
        } catch (e: IllegalStateException) {
            Log.e("HomeFragment", "Navigation blocked: ${e.message}")
        }
    }

    private fun showLoadingState() {
        if (isFragmentDestroyed) return
        handler.post {
            loadingContainer?.visibility = View.VISIBLE
            contentContainer?.visibility = View.GONE
            layoutSemInternet?.visibility = View.GONE
            txtStuckHint?.visibility = View.VISIBLE
        }
    }

    private fun showContentState() {
        if (isFragmentDestroyed) return
        handler.post {
            loadingContainer?.visibility = View.GONE
            contentContainer?.visibility = View.VISIBLE
            layoutSemInternet?.visibility = View.GONE
            txtStuckHint?.visibility = View.GONE
        }
    }

    private fun showOfflineState() {
        if (isFragmentDestroyed) return
        handler.post {
            loadingContainer?.visibility = View.GONE
            contentContainer?.visibility = View.GONE
            layoutSemInternet?.visibility = View.VISIBLE
            txtStuckHint?.visibility = View.GONE
        }
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

    private fun hasInternetConnection(): Boolean {
        if (isFragmentDestroyed) return false
        val context = context ?: return false

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun setupUI() {
        setupCarouselWithLoading()
        setupNews()
    }

    private fun setupCarouselWithLoading() {
        if (carouselItems.isEmpty() || isFragmentDestroyed) return

        Log.d("HomeFragment", "Configurando carrossel com ${carouselItems.size} itens")

        // Mostrar loading do carrossel
        showCarouselLoading()

        // Garantir que a altura esteja configurada antes de criar o adapter
        configureCarouselHeight()

        // Aguardar 250ms antes de mostrar o carrossel
        handler.postDelayed({
            if (isFragmentDestroyed || !isCarouselLoading) return@postDelayed

            setupCarousel()
            hideCarouselLoading()
        }, CAROUSEL_LOADING_DELAY)
    }

    private fun setupCarousel() {
        if (carouselItems.isEmpty() || isFragmentDestroyed) return

        val viewPager = this.viewPager ?: return

        // Criar e configurar o adapter IMEDIATAMENTE
        val adapter = CarouselAdapter()
        viewPager.adapter = adapter

        // Forçar aplicação instantânea do layout em toda a hierarquia
        viewPager.apply {
            requestLayout()
            invalidate()
            // Forçar medição e layout imediatos
            measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(layoutParams.height, View.MeasureSpec.EXACTLY)
            )
            layout(left, top, right, top + layoutParams.height)
        }

        // Forçar o container pai também
        val parentView = viewPager.parent as? View
        parentView?.let {
            it.requestLayout()
            it.invalidate()
        }

        Log.d("HomeFragment", "Carrossel configurado com altura: ${viewPager.layoutParams.height}px")
    }

    private fun setupNews() {
        if (newsItems.isEmpty() || isFragmentDestroyed) return

        Log.d("HomeFragment", "Configurando notícias com ${newsItems.size} itens")

        // Sempre recria o adapter para garantir que funcione
        newsRecyclerView?.adapter = NewsAdapter()
    }

    private inner class CarouselAdapter : RecyclerView.Adapter<CarouselViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CarouselViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_carousel, parent, false)
            return CarouselViewHolder(view)
        }

        override fun onBindViewHolder(holder: CarouselViewHolder, position: Int) {
            val item = carouselItems[position]
            val context = holder.itemView.context
            val isTablet = context.resources.configuration.screenWidthDp >= 600

            // Configurar a ImageView
            holder.imageView.apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = false

                // Garantir que a ImageView ocupe todo o container
                val layoutParams = this.layoutParams as FrameLayout.LayoutParams
                layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                layoutParams.setMargins(0, 0, 0, 0)
                this.layoutParams = layoutParams

                setPadding(0, 0, 0, 0)
            }

            // Carregar imagem de forma otimizada
            if (isTablet) {
                // Para tablet, usar comportamento original
                val viewPager = this@HomeFragment.viewPager
                val actualWidth = viewPager?.width ?: context.resources.displayMetrics.widthPixels
                val actualHeight = viewPager?.height ?: run {
                    val screenWidth = context.resources.displayMetrics.widthPixels
                    val horizontalPadding = context.resources.getDimensionPixelSize(R.dimen.carousel_padding_horizontal) * 2 +
                            context.resources.getDimensionPixelSize(R.dimen.carousel_margin) * 2
                    val targetWidth = screenWidth - horizontalPadding
                    (targetWidth / CAROUSEL_ASPECT_RATIO).toInt()
                }

                Glide.with(context)
                    .load(item.imageUrl)
                    .centerCrop()
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_gallery)
                    .override(actualWidth, actualHeight)
                    .fitCenter()
                    .into(holder.imageView)
            } else {
                // Para celular, usar dimensões fixas conhecidas (800x300) e deixar o Glide otimizar
                Glide.with(context)
                    .load(item.imageUrl)
                    .centerCrop()
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_gallery)
                    .override(800, 300) // Dimensões originais da imagem
                    .into(holder.imageView)
            }

            holder.itemView.setOnClickListener {
                item.linkUrl?.let { url -> navigateToWebView(url) }
            }

            Log.d("HomeFragment", "Carregando imagem do carrossel (posição $position): ${item.imageUrl} - ${if (isTablet) "tablet" else "mobile"}")
        }

        override fun getItemCount(): Int = carouselItems.size
    }

    private inner class NewsAdapter : RecyclerView.Adapter<NewsViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NewsViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.news_item, parent, false)
            return NewsViewHolder(view)
        }

        override fun onBindViewHolder(holder: NewsViewHolder, position: Int) {
            val item = newsItems[position]

            Glide.with(holder.itemView.context)
                .load(item.iconUrl)
                .into(holder.icon)

            holder.title.text = item.title
            holder.description.text = item.description

            holder.itemView.setOnClickListener {
                item.link?.let { url -> navigateToWebView(url) }
            }
        }

        override fun getItemCount(): Int = newsItems.size
    }

    internal class CarouselViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.imageView)
    }

    internal class NewsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.news_icon)
        val title: TextView = view.findViewById(R.id.news_title)
        val description: TextView = view.findViewById(R.id.news_description)
    }

    data class CarouselItem(
        val imageUrl: String?,
        val linkUrl: String?
    )

    data class NewsItem(
        val iconUrl: String?,
        val title: String?,
        val description: String?,
        val link: String?
    )
}