package egx.relab_app.ui.orders

import android.app.DatePickerDialog
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
import java.util.Calendar

class OrderFormFragment : Fragment() {

    private var _binding: FragmentOrderCreateBinding? = null
    private val binding get() = _binding!!

    private var selectedPhotoUri: Uri? = null
    private val args: OrderFormFragmentArgs by navArgs()
    private val isEditMode get() = args.order != null

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
        var selectedDate: String? = null

        binding.orderTypeSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, types)
        binding.statusSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, statuses)
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

        if (isEditMode) {
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
            binding.textViewSelectedDate.text = o.date
            selectedDate = o.date
            reverseOrderTypeMap[o.orderType]?.let {
                binding.orderTypeSpinner.setSelection((binding.orderTypeSpinner.adapter as ArrayAdapter<String>).getPosition(it))
            }
            reverseStatusMap[o.status]?.let {
                binding.statusSpinner.setSelection((binding.statusSpinner.adapter as ArrayAdapter<String>).getPosition(it))
            }
        }

        binding.buttonSave.setOnClickListener { saveOrUpdate() }
    }

    private fun saveOrUpdate() {
        val req = listOf(binding.editTextCustomerName, binding.editTextDeviceName)
        if (req.any { it.text.isNullOrBlank() }) {
            Toast.makeText(requireContext(), "Заполните обязательные поля", Toast.LENGTH_SHORT).show()
            return
        }

        val filledOrder = if (isEditMode) buildUpdatedOrder() else buildNewOrder()

        if (isEditMode) {
            RetrofitClient.updateOrder(requireContext(), filledOrder, selectedPhotoUri) { success, code, errorBody ->
                handleSaveResult(success, code, errorBody, filledOrder)
            }
        } else {
            RetrofitClient.createOrder(requireContext(), filledOrder, selectedPhotoUri) { success, code, errorBody ->
                handleSaveResult(success, code, errorBody, filledOrder)
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
            orderType = orderTypeMap[binding.orderTypeSpinner.selectedItem.toString()] ?: "repair"
        )
    }

    private fun buildUpdatedOrder(): Order {
        val originalOrder = args.order!!
        return buildNewOrder().copy(id = originalOrder.id)
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
