package com.marinov.colegioetapa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import com.google.android.material.navigationrail.NavigationRailView
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), WebViewFragment.LoginSuccessListener {

    // Interface para comunicação com os Fragments
    interface RefreshableFragment {
        fun onRefresh()
    }

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 100
        private const val TAG = "MainActivity"

        private val REFRESHABLE_FRAGMENTS = setOf(
            R.id.navigation_notas,
            R.id.option_horarios_aula,
            R.id.action_profile,
            R.id.navigation_more
        )
    }

    // --- Instâncias dos Fragments Principais ---
    private val homeFragment = HomeFragment()
    private val notasFragment = NotasFragment()
    private val calendarioProvasFragment = CalendarioProvas()
    private val horariosAulaFragment = HorariosAula()
    private val profileFragment = ProfileFragment()
    private val moreFragment = MoreFragment()
    private var activeFragment: Fragment = homeFragment // Começa com o Home

    // --- Views e State ---
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var navRail: NavigationRailView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private var isLayoutReady = false
    private var currentFragmentId = R.id.navigation_home
    private var isUpdatingSelection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBarsForLegacyDevices()
        setContentView(R.layout.activity_main)

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        setupSwipeRefresh()

        val toolbar: MaterialToolbar = findViewById(R.id.topAppBar)
        setSupportActionBar(toolbar)
        setupToolbarInsets(toolbar)

        bottomNav = findViewById(R.id.bottom_navigation)
        navRail = findViewById(R.id.navigation_rail)

        val rootView = findViewById<View>(R.id.main)
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (isLayoutReady) return
                rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                isLayoutReady = true

                // Configura os fragments uma única vez se a Activity não estiver sendo recriada.
                // Esta lógica já carrega todos os fragments em segundo plano.
                if (savedInstanceState == null) {
                    setupFragments()
                }

                configureNavigationForDevice()
                handleIntent(intent)
            }
        })
        solicitarPermissaoNotificacao()
        iniciarNotasWorker()
        iniciarUpdateWorker()
    }

    /**
     * Adiciona todos os fragments principais ao FragmentManager,
     * mostrando apenas o homeFragment e escondendo os outros.
     * Esta é a lógica de pré-carregamento.
     */
    private fun setupFragments() {
        supportFragmentManager.beginTransaction()
            .add(R.id.nav_host_fragment, moreFragment, "more").hide(moreFragment)
            .add(R.id.nav_host_fragment, profileFragment, "profile").hide(profileFragment)
            .add(R.id.nav_host_fragment, horariosAulaFragment, "horarios").hide(horariosAulaFragment)
            .add(R.id.nav_host_fragment, calendarioProvasFragment, "calendario").hide(calendarioProvasFragment)
            .add(R.id.nav_host_fragment, notasFragment, "notas").hide(notasFragment)
            .add(R.id.nav_host_fragment, homeFragment, "home")
            .commit()
        activeFragment = homeFragment
        currentFragmentId = R.id.navigation_home
        updateRefreshLayoutState()
    }


    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            if (isRefreshEnabled()) {
                (activeFragment as? RefreshableFragment)?.onRefresh() ?: run {
                    swipeRefreshLayout.isRefreshing = false
                }
            } else {
                swipeRefreshLayout.isRefreshing = false
            }
        }
        swipeRefreshLayout.setDistanceToTriggerSync(250)
    }

    private fun setupToolbarInsets(toolbar: MaterialToolbar) {
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, statusBarHeight, v.paddingRight, v.paddingBottom)
            insets
        }
    }

    private fun isRefreshEnabled(): Boolean {
        // Se o fragmento ativo é um fragmento padrão da navegação, verifica se está na lista
        return REFRESHABLE_FRAGMENTS.contains(currentFragmentId)
    }

    private fun updateRefreshLayoutState() {
        swipeRefreshLayout.isEnabled = isRefreshEnabled()
        if (!isRefreshEnabled()) {
            swipeRefreshLayout.isRefreshing = false
        }
    }

    fun setRefreshing(refreshing: Boolean) {
        if (isRefreshEnabled()) {
            swipeRefreshLayout.isRefreshing = refreshing
        } else {
            swipeRefreshLayout.isRefreshing = false
        }
    }

    override fun onLoginSuccess() {
        (supportFragmentManager.findFragmentByTag("home") as? HomeFragment)?.onLoginSuccess()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isLayoutReady) handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val destination = intent?.getStringExtra("destination")
        if (destination != null) {
            Log.d(TAG, "Handling intent with destination: $destination")
            when (destination) {
                "notas" -> openFragment(R.id.navigation_notas)
                "horarios" -> openFragment(R.id.option_horarios_aula)
                "provas" -> openFragment(R.id.option_calendario_provas)
            }
        }
    }

    /**
     * Troca de `replace` para `hide` e `show`.
     */
    private fun openFragment(fragmentId: Int) {
        if (isFinishing || isDestroyed || currentFragmentId == fragmentId) return

        swipeRefreshLayout.isRefreshing = false
        Log.d(TAG, "Switching to fragment: $fragmentId")

        val fragmentToShow = when (fragmentId) {
            R.id.navigation_home -> homeFragment
            R.id.option_calendario_provas -> calendarioProvasFragment
            R.id.navigation_notas -> notasFragment
            R.id.option_horarios_aula -> horariosAulaFragment
            R.id.action_profile -> profileFragment
            R.id.navigation_more -> moreFragment
            else -> return
        }

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .hide(activeFragment)
            .show(fragmentToShow)
            .commit()

        activeFragment = fragmentToShow
        currentFragmentId = fragmentId

        updateMenuSelection(fragmentId)
        updateRefreshLayoutState()
    }

    private fun updateMenuSelection(fragmentId: Int) {
        if (isUpdatingSelection) return
        isUpdatingSelection = true
        runOnUiThread {
            try {
                if (resources.getBoolean(R.bool.isTablet)) {
                    if (navRail.selectedItemId != fragmentId) navRail.selectedItemId = fragmentId
                } else {
                    if (bottomNav.selectedItemId != fragmentId) bottomNav.selectedItemId = fragmentId
                }
            } finally {
                isUpdatingSelection = false
            }
        }
    }

    private fun configureNavigationForDevice() {
        val isTablet = resources.getBoolean(R.bool.isTablet)
        if (isTablet) {
            bottomNav.visibility = View.GONE
            navRail.visibility = View.VISIBLE
            navRail.setOnItemSelectedListener { item ->
                if (!isUpdatingSelection) openFragment(item.itemId)
                true
            }
            if (navRail.selectedItemId != currentFragmentId) navRail.selectedItemId = currentFragmentId
        } else {
            navRail.visibility = View.GONE
            bottomNav.visibility = View.VISIBLE
            bottomNav.setOnItemSelectedListener { item ->
                if (!isUpdatingSelection) openFragment(item.itemId)
                true
            }
            if (bottomNav.selectedItemId != currentFragmentId) bottomNav.selectedItemId = currentFragmentId
            setupKeyboardListener()
        }
    }

    private fun setupKeyboardListener() {
        val rootView: View = findViewById(R.id.main)
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            rootView.getWindowVisibleDisplayFrame(r)
            val screenHeight = rootView.rootView.height
            val keypadHeight = screenHeight - r.bottom
            bottomNav.visibility = if (keypadHeight > screenHeight * 0.15) View.GONE else View.VISIBLE
        }
    }

    // --- Métodos para Workers, Permissões, etc. (sem alterações) ---

    private fun iniciarUpdateWorker() {
        val updateWork = PeriodicWorkRequest.Builder(UpdateCheckWorker::class.java, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("UpdateCheckWorker", ExistingPeriodicWorkPolicy.KEEP, updateWork)
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun configureSystemBarsForLegacyDevices() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val isDarkMode = when (AppCompatDelegate.getDefaultNightMode()) {
                AppCompatDelegate.MODE_NIGHT_YES -> true
                AppCompatDelegate.MODE_NIGHT_NO -> false
                else -> {
                    val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    currentNightMode == Configuration.UI_MODE_NIGHT_YES
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.apply {
                    @Suppress("DEPRECATION")
                    clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                    addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
                        @Suppress("DEPRECATION")
                        statusBarColor = Color.BLACK
                        @Suppress("DEPRECATION")
                        navigationBarColor = Color.BLACK
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            @Suppress("DEPRECATION")
                            var flags = decorView.systemUiVisibility
                            @Suppress("DEPRECATION")
                            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                            @Suppress("DEPRECATION")
                            decorView.systemUiVisibility = flags
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        navigationBarColor = if (isDarkMode) {
                            ContextCompat.getColor(this@MainActivity, R.color.nav_bar_dark)
                        } else {
                            ContextCompat.getColor(this@MainActivity, R.color.nav_bar_light)
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                var flags = window.decorView.systemUiVisibility
                if (isDarkMode) {
                    @Suppress("DEPRECATION")
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
                    @Suppress("DEPRECATION")
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = flags
            }
            if (!isDarkMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                var flags = window.decorView.systemUiVisibility
                @Suppress("DEPRECATION")
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = flags
            }
        }
    }

    private fun solicitarPermissaoNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
            }
        }
    }

    private fun iniciarNotasWorker() {
        val notasWork = PeriodicWorkRequest.Builder(NotasWorker::class.java, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("NotasWorkerTask", ExistingPeriodicWorkPolicy.KEEP, notasWork)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    /**
     * Mantido com `replace` para fragments que não são parte da navegação principal, como WebViewFragment.
     */
    fun openCustomFragment(fragment: Fragment) {
        swipeRefreshLayout.isRefreshing = false
        val isRefreshable = fragment is RefreshableFragment

        // Oculta o fragmento ativo da navegação principal antes de adicionar o fragmento personalizado
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .hide(activeFragment)
            .add(R.id.nav_host_fragment, fragment)
            .addToBackStack(null)
            .commit()

        // Para fragments personalizados, não há ID de menu.
        updateMenuSelection(View.NO_ID)
        swipeRefreshLayout.isEnabled = isRefreshable
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.top_app_bar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_profile -> {
                openFragment(R.id.action_profile)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun abrirDetalhesProva(url: String) {
        val fragment = DetalhesProvaFragment.newInstance(url)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.nav_host_fragment, fragment) // Usa replace para sobrepor
            .addToBackStack(null)
            .commit()
    }

    fun navigateToHome() {
        // Usa `openFragment` para garantir que o HomeFragment seja mostrado corretamente
        // e que o estado de navegação seja atualizado.
        openFragment(R.id.navigation_home)
    }
}
