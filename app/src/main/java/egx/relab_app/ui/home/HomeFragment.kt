package egx.relab_app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.core.os.bundleOf
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
        
        // ЗАТЕМ запускаем полную синхронизацию (она обновит ранг и права в фоне)
        lifecycleScope.launch {
            try {
                val app = requireContext().applicationContext as egx.relab_app.RelabApplication
                val syncManager = egx.relab_app.sync.SyncManager(
                    app.orderRepository, 
                    requireContext(),
                    app.customerDao,
                    app.consumableDao
                )
                syncManager.fullSync()
                // После синхронизации еще раз обновляем UI на случай изменения ранга
                updateUserData()
            } catch (e: Exception) {
                android.util.Log.e("HomeFragment", "Initial sync failed", e)
            }
        }
        
        // ЗАТЕМ загружаем данные с сервера в фоне (старая логика)
        loadUserData()

        // Слушаем результат из сканера
        findNavController().currentBackStackEntry?.savedStateHandle?.getLiveData<String>("scannedQr")
            ?.observe(viewLifecycleOwner) { uri ->
                if (uri != null) {
                    findNavController().currentBackStackEntry?.savedStateHandle?.remove<String>("scannedQr")
                    handleDeepLink(uri)
                }
            }

        val messagingViewModel = androidx.lifecycle.ViewModelProvider(requireActivity())[egx.relab_app.ui.messaging.MessagingViewModel::class.java]
        messagingViewModel.totalUnreadCount.observe(viewLifecycleOwner) { count ->
            _binding?.let { b ->
                if (count > 0) {
                    b.tvChatsBadge.text = if (count > 99) "99+" else count.toString()
                    b.tvChatsBadge.visibility = View.VISIBLE
                } else {
                    b.tvChatsBadge.visibility = View.GONE
                }
            }
        }
        // Загружаем список чатов, чтобы получить актуальные бейджи
        messagingViewModel.loadChatRooms()

        // Настраиваем индикатор соединения
        setupConnectionIndicator()
    }

    private fun setupConnectionIndicator() {
        val tokenManager = egx.relab_app.storage.TokenManager(requireContext())
        
        egx.relab_app.network.GlobalConnectionManager.status.observe(viewLifecycleOwner) { status ->
            _binding?.let { b ->
                if (tokenManager.isGuestMode) {
                    b.viewConnectionStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#9E9E9E")) // Серый
                    b.tvConnectionLabel.text = "Локально"
                    return@observe
                }

                when (status) {
                    egx.relab_app.network.GlobalConnectionManager.ConnectionStatus.CONNECTED -> {
                        b.viewConnectionStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50"))
                        b.tvConnectionLabel.text = "Бэк в сети"
                    }
                    egx.relab_app.network.GlobalConnectionManager.ConnectionStatus.CONNECTING -> {
                        b.viewConnectionStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFC107"))
                        b.tvConnectionLabel.text = "Подключение..."
                    }
                    egx.relab_app.network.GlobalConnectionManager.ConnectionStatus.DISCONNECTED -> {
                        b.viewConnectionStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F44336"))
                        b.tvConnectionLabel.text = "Оффлайн"
                    }
                    else -> {}
                }
            }
        }
        
        // Сразу устанавливаем статус для гостевого режима, так как observe может не сработать если статус не изменился
        if (tokenManager.isGuestMode) {
            _binding?.let { b ->
                b.viewConnectionStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#9E9E9E")) // Серый
                b.tvConnectionLabel.text = "Локально"
            }
        }
    }

    private fun handleDeepLink(uriString: String) {
        try {
            val uri = android.net.Uri.parse(uriString)
            if (uri.scheme == "relab" && uri.host == "order") {
                val lastSegment = uri.lastPathSegment
                if (uri.pathSegments.contains("local")) {
                    val localId = lastSegment?.toLongOrNull()
                    if (localId != null) {
                        openOrderDetailsByLocalId(localId)
                    }
                } else {
                    val orderId = lastSegment?.toIntOrNull()
                    if (orderId != null) {
                        openOrderDetailsById(orderId)
                    }
                }
            } else {
                Toast.makeText(requireContext(), "Неверный формат QR-кода", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOrderDetailsById(orderId: Int) {
        lifecycleScope.launch {
            try {
                val context = context ?: return@launch
                val app = context.applicationContext as egx.relab_app.RelabApplication
                val repository = app.orderRepository
                
                var order = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repository.getOrderByServerId(orderId)
                }
                
                if (order == null) {
                    // Если нет локально, пробуем загрузить с сервера
                    _binding?.let {
                        Toast.makeText(requireContext(), "Загрузка данных заказа #$orderId...", Toast.LENGTH_SHORT).show()
                    }
                    order = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            RetrofitClient.apiService.getOrderById(orderId.toString())
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                
                if (order != null && isAdded) {
                    // Переходим к деталям заказа, передавая полный объект Order
                    val bundle = Bundle().apply { putParcelable("order", order) }
                    findNavController().navigate(egx.relab_app.R.id.orderDetailFragment, bundle, egx.relab_app.utils.NavAnimations.slideNavOptions())
                } else if (isAdded) {
                    Toast.makeText(requireContext(), "Заказ #$orderId не найден", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(context ?: return@launch, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openOrderDetailsByLocalId(localId: Long) {
        lifecycleScope.launch {
            try {
                val context = context ?: return@launch
                val app = context.applicationContext as egx.relab_app.RelabApplication
                val repository = app.orderRepository
                
                val order = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repository.getOrderByLocalId(localId)
                }
                
                if (order != null && isAdded) {
                    val bundle = Bundle().apply { putParcelable("order", order) }
                    findNavController().navigate(egx.relab_app.R.id.orderDetailFragment, bundle, egx.relab_app.utils.NavAnimations.slideNavOptions())
                } else if (isAdded) {
                    Toast.makeText(requireContext(), "Заказ локально не найден", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(context ?: return@launch, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
        val tokenManager = TokenManager(requireContext())
        val isGuest = tokenManager.isGuestMode
        
        binding.cardOrders.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_orderListFragment)
        }

        binding.cardProfile.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_profileFragment)
        }
        
        binding.cardCustomers.setOnClickListener {
            findNavController().navigate(R.id.nav_customers, null, egx.relab_app.utils.NavAnimations.slideNavOptions())
        }
        
        binding.cardSettings.setOnClickListener {
            findNavController().navigate(R.id.settingsFragment, null, egx.relab_app.utils.NavAnimations.slideNavOptions())
        }
        
        binding.cardTools.setOnClickListener {
            findNavController().navigate(R.id.toolsFragment, null, egx.relab_app.utils.NavAnimations.slideNavOptions())
        }

        binding.cardScanQr.setOnClickListener {
            // Запускаем настоящий сканер через камеру
            findNavController().navigate(R.id.qrScannerFragment, null, egx.relab_app.utils.NavAnimations.scaleNavOptions())
        }

        binding.cardWarehouse.setOnClickListener {
            findNavController().navigate(R.id.action_nav_home_to_consumableListFragment)
        }

        // --- Функции, требующие авторизацию ---
        if (isGuest) {
            val guestMessage = "Необходимо войти в аккаунт"
            
            // Делаем карточки серыми и полупрозрачными
            listOf(binding.cardChats, binding.cardAssistant, binding.cardAnalytics, binding.cardEmployees).forEach { card ->
                card.alpha = 0.45f
            }
            
            binding.cardAnalytics.setOnClickListener {
                Toast.makeText(requireContext(), guestMessage, Toast.LENGTH_SHORT).show()
            }
            binding.cardAssistant.setOnClickListener {
                Toast.makeText(requireContext(), guestMessage, Toast.LENGTH_SHORT).show()
            }
            binding.cardEmployees.setOnClickListener {
                Toast.makeText(requireContext(), guestMessage, Toast.LENGTH_SHORT).show()
            }
            binding.cardChats.setOnClickListener {
                Toast.makeText(requireContext(), guestMessage, Toast.LENGTH_SHORT).show()
            }
        } else {
            binding.cardAnalytics.setOnClickListener {
                findNavController().navigate(R.id.action_homeFragment_to_analyticsFragment)
            }

            binding.cardAssistant.setOnClickListener {
                val messagingViewModel = androidx.lifecycle.ViewModelProvider(requireActivity())[egx.relab_app.ui.messaging.MessagingViewModel::class.java]
                messagingViewModel.getOrCreateAiChat { aiRoom ->
                    if (aiRoom != null && isAdded) {
                        val bundle = Bundle().apply {
                            putInt("roomId", aiRoom.id)
                            putString("roomName", aiRoom.name ?: "ИИ-Помощник")
                        }
                        findNavController().navigate(R.id.chatDetailFragment, bundle, egx.relab_app.utils.NavAnimations.slideNavOptions())
                    }
                }
            }

            binding.cardEmployees.setOnClickListener {
                findNavController().navigate(R.id.action_nav_home_to_employeeListFragment)
            }

            binding.cardChats.setOnClickListener {
                findNavController().navigate(R.id.action_nav_home_to_chatListFragment)
            }
        }
    }
    
    private fun updateUserData() {
        val binding = _binding ?: return
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
                val binding = _binding ?: return@launch
                
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
        val binding = _binding ?: return
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
