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
import java.io.File
import java.io.FileOutputStream

class OrderDetailFragment : Fragment() {

    private var _binding: FragmentOrderDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var currentOrder: Order
    
    // Получаем Repository из Application
    private val repository by lazy { requireContext().app.orderRepository }
    
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
        // Сначала показываем данные из аргументов
        bindOrderToUI(currentOrder)
        
        // Затем пытаемся загрузить полные данные с сервера, если есть ID
        // Если нет подключения, используем данные из аргументов
        if (currentOrder.id != null) {
            loadOrderDetails()
        } else {
            // Если нет serverId, пытаемся загрузить из локальной БД
            lifecycleScope.launch {
                try {
                    // Ищем по другим признакам (например, orderNumber)
                    // Пока используем данные из аргументов
                } catch (e: Exception) {
                    android.util.Log.e("OrderDetail", "Ошибка загрузки из БД", e)
                }
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
        orderId.text        = "ID: ${order.id}"
        
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

        Glide.with(this@OrderDetailFragment)
            .load(order.photo)
            .placeholder(R.drawable.placeholder_image)
            .into(orderImage)

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
                deleteService(order.id!!, svc.id)
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

    private fun addServiceToOrder(description: String, price: Double) {
        RetrofitClient.addService(currentOrder.id.toString(), description, price) { success, _, error ->  // Преобразуем id в String
            if (success) {
                showToast("Услуга добавлена")
                loadOrderDetails()
            } else {
                showToast("Ошибка: $error")
                loadOrderDetails()
            }
            loadOrderDetails()
        }
    }

    private fun deleteService(orderId: Int, serviceId: Int) {
        RetrofitClient.apiService.deleteService(orderId, serviceId)
            .enqueue(object : retrofit2.Callback<Void> {
                override fun onResponse(call: retrofit2.Call<Void>, response: retrofit2.Response<Void>) {
                    if (response.isSuccessful) {
                        showToast("Услуга удалена")
                        loadOrderDetails()
                    } else {
                        showToast("Ошибка удаления")
                        loadOrderDetails()
                    }
                }

                override fun onFailure(call: retrofit2.Call<Void>, t: Throwable) {
                    showToast("Сеть недоступна")
                    loadOrderDetails()
                }
            })
    }


    private fun loadOrderDetails() {
        lifecycleScope.launch {
            try {
                // Пытаемся загрузить с сервера, если есть ID
                if (currentOrder.id != null) {
                    try {
                        val updatedOrder = withContext(Dispatchers.IO) {
                            RetrofitClient.apiService.getOrderById(currentOrder.id!!.toString())
                        }
                        currentOrder = updatedOrder
                        bindOrderToUI(updatedOrder)
                        
                        // Сохраняем обновленный заказ в локальную БД (включая услуги)
                        repository.saveOrderFromServer(updatedOrder)
                    } catch (e: Exception) {
                        // Если нет подключения, пытаемся загрузить из локальной БД
                        android.util.Log.d("OrderDetail", "Ошибка загрузки с сервера, загружаем из локальной БД: ${e.message}")
                        loadFromLocalDatabase()
                    }
                } else {
                    // Если нет serverId, загружаем из локальной БД
                    loadFromLocalDatabase()
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderDetail", "Ошибка загрузки заказа", e)
                showToast("Ошибка загрузки заказа")
            }
        }
    }
    
    /**
     * Загрузить заказ из локальной БД
     */
    private suspend fun loadFromLocalDatabase() {
        if (!isAdded) return
        try {
            if (currentOrder.id != null) {
                // Ищем по serverId
                val localOrder = repository.getOrderByServerId(currentOrder.id!!)
                if (localOrder != null && isAdded) {
                    // Загружаем услуги для заказа
                    val orderEntity = repository.getOrderEntityByServerId(currentOrder.id!!)
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
     */
    private fun deleteOrder() {
        lifecycleScope.launch {
            try {
                if (currentOrder.id != null) {
                    // Заказ есть на сервере - удаляем и там, и локально
                    val orderEntity = repository.getOrderEntityByServerId(currentOrder.id!!)
                    
                    // Удаляем на сервере
                    RetrofitClient.apiService.deleteOrder(currentOrder.id!!.toString())
                        .enqueue(object : retrofit2.Callback<Void> {
                            override fun onResponse(
                                call: retrofit2.Call<Void>,
                                response: retrofit2.Response<Void>
                            ) {
                                lifecycleScope.launch {
                                    // Удаляем локально
                                    orderEntity?.let {
                                        repository.deleteOrder(it.localId)
                                    }
                                    
                                    if (response.isSuccessful) {
                                        showToast("Заказ удалён")
                                        findNavController().popBackStack()
                                    } else {
                                        showToast("Заказ удалён локально (ошибка на сервере)")
                                        findNavController().popBackStack()
                                    }
                                }
                            }
                            
                            override fun onFailure(call: retrofit2.Call<Void>, t: Throwable) {
                                lifecycleScope.launch {
                                    // Удаляем локально даже при ошибке сети
                                    orderEntity?.let {
                                        repository.deleteOrder(it.localId)
                                    }
                                    showToast("Заказ удалён локально (ошибка сети)")
                                    findNavController().popBackStack()
                                }
                            }
                        })
                } else {
                    // Заказ еще не синхронизирован - удаляем только локально
                    // Ищем по другим признакам (например, по orderNumber)
                    // Пока просто показываем сообщение
                    showToast("Заказ ещё не синхронизирован. Удаление только локально.")
                    findNavController().popBackStack()
                }
            } catch (e: Exception) {
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
