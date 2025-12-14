package egx.relab_app.ui.orders

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import egx.relab_app.R
import egx.relab_app.app
import egx.relab_app.databinding.FragmentOrdersBinding
import egx.relab_app.models.Order
import egx.relab_app.network.RetrofitClient
import egx.relab_app.orders.OrderAdapter
import egx.relab_app.sync.SyncManager
import kotlinx.coroutines.launch

class OrderListFragment : Fragment() {

    private var _binding: FragmentOrdersBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: OrderAdapter
    
    // Получаем Repository из Application
    private val repository by lazy { requireContext().app.orderRepository }
    private val syncManager by lazy { 
        SyncManager(repository, requireContext())
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
        setHasOptionsMenu(true)
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
        setupFab()
        setupSyncButton()
        
        // Загружаем заказы из локальной БД (реактивно через Flow)
        observeOrders()
        
        // Синхронизируем с сервером в фоне при загрузке
        syncOrders()
        
        // Показываем количество несинхронизированных заказов
        updateSyncStatus()
        
        // Проверяем подключение и обновляем индикатор
        checkConnectionAndUpdateIndicator()
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
    
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.order_list_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sync -> {
                performManualSync()
                true
            }
            R.id.action_clear_database -> {
                showClearDatabaseDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    /**
     * Показать диалог подтверждения очистки БД
     */
    private fun showClearDatabaseDialog() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("Очистить базу данных")
            .setMessage("Вы уверены, что хотите удалить все локальные данные? Это действие нельзя отменить.")
            .setPositiveButton("Очистить") { _, _ ->
                clearDatabase()
            }
            .setNegativeButton("Отмена", null)
            .show()
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
        binding.ordersRecyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.ordersRecyclerView.adapter = adapter
    }

    private fun setupFilterSpinner() {
        Log.d("OrderListFragment", "setupFilterSpinner called")

        val options = listOf("Все") + statusMap.values
        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            options
        )
        binding.statusFilterSpinner.adapter = spinnerAdapter

        binding.statusFilterSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val selected = options[position]
                    Log.d("OrderListFragment", "Spinner selected: $selected")
                    
                    // Подписываемся на заказы с фильтром по статусу
                    if (selected == "Все") {
                        observeOrders()
                    } else {
                        val code = reverseStatusMap[selected]
                        observeOrdersByStatus(code ?: "")
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun setupFab() {
        binding.add.setOnClickListener {
            findNavController().navigate(
                OrderListFragmentDirections.actionOrderListFragmentToOrderFormFragment()
            )
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
        val ctx = context ?: return
        binding.syncStatusCard.visibility = View.VISIBLE
        binding.syncStatusText.text = "🔄 Синхронизация..."
        binding.syncStatusText.setTextColor(ctx.getColor(android.R.color.holo_blue_dark))
        
        lifecycleScope.launch {
            try {
                val result = syncManager.fullSync()
                
                val ctx = context ?: return@launch
                val activity = activity as? egx.relab_app.MainActivity
                
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
                    updateSyncStatus()
                }
            } catch (e: Exception) {
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
     * Flow автоматически обновит UI при изменении данных
     */
    private fun observeOrders() {
        lifecycleScope.launch {
            repository.getAllOrders().collect { orders ->
                showOrders(orders)
            }
        }
    }
    
    /**
     * Наблюдаем за заказами по статусу
     */
    private fun observeOrdersByStatus(status: String) {
        lifecycleScope.launch {
            repository.getOrdersByStatus(status).collect { orders ->
                showOrders(orders)
            }
        }
    }
    
    /**
     * Синхронизация с сервером в фоне
     * ВАЖНО: Не блокирует работу при отсутствии подключения
     */
    private fun syncOrders() {
        lifecycleScope.launch {
            try {
                // Пытаемся синхронизировать, но не блокируем работу при ошибке
                val result = syncManager.fullSync()
                if (result.success) {
                    Log.d("OrderListFragment", "Синхронизация успешна: ${result.syncedCount} заказов")
                    // Обновляем статус синхронизации
                    updateSyncStatus()
                } else {
                    Log.e("OrderListFragment", "Ошибка синхронизации: ${result.error}")
                    // Показываем данные из локальной БД даже при ошибке синхронизации
                    // Это нормально - приложение работает офлайн
                }
            } catch (e: Exception) {
                Log.e("OrderListFragment", "Ошибка при синхронизации (офлайн режим)", e)
                // Продолжаем работать с локальными данными
                // Это нормальное поведение при отсутствии сети
            }
        }
    }

    private fun showOrders(orders: List<Order>) {
        // Преобразуем статус из кода в русское значение
        val displayOrders = orders.map { order ->
            order.copy(
                status = statusMap[order.status] ?: order.status,
                orderType = order.orderType
            )
        }
        adapter.updateList(displayOrders)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
