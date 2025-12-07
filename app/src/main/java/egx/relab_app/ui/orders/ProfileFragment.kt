package egx.relab_app.ui.orders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import egx.relab_app.databinding.FragmentProfileBinding
import egx.relab_app.models.UserResponse
import egx.relab_app.network.ApiService
import egx.relab_app.network.RetrofitClient
import egx.relab_app.storage.TokenManager
import kotlinx.coroutines.launch
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private lateinit var tokenManager: TokenManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tokenManager = TokenManager(requireContext())

        // Сначала покажем то, что уже есть (из TokenManager), чтобы не было пустого экрана
        binding.tvUserName.text = tokenManager.username ?: "Загрузка..."
        binding.tvEmail.text    = tokenManager.email    ?: "Загрузка..."

        // Теперь запросим свежие данные с сервера
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val user = RetrofitClient.apiService.getCurrentUser()
                // Обновляем UI
                binding.tvUserName.text = user.username
                binding.tvEmail.text    = user.email

                // Сохраняем в TokenManager, чтобы в других местах было актуально
                tokenManager.username = user.username
                tokenManager.email    = user.email
            } catch (e: Exception) {
                Toast.makeText(requireContext(),
                    "Не удалось загрузить профиль: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLogout.setOnClickListener {
            logout()
        }
    }

    private fun logout() {
        tokenManager.accessToken = null
        tokenManager.refreshToken = null
        tokenManager.username     = null
        tokenManager.email        = null

        findNavController().navigate(R.id.loginFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

