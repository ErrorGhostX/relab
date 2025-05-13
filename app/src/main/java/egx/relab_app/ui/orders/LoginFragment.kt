package egx.relab_app.ui.orders

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
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

        binding.buttonLogin.setOnClickListener {
            val user = binding.editTextUsername.text.toString().trim()
            val pass = binding.editTextPassword.text.toString().trim()
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
                // 1. Получаем JWT
                val resp = RetrofitClient.apiService.login(ApiService.LoginRequest(user, pass))
                RetrofitClient.tokenManager.accessToken = resp.access
                RetrofitClient.tokenManager.refreshToken = resp.refresh

                // 2. Получаем данные текущего пользователя
                val currentUser = RetrofitClient.apiService.getCurrentUser()

                // 3. Обновляем UI навигационного бара
                updateNavBar(currentUser)

                // 4. Переходим сразу в список заказов и очищаем back-stack
                findNavController().navigate(
                    R.id.orderListFragment,
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
        // Обновляем UI навигационного бара
        val navView = requireActivity().findViewById<NavigationView>(R.id.nav_view) // Измените id на ваш actual nav_view
        val headerView = navView.getHeaderView(0)

        val userNameTextView = headerView.findViewById<TextView>(R.id.textView)
        val userMailTextView = headerView.findViewById<TextView>(R.id.textMail)

        userNameTextView.text = user.username  // Или любое другое поле, которое хранит имя
        userMailTextView.text = user.email  // Или другое поле для почты
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
