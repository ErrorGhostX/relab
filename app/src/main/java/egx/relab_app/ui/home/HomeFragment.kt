package egx.relab_app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import egx.relab_app.R
import egx.relab_app.databinding.FragmentHomeBinding
import egx.relab_app.network.RetrofitClient
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Проверка авторизации
        lifecycleScope.launch {
            try {
                RetrofitClient.apiService.getCurrentUser()
                // Если успешно, отображаем кнопку заказов
                binding.cardOrders.setOnClickListener {
                    findNavController().navigate(R.id.action_homeFragment_to_orderListFragment)
                }
            } catch (e: Exception) {
                // Если токен невалиден или отсутствует — перенаправляем на логин
                findNavController().navigate(R.id.loginFragment)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
