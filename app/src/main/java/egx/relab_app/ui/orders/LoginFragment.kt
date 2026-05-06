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

        // Загружаем сохранённый URL сервера
        val prefs = requireContext().getSharedPreferences("relab_prefs", android.content.Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", RetrofitClient.tokenManager.serverUrl ?: "http://192.168.1.100:8000/api/") ?: ""
        binding.editTextServerUrl.setText(savedUrl.replace("/api/", "").replace("/api", ""))

        binding.btnCheckConnection.setOnClickListener {
            val rawUrl = binding.editTextServerUrl.text.toString().trim()
            if (rawUrl.isBlank()) {
                Toast.makeText(requireContext(), "Введите адрес сервера", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val fullUrl = if (rawUrl.endsWith("/api/")) rawUrl
                          else if (rawUrl.endsWith("/")) "${rawUrl}api/"
                          else "$rawUrl/api/"

            // Сохраняем URL
            prefs.edit().putString("server_url", fullUrl).apply()
            RetrofitClient.tokenManager.serverUrl = fullUrl
            RetrofitClient.updateBaseUrl(fullUrl)

            // Проверяем подключение
            binding.viewConnectionStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.GRAY
            )
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    RetrofitClient.apiService.getAiStatus("ollama")
                    if (!isAdded) return@launch
                    binding.viewConnectionStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#4CAF50")
                    )
                    Toast.makeText(requireContext(), "✅ Сервер доступен", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    // Даже 401 означает, что сервер отвечает
                    if (!isAdded) return@launch
                    if (e is retrofit2.HttpException && e.code() == 401) {
                        binding.viewConnectionStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#4CAF50")
                        )
                        Toast.makeText(requireContext(), "✅ Сервер доступен", Toast.LENGTH_SHORT).show()
                    } else {
                        binding.viewConnectionStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#F44336")
                        )
                        Toast.makeText(requireContext(), "❌ Сервер недоступен", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.buttonLogin.setOnClickListener {
            val user = binding.editTextUsername.text.toString().trim()
            val pass = binding.editTextPassword.text.toString().trim()

            // Сохраняем URL перед логином
            val rawUrl = binding.editTextServerUrl.text.toString().trim()
            if (rawUrl.isNotBlank()) {
                val fullUrl = if (rawUrl.endsWith("/api/")) rawUrl
                              else if (rawUrl.endsWith("/")) "${rawUrl}api/"
                              else "$rawUrl/api/"
                prefs.edit().putString("server_url", fullUrl).apply()
                RetrofitClient.tokenManager.serverUrl = fullUrl
                RetrofitClient.updateBaseUrl(fullUrl)
            }

            if (user.isBlank() || pass.isBlank()) {
                Toast.makeText(requireContext(), "Введите логин и пароль", Toast.LENGTH_SHORT).show()
            } else {
                doLogin(user, pass)
            }
        }
    }

    private fun doLogin(user: String, pass: String) {
        lifecycleScope.launch {
            try {

                val resp = RetrofitClient.apiService.login(ApiService.LoginRequest(user, pass))
                RetrofitClient.tokenManager.accessToken = resp.access
                RetrofitClient.tokenManager.refreshToken = resp.refresh


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
