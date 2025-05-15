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

        binding.tvUserName.text = tokenManager.username ?: "Неизвестный пользователь"
        binding.tvEmail.text = tokenManager.email ?: "Email не указан"



//        binding.btnEditProfile.setOnClickListener {
//
//            Toast.makeText(requireContext(),
//        }

        binding.btnLogout.setOnClickListener {
            logout()
        }
    }

    private fun logout() {
        tokenManager.accessToken = null
        tokenManager.refreshToken = null
        tokenManager.username = null
        tokenManager.email = null

        findNavController().navigate(R.id.loginFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
