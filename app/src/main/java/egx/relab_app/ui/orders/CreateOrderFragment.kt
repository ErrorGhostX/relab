package egx.relab_app.ui.orders

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import egx.relab_app.databinding.FragmentCreateOrderBinding
import egx.relab_app.models.Order
import egx.relab_app.network.RetrofitClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class CreateOrderFragment : Fragment() {

    private var _binding: FragmentCreateOrderBinding? = null
    private val binding get() = _binding!!
    private var selectedPhotoUri: Uri? = null

    companion object {
        private const val PICK_IMAGE_REQUEST = 1
    }

    // Карты для соответствия отображаемых строк и кодов
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateOrderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupSpinners()
        setupListeners()
    }

    // Настройка спиннеров
    private fun setupSpinners() {
        val types = listOf("Ремонт", "Диагностика")
        val statuses = listOf("Новый", "В процессе", "Завершён", "Ожидает")

        binding.orderTypeSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, types)
        binding.statusSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, statuses)
    }

    // Настройка слушателей
    private fun setupListeners() {
        binding.buttonChoosePhoto.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, PICK_IMAGE_REQUEST)
        }

        binding.buttonSave.setOnClickListener {
            saveOrder()
        }
    }

    // Сохранение заказа
    private fun saveOrder() {
        // Проверка обязательных полей
        val fields = listOf(
            binding.editTextCustomerName, binding.editTextDeviceName
        )
        if (fields.any { it.text.isNullOrBlank() }) {
            Toast.makeText(requireContext(), "Пожалуйста, заполните обязательные поля", Toast.LENGTH_SHORT).show()
            return
        }

        // Преобразование выбранных значений в коды
        val statusCode = statusMap[binding.statusSpinner.selectedItem.toString()] ?: "new" // код по умолчанию
        val orderTypeCode = orderTypeMap[binding.orderTypeSpinner.selectedItem.toString()] ?: "repair" // код по умолчанию

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
            orderType = orderTypeCode, // отправляем код
            status = statusCode, // отправляем код
            photoUrl = selectedPhotoUri?.toString() ?: ""
        )

        // Отправка данных через Retrofit
        RetrofitClient.apiService.createOrder(order).enqueue(object : Callback<Order> {
            override fun onResponse(call: Call<Order>, response: Response<Order>) {
                if (response.isSuccessful) {
                    Toast.makeText(requireContext(), "Заказ сохранён", Toast.LENGTH_SHORT).show()
                    // Навигация назад или к списку заказов
                } else {
                    Toast.makeText(requireContext(), "Ошибка сохранения", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<Order>, t: Throwable) {
                Toast.makeText(requireContext(), "Ошибка сети: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // Обработка результата выбора фото
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK) {
            selectedPhotoUri = data?.data
            binding.textPhotoChosen.text = "Фото выбрано"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
