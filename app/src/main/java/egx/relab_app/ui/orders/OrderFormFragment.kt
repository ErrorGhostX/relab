package egx.relab_app.ui.orders

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import egx.relab_app.databinding.FragmentOrderCreateBinding
import egx.relab_app.models.Order
import egx.relab_app.network.RetrofitClient

class OrderFormFragment : Fragment() {

    private var _binding: FragmentOrderCreateBinding? = null
    private val binding get() = _binding!!

    private var selectedPhotoUri: Uri? = null
    private val args: OrderFormFragmentArgs by navArgs()
    private val isEditMode get() = args.order != null

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

    private val reverseStatusMap = statusMap.entries.associate { it.value to it.key }
    private val reverseOrderTypeMap = orderTypeMap.entries.associate { it.value to it.key }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOrderCreateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupSpinners()
        setupListeners()
        if (isEditMode) {
            populateFields(args.order!!)
        }
    }

    private fun setupSpinners() {
        val types = listOf("Ремонт", "Диагностика")
        val statuses = listOf("Новый", "В процессе", "Завершён", "Ожидает")

        binding.orderTypeSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, types)
        binding.statusSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, statuses)
    }

    private fun setupListeners() {
        val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            selectedPhotoUri = uri
            binding.textPhotoChosen.text = if (uri != null) "Фото выбрано" else "Фото не выбрано"
        }

        binding.buttonChoosePhoto.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.buttonSave.setOnClickListener {
            saveOrUpdateOrder()
        }
    }

    private fun populateFields(order: Order) {
        binding.editTextOrderNumber.setText(order.orderNumber)
        binding.editTextCustomerName.setText(order.customer)
        binding.editTextContactInfo.setText(order.contactInfo)
        binding.editTextExtraInfo.setText(order.extraInfo)
        binding.editTextTelegram.setText(order.telegram)
        binding.editTextDeviceName.setText(order.deviceName)
        binding.editTextDeviceType.setText(order.deviceType)
        binding.editTextManufacturer.setText(order.manufacturer)
        binding.editTextModel.setText(order.model)
        binding.editTextKit.setText(order.kit)
        binding.editTextDescription.setText(order.description)
        binding.editTextDate.setText(order.date)

        // Устанавливаем значения спиннеров
        reverseOrderTypeMap[order.orderType]?.let {
            val position = (binding.orderTypeSpinner.adapter as ArrayAdapter<String>).getPosition(it)
            binding.orderTypeSpinner.setSelection(position)
        }
        reverseStatusMap[order.status]?.let {
            val position = (binding.statusSpinner.adapter as ArrayAdapter<String>).getPosition(it)
            binding.statusSpinner.setSelection(position)
        }
    }

    private fun saveOrUpdateOrder() {
        val requiredFields = listOf(binding.editTextCustomerName, binding.editTextDeviceName)
        if (requiredFields.any { it.text.isNullOrBlank() }) {
            Toast.makeText(requireContext(), "Пожалуйста, заполните обязательные поля", Toast.LENGTH_SHORT).show()
            return
        }

        // Собираем данные в объект Order
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
            status = statusMap[binding.statusSpinner.selectedItem.toString()] ?: "new",
            orderType = orderTypeMap[binding.orderTypeSpinner.selectedItem.toString()] ?: "repair",
            photo = selectedPhotoUri?.toString() // URI фото
        )

        val context = requireContext()
        val callback = { success: Boolean ->
            Toast.makeText(context, if (success) "Заказ сохранён" else "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            if (success) findNavController().popBackStack()
        }

        // Передаем данные в Retrofit для создания или обновления заказа
        if (isEditMode) {
            RetrofitClient.updateOrder(context, order.id, order, selectedPhotoUri, callback) // передаем ID для обновления
        } else {
            RetrofitClient.createOrder(context, order, selectedPhotoUri, callback)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
