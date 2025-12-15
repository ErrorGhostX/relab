package egx.relab_app.ui.orders

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import egx.relab_app.R
import egx.relab_app.app
import egx.relab_app.databinding.FragmentOrderDetailBinding
import egx.relab_app.models.Order
import egx.relab_app.network.RetrofitClient
import egx.relab_app.repository.OrderRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import java.io.File
import java.io.FileOutputStream

class OrderDetailFragment : Fragment() {

    private var _binding: FragmentOrderDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var currentOrder: Order
    
    // Получаем Repository и ServiceDao из Application
    private val repository by lazy { requireContext().app.orderRepository }
    private val serviceDao by lazy { requireContext().app.database.serviceDao() }
    
    // Маппинг статусов и типов заказов
    private val statusMap = mapOf(
        "new" to "Новый",
        "in_progress" to "В процессе",
        "done" to "Завершён",
        "pending" to "Ожидает"
    )
    private val orderTypeMap = mapOf(
        "repair" to "Ремонт",
        "diagnosis" to "Диагностика"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOrderDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        currentOrder = OrderDetailFragmentArgs.fromBundle(requireArguments()).order
        
        // ВАЖНО: Приоритет на локальность - загружаем СРАЗУ из локальной БД
        // Показываем данные из аргументов как временные, пока загружаем из БД
        bindOrderToUI(currentOrder)
        
        // Загружаем заказ из локальной БД (приоритет на локальность)
        lifecycleScope.launch {
            try {
                loadFromLocalDatabase()
                
                // ВАЖНО: После загрузки из локальной БД пытаемся обновить с сервера в ФОНОВОМ режиме
                // Это не блокирует отображение - пользователь видит локальные данные сразу
                if (currentOrder.id != null) {
                    // Если есть serverId, пытаемся обновить с сервера в фоне
                    loadOrderDetailsFromServer()
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderDetail", "Ошибка загрузки из локальной БД", e)
                // Продолжаем показывать данные из аргументов
            }
        }

        findNavController().currentBackStackEntry
            ?.savedStateHandle
            ?.getLiveData<Order>("updatedOrder")
            ?.observe(viewLifecycleOwner) {
                currentOrder = it
                bindOrderToUI(it)
            }

        binding.buttonEdit.setOnClickListener {
            val action = OrderDetailFragmentDirections.actionOrderDetailFragmentToOrderFormFragment(currentOrder)
            findNavController().navigate(action)
        }

        binding.buttonDelete.setOnClickListener { showDeleteConfirmationDialog() }
        binding.buttonAddService.setOnClickListener { showAddServiceDialog() }
        binding.buttonPrint.setOnClickListener { generateAndShareReport() }
    }

    private fun bindOrderToUI(order: Order) = with(binding) {
        // ВАЖНО: Обрабатываем случай, когда order.id = null (локально созданный заказ)
        // Показываем serverId если есть (положительный), иначе показываем информацию о локальном заказе
        orderId.text = if (order.id != null && order.id!! > 0) {
            "ID: ${order.id}"
        } else {
            "ID: Локальный (ожидает синхронизации)"
        }
        
        // Отображаем ФИО создателя если есть, иначе username
        val creatorName = order.createdByFullName ?: order.createdByUsername
        createdBy.text      = "Создал: ${creatorName ?: "-"}"
        
        // Загружаем аватар создателя если есть
        android.util.Log.d("OrderDetail", "Created by avatar: ${order.createdByAvatar}")
        if (!order.createdByAvatar.isNullOrEmpty() && order.createdByAvatar != "null") {
            Glide.with(this@OrderDetailFragment)
                .load(order.createdByAvatar)
                .placeholder(R.mipmap.ic_launcher_round)
                .error(R.mipmap.ic_launcher_round)
                .circleCrop()
                .into(createdByAvatar)
        } else {
            // Если аватара нет, показываем иконку приложения
            createdByAvatar.setImageResource(R.mipmap.ic_launcher_round)
        }
        orderNumber.text    = "Номер заказа: ${order.orderNumber}"
        customer.text       = "Клиент: ${order.customer}"
        contactInfo.text    = "Контакты: ${order.contactInfo}"
        extraInfo.text      = "Доп. инфо: ${order.extraInfo}"
        telegram.text       = "Мэссэджер: ${order.telegram}"
        deviceName.text     = "Устройство: ${order.deviceName}"
        deviceType.text     = "Тип: ${order.deviceType}"
        manufacturer.text   = "Производитель: ${order.manufacturer}"
        model.text          = "Модель: ${order.model}"
        kit.text            = "Комплектация: ${order.kit}"
        description.text    = "Описание: ${order.description}"
        date.text           = "Дата: ${order.date}"
        orderType.text      = "Тип: ${orderTypeMap[order.orderType] ?: order.orderType}"
        status.text         = "Статус: ${statusMap[order.status] ?: order.status}"

        // Фото заказа - обрабатываем 404 ошибки
        if (!order.photo.isNullOrEmpty() && order.photo != "null") {
            Glide.with(this@OrderDetailFragment)
                .load(order.photo)
                .placeholder(R.drawable.placeholder_image)
                .error(R.drawable.placeholder_image)
                .fallback(R.drawable.placeholder_image)
                .into(orderImage)
        } else {
            orderImage.setImageResource(R.drawable.placeholder_image)
        }

        displayServices(order)
    }

    private fun displayServices(order: Order) {
        val container = binding.servicesContainer
        container.removeAllViews()

        if (order.services.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "Услуг нет"
                setTextAppearance(R.style.DetailTextStyleBlack)
                setPadding(0, 8, 0, 8)
            })
            return
        }
        order.services.forEach { svc ->
            val row = layoutInflater.inflate(R.layout.item_service, container, false)
            row.findViewById<TextView>(R.id.tvServiceDesc).text =
                "${svc.description}: ${"%.2f".format(svc.price)} ₽"

            row.findViewById<ImageButton>(R.id.btnDeleteService).setOnClickListener {
                // ВАЖНО: Удаление работает локально в первую очередь
                // Находим услугу по serverId или localId и удаляем из локальной БД
                if (order.id != null && svc.id > 0) {
                    deleteService(order.id!!, svc.id)
                } else {
                    // Если нет serverId, ищем по другим признакам
                    // Пока просто показываем ошибку
                    showToast("Услуга не может быть удалена (нет ID)")
                }
            }

            container.addView(row)
        }

        val total = order.services.sumOf { it.price }
        container.addView(TextView(requireContext()).apply {
            text = "Итого: ${"%.2f".format(total)} ₽"
            setTextAppearance(R.style.DetailTextStyleBlack)
            setPadding(0, 10, 0, 4)
        })
    }

    private fun showAddServiceDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_service, null)
        val etDesc = dialogView.findViewById<TextInputEditText>(R.id.etDescription)
        val etPrice = dialogView.findViewById<TextInputEditText>(R.id.etPrice)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Добавить услугу")
            .setView(dialogView)
            .setPositiveButton("Добавить") { dialog, _ ->
                val desc = etDesc.text.toString().trim()
                val priceText = etPrice.text.toString().trim()

                if (desc.isEmpty() || priceText.isEmpty()) {
                    showToast("Заполните оба поля")
                } else {
                    priceText.toDoubleOrNull()?.let {
                        addServiceToOrder(desc, it)
                    } ?: showToast("Некорректная цена")
                }

                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Добавить услугу к заказу
     * 
     * ВАЖНО: Приоритет на локальность
     * 1. Сохраняет услугу СРАЗУ в локальную БД
     * 2. Обновляет UI СРАЗУ
     * 3. Синхронизирует с сервером в ФОНОВОМ режиме
     * 4. Не ждет ответа от сервера - приложение работает автономно
     */
    private fun addServiceToOrder(description: String, price: Double) {
        lifecycleScope.launch {
            try {
                // Находим заказ в локальной БД
                val orderEntity = if (currentOrder.id != null) {
                    repository.getOrderEntityByServerId(currentOrder.id!!)
                } else if (currentOrder.orderNumber != null) {
                    val allEntities = repository.getAllOrderEntities()
                    allEntities.firstOrNull { 
                        it.orderNumber == currentOrder.orderNumber && !it.isDeleted 
                    }
                } else {
                    null
                }
                
                if (orderEntity != null) {
                    // ВАЖНО: Добавляем услугу СРАЗУ в локальную БД
                    repository.addServiceToOrder(
                        orderLocalId = orderEntity.localId,
                        orderServerId = currentOrder.id,
                        description = description,
                        price = price
                    )
                    
                    // Обновляем UI СРАЗУ из локальной БД
                    loadFromLocalDatabase()
                    
                    showToast("Услуга добавлена")
                    
                    // ВАЖНО: Синхронизация происходит в ФОНОВОМ режиме через SyncManager
                    // Не блокируем UI и не ждем ответа
                    if (currentOrder.id != null) {
                        // Пытаемся синхронизировать с сервером в фоне
                        try {
                            RetrofitClient.addService(currentOrder.id!!.toString(), description, price) { success, _, error ->
                                if (success) {
                                    android.util.Log.d("OrderDetail", "Услуга синхронизирована с сервером")
                                    // Обновляем заказ с сервера в фоне
                                    lifecycleScope.launch {
                                        try {
                                            val updatedOrder = RetrofitClient.apiService.getOrderById(currentOrder.id!!.toString())
                                            repository.saveOrderFromServer(updatedOrder, orderEntity.localId)
                                            loadFromLocalDatabase()
                                        } catch (e: Exception) {
                                            // Ошибка - это нормально, продолжаем работать с локальными данными
                                        }
                                    }
                                } else {
                                    android.util.Log.d("OrderDetail", "Ошибка синхронизации услуги: $error")
                                    // Ошибка - это нормально, продолжаем работать с локальными данными
                                }
                            }
                        } catch (e: Exception) {
                            // Ошибка - это нормально, продолжаем работать с локальными данными
                            android.util.Log.d("OrderDetail", "Не удалось синхронизировать услугу (офлайн режим): ${e.message}")
                        }
                    }
                } else {
                    showToast("Ошибка: заказ не найден в локальной БД")
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderDetail", "Ошибка при добавлении услуги", e)
                showToast("Ошибка: ${e.message}")
            }
        }
    }

    /**
     * Удалить услугу из заказа
     * 
     * ВАЖНО: Приоритет на локальность
     * 1. Удаляет услугу СРАЗУ из локальной БД
     * 2. Обновляет UI СРАЗУ
     * 3. Синхронизирует удаление с сервером в ФОНОВОМ режиме
     * 4. Не ждет ответа от сервера - приложение работает автономно
     */
    private fun deleteService(orderId: Int, serviceId: Int) {
        lifecycleScope.launch {
            try {
                // Находим заказ в локальной БД
                val orderEntity = if (orderId > 0) {
                    repository.getOrderEntityByServerId(orderId)
                } else if (currentOrder.orderNumber != null) {
                    // Если нет serverId, ищем по orderNumber
                    val allEntities = repository.getAllOrderEntities()
                    allEntities.firstOrNull { 
                        it.orderNumber == currentOrder.orderNumber && !it.isDeleted 
                    }
                } else {
                    null
                }
                
                if (orderEntity != null) {
                    // Находим услугу в локальной БД
                    val serviceEntity = if (serviceId > 0) {
                        // Ищем по serverId
                        serviceDao.getServiceByServerId(serviceId)
                    } else {
                        // Если нет serverId, ищем последнюю услугу заказа (предполагаем, что удаляем последнюю)
                        val servicesFlow = serviceDao.getServicesByOrderLocalId(orderEntity.localId)
                        val allServiceEntities = servicesFlow.first()
                        allServiceEntities.lastOrNull()
                    }
                    
                    if (serviceEntity != null) {
                        // ВАЖНО: Удаляем услугу СРАЗУ из локальной БД
                        repository.deleteService(serviceEntity.localId, orderEntity.localId)
                        
                        // Обновляем UI СРАЗУ из локальной БД
                        loadFromLocalDatabase()
                        
                        showToast("Услуга удалена")
                        
                        // ВАЖНО: Синхронизация удаления происходит в ФОНОВОМ режиме
                        // Не блокируем UI и не ждем ответа
                        if (orderId > 0 && serviceId > 0) {
                            // Пытаемся удалить на сервере в фоне
                            try {
                                RetrofitClient.apiService.deleteService(orderId, serviceId)
                                    .enqueue(object : retrofit2.Callback<Void> {
                                        override fun onResponse(
                                            call: retrofit2.Call<Void>,
                                            response: retrofit2.Response<Void>
                                        ) {
                                            if (response.isSuccessful) {
                                                android.util.Log.d("OrderDetail", "Услуга удалена на сервере")
                                            } else {
                                                android.util.Log.d("OrderDetail", "Ошибка удаления услуги на сервере")
                                                // Ошибка - это нормально, продолжаем работать с локальными данными
                                            }
                                        }
                                        
                                        override fun onFailure(call: retrofit2.Call<Void>, t: Throwable) {
                                            android.util.Log.d("OrderDetail", "Не удалось удалить услугу на сервере (офлайн режим): ${t.message}")
                                            // Ошибка - это нормально, продолжаем работать с локальными данными
                                        }
                                    })
                            } catch (e: Exception) {
                                // Ошибка - это нормально, продолжаем работать с локальными данными
                                android.util.Log.d("OrderDetail", "Не удалось синхронизировать удаление услуги (офлайн режим): ${e.message}")
                            }
                        }
                    } else {
                        showToast("Ошибка: услуга не найдена в локальной БД")
                    }
                } else {
                    showToast("Ошибка: заказ не найден в локальной БД")
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderDetail", "Ошибка при удалении услуги", e)
                showToast("Ошибка: ${e.message}")
            }
        }
    }


    /**
     * Загрузить обновления с сервера в ФОНОВОМ режиме
     * 
     * ВАЖНО: Это НЕ блокирует отображение - пользователь уже видит локальные данные
     * Используется только для обновления данных в фоне
     */
    private fun loadOrderDetailsFromServer() {
        lifecycleScope.launch {
            try {
                if (currentOrder.id != null && isAdded) {
                    // Пытаемся загрузить с сервера в фоне
                    val updatedOrder = withContext(Dispatchers.IO) {
                        RetrofitClient.apiService.getOrderById(currentOrder.id!!.toString())
                    }
                    
                    // Сохраняем обновленный заказ в локальную БД
                    // ВАЖНО: saveOrderFromServer не перезапишет локальные изменения (PENDING статус)
                    repository.saveOrderFromServer(updatedOrder)
                    
                    // Обновляем UI только если фрагмент еще прикреплен
                    if (isAdded) {
                        // Загружаем обновленные данные из локальной БД
                        loadFromLocalDatabase()
                    }
                }
            } catch (e: Exception) {
                // Ошибка загрузки с сервера - это нормально, продолжаем работать с локальными данными
                android.util.Log.d("OrderDetail", "Не удалось обновить с сервера (офлайн режим): ${e.message}")
                // Не показываем ошибку пользователю - приложение работает автономно
            }
        }
    }
    
    /**
     * Загрузить заказ из локальной БД
     * 
     * ВАЖНО: Приоритет на локальность - это основной метод загрузки данных
     * Поддерживает поиск как по serverId, так и по orderNumber (для несинхронизированных заказов)
     * 
     * Логика поиска:
     * 1. Если есть serverId - ищем по serverId
     * 2. Если нет serverId, но есть orderNumber - ищем по orderNumber
     * 3. Загружаем услуги из локальной БД
     * 4. Обновляем UI с данными из локальной БД
     */
    private suspend fun loadFromLocalDatabase() {
        if (!isAdded) return
        try {
            var orderEntity: egx.relab_app.database.entity.OrderEntity? = null
            var localOrder: Order? = null
            
            if (currentOrder.id != null) {
                // Ищем по serverId
                orderEntity = repository.getOrderEntityByServerId(currentOrder.id!!)
                localOrder = repository.getOrderByServerId(currentOrder.id!!)
            } else if (currentOrder.orderNumber != null) {
                // Если нет serverId, ищем по orderNumber
                // Получаем все заказы и ищем по orderNumber
                val allOrders = repository.getAllOrders().first()
                localOrder = allOrders.firstOrNull { it.orderNumber == currentOrder.orderNumber }
                if (localOrder != null && localOrder.id != null) {
                    orderEntity = repository.getOrderEntityByServerId(localOrder.id!!)
                } else {
                    // Если не нашли по serverId, ищем по orderNumber в Entity
                    // Нужно получить все Entity и найти по orderNumber
                    val allEntities = repository.getAllOrderEntities()
                    orderEntity = allEntities.firstOrNull { 
                        it.orderNumber == currentOrder.orderNumber && !it.isDeleted 
                    }
                    if (orderEntity != null) {
                        localOrder = orderEntity.toOrder()
                    }
                }
            }
            
            if (localOrder != null && isAdded) {
                // Загружаем услуги для заказа
                if (orderEntity != null) {
                    val servicesFlow = repository.getServicesForOrder(orderEntity.localId)
                    // Получаем первое значение из Flow
                    val servicesList = try {
                        servicesFlow.first()
                    } catch (e: Exception) {
                        emptyList()
                    }
                    val orderWithServices = localOrder.copy(services = servicesList)
                    if (isAdded) {
                        currentOrder = orderWithServices
                        bindOrderToUI(orderWithServices)
                    }
                } else {
                    if (isAdded) {
                        currentOrder = localOrder
                        bindOrderToUI(localOrder)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OrderDetail", "Ошибка загрузки из локальной БД", e)
        }
    }

    private fun generateAndShareReport() {
        lifecycleScope.launch {
            try {
                val pdfBytes = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getOrderReport(currentOrder.id.toString()).execute()
                        .body()?.byteStream()?.readBytes() ?: throw Exception("Пустой ответ")
                }

                val file = File(requireContext().cacheDir, "Order_${currentOrder.id}.pdf")
                FileOutputStream(file).use { it.write(pdfBytes) }

                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(intent, "Открыть отчёт"))
            } catch (e: ActivityNotFoundException) {
                showToast("Нет приложения для открытия PDF")
            } catch (e: Exception) {
                showToast("Ошибка генерации PDF: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Показать диалог подтверждения удаления заказа
     */
    private fun showDeleteConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Удалить заказ?")
            .setMessage("Вы уверены, что хотите удалить этот заказ? Это действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ ->
                deleteOrder()
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    /**
     * Удалить заказ
     * 
     * ВАЖНО: Приоритет на локальность
     * 1. Удаляет заказ СРАЗУ в локальной БД (мягкое удаление)
     * 2. Закрывает экран СРАЗУ
     * 3. Синхронизация удаления происходит в ФОНОВОМ режиме через SyncManager
     * 4. Не ждет ответа от сервера - приложение работает автономно
     */
    private fun deleteOrder() {
        lifecycleScope.launch {
            try {
                // Находим заказ в локальной БД
                val orderEntity = if (currentOrder.id != null) {
                    repository.getOrderEntityByServerId(currentOrder.id!!)
                } else if (currentOrder.orderNumber != null) {
                    // Если нет serverId, ищем по orderNumber
                    val allEntities = repository.getAllOrderEntities()
                    allEntities.firstOrNull { 
                        it.orderNumber == currentOrder.orderNumber && !it.isDeleted 
                    }
                } else {
                    null
                }
                
                if (orderEntity != null) {
                    // ВАЖНО: Удаляем СРАЗУ в локальной БД (мягкое удаление)
                    repository.deleteOrder(orderEntity.localId)
                    android.util.Log.d("OrderDetail", "Заказ удален локально. localId: ${orderEntity.localId}")
                    
                    // Закрываем экран СРАЗУ - не ждем сервера
                    showToast("Заказ удалён")
                    findNavController().popBackStack()
                    
                    // ВАЖНО: Синхронизация удаления происходит в ФОНОВОМ режиме через SyncManager
                    // Не блокируем UI и не ждем ответа
                    val syncManager = egx.relab_app.sync.SyncManager(repository, requireContext())
                    lifecycleScope.launch {
                        syncManager.pushChanges() // Запускаем в фоне
                    }
                } else {
                    showToast("Ошибка: заказ не найден в локальной БД")
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderDetail", "Ошибка при удалении заказа", e)
                showToast("Ошибка: ${e.message}")
            }
        }
    }

    private fun showToast(message: String) {
        if (isAdded && context != null) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
