package egx.relab_app.ui.orders

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.google.android.material.navigation.NavigationView
import egx.relab_app.R
import egx.relab_app.databinding.FragmentLoginBinding
import egx.relab_app.models.UserResponse
import egx.relab_app.network.ApiService
import egx.relab_app.network.RetrofitClient
import kotlinx.coroutines.launch
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ) = FragmentLoginBinding.inflate(inflater, container, false)
        .also { _binding = it }
        .root

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupCompanyPicker()

        binding.btnSettings.setOnClickListener {
            showAddCompanyDialog()
        }

        binding.buttonGuest.setOnClickListener {
            enterGuestMode()
        }

        binding.buttonLogin.setOnClickListener {
            val user = binding.editTextUsername.text.toString().trim()
            val pass = binding.editTextPassword.text.toString().trim()

            if (user.isBlank() || pass.isBlank()) {
                Toast.makeText(requireContext(), "Введите логин и пароль", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Убеждаемся, что выбран какой-то бэкенд
            if (RetrofitClient.tokenManager.getCompanies().isEmpty() && RetrofitClient.tokenManager.serverUrl.isNullOrBlank()) {
                Toast.makeText(requireContext(), "Сначала добавьте компанию в настройках", Toast.LENGTH_LONG).show()
                showAddCompanyDialog()
                return@setOnClickListener
            }

            doLogin(user, pass)
        }
    }

    private fun setupCompanyPicker() {
        val tokenManager = RetrofitClient.tokenManager
        val companies = tokenManager.getCompanies()
        
        if (companies.isEmpty()) {
            binding.spinnerCompany.setText("Добавьте компанию →")
            return
        }

        val displayNames = companies.map { it.nickname ?: it.name }
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, displayNames)
        binding.spinnerCompany.setAdapter(adapter)

        // Восстанавливаем последнюю выбранную
        val currentId = tokenManager.currentCompanyId
        val selectedIndex = companies.indexOfFirst { it.id == currentId }
        if (selectedIndex >= 0) {
            binding.spinnerCompany.setText(displayNames[selectedIndex], false)
        } else {
            binding.spinnerCompany.setText(displayNames[0], false)
            tokenManager.currentCompanyId = companies[0].id
            RetrofitClient.recreateRetrofit()
        }

        binding.spinnerCompany.setOnItemClickListener { _, _, position, _ ->
            val company = companies[position]
            tokenManager.currentCompanyId = company.id
            RetrofitClient.recreateRetrofit()
            tokenManager.isGuestMode = false
            egx.relab_app.database.AppDatabase.destroyInstance()
            android.util.Log.d("LoginFragment", "Selected company: ${company.name} at ${company.baseUrl}")
        }
    }

    private fun showAddCompanyDialog() {
        val dialogView = android.view.LayoutInflater.from(requireContext()).inflate(egx.relab_app.R.layout.dialog_add_company, null)
        val editIp = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editIp)
        val editNickname = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editNickname)
        val btnAdd = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAdd)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressBar)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        btnAdd.setOnClickListener {
            val ip = editIp.text.toString().trim()
            val nick = editNickname.text.toString().trim()

            if (ip.isBlank()) return@setOnClickListener

            // Формируем правильный URL
            val baseUrl = if (ip.startsWith("http")) ip else "http://$ip"
            val finalUrl = if (baseUrl.endsWith("/api/")) baseUrl else if (baseUrl.endsWith("/")) "${baseUrl}api/" else "$baseUrl/api/"

            progressBar.visibility = android.view.View.VISIBLE
            btnAdd.isEnabled = false

            lifecycleScope.launch {
                try {
                    // Создаем временный клиент для проверки нового адреса
                    val tempRetrofit = retrofit2.Retrofit.Builder()
                        .baseUrl(finalUrl)
                        .client(okhttp3.OkHttpClient.Builder()
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .build())
                        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                        .build()

                    val tempApi = tempRetrofit.create(egx.relab_app.network.ApiService::class.java)
                    val info = tempApi.getCompanyInfo()
                    
                    val newConfig = egx.relab_app.models.CompanyConfig(
                        id = java.util.UUID.randomUUID().toString(),
                        name = info.name,
                        nickname = if (nick.isBlank()) null else nick,
                        baseUrl = finalUrl,
                        logoUrl = info.logo_url
                    )
                    
                    RetrofitClient.tokenManager.addCompany(newConfig)
                    RetrofitClient.tokenManager.currentCompanyId = newConfig.id
                    RetrofitClient.tokenManager.isGuestMode = false
                    RetrofitClient.recreateRetrofit()
                    
                    setupCompanyPicker()
                    dialog.dismiss()
                    Toast.makeText(requireContext(), "Компания '${info.name}' добавлена", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.util.Log.e("LoginFragment", "Error fetching company info", e)
                    Toast.makeText(requireContext(), "Не удалось найти компанию по этому адресу", Toast.LENGTH_LONG).show()
                } finally {
                    progressBar.visibility = android.view.View.GONE
                    btnAdd.isEnabled = true
                }
            }
        }

        dialog.show()
    }

    private fun enterGuestMode() {
        val tokenManager = RetrofitClient.tokenManager
        tokenManager.isGuestMode = true
        tokenManager.accessToken = "guest_token" // Фейковый токен
        tokenManager.rank = "guest"
        tokenManager.username = "Гость"
        tokenManager.fullName = "Локальный гость"
        
        egx.relab_app.database.AppDatabase.destroyInstance()
        
        Toast.makeText(requireContext(), "Вход в гостевой режим", Toast.LENGTH_SHORT).show()
        
        // Переходим в приложение
        findNavController().navigate(
            R.id.nav_home,
            null,
            navOptions {
                popUpTo(R.id.loginFragment) { inclusive = true }
            }
        )
    }

    private fun doLogin(user: String, pass: String) {
        lifecycleScope.launch {
            try {

                val resp = RetrofitClient.apiService.login(ApiService.LoginRequest(user, pass))
                RetrofitClient.tokenManager.accessToken = resp.access
                RetrofitClient.tokenManager.refreshToken = resp.refresh
                RetrofitClient.tokenManager.isGuestMode = false
                
                // Пересоздаём БД для серверного режима (уходим из гостевой БД)
                egx.relab_app.database.AppDatabase.destroyInstance()


                val currentUser = RetrofitClient.apiService.getCurrentUser()


                updateNavBar(currentUser)

                // Регистрация устройства для Push-уведомлений (если доступно)
                try {
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val token = task.result
                            lifecycleScope.launch {
                                try {
                                    RetrofitClient.apiService.registerDevice(ApiService.RegisterDeviceRequest(token))
                                    android.util.Log.d("FCM", "Токен успешно зарегистрирован: $token")
                                } catch (e: Exception) {
                                    android.util.Log.e("FCM", "Ошибка регистрации токена", e)
                                }
                            }
                        } else {
                            android.util.Log.w("FCM", "Fetching FCM registration token failed", task.exception)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("FCM", "Firebase не инициализирован")
                }

                findNavController().navigate(
                    R.id.nav_home,
                    null,
                    navOptions {
                        popUpTo(R.id.loginFragment) { inclusive = true }
                    }
                )
            } catch (_: Exception) {
                Toast.makeText(requireContext(), "Ошибка авторизации", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateNavBar(user: UserResponse) {
        // Используем метод из MainActivity для обновления навигации
        val activity = requireActivity() as? egx.relab_app.MainActivity
        activity?.updateNavBar(user)
        
        // Также сохраняем данные в TokenManager
        val tokenManager = egx.relab_app.storage.TokenManager(requireContext())
        tokenManager.userId = user.id
        tokenManager.username = user.username
        tokenManager.email = user.email
        tokenManager.fullName = user.full_name
        tokenManager.avatarUrl = user.avatar
        tokenManager.rank = user.rank
        tokenManager.rankDisplay = user.rank_display
    }
    override fun onResume() {
        super.onResume()
        (activity as AppCompatActivity).supportActionBar?.hide()
    }

    override fun onStop() {
        super.onStop()
        (activity as AppCompatActivity).supportActionBar?.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
