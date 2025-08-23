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

        // Lista de fragments que suportam pull-to-refresh
        private val REFRESHABLE_FRAGMENTS = setOf(
            R.id.navigation_notas,
            R.id.option_horarios_aula,
            R.id.action_profile,
            R.id.navigation_more
            // WebViewFragment não tem ID específico pois é aberto via openCustomFragment
        )
    }

    private var currentFragment: Fragment? = null
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var navRail: NavigationRailView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private var isLayoutReady = false
    private var currentFragmentId = View.NO_ID
    private var isUpdatingSelection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBarsForLegacyDevices()
        MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimaryContainer,
            Color.BLACK
        )
        setContentView(R.layout.activity_main)

        // Referência ao SwipeRefreshLayout
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)

        // Configuração do Pull-to-Refresh
        swipeRefreshLayout.setOnRefreshListener {
            if (isRefreshEnabled()) {
                (currentFragment as? RefreshableFragment)?.onRefresh() ?: run {
                    swipeRefreshLayout.isRefreshing = false
                }
            } else {
                swipeRefreshLayout.isRefreshing = false
            }
        }

        // Desabilita refresh enquanto rola para baixo
        swipeRefreshLayout.setDistanceToTriggerSync(250)

        val toolbar: MaterialToolbar = findViewById(R.id.topAppBar)
        setSupportActionBar(toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(
                v.paddingLeft,
                statusBarHeight,
                v.paddingRight,
                v.paddingBottom
            )
            insets
        }
        bottomNav = findViewById(R.id.bottom_navigation)
        navRail = findViewById(R.id.navigation_rail)

        // Aguardar layout estar pronto
        val rootView = findViewById<View>(R.id.main)
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (isLayoutReady) return
                rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                isLayoutReady = true
                configureNavigationForDevice()
                handleIntent(intent)
            }
        })
        solicitarPermissaoNotificacao()
        iniciarNotasWorker()
        iniciarUpdateWorker()
    }

    // Verifica se o fragment atual deve ter pull-to-refresh habilitado
    private fun isRefreshEnabled(): Boolean {
        return when {
            // Se é um fragment customizado (como WebViewFragment), verifica se implementa RefreshableFragment
            currentFragmentId == View.NO_ID -> currentFragment is RefreshableFragment
            // Se é um fragment padrão, verifica se está na lista de refreshable
            else -> REFRESHABLE_FRAGMENTS.contains(currentFragmentId)
        }
    }

    // Atualiza o estado do SwipeRefreshLayout baseado no fragment atual
    private fun updateRefreshLayoutState() {
        swipeRefreshLayout.isEnabled = isRefreshEnabled()
        if (!isRefreshEnabled()) {
            swipeRefreshLayout.isRefreshing = false
        }
    }

    // Método para os Fragments controlarem o estado do refresh
    fun setRefreshing(refreshing: Boolean) {
        if (isRefreshEnabled()) {
            swipeRefreshLayout.isRefreshing = refreshing
        } else {
            swipeRefreshLayout.isRefreshing = false
        }
    }

    override fun onLoginSuccess() {
        (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? HomeFragment)?.onLoginSuccess()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isLayoutReady) handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val destination = intent?.getStringExtra("destination") ?: run {
            if (currentFragment == null) openFragment(R.id.navigation_home)
            return
        }
        Log.d(TAG, "Handling intent with destination: $destination")
        when (destination) {
            "notas" -> openFragment(R.id.navigation_notas)
            "horarios" -> openFragment(R.id.option_horarios_aula)
            "provas" -> openFragment(R.id.option_calendario_provas)
        }
    }

    private fun openFragment(fragmentId: Int) {
        if (isFinishing || isDestroyed) return

        // Reseta estado do refresh ao trocar de fragment
        swipeRefreshLayout.isRefreshing = false

        Log.d(TAG, "Opening fragment: $fragmentId")
        val fragment = when (fragmentId) {
            R.id.navigation_home -> HomeFragment()
            R.id.option_calendario_provas -> CalendarioProvas()
            R.id.navigation_notas -> NotasFragment()
            R.id.option_horarios_aula -> HorariosAula()
            R.id.action_profile -> ProfileFragment()
            R.id.navigation_more -> MoreFragment()
            else -> return
        }
        currentFragment = fragment
        currentFragmentId = fragmentId

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.nav_host_fragment, fragment)
            .commit()

        updateMenuSelection(fragmentId)

        // Atualiza o estado do pull-to-refresh após trocar de fragment
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
            if (currentFragmentId == View.NO_ID) navRail.selectedItemId = R.id.navigation_home
        } else {
            navRail.visibility = View.GONE
            bottomNav.visibility = View.VISIBLE
            bottomNav.setOnItemSelectedListener { item ->
                if (!isUpdatingSelection) openFragment(item.itemId)
                true
            }
            if (currentFragmentId == View.NO_ID) bottomNav.selectedItemId = R.id.navigation_home
            val rootView: View = findViewById(R.id.main)
            rootView.viewTreeObserver.addOnGlobalLayoutListener {
                val r = Rect()
                rootView.getWindowVisibleDisplayFrame(r)
                val screenHeight = rootView.rootView.height
                val keypadHeight = screenHeight - r.bottom
                bottomNav.visibility = if (keypadHeight > screenHeight * 0.15) View.GONE else View.VISIBLE
            }
        }
    }

    private fun iniciarUpdateWorker() {
        val updateWork = PeriodicWorkRequest.Builder(
            UpdateCheckWorker::class.java,
            15,
            TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "UpdateCheckWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            updateWork
        )
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
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    private fun iniciarNotasWorker() {
        val notasWork = PeriodicWorkRequest.Builder(
            NotasWorker::class.java,
            15,
            TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "NotasWorkerTask",
            ExistingPeriodicWorkPolicy.KEEP,
            notasWork
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    fun openCustomFragment(fragment: Fragment) {
        // Reseta estado do refresh
        swipeRefreshLayout.isRefreshing = false

        currentFragment = fragment
        currentFragmentId = View.NO_ID

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.nav_host_fragment, fragment)
            .addToBackStack(null)
            .commit()

        updateMenuSelection(View.NO_ID)

        // Atualiza o estado do pull-to-refresh após trocar de fragment
        updateRefreshLayoutState()
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
            .replace(R.id.nav_host_fragment, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun navigateToHome() {
        openFragment(R.id.navigation_home)
    }
}