package egx.relab_app.ui.orders

import android.app.DatePickerDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import egx.relab_app.R
import egx.relab_app.app
import egx.relab_app.data.DeviceDatabase
import egx.relab_app.databinding.FragmentOrderCreateBinding
import egx.relab_app.models.Order
import egx.relab_app.network.RetrofitClient
import egx.relab_app.storage.TokenManager
import egx.relab_app.sync.SyncManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Calendar

class OrderFormFragment : Fragment() {

    private var _binding: FragmentOrderCreateBinding? = null
    private val binding get() = _binding!!

    private var selectedPhotoUri: Uri? = null
    private var selectedDate: String? = null
    private val args: OrderFormFragmentArgs by navArgs()
    private val isEditMode get() = args.order != null

    private var allOrders: List<Order> = emptyList()
    
    // Получаем Repository и SyncManager из Application
    private val repository by lazy { requireContext().app.orderRepository }
    private val syncManager by lazy { SyncManager(repository, requireContext()) }
    private val tokenManager by lazy { TokenManager(requireContext()) }
    
    // Локальный ID заказа (для режима редактирования)
    private var orderLocalId: Long? = null

    private val statusMap = mapOf(
        "Новый" to "new", "В процессе" to "in_progress",
        "Завершён" to "done", "Ожидает" to "pending"
    )
    private val orderTypeMap = mapOf(
        "Ремонт" to "repair", "Диагностика" to "diagnosis"
    )

    private val reverseStatusMap = statusMap.entries.associate { it.value to it.key }
    private val reverseOrderTypeMap = orderTypeMap.entries.associate { it.value to it.key }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOrderCreateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val types = listOf("Ремонт", "Диагностика")
        val statuses = listOf("Новый", "В процессе", "Завершён", "Ожидает")

        binding.orderTypeSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, types)
        binding.statusSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, statuses)
        
        // Инициализируем базу данных устройств
        DeviceDatabase.initialize(requireContext())
        
        // Загружаем заказы для автодополнения
        loadOrdersForAutocomplete()
        binding.buttonSelectDate.setOnClickListener {
            val calendar = Calendar.getInstance()
            val datePicker = DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    selectedDate = "%04d-%02d-%02d".format(year, month + 1, day)
                    binding.textViewSelectedDate.text = selectedDate
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            datePicker.show()
        }



        val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            selectedPhotoUri = uri
            binding.textPhotoChosen.text = if (uri != null) "Фото выбрано" else "Фото не выбрано"
        }
        binding.buttonChoosePhoto.setOnClickListener { pickImage.launch("image/*") }

        // В режиме редактирования загружаем localId заказа
        if (isEditMode) {
            loadOrderLocalId()
            // Если данные еще не загружены, заполняем поля сразу
            if (allOrders.isEmpty()) {
                populateEditFields()
            }
        }

        binding.buttonSave.setOnClickListener { saveOrUpdate() }
        
        // Добавляем валидацию при вводе для обязательных полей
        setupFieldValidation()
    }
    
    /**
     * Настройка валидации полей при вводе
     */
    private fun setupFieldValidation() {
        // Валидация для имени клиента
        binding.editTextCustomerName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateField(binding.editTextCustomerName, binding.inputLayoutCustomerName, "Имя клиента обязательно")
            } else {
                clearFieldError(binding.inputLayoutCustomerName)
            }
        }
        
        binding.editTextCustomerName.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s?.isNotBlank() == true) {
                    clearFieldError(binding.inputLayoutCustomerName)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        
        // Валидация для названия устройства
        binding.editTextDeviceName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateField(binding.editTextDeviceName, binding.inputLayoutDeviceName, "Название устройства обязательно")
            } else {
                clearFieldError(binding.inputLayoutDeviceName)
            }
        }
        
        binding.editTextDeviceName.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s?.isNotBlank() == true) {
                    clearFieldError(binding.inputLayoutDeviceName)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }
    
    /**
     * Валидация поля
     */
    private fun validateField(
        field: android.widget.EditText,
        layout: com.google.android.material.textfield.TextInputLayout,
        errorMessage: String
    ): Boolean {
        val isValid = field.text?.isNotBlank() == true
        if (!isValid) {
            setFieldError(layout, errorMessage)
        } else {
            clearFieldError(layout)
        }
        return isValid
    }
    
    /**
     * Установить ошибку для поля (красная подсветка)
     */
    private fun setFieldError(
        layout: com.google.android.material.textfield.TextInputLayout,
        errorMessage: String
    ) {
        val ctx = context ?: return
        layout.error = errorMessage
        layout.boxStrokeColor = ctx.getColor(R.color.error_red)
        layout.setErrorTextColor(android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.error_red)))
    }
    
    /**
     * Убрать ошибку с поля
     */
    private fun clearFieldError(layout: com.google.android.material.textfield.TextInputLayout) {
        val ctx = context ?: return
        layout.error = null
        layout.boxStrokeColor = ctx.getColor(R.color.gray_400)
    }

    /**
     * Загрузить локальный ID заказа для редактирования
     */
    private fun loadOrderLocalId() {
        if (!isEditMode) return
        
        lifecycleScope.launch {
            try {
                val order = args.order!!
                // Сначала пытаемся найти по serverId
                order.id?.let { serverId ->
                    val orderEntity = repository.getOrderEntityByServerId(serverId)
                    orderLocalId = orderEntity?.localId
                }
                
                // Если не нашли по serverId, возможно заказ еще не синхронизирован
                // В этом случае ищем по другим признакам (например, по orderNumber)
                if (orderLocalId == null && order.orderNumber != null) {
                    // Можно добавить поиск по orderNumber, но пока оставим так
                    // В реальности нужно хранить localId в навигационных аргументах
                }
            } catch (e: Exception) {
                // Игнорируем ошибку, попробуем найти при сохранении
            }
        }
    }
    
    private fun loadOrdersForAutocomplete() {
        RetrofitClient.apiService.getOrders().enqueue(object : Callback<List<Order>> {
            override fun onResponse(
                call: Call<List<Order>>,
                response: Response<List<Order>>
            ) {
                if (response.isSuccessful) {
                    allOrders = response.body().orEmpty()
                    setupAutocompleteAdapters()
                    // Если режим редактирования, устанавливаем значения после загрузки данных
                    if (isEditMode) {
                        populateEditFields()
                    }
                } else {
                    // Если не удалось загрузить, но режим редактирования - все равно заполняем поля
                    if (isEditMode) {
                        populateEditFields()
                    }
                }
            }

            override fun onFailure(call: Call<List<Order>>, t: Throwable) {
                // В случае ошибки просто не будет автодополнения
                // Но если режим редактирования - все равно заполняем поля
                if (isEditMode && isAdded && _binding != null) {
                    populateEditFields()
                }
            }
        })
    }

    private fun setupAutocompleteAdapters() {
        // Извлекаем уникальные значения из заказов для полей, не связанных с устройствами
        val customers = allOrders.mapNotNull { it.customer }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        val contactInfos = allOrders.mapNotNull { it.contactInfo }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        val telegrams = allOrders.mapNotNull { it.telegram }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        val kits = allOrders.mapNotNull { it.kit }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        // Настраиваем адаптеры для каждого поля
        // Для полей с базой данных устройств используем динамическую фильтрацию
        // Это позволяет эффективно фильтровать большие списки устройств по мере ввода
        setupAutocompleteWithDeviceDB(binding.editTextManufacturer, AutocompleteFieldType.MANUFACTURER)
        setupAutocompleteWithDeviceDB(binding.editTextDeviceType, AutocompleteFieldType.DEVICE_TYPE)
        setupAutocompleteWithDeviceDB(binding.editTextDeviceName, AutocompleteFieldType.DEVICE_NAME)
        setupAutocompleteWithDeviceDB(binding.editTextModel, AutocompleteFieldType.MODEL)
        
        // Для остальных полей используем статический список из существующих заказов
        setupAutocompleteAdapter(binding.editTextCustomerName, customers)
        setupAutocompleteAdapter(binding.editTextContactInfo, contactInfos)
        setupAutocompleteAdapter(binding.editTextTelegram, telegrams)
        setupAutocompleteAdapter(binding.editTextKit, kits)
    }

    private fun setupAutocompleteAdapter(
        autoCompleteTextView: AutoCompleteTextView,
        suggestions: List<String>
    ) {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            suggestions
        )
        autoCompleteTextView.setAdapter(adapter)
    }
    
    private fun setupAutocompleteWithDeviceDB(
        autoCompleteTextView: AutoCompleteTextView,
        fieldType: AutocompleteFieldType
    ) {
        // Создаем адаптер, который будет динамически фильтровать результаты
        val adapter = object : ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            mutableListOf()
        ) {
            override fun getFilter(): android.widget.Filter {
                return object : android.widget.Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults {
                        val results = FilterResults()
                        val query = constraint?.toString()?.lowercase() ?: ""
                        
                        val suggestions = when (fieldType) {
                            AutocompleteFieldType.MANUFACTURER -> {
                                val fromDB = DeviceDatabase.searchManufacturers(query)
                                val fromOrders = allOrders.mapNotNull { it.manufacturer }
                                    .filter { it.isNotBlank() && it.lowercase().contains(query) }
                                    .distinct()
                                (fromDB + fromOrders).distinct().sorted()
                            }
                            AutocompleteFieldType.DEVICE_TYPE -> {
                                val fromDB = DeviceDatabase.searchDeviceTypes(query)
                                val fromOrders = allOrders.mapNotNull { it.deviceType }
                                    .filter { it.isNotBlank() && it.lowercase().contains(query) }
                                    .distinct()
                                (fromDB + fromOrders).distinct().sorted()
                            }
                            AutocompleteFieldType.DEVICE_NAME -> {
                                val fromDB = DeviceDatabase.searchDeviceNames(query)
                                val fromOrders = allOrders.mapNotNull { it.deviceName }
                                    .filter { it.isNotBlank() && it.lowercase().contains(query) }
                                    .distinct()
                                (fromDB + fromOrders).distinct().sorted()
                            }
                            AutocompleteFieldType.MODEL -> {
                                val fromDB = DeviceDatabase.searchModels(query)
                                val fromOrders = allOrders.mapNotNull { it.model }
                                    .filter { it.isNotBlank() && it.lowercase().contains(query) }
                                    .distinct()
                                (fromDB + fromOrders).distinct().sorted()
                            }
                            else -> emptyList()
                        }
                        
                        results.values = suggestions
                        results.count = suggestions.size
                        return results
                    }
                    
                    @Suppress("UNCHECKED_CAST")
                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        clear()
                        if (results != null && results.count > 0) {
                            addAll(results.values as List<String>)
                        }
                        notifyDataSetChanged()
                    }
                }
            }
        }
        
        autoCompleteTextView.setAdapter(adapter)
    }
    
    private enum class AutocompleteFieldType {
        MANUFACTURER,
        DEVICE_TYPE,
        DEVICE_NAME,
        MODEL,
        OTHER
    }

    private fun populateEditFields() {
        val o = args.order!!
        binding.editTextOrderNumber.setText(o.orderNumber)
        binding.editTextCustomerName.setText(o.customer)
        binding.editTextContactInfo.setText(o.contactInfo)
        binding.editTextExtraInfo.setText(o.extraInfo)
        binding.editTextTelegram.setText(o.telegram)
        binding.editTextDeviceName.setText(o.deviceName)
        binding.editTextDeviceType.setText(o.deviceType)
        binding.editTextManufacturer.setText(o.manufacturer)
        binding.editTextModel.setText(o.model)
        binding.editTextKit.setText(o.kit)
        binding.editTextDescription.setText(o.description)
        selectedDate = o.date
        binding.textViewSelectedDate.text = o.date
        reverseOrderTypeMap[o.orderType]?.let {
            binding.orderTypeSpinner.setSelection((binding.orderTypeSpinner.adapter as ArrayAdapter<String>).getPosition(it))
        }
        reverseStatusMap[o.status]?.let {
            binding.statusSpinner.setSelection((binding.statusSpinner.adapter as ArrayAdapter<String>).getPosition(it))
        }
    }

    private fun saveOrUpdate() {
        // Валидируем обязательные поля с подсветкой красным
        val customerNameValid = validateField(
            binding.editTextCustomerName,
            binding.inputLayoutCustomerName,
            "Имя клиента обязательно"
        )
        val deviceNameValid = validateField(
            binding.editTextDeviceName,
            binding.inputLayoutDeviceName,
            "Название устройства обязательно"
        )
        
        if (!customerNameValid || !deviceNameValid) {
            val ctx = context
            if (ctx != null && isAdded) {
                Toast.makeText(ctx, "Заполните обязательные поля", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val filledOrder = if (isEditMode) buildUpdatedOrder() else buildNewOrder()

        // Сохраняем локально и синхронизируем в фоне
        lifecycleScope.launch {
            try {
                if (isEditMode) {
                    // Обновляем существующий заказ
                    val localId = orderLocalId ?: run {
                        // Если нет локального ID, ищем по serverId
                        val foundId = filledOrder.id?.let { serverId ->
                            val orderEntity = repository.getOrderEntityByServerId(serverId)
                            orderEntity?.localId
                        }
                        foundId ?: throw Exception("Не найден локальный ID заказа. Попробуйте синхронизировать данные.")
                    }
                    
                    // Обновляем локально
                    repository.updateOrder(localId, filledOrder)
                    
                    // Пытаемся синхронизировать с сервером
                    if (filledOrder.id != null) {
                        // Заказ уже есть на сервере - обновляем
                        val context = context ?: return@launch
                        RetrofitClient.updateOrder(context, filledOrder, selectedPhotoUri) { success, code, errorBody, updatedOrder ->
                            if (!isAdded) return@updateOrder // Проверяем, что фрагмент еще прикреплен
                            lifecycleScope.launch {
                                val ctx = context ?: return@launch
                                if (success && updatedOrder != null) {
                                    // Обновляем заказ с данными с сервера (включая статус синхронизации)
                                    repository.saveOrderFromServer(updatedOrder)
                                    if (isAdded) {
                                        Toast.makeText(ctx, "Заказ обновлён и синхронизирован", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    if (isAdded) {
                                        Toast.makeText(ctx, "Заказ обновлён локально (ошибка синхронизации)", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                    
                    val ctx = context
                    if (ctx != null && isAdded) {
                        Toast.makeText(ctx, "Заказ сохранён локально", Toast.LENGTH_SHORT).show()
                        findNavController().popBackStack()
                    }
                } else {
                    // Создаем новый заказ локально
                    val localId = repository.createOrder(filledOrder)
                    
                    // Пытаемся сразу синхронизировать с сервером
                    val context = context ?: return@launch
                    RetrofitClient.createOrder(context, filledOrder, selectedPhotoUri) { success, code, errorBody, createdOrder ->
                        if (!isAdded) return@createOrder // Проверяем, что фрагмент еще прикреплен
                        lifecycleScope.launch {
                            val ctx = context ?: return@launch
                            if (success && createdOrder != null && createdOrder.id != null) {
                                // Обновляем локальный заказ с данными с сервера
                                // Передаем localId, чтобы гарантированно обновить правильный заказ
                                repository.saveOrderFromServer(createdOrder, localId)
                                
                                if (isAdded) {
                                    Toast.makeText(ctx, "Заказ сохранён и синхронизирован", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                // Ошибка синхронизации, но заказ сохранен локально
                                if (isAdded) {
                                    Toast.makeText(ctx, "Заказ сохранён локально (ошибка синхронизации)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    
                    val ctx = context
                    if (ctx != null && isAdded) {
                        Toast.makeText(ctx, "Заказ сохранён локально", Toast.LENGTH_SHORT).show()
                        findNavController().popBackStack()
                    }
                }
            } catch (e: Exception) {
                val ctx = context
                if (ctx != null && isAdded) {
                    Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildNewOrder(): Order {
        return Order(
            id = null,
            orderNumber = binding.editTextOrderNumber.text.toString(),
            customer = binding.editTextCustomerName.text.toString(),
            contactInfo = binding.editTextContactInfo.text.toString(),
            extraInfo = binding.editTextExtraInfo.text.toString(),
            telegram = binding.editTextTelegram.text.toString(),
            deviceName = binding.editTextDeviceName.text.toString(),
            deviceType = binding.editTextDeviceType.text.toString(),
            manufacturer = binding.editTextManufacturer.text.toString(),
            model = binding.editTextModel.text.toString(),
            kit = binding.editTextKit.text.toString(),
            description = binding.editTextDescription.text.toString(),
            date = binding.textViewSelectedDate.text.toString(),
            status = statusMap[binding.statusSpinner.selectedItem.toString()] ?: "new",
            orderType = orderTypeMap[binding.orderTypeSpinner.selectedItem.toString()] ?: "repair",
            createdByUsername = tokenManager.username,  // Сохраняем username текущего пользователя
            createdByFullName = tokenManager.fullName,  // Сохраняем ФИО текущего пользователя
            createdByAvatar = tokenManager.avatarUrl   // Сохраняем аватар текущего пользователя
        )
    }

    private fun buildUpdatedOrder(): Order {
        val originalOrder = args.order!!
        // При обновлении сохраняем данные создателя из оригинального заказа, если они есть
        // Иначе используем данные текущего пользователя
        return buildNewOrder().copy(
            id = originalOrder.id,
            createdByUsername = originalOrder.createdByUsername ?: tokenManager.username,
            createdByFullName = originalOrder.createdByFullName ?: tokenManager.fullName,
            createdByAvatar = originalOrder.createdByAvatar ?: tokenManager.avatarUrl
        )
    }

    private fun handleSaveResult(success: Boolean, code: Int, errorBody: String?, order: Order) {
        if (success) {
            Toast.makeText(requireContext(), "Заказ сохранён", Toast.LENGTH_SHORT).show()

            if (isEditMode) {
                findNavController().previousBackStackEntry
                    ?.savedStateHandle
                    ?.set("updatedOrder", order)
            }

            findNavController().popBackStack()
        } else {
            Toast.makeText(requireContext(), "Ошибка: $code\n$errorBody", Toast.LENGTH_LONG).show()
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
