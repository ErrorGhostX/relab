package egx.relab_app

import android.os.Bundle
import android.view.Menu
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

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)

        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.nav_home, R.id.orderListFragment),
            drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        // Обработка пункта "Выход"
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_logout -> {
                    tokenManager.accessToken = null
                    tokenManager.refreshToken = null
                    tokenManager.username = null
                    tokenManager.username = null
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    navController.navigate(R.id.loginFragment)
                    true
                }
                else -> false
            }
        }

        // Проверка авторизации и загрузка текущего пользователя
        lifecycleScope.launch {
            val token = tokenManager.accessToken
            if (token.isNullOrBlank()) {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                navController.navigate(R.id.loginFragment)
            } else {
                try {
                    val user = RetrofitClient.apiService.getCurrentUser() // Получаем пользователя с почтой
                    tokenManager.username = user.username
                    tokenManager.email = user.email // Сохраняем почту пользователя
                    updateNavBar(user) // Обновляем UI с именем и почтой
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                } catch (e: Exception) {
                    // Неверный токен — направляем на логин
                    tokenManager.accessToken = null
                    tokenManager.refreshToken = null
                    tokenManager.username = null
                    tokenManager.email = null
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    navController.navigate(R.id.loginFragment)
                }
            }
        }

        // Установка имени и почты пользователя из SharedPreferences (на случай, если API ещё не вызван)
        //updateNavBar(UserResponse(username = tokenManager.username, email = tokenManager.email))
    }

    private fun updateNavBar(user: UserResponse) {
        val navView = binding.navView
        val headerView = navView.getHeaderView(0)

        val userNameTextView = headerView.findViewById<TextView>(R.id.textView)
        val userMailTextView = headerView.findViewById<TextView>(R.id.textMail)

        userNameTextView.text = user.username ?: "Гость"  // Или любое другое поле, которое хранит имя
        userMailTextView.text = user.email ?: "Не указан"  // Или другое поле для почты
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
