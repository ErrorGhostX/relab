package egx.relab_app

import android.os.Bundle
import android.view.Menu
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        RetrofitClient.init(applicationContext)
        tokenManager = TokenManager(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.appBarMain.toolbar)
        
        // Устанавливаем белый цвет текста в Toolbar
        binding.appBarMain.toolbar.setTitleTextColor(resources.getColor(egx.relab_app.R.color.white, theme))
        binding.appBarMain.toolbar.setSubtitleTextColor(resources.getColor(egx.relab_app.R.color.white, theme))

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)

        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.nav_home),
            drawerLayout
        )
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
                // Если есть токен, разрешаем работу локально даже без интернета
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                
                // ВАЖНО: СНАЧАЛА загружаем и показываем сохраненные данные для мгновенного отображения
                loadAndDisplaySavedProfile()
                
                // ЗАТЕМ пытаемся загрузить свежие данные с сервера в фоне
                loadProfileFromServer()
            }
        }
    }
    
    /**
     * Загрузить и отобразить сохраненные данные профиля из TokenManager
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
            } catch (e: Exception) {
                // Если ошибка сети, просто показываем что нет подключения, но не выкидываем
                updateConnectionIndicator(false)
                // Данные уже показаны из сохраненных в loadAndDisplaySavedProfile()
                android.util.Log.d("MainActivity", "Не удалось загрузить профиль с сервера: ${e.message}")
            }
        }


    }

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
    
    fun refreshNavBar() {
        // Обновляем навигацию используя сохраненные данные
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
            resources.getDrawable(egx.relab_app.R.drawable.connection_indicator_green, theme)
        } else {
            resources.getDrawable(egx.relab_app.R.drawable.connection_indicator_red, theme)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}
