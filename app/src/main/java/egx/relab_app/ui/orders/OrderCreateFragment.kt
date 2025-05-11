package egx.relab_app.ui.orders

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import egx.relab_app.databinding.FragmentCreateOrderBinding
import egx.relab_app.models.Order
import egx.relab_app.network.RetrofitClient

// Фрагмент для создания нового заказа
class OrderCreateFragment : Fragment() {

    // Приватное свойство для биндинга макета, чтобы избежать утечек
    private var _binding: FragmentCreateOrderBinding? = null
    private val binding get() = _binding!!

    // Храним Uri выбираемого фото
    private var selectedPhotoUri: Uri? = null

    companion object {
        // Код запроса выбора изображения (не используется, т.к. применен контракт)
        private const val PICK_IMAGE_REQUEST = 1
    }

    // Словарь для соответствия отображаемого текста в Spinner и кода для API
    private val statusMap = mapOf(
        "Новый" to "new",
        "В процессе" to "in_progress",
        "Завершён" to "done",
        "Ожидает" to "pending"
    )

    private val orderTypeMap = mapOf(
        "Ремонт" to "repair",
        "Диагностика" to "diagnosis"
    )

    // Создание и привязка макета фрагмента
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Инициализируем ViewBinding
        _binding = FragmentCreateOrderBinding.inflate(inflater, container, false)
        return binding.root  // Возвращаем корневой view
    }

    // Вызывается после создания view
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSpinners()    // Настраиваем выпадающие списки
        setupListeners()   // Настраиваем обработчики кликов
    }

    // Настройка Spinner для выбора типа заказа и статуса
    private fun setupSpinners() {
        val types = listOf("Ремонт", "Диагностика")
        val statuses = listOf("Новый", "В процессе", "Завершён", "Ожидает")

        // Adapter для Spinner типов заказа
        binding.orderTypeSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            types
        )

        // Adapter для Spinner статусов заказа
        binding.statusSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            statuses
        )
    }

    // Настройка слушателей для кнопок выбора фото и сохранения заказа
    private fun setupListeners() {
        // Регистрация контракта выбора контента (фото)
        val pickImageLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            selectedPhotoUri = uri
            binding.textPhotoChosen.text = "Фото выбрано"  // Меняем текст при выборе
        }

        // Обработчик кнопки "Выбрать фото"
        binding.buttonChoosePhoto.setOnClickListener {
            pickImageLauncher.launch("image/*")  // Открываем галерею
        }

        // Обработчик кнопки "Сохранить"
        binding.buttonSave.setOnClickListener {
            saveOrder()  // Вызываем метод сохранения
        }
    }

    // Метод сборки данных из полей и отправки на сервер
    private fun saveOrder() {
        // Проверяем обязательные поля: имя клиента и название устройства
        val fields = listOf(
            binding.editTextCustomerName,
            binding.editTextDeviceName
        )
        if (fields.any { it.text.isNullOrBlank() }) {
            Toast.makeText(
                requireContext(),
                "Пожалуйста, заполните обязательные поля",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Конвертируем выбранные значения из Spinner в коды для API
        val statusCode = statusMap[binding.statusSpinner.selectedItem.toString()] ?: "new"
        val orderTypeCode = orderTypeMap[binding.orderTypeSpinner.selectedItem.toString()] ?: "repair"

        // Создаем модель Order для отправки
        val order = Order(
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
            date = binding.editTextDate.text.toString(),
            orderType = orderTypeCode,
            status = statusCode
        )

        // Вызываем RetrofitClient для отправки данных
        RetrofitClient.createOrder(
            requireContext(),
            order,
            selectedPhotoUri
        ) { isSuccess ->
            // Коллбэк: покажем toast в зависимости от результата
            if (isSuccess) {
                Toast.makeText(requireContext(), "Заказ сохранён", Toast.LENGTH_SHORT).show()
                // Здесь можно добавить навигацию назад
            } else {
                Toast.makeText(requireContext(), "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Очистка binding при уничтожении view
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}