package egx.relab_app.ui.orders

import android.app.DatePickerDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Calendar

class OrderFormFragment : Fragment() {

    private var _binding: FragmentOrderCreateBinding? = null
    private val binding get() = _binding!!

    private var selectedPhotoUris: MutableList<Uri> = mutableListOf()
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
        super.onViewCreated(view, savedInstanceState)

        val types = listOf("Ремонт", "Диагностика")
        val statuses = listOf("Новый", "В процессе", "Завершён", "Ожидает")

        val typeAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner_black,
            types
        )

        val statusAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner_black,
            statuses
        )

        binding.orderTypeSpinner.setAdapter(typeAdapter)
        binding.statusSpinner.setAdapter(statusAdapter)

        // ВАЖНО: В режиме редактирования не устанавливаем значения по умолчанию
        // Они будут установлены в populateEditFields()
        if (!isEditMode) {
            // значения по умолчанию только для нового заказа (чтобы hint не прыгал)
            binding.orderTypeSpinner.setText(types.first(), false)
            binding.statusSpinner.setText(statuses.first(), false)
            binding.textViewSelectedDate.text = "Выберите дату"
        }

        binding.orderTypeSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedType = types[position]
            Log.d("Order", "Тип заказа: $selectedType")
        }

        binding.statusSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedStatus = statuses[position]
            Log.d("Order", "Статус заказа: $selectedStatus")
        }
        // Инициализируем базу данных устройств
        DeviceDatabase.initialize(requireContext())
        
        // Загружаем заказы для автодополнения
        loadOrdersForAutocomplete()
        binding.buttonSelectDate.setOnClickListener {
            val calendar = Calendar.getInstance()
            val datePicker = DatePickerDialog(
                requireContext(),
                android.R.style.Theme_Material_Light_Dialog,
                { _, year, month, day ->
                    selectedDate = "%04d-%02d-%02d".format(year, month + 1, day)
                    binding.textViewSelectedDate.text = selectedDate
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            // Устанавливаем черный цвет текста для DatePickerDialog
            datePicker.setOnShowListener {
                try {
                    val blackColor = resources.getColor(R.color.gray_900, null)
                    
                    // Получаем корневой view диалога
                    val dialogView = datePicker.window?.decorView
                    val datePickerView = datePicker.datePicker
                    
                    // Устанавливаем черный цвет текста для всех найденных TextView рекурсивно
                    fun setTextColorRecursive(view: View?) {
                        if (view == null) return
                        
                        when (view) {
                            is android.widget.TextView -> {
                                view.setTextColor(blackColor)
                                // Также устанавливаем цвет hint, если есть
                                if (view.hint != null) {
                                    view.setHintTextColor(blackColor)
                                }
                            }
                            is android.view.ViewGroup -> {
                                for (i in 0 until view.childCount) {
                                    setTextColorRecursive(view.getChildAt(i))
                                }
                            }
                        }
                    }
                    
                    // Применяем ко всему диалогу и DatePicker
                    dialogView?.let { setTextColorRecursive(it) }
                    setTextColorRecursive(datePickerView)
                    
                    // Дополнительно: устанавливаем цвет для кнопок диалога
                    datePicker.getButton(DatePickerDialog.BUTTON_POSITIVE)?.setTextColor(blackColor)
                    datePicker.getButton(DatePickerDialog.BUTTON_NEGATIVE)?.setTextColor(blackColor)
                    
                    // Используем рефлексию для установки цвета в NumberPicker (используется внутри DatePicker)
                    try {
                        val numberPickerFields = datePickerView.javaClass.declaredFields
                        for (field in numberPickerFields) {
                            if (field.type.name.contains("NumberPicker")) {
                                field.isAccessible = true
                                val numberPicker = field.get(datePickerView) as? android.widget.NumberPicker
                                numberPicker?.let { np ->
                                    // Устанавливаем цвет для всех TextView в NumberPicker
                                    for (i in 0 until np.childCount) {
                                        val child = np.getChildAt(i)
                                        setTextColorRecursive(child)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.d("OrderForm", "Не удалось установить цвет через рефлексию: ${e.message}")
                    }
                } catch (e: Exception) {
                    // Игнорируем ошибки, если не удалось установить цвет
                    android.util.Log.d("OrderForm", "Не удалось установить цвет текста для DatePicker: ${e.message}")
                }
            }
            datePicker.show()
        }



        val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            selectedPhotoUris.clear()
            selectedPhotoUris.addAll(uris)
            binding.textPhotosChosen.text = if (uris.isNotEmpty()) "Выбрано фото: ${uris.size}" else "Фото не выбрано"
        }
        binding.buttonChoosePhotos.setOnClickListener { pickImages.launch("image/*") }

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
        // ВАЖНО: Загружаем заказы из локальной БД, а не с сервера
        lifecycleScope.launch {
            try {
                // Загружаем из локальной БД
                repository.getAllOrders().collect { orders ->
                    allOrders = orders
                    setupAutocompleteAdapters()
                    // Если режим редактирования, устанавливаем значения после загрузки данных
                    if (isEditMode) {
                        populateEditFields()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderForm", "Ошибка загрузки заказов для автодополнения", e)
                // В случае ошибки просто не будет автодополнения
                // Но если режим редактирования - все равно заполняем поля
                if (isEditMode && isAdded && _binding != null) {
                    populateEditFields()
                }
            }
        }
        
        // Также пытаемся загрузить с сервера в фоне для обновления данных
        RetrofitClient.apiService.getOrders().enqueue(object : Callback<List<Order>> {
            override fun onResponse(
                call: Call<List<Order>>,
                response: Response<List<Order>>
            ) {
                if (response.isSuccessful) {
                    // Обновляем список заказов с сервера
                    val serverOrders = response.body().orEmpty()
                    allOrders = (allOrders + serverOrders).distinctBy { it.id ?: it.orderNumber }
                    setupAutocompleteAdapters()
                }
            }

            override fun onFailure(call: Call<List<Order>>, t: Throwable) {
                // Игнорируем ошибку - используем локальные данные
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
        //setupAutocompleteWithDeviceDB(binding.editTextModel, AutocompleteFieldType.MODEL)
        
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
            R.layout.item_spinner_black,
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
            R.layout.item_spinner_black,
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
        //binding.editTextModel.setText(o.model)
        binding.editTextKit.setText(o.kit)
        binding.editTextDescription.setText(o.description)
        selectedDate = o.date
        // ВАЖНО: Если дата есть, показываем её, иначе показываем "Выберите дату"
        binding.textViewSelectedDate.text = o.date ?: "Выберите дату"
        reverseOrderTypeMap[o.orderType]?.let { orderTypeText ->
            // ВАЖНО: Для AutoCompleteTextView используем только setText, не setSelection
            // setSelection может вызвать IndexOutOfBoundsException если текст пустой
            try {
                binding.orderTypeSpinner.setText(orderTypeText, false)
            } catch (e: Exception) {
                android.util.Log.e("OrderForm", "Ошибка установки orderType: ${e.message}")
                // Пробуем установить текст без фильтрации
                binding.orderTypeSpinner.setText(orderTypeText)
            }
        }
        reverseStatusMap[o.status]?.let { statusText ->
            // ВАЖНО: Для AutoCompleteTextView используем только setText, не setSelection
            // setSelection может вызвать IndexOutOfBoundsException если текст пустой
            try {
                binding.statusSpinner.setText(statusText, false)
            } catch (e: Exception) {
                android.util.Log.e("OrderForm", "Ошибка установки status: ${e.message}")
                // Пробуем установить текст без фильтрации
                binding.statusSpinner.setText(statusText)
            }
        }
    }

    /**
     * Сохранить или обновить заказ
     * 
     * ВАЖНО: Приоритет на локальность
     * 1. Сохраняет заказ в локальную БД СРАЗУ
     * 2. Закрывает форму СРАЗУ после локального сохранения
     * 3. Синхронизация с сервером происходит в ФОНОВОМ режиме через SyncManager
     * 4. Не ждет ответа от сервера - приложение работает автономно
     */
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

        // ВАЖНО: Сохраняем СРАЗУ в локальную БД (приоритет на локальность)
        lifecycleScope.launch {
            try {
                if (isEditMode) {
                    // ========== РЕЖИМ РЕДАКТИРОВАНИЯ ==========
                    // Находим локальный ID заказа
                    val localId = orderLocalId ?: run {
                        // Если нет локального ID, ищем по serverId или orderNumber
                        val foundId = filledOrder.id?.let { serverId ->
                            repository.getOrderEntityByServerId(serverId)?.localId
                        } ?: run {
                            // Если нет serverId, ищем по orderNumber
                            if (filledOrder.orderNumber != null) {
                                val allEntities = repository.getAllOrderEntities()
                                allEntities.firstOrNull {
                                    it.orderNumber == filledOrder.orderNumber && !it.isDeleted
                                }?.localId
                            } else {
                                null
                            }
                        }
                        foundId ?: throw Exception("Не найден локальный ID заказа")
                    }

                    // Сохраняем фото локально перед обновлением заказа
                    val orderWithPhoto = if (selectedPhotoUris.isNotEmpty()) {
                        // Пользователь выбрал новые фото — пересохраняем их
                        val photoPath = savePhotosLocally(selectedPhotoUris)
                        filledOrder.copy(photo = photoPath)
                    } else {
                        // Фото не менялись — сохраняем уже существующие пути к фото,
                        // чтобы не потерять локальные несколько фотографий
                        val existingEntity = repository.getOrderByServerId(filledOrder.id ?: 0)
                        val existingPhoto = existingEntity?.photo ?: args.order?.photo
                        filledOrder.copy(photo = existingPhoto)
                    }

                    // ВАЖНО: Обновляем СРАЗУ в локальной БД (статус PENDING)
                    repository.updateOrder(localId, orderWithPhoto)
                    android.util.Log.d("OrderForm", "Заказ обновлен локально. localId: $localId")
                    
                    // Закрываем форму СРАЗУ - не ждем сервера
                    val ctx = context
                    if (ctx != null && isAdded) {
                        Toast.makeText(ctx, "Заказ обновлён", Toast.LENGTH_SHORT).show()
                        findNavController().popBackStack()
                    }
                    
                    // ВАЖНО: Автоматическая синхронизация отключена
                    // Синхронизация происходит только при нажатии кнопки синхронизации
                    
                } else {
                    // ========== РЕЖИМ СОЗДАНИЯ ==========
                    // Сохраняем фото локально перед созданием заказа
                    val orderWithPhoto = if (selectedPhotoUris.isNotEmpty()) {
                        val photoPath = savePhotosLocally(selectedPhotoUris)
                        filledOrder.copy(photo = photoPath)
                    } else {
                        filledOrder
                    }
                    
                    // ВАЖНО: Создаем СРАЗУ в локальной БД (статус PENDING, временный отрицательный serverId)
                    val localId = repository.createOrder(orderWithPhoto)
                    android.util.Log.d("OrderForm", "Заказ создан локально. localId: $localId")
                    
                    // Закрываем форму СРАЗУ - не ждем сервера
                    val ctx = context
                    if (ctx != null && isAdded) {
                        Toast.makeText(ctx, "Заказ создан", Toast.LENGTH_SHORT).show()
                        findNavController().popBackStack()
                    }
                    
                    // ВАЖНО: Автоматическая синхронизация отключена
                    // Синхронизация происходит только при нажатии кнопки синхронизации
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderForm", "Ошибка при сохранении заказа", e)
                val ctx = context
                if (ctx != null && isAdded) {
                    Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildNewOrder(): Order {
        // Форматируем дату в формат YYYY-MM-DD
        // Если дата не выбрана, возвращаем null вместо текущей даты
        val dateText = binding.textViewSelectedDate.text.toString()
        val dateString = if (dateText.isNotBlank() && dateText != "Выберите дату") {
            formatDateForServer(dateText)
        } else {
            null
        }

        // Получаем выбранные значения из MaterialAutoCompleteTextView
        val statusSelected = binding.statusSpinner.text.toString()
        val typeSelected = binding.orderTypeSpinner.text.toString()

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
            //model = binding.editTextModel.text.toString(),
            kit = binding.editTextKit.text.toString(),
            description = binding.editTextDescription.text.toString(),
            date = dateString,
            status = statusMap[statusSelected] ?: "new",
            orderType = orderTypeMap[typeSelected] ?: "repair",
            createdByUsername = tokenManager.username,
            createdByFullName = tokenManager.fullName,
            createdByAvatar = tokenManager.avatarUrl
        )
    }

    
    /**
     * Форматирует дату в формат YYYY-MM-DD для отправки на сервер
     */
    private fun formatDateForServer(dateString: String?): String {
        if (dateString.isNullOrBlank() || dateString == "Выберите дату") {
            val calendar = Calendar.getInstance()
            return "%04d-%02d-%02d".format(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
            )
        }
        
        // Проверяем, что дата уже в формате YYYY-MM-DD
        val datePattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        if (datePattern.matches(dateString)) {
            return dateString
        }
        
        // Пытаемся распарсить дату в других форматах
        try {
            val formats = listOf(
                java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()),
                java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()),
                java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault()),
                java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
            )
            
            for (format in formats) {
                try {
                    val date = format.parse(dateString)
                    if (date != null) {
                        val calendar = Calendar.getInstance()
                        calendar.time = date
                        return "%04d-%02d-%02d".format(
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH) + 1,
                            calendar.get(Calendar.DAY_OF_MONTH)
                        )
                    }
                } catch (e: Exception) {
                    // Пробуем следующий формат
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OrderForm", "Ошибка парсинга даты: $dateString", e)
        }
        
        // Если не удалось распарсить, используем текущую дату
        val calendar = Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun buildUpdatedOrder(): Order {
        val originalOrder = args.order!!
        val newOrder = buildNewOrder()
        
        // ВАЖНО: При обновлении сохраняем старые значения даты и статуса, если они не были изменены
        val finalDate = if (newOrder.date.isNullOrBlank() || newOrder.date == "Выберите дату") {
            // Если дата не выбрана, используем старую дату
            originalOrder.date
        } else {
            // Если дата выбрана, используем новую
            newOrder.date
        }
        
        val finalStatus = if (newOrder.status == "new" && originalOrder.status != "new") {
            // Если статус не был изменен (остался "new"), используем старый статус
            originalOrder.status
        } else {
            // Если статус был изменен, используем новый
            newOrder.status
        }
        
        // При обновлении сохраняем данные создателя из оригинального заказа, если они есть
        // Иначе используем данные текущего пользователя
        return newOrder.copy(
            id = originalOrder.id,
            date = finalDate,
            status = finalStatus,
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


    /**
     * Сохраняет фото локально и возвращает путь к первому фото (или JSON с путями для нескольких фото)
     */
    private suspend fun savePhotosLocally(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext ""
        
        val context = requireContext()
        val photosDir = File(context.filesDir, "order_photos")
        if (!photosDir.exists()) {
            photosDir.mkdirs()
        }
        
        val photoPaths = mutableListOf<String>()
        
        uris.forEachIndexed { index, uri ->
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val timestamp = System.currentTimeMillis()
                    val fileName = "photo_${timestamp}_$index.jpg"
                    val photoFile = File(photosDir, fileName)
                    
                    FileOutputStream(photoFile).use { output ->
                        inputStream.copyTo(output)
                    }
                    
                    // Сохраняем абсолютный путь к файлу для локального доступа
                    // Используем file:// URI для корректной загрузки в Glide
                    photoPaths.add(photoFile.absolutePath)
                    inputStream.close()
                }
            } catch (e: Exception) {
                Log.e("OrderForm", "Ошибка при сохранении фото $index", e)
            }
        }
        
        // Если одно фото - возвращаем путь, если несколько - JSON массив
        if (photoPaths.size == 1) {
            photoPaths[0]
        } else if (photoPaths.size > 1) {
            // Сохраняем как JSON массив для нескольких фото
            JSONArray().apply {
                photoPaths.forEach { put(it) }
            }.toString()
        } else {
            ""
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
