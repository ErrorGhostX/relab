package egx.relab_app

import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationView
import egx.relab_app.databinding.ActivityMainBinding
import egx.relab_app.models.UserResponse
import egx.relab_app.network.RetrofitClient
import egx.relab_app.storage.TokenManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    lateinit var binding: ActivityMainBinding
    private lateinit var tokenManager: TokenManager
    private lateinit var toggle: ActionBarDrawerToggle
    // Пример (в Activity)
    private val PERM_REQUEST = 1001

    fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            perms.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERM_REQUEST)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        RetrofitClient.init(applicationContext)
        tokenManager = TokenManager(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        applyDisplayCutoutMode()
        requestNeededPermissions()

        // Глобальный слушатель ошибок авторизации (401)
        lifecycleScope.launch {
            RetrofitClient.authErrorFlow.collect {
                android.util.Log.w("MainActivity", "Global 401 detected, redirecting to login")
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                androidx.navigation.Navigation.findNavController(this@MainActivity, R.id.nav_host_fragment_content_main)
                    .navigate(R.id.loginFragment)
            }
        }

        // ------------------ Toolbar ------------------
        val toolbar = binding.appBarMain.toolbar
        setSupportActionBar(toolbar)
        binding.appBarMain.toolbar.setTitleTextColor(resources.getColor(R.color.white, theme))
        binding.appBarMain.toolbar.setSubtitleTextColor(resources.getColor(R.color.white, theme))

        // ------------------ Drawer ------------------
        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView

        toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        setSupportActionBar(toolbar)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val isHome = destination.id == R.id.nav_home
            val isLogin = destination.id == R.id.loginFragment

            if (isHome || isLogin) {
                hideToolbarAnimated(toolbar)
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
            } else {
                showToolbarAnimated(toolbar)
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
            }
        }




        // ------------------ AppBarConfiguration ------------------

        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.nav_home),
            drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)

        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_home -> {
                    navController.navigate(R.id.nav_home)
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_orders -> {
                    navController.navigate(R.id.orderListFragment)
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_analytics -> {
                    navController.navigate(R.id.analyticsFragment)
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_profile -> {
                    navController.navigate(R.id.profileFragment)
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_settings -> {
                    navController.navigate(R.id.settingsFragment)
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_tools -> {
                    navController.navigate(R.id.toolsFragment)
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_logout -> {
                    // Останавливаем фоновый сервис уведомлений WebSocket
                    egx.relab_app.services.NotificationWebSocketService.stop(this)
                    tokenManager.accessToken = null
                    tokenManager.refreshToken = null
                    tokenManager.username = null
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    navController.navigate(R.id.loginFragment)
                    true
                }
                else -> false
            }
        }


                lifecycleScope.launch {
                    val token = tokenManager.accessToken
                    if (token.isNullOrBlank()) {
                        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                        navController.navigate(R.id.loginFragment)
                    } else {
                        // ВАЖНО: Приоритет на локальность
                        // Если есть токен, разрешаем работу локально даже без интернета
                        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                        
                        // ВАЖНО: СНАЧАЛА загружаем и показываем сохраненные данные для мгновенного отображения
                        // Пользователь видит данные сразу из локального хранилища
                        loadAndDisplaySavedProfile()
                        
                        // ВАЖНО: ЗАТЕМ пытаемся загрузить свежие данные с сервера в ФОНОВОМ режиме
                        // Не блокирует отображение - пользователь уже видит локальные данные
                        loadProfileFromServer()
                        
                        // Запускаем фоновый сервис уведомлений через WebSocket (гарантированный канал)
                        egx.relab_app.services.NotificationWebSocketService.start(this@MainActivity)
                    }
                }
        
        handleRemoteIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRemoteIntent(intent)
    }

    private fun handleRemoteIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra("EXTRA_OPEN_REMOTE", false) == true) {
            val dialog = egx.relab_app.ui.tools.RemoteControlGuideDialog()
            dialog.show(supportFragmentManager, "RemoteControlGuide")
        }
    }



    private fun hideToolbarAnimated(toolbar: View) {
        toolbar.animate()
            .translationY(-toolbar.height.toFloat())
            .alpha(0f)
            .setDuration(200)
            .withEndAction { toolbar.visibility = View.GONE }
            .start()
    }

    private fun showToolbarAnimated(toolbar: View) {
        toolbar.visibility = View.VISIBLE
        toolbar.translationY = -toolbar.height.toFloat()
        toolbar.alpha = 0f
        toolbar.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(200)
            .start()
    }

    /**
     * Загрузить и отобразить сохраненные данные профиля из TokenManager
     * 
     * ВАЖНО: Приоритет на локальность
     * - Загружает данные СРАЗУ из локального хранилища (TokenManager)
     * - Пользователь видит данные мгновенно
     * - Не делает запросов к серверу
     * - Работает полностью автономно
     */
    private fun loadAndDisplaySavedProfile() {
        val savedUsername = tokenManager.username
        val savedEmail = tokenManager.email
        val savedFullName = tokenManager.fullName
        val savedAvatar = tokenManager.avatarUrl
        
        if (savedUsername != null) {
            val savedUser = egx.relab_app.models.UserResponse(
                id = 0,
                username = savedUsername,
                email = savedEmail,
                full_name = savedFullName,
                avatar = savedAvatar
            )
            updateNavBar(savedUser)
        } else {
            // Если нет сохраненных данных, показываем заглушку
            val defaultUser = egx.relab_app.models.UserResponse(
                id = 0,
                username = "Гость",
                email = null,
                full_name = null,
                avatar = null
            )
            updateNavBar(defaultUser)
        }
    }
    
    /**
     * Загрузить профиль с сервера и обновить локальные данные
     * 
     * ВАЖНО: Приоритет на локальность
     * - Работает в ФОНОВОМ режиме - не блокирует UI
     * - Обновляет локальное хранилище (TokenManager) с данными с сервера
     * - Не перезаписывает локальные данные пустыми значениями
     * - При отсутствии сети продолжает работать с локальными данными
     */
    private fun loadProfileFromServer() {
        lifecycleScope.launch {
            try {
                val user = RetrofitClient.apiService.getCurrentUser()
                // Сохраняем в TokenManager ПЕРЕД обновлением UI
                // ВАЖНО: Обновляем только если значение не null, чтобы не перезаписывать сохраненные данные
                tokenManager.username = user.username
                tokenManager.email = user.email ?: tokenManager.email
                
                // Обновляем full_name только если сервер вернул непустое значение
                if (!user.full_name.isNullOrBlank()) {
                    tokenManager.fullName = user.full_name
                }
                
                // Обновляем avatar только если сервер вернул непустое значение
                if (!user.avatar.isNullOrEmpty() && user.avatar != "null") {
                    tokenManager.avatarUrl = user.avatar
                }
                
                // Создаем UserResponse с сохраненными значениями, если сервер не вернул
                val userWithSavedData = user.copy(
                    full_name = user.full_name ?: tokenManager.fullName,
                    avatar = if (!user.avatar.isNullOrEmpty() && user.avatar != "null") user.avatar else tokenManager.avatarUrl
                )
                
                // Обновляем UI с данными с сервера (с сохраненными значениями для full_name и avatar)
                updateNavBar(userWithSavedData)
                updateConnectionIndicator(true)
                
                // Отправляем токен Firebase на сервер (если Firebase доступен)
                try {
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val token = task.result
                            android.util.Log.d("MainActivity", "MY FCM TOKEN: $token")
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    RetrofitClient.apiService.registerDevice(egx.relab_app.network.ApiService.RegisterDeviceRequest(token))
                                    android.util.Log.d("MainActivity", "FCM токен успешно отправлен")
                                } catch (e: Exception) {
                                    android.util.Log.e("MainActivity", "Ошибка отправки FCM токена: ${e.message}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "Firebase не инициализирован (отсутствует google-services.json)")
                }

            } catch (e: Exception) {
                // Если ошибка сети, просто показываем что нет подключения, но не выкидываем
                updateConnectionIndicator(false)
                
                // ВАЖНО: Если сервер вернул 401 (Unauthorized), выкидываем на логин
                if (e.message?.contains("401") == true || (e is retrofit2.HttpException && e.code() == 401)) {
                    android.util.Log.w("MainActivity", "Сессия истекла (401), переход на логин")
                    tokenManager.accessToken = null
                    binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    androidx.navigation.Navigation.findNavController(this@MainActivity, R.id.nav_host_fragment_content_main)
                        .navigate(R.id.loginFragment)
                }
                
                // Данные уже показаны из сохраненных в loadAndDisplaySavedProfile()
                android.util.Log.d("MainActivity", "Не удалось загрузить профиль с сервера: ${e.message}")
            }
        }


    }

    /**
     * Обновить навигационную панель с данными пользователя
     * 
     * ВАЖНО: Приоритет на локальность
     * - Обновляет UI с данными пользователя
     * - Сохраняет данные в TokenManager (локальное хранилище)
     * - Не перезаписывает локальные данные пустыми значениями
     * 
     * @param user - данные пользователя (может быть из сервера или локального хранилища)
     */
    fun updateNavBar(user: UserResponse) {
        val navView = binding.navView
        val headerView = navView.getHeaderView(0)

        val fullNameTextView = headerView.findViewById<TextView>(R.id.textFullName)
        val userNameTextView = headerView.findViewById<TextView>(R.id.textView)
        val userMailTextView = headerView.findViewById<TextView>(R.id.textMail)
        val userImageView = headerView.findViewById<android.widget.ImageView>(R.id.imageView)

        // Отображаем ФИО отдельной строчкой, если есть
        if (!user.full_name.isNullOrBlank()) {
            fullNameTextView.text = user.full_name
            fullNameTextView.visibility = View.VISIBLE
        } else {
            fullNameTextView.visibility = View.GONE
        }
        
        // Отображаем username
        userNameTextView.text = user.username ?: "Гость"
        userMailTextView.text = user.email ?: "Не указан"
        
        // Используем URL аватара из user, если есть, иначе из TokenManager
        val avatarUrl = user.avatar ?: tokenManager.avatarUrl
        
        // Загружаем аватар если есть, иначе показываем placeholder
        android.util.Log.d("MainActivity", "Avatar URL: $avatarUrl")
        if (!avatarUrl.isNullOrEmpty() && avatarUrl != "null") {
            com.bumptech.glide.Glide.with(this)
                .load(avatarUrl)
                .placeholder(egx.relab_app.R.mipmap.ic_launcher_round)
                .error(egx.relab_app.R.mipmap.ic_launcher_round)
                .circleCrop()
                .into(userImageView)
        } else {
            // Показываем иконку приложения как placeholder
            userImageView.setImageResource(egx.relab_app.R.mipmap.ic_launcher_round)
        }
        
        // ВАЖНО: Сохраняем в TokenManager только если значения не пустые
        // Это предотвращает перезапись сохраненных данных на null
        if (!user.full_name.isNullOrBlank()) {
            tokenManager.fullName = user.full_name
        }
        if (!user.avatar.isNullOrEmpty() && user.avatar != "null") {
            tokenManager.avatarUrl = user.avatar
        }
    }
    
    /**
     * Обновить навигационную панель из локального хранилища
     * 
     * ВАЖНО: Приоритет на локальность
     * - Загружает данные из TokenManager (локальное хранилище)
     * - Не делает запросов к серверу
     * - Работает полностью автономно
     */
    fun refreshNavBar() {
        // ВАЖНО: Обновляем навигацию используя сохраненные данные из локального хранилища
        val savedUsername = tokenManager.username
        val savedEmail = tokenManager.email
        val savedFullName = tokenManager.fullName
        val savedAvatar = tokenManager.avatarUrl
        
        if (savedUsername != null) {
            val savedUser = egx.relab_app.models.UserResponse(
                id = 0,
                username = savedUsername,
                email = savedEmail,
                full_name = savedFullName,
                avatar = savedAvatar
            )
            updateNavBar(savedUser)
        } else {
            // Если нет сохраненных данных, показываем заглушку
            val defaultUser = egx.relab_app.models.UserResponse(
                id = 0,
                username = "Гость",
                email = null,
                full_name = null,
                avatar = null
            )
            updateNavBar(defaultUser)
        }
    }
    
    fun updateConnectionIndicator(isConnected: Boolean) {
        val indicator = binding.appBarMain.toolbar.findViewById<View>(R.id.connectionIndicator)
        indicator?.background = if (isConnected) {
            resources.getDrawable(R.drawable.connection_indicator_green, theme)
        } else {
            resources.getDrawable(R.drawable.connection_indicator_red, theme)
        }
    }

    private fun applyDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (tokenManager.isDisplayCutoutEnabled) {
                window.attributes.layoutInDisplayCutoutMode = 
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                
                // Чтобы фон заезжал под статус-бар
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                
                // Делаем контент на весь экран
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE 
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            } else {
                window.attributes.layoutInDisplayCutoutMode = 
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        
        // Обработка отступов (Safe Areas)
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.displayCutout
            binding.appBarMain.toolbar.setPadding(0, systemBars.top, 0, 0)
            
            insets
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return if (navController.currentDestination?.id != R.id.nav_home) {
            navController.popBackStack()
        } else {
            false
        }
    }

}
