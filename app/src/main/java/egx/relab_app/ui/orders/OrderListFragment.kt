package egx.relab_app.ui.orders

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import egx.relab_app.R
import egx.relab_app.app
import egx.relab_app.databinding.FragmentOrdersBinding
import egx.relab_app.models.Order
import egx.relab_app.network.RetrofitClient
import egx.relab_app.orders.OrderAdapter
import egx.relab_app.sync.SyncManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class OrderListFragment : Fragment() {

    private var _binding: FragmentOrdersBinding? = null
    private val binding get() = _binding!!
    private var ordersJob: kotlinx.coroutines.Job? = null
    private var selectedStatus: String = "Все"
    private var selectedTab: Int = 0
    private lateinit var statusOptions: List<String>
    private val tokenManager by lazy { egx.relab_app.storage.TokenManager(requireContext()) }

    private lateinit var adapter: OrderAdapter
    
    // Получаем Repository из Application
    private val repository by lazy { requireContext().app.orderRepository }
    private val syncManager by lazy { 
        SyncManager(repository, requireContext(), requireContext().app.customerDao)
    }

    private val statusMap = mapOf(
        "new" to "Новый",
        "in_progress" to "В процессе",
        "done" to "Завершён",
        "pending" to "Ожидает"
    )
    private val reverseStatusMap = statusMap.entries.associate { it.value to it.key }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOrdersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Log.d("OrderListFragment", "onViewCreated called")

        setupRecyclerView()
        setupFilterSpinner()
        if (selectedStatus == "Все") {
            observeOrders()
        } else {
            reverseStatusMap[selectedStatus]?.let {
                observeOrdersByStatus(it)
            }
        }

        setupTabs()
        setupFab()
        setupSyncButton()
        checkConnectionAndUpdateIndicator()
        observeOrders()
        setupMenu()
        setupToggleViewMode()
    }

    private fun setupMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.order_list_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_clear_database -> {
                        showClearDatabaseDialog()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }
    
    private fun checkConnectionAndUpdateIndicator() {
        lifecycleScope.launch {
            try {
                if (!isAdded) return@launch
                val activity = activity as? egx.relab_app.MainActivity ?: return@launch
                
                // Проверяем реальное подключение к серверу
                val isConnected = try {
                    val response = RetrofitClient.apiService.getCurrentUser()
                    true
                } catch (e: Exception) {
                    false
                }
                
                activity.updateConnectionIndicator(isConnected)
            } catch (e: Exception) {
                // Игнорируем ошибки
            }
        }
    }
    
    
    /**
     * Показать диалог подтверждения очистки БД
     */
    private fun showClearDatabaseDialog() {
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Очистить базу данных")
            .setMessage("Вы точно хотите очистить БД? Вы уверены, что хотите удалить все локальные данные? Это действие нельзя отменить.")
            .setPositiveButton("Очистить") { _, _ ->
                clearDatabase()
            }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(android.R.id.message)
                ?.setTextColor(Color.BLACK)

            dialog.findViewById<TextView>(android.R.id.title)
                ?.setTextColor(Color.BLACK)

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(Color.BLACK)

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(Color.BLACK)
        }


        dialog.show()
    }
    
    /**
     * Очистить локальную базу данных
     */
    private fun clearDatabase() {
        lifecycleScope.launch {
            try {
                repository.clearAllData()
                val ctx = context
                if (ctx != null && isAdded) {
                    Toast.makeText(ctx, "База данных очищена", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                val ctx = context
                if (ctx != null && isAdded) {
                    Toast.makeText(ctx, "Ошибка при очистке БД: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = OrderAdapter { order ->
            val nav = OrderListFragmentDirections
                .actionOrderListFragmentToOrderDetailFragment(order)
            findNavController().navigate(nav)
        }
        
        // Применяем текущий режим из настроек
        applyViewMode(tokenManager.isOrderGridView)
        
        binding.ordersRecyclerView.adapter = adapter
    }

    private fun setupToggleViewMode() {
        // Устанавливаем начальную иконку
        updateToggleIcon(tokenManager.isOrderGridView)

        binding.btnToggleViewMode.setOnClickListener {
            val newMode = !tokenManager.isOrderGridView
            tokenManager.isOrderGridView = newMode
            updateToggleIcon(newMode)
            applyViewMode(newMode)
        }
    }

    private fun updateToggleIcon(isGrid: Boolean) {
        binding.btnToggleViewMode.setImageResource(
            if (isGrid) R.drawable.ic_view_list else R.drawable.ic_view_grid
        )
    }

    private fun applyViewMode(isGrid: Boolean) {
        adapter.isGridView = isGrid
        binding.ordersRecyclerView.layoutManager = if (isGrid) {
            GridLayoutManager(requireContext(), 2)
        } else {
            androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        }
        // Перерисовываем список
        adapter.notifyDataSetChanged()
    }

    private fun setupFilterSpinner() {
        statusOptions = listOf("Все") + statusMap.values.toList()

        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner_black,
            statusOptions
        )

        binding.statusFilterSpinner.setAdapter(spinnerAdapter)

        spinnerAdapter.filter.filter(null)

        binding.statusFilterSpinner.setText(selectedStatus, false)

        binding.statusFilterSpinner.setOnItemClickListener { _, _, position, _ ->
            selectedStatus = statusOptions[position]

            if (selectedStatus == "Все") {
                observeOrders()
            } else {
                reverseStatusMap[selectedStatus]?.let {
                    observeOrdersByStatus(it)
                }
            }
        }
    }





    private fun setupFab() {
        binding.add.setOnClickListener {
            findNavController().navigate(
                OrderListFragmentDirections.actionOrderListFragmentToOrderFormFragment()
            )
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                selectedTab = tab?.position ?: 0
                // Перезапускаем наблюдение с новым фильтром
                refreshOrdersList()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun refreshOrdersList() {
        if (selectedStatus == "Все") {
            observeOrders()
        } else {
            reverseStatusMap[selectedStatus]?.let {
                observeOrdersByStatus(it)
            }
        }
    }
    
    private fun setupSyncButton() {
        binding.buttonSync.setOnClickListener {
            performManualSync()
        }
    }
    
    
    /**
     * Ручная синхронизация с индикацией статуса
     */
    private fun performManualSync() {
        // Проверяем, что фрагмент еще прикреплен и binding доступен
        if (!isAdded || _binding == null) return
        
        val ctx = context ?: return
        val activity = activity as? egx.relab_app.MainActivity
        
        // Проверяем подключение перед синхронизацией
        lifecycleScope.launch {
            try {
                // Проверяем подключение к серверу
                val isConnected = try {
                    RetrofitClient.apiService.getCurrentUser()
                    true
                } catch (e: Exception) {
                    false
                }
                
                // Обновляем индикатор подключения
                activity?.updateConnectionIndicator(isConnected)
                
                // Если нет подключения, показываем уведомление и не синхронизируем
                if (!isConnected) {
                    if (isAdded && _binding != null) {
                        binding.syncStatusCard.visibility = View.VISIBLE
                        binding.syncStatusText.text = "✗ Нет подключения к серверу"
                        binding.syncStatusText.setTextColor(ctx.getColor(android.R.color.holo_red_dark))
                        
                        // Показываем всплывающее окно
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("Нет подключения")
                            .setMessage("Невозможно синхронизировать данные. Проверьте подключение к интернету и попробуйте снова.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    return@launch
                }
                
                // Если есть подключение, начинаем синхронизацию
                if (isAdded && _binding != null) {
                    binding.syncStatusCard.visibility = View.VISIBLE
                    binding.syncStatusText.text = "🔄 Синхронизация..."
                    binding.syncStatusText.setTextColor(ctx.getColor(android.R.color.holo_blue_dark))
                }
                
                val result = syncManager.fullSync()
                
                // Проверяем, что фрагмент еще прикреплен перед обновлением UI
                if (!isAdded || _binding == null) return@launch
                
                if (result.success) {
                    binding.syncStatusText.text = "✓ Синхронизировано: ${result.syncedCount} заказов"
                    binding.syncStatusText.setTextColor(ctx.getColor(android.R.color.holo_green_dark))
                    // Обновляем индикатор подключения
                    activity?.updateConnectionIndicator(true)
                    if (isAdded) {
                        Toast.makeText(ctx, 
                            "Синхронизация завершена: ${result.syncedCount} заказов", 
                            Toast.LENGTH_SHORT).show()
                    }
                } else {
                    binding.syncStatusText.text = "✗ Ошибка: ${result.error}"
                    binding.syncStatusText.setTextColor(ctx.getColor(android.R.color.holo_red_dark))
                    // Обновляем индикатор подключения
                    activity?.updateConnectionIndicator(false)
                    if (isAdded) {
                        Toast.makeText(ctx, 
                            "Ошибка синхронизации: ${result.error}", 
                            Toast.LENGTH_LONG).show()
                    }
                }
                
                // Обновляем статус через 3 секунды
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(3000)
                    // Проверяем, что фрагмент еще прикреплен
                    if (isAdded && _binding != null) {
                        updateSyncStatus()
                    }
                }
            } catch (e: Exception) {
                // Проверяем, что фрагмент еще прикреплен перед обновлением UI
                if (!isAdded || _binding == null) return@launch
                
                val ctx = context ?: return@launch
                binding.syncStatusText.text = "✗ Ошибка: ${e.message}"
                binding.syncStatusText.setTextColor(ctx.getColor(android.R.color.holo_red_dark))
                if (isAdded) {
                    Toast.makeText(ctx, 
                        "Ошибка: ${e.message}", 
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Обновить индикатор статуса синхронизации

     */
    private fun updateSyncStatus() {
        lifecycleScope.launch {
            val pendingCount = repository.getPendingCount()
            
            // Проверяем, что фрагмент еще прикреплен перед обновлением UI
            if (!isAdded || _binding == null) return@launch
            
            val ctx = context ?: return@launch
            if (pendingCount > 0) {
                binding.syncStatusCard.visibility = View.VISIBLE
                binding.syncStatusText.text = "⚠ Ожидает синхронизации: $pendingCount заказов"
                binding.syncStatusText.setTextColor(ctx.getColor(android.R.color.holo_orange_dark))
            } else {
                binding.syncStatusCard.visibility = View.GONE
            }
        }
    }

    /**
     * Наблюдаем за всеми заказами из локальной БД (реактивно)
     * - Показывает заказы ТОЛЬКО из локальной БД
     * - Flow автоматически обновит UI при изменении данных в БД
     * - Не делает запросов к серверу - работает полностью автономно
     * - Данные с сервера попадают в БД только через SyncManager
     */
    private fun observeOrders() {
        ordersJob?.cancel()
        ordersJob = lifecycleScope.launch {
            repository.getAllOrders().collect { orders ->
                showOrders(filterOrdersByTab(orders))
            }
        }
    }

    private fun filterOrdersByTab(orders: List<Order>): List<Order> {
        val currentUsername = tokenManager.username ?: ""
        return when (selectedTab) {
            1 -> orders.filter { it.createdByUsername == currentUsername }
            2 -> orders.filter { 
                it.isPublic && (it.assignedToUsername.isNullOrEmpty() || it.assignedToUsername == "null") 
            }
            3 -> orders.filter { 
                it.assignedToUsername == currentUsername || 
                it.collaborators.any { col -> col.username == currentUsername } 
            }
            else -> orders // "Все"
        }
    }
    /**
     * Наблюдаем за заказами по статусу
     */
    private fun observeOrdersByStatus(status: String) {
        ordersJob?.cancel()
        ordersJob = lifecycleScope.launch {
            repository.getOrdersByStatus(status).collect { orders ->
                showOrders(filterOrdersByTab(orders))
            }
        }
    }
    // Автоматическая синхронизация отключена
    // Синхронизация происходит только при нажатии кнопки синхронизации

    private fun showOrders(orders: List<Order>) {
        val displayOrders = orders.map { order ->
            order.copy(
                status = statusMap[order.status] ?: order.status,
                orderType = order.orderType
            )
        }
        adapter.updateList(displayOrders)
    }
    private var autoSyncJob: kotlinx.coroutines.Job? = null

    override fun onResume() {
        super.onResume()

        if (_binding == null) return

        val spinnerAdapter =
            binding.statusFilterSpinner.adapter as? ArrayAdapter<*>

        spinnerAdapter?.filter?.filter(null)


        binding.statusFilterSpinner.setText(selectedStatus, false)
        
        if (tokenManager.autoSyncEnabled) {
            startAutoSync()
        }
    }
    
    private fun startAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = lifecycleScope.launch {
            while (isActive) {
                // Ждём заданный интервал, минимум 1 минута
                val intervalMinutes = kotlin.math.max(1, tokenManager.syncIntervalMinutes)
                delay(intervalMinutes.toLong() * 60 * 1000)
                if (isAdded && tokenManager.autoSyncEnabled) {
                    performManualSync()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        autoSyncJob?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        autoSyncJob?.cancel()
        _binding = null
    }
}
