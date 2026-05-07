package egx.relab_app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import egx.relab_app.R
import egx.relab_app.databinding.FragmentHomeBinding
import egx.relab_app.network.RetrofitClient
import egx.relab_app.storage.TokenManager
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
        
        setupClickListeners()
        // СНАЧАЛА показываем сохраненные данные для мгновенного отображения
        updateUserData()
        
        // ЗАТЕМ загружаем данные с сервера в фоне
        loadUserData()

        val messagingViewModel = androidx.lifecycle.ViewModelProvider(requireActivity())[egx.relab_app.ui.messaging.MessagingViewModel::class.java]
        messagingViewModel.totalUnreadCount.observe(viewLifecycleOwner) { count ->
            if (count > 0) {
                binding.tvChatsBadge.text = if (count > 99) "99+" else count.toString()
                binding.tvChatsBadge.visibility = View.VISIBLE
            } else {
                binding.tvChatsBadge.visibility = View.GONE
            }
        }
        // Загружаем список чатов, чтобы получить актуальные бейджи
        messagingViewModel.loadChatRooms()
    }
    
    override fun onResume() {
        super.onResume()
        // ВАЖНО: Обновляем данные при возврате из других экранов (например, из профиля)
        // СНАЧАЛА показываем сохраненные данные
        updateUserData()
        // ЗАТЕМ пытаемся обновить с сервера
        loadUserData()
        
        // Обновляем бейджи чатов
        val messagingViewModel = androidx.lifecycle.ViewModelProvider(requireActivity())[egx.relab_app.ui.messaging.MessagingViewModel::class.java]
        messagingViewModel.loadChatRooms()
    }
    
    private fun setupClickListeners() {
        binding.cardOrders.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_orderListFragment)
        }

        binding.cardProfile.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_profileFragment)
        }
        
        binding.cardCustomers.setOnClickListener {
            findNavController().navigate(R.id.nav_customers)
        }
        
        binding.cardAnalytics.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_analyticsFragment)
        }
        
        binding.cardSettings.setOnClickListener {
            findNavController().navigate(R.id.settingsFragment)
        }
        
        binding.cardTools.setOnClickListener {
            findNavController().navigate(R.id.toolsFragment)
        }

        binding.cardAssistant.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val aiRoom = RetrofitClient.apiService.getOrCreateAiChat()
                    val bundle = Bundle().apply {
                        putInt("roomId", aiRoom.id)
                        putString("roomName", aiRoom.name ?: "ИИ-Помощник")
                    }
                    findNavController().navigate(R.id.chatDetailFragment, bundle)
                } catch (e: Exception) {
                    Toast.makeText(context, "Ошибка открытия чата с ИИ: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }


        binding.cardEmployees.setOnClickListener {
            findNavController().navigate(R.id.action_nav_home_to_employeeListFragment)
        }

        binding.cardChats.setOnClickListener {
            findNavController().navigate(R.id.action_nav_home_to_chatListFragment)
        }

        binding.cardWarehouse.setOnClickListener {
            findNavController().navigate(R.id.action_nav_home_to_consumableListFragment)
        }

    }
    
    private fun updateUserData() {
        val tokenManager = TokenManager(requireContext())
        
        // Показываем ФИО из TokenManager сразу, если есть, иначе username
        val displayName = tokenManager.fullName ?: tokenManager.username
        if (!displayName.isNullOrBlank()) {
            binding.textUserName.text = "$displayName!"
        }
        
        // Загружаем аватар пользователя в карточку профиля из сохраненных данных
        loadProfileAvatar(tokenManager)
    }
    
    private fun loadUserData() {
        val tokenManager = TokenManager(requireContext())
        
        lifecycleScope.launch {
            try {
                val user = RetrofitClient.apiService.getCurrentUser()
                
                // ВАЖНО: Обновляем TokenManager только если сервер вернул непустые значения
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
                
                // Обновляем отображаемое имя (ФИО или username) из TokenManager
                val displayName = tokenManager.fullName ?: tokenManager.username
                binding.textUserName.text = "$displayName!"
                
                // Обновляем аватар в карточке профиля
                loadProfileAvatar(tokenManager)

            } catch (e: Exception) {
                // Не выкидываем на авторизацию при ошибке сети
                // Просто продолжаем работу локально
            }
        }
    }
    
    private fun loadProfileAvatar(tokenManager: TokenManager) {
        val avatarUrl = tokenManager.avatarUrl
        if (!avatarUrl.isNullOrEmpty() && avatarUrl != "null") {
            Glide.with(this)
                .load(avatarUrl)
                .placeholder(R.mipmap.ic_launcher_round)
                .error(R.mipmap.ic_launcher_round)
                .circleCrop()
                .into(binding.cardProfileAvatar)
        } else {
            // Если аватара нет, показываем иконку приложения
            binding.cardProfileAvatar.setImageResource(R.mipmap.ic_launcher_round)
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
