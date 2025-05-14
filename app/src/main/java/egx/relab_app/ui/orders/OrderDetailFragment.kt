package egx.relab_app.ui.orders

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import egx.relab_app.R
import egx.relab_app.databinding.FragmentOrderDetailBinding
import egx.relab_app.models.Order
import egx.relab_app.models.Service
import egx.relab_app.network.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream

class OrderDetailFragment : Fragment() {

    private var _binding: FragmentOrderDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var currentOrder: Order

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOrderDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        currentOrder = OrderDetailFragmentArgs.fromBundle(requireArguments()).order
        bindOrderToUI(currentOrder)

        findNavController().currentBackStackEntry
            ?.savedStateHandle
            ?.getLiveData<Order>("updatedOrder")
            ?.observe(viewLifecycleOwner) { updated ->
                currentOrder = updated
                bindOrderToUI(updated)
            }

        binding.buttonEdit.setOnClickListener {
            val action = OrderDetailFragmentDirections
                .actionOrderDetailFragmentToOrderFormFragment(currentOrder)
            findNavController().navigate(action)
        }

        binding.buttonAddService.setOnClickListener { showAddServiceDialog() }

        binding.buttonPrint.setOnClickListener { generateAndShareReport() }
    }

    private fun bindOrderToUI(order: Order) {
        binding.orderId.text        = "ID: ${order.id}"
        binding.createdBy.text      = "Создан: ${order.createdByUsername ?: "-"}"
        binding.orderNumber.text    = "Номер заказа: ${order.orderNumber}"
        binding.customer.text       = "Клиент: ${order.customer}"
        binding.contactInfo.text    = "Контакты: ${order.contactInfo}"
        binding.extraInfo.text      = "Доп. инфо: ${order.extraInfo}"
        binding.telegram.text       = "Телеграм: ${order.telegram}"
        binding.deviceName.text     = "Устройство: ${order.deviceName}"
        binding.deviceType.text     = "Тип: ${order.deviceType}"
        binding.manufacturer.text   = "Производитель: ${order.manufacturer}"
        binding.model.text          = "Модель: ${order.model}"
        binding.kit.text            = "Комплектация: ${order.kit}"
        binding.description.text    = "Описание: ${order.description}"
        binding.date.text           = "Дата: ${order.date}"
        binding.orderType.text      = "Тип: ${order.orderType}"
        binding.status.text         = "Статус: ${order.status}"

        Glide.with(this)
            .load(order.photo)
            .placeholder(R.drawable.placeholder_image)
            .into(binding.orderImage)

        // Отображение оказанных услуг
        val container = binding.servicesContainer
        container.removeAllViews()

        if (order.services.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "Услуг нет"
                setPadding(0, 8, 0, 8)
                setTextAppearance(R.style.DetailTextStyle)
            })
        } else {
            var totalPrice = 0.0
            order.services.forEach { svc ->
                totalPrice += svc.price
                container.addView(TextView(requireContext()).apply {
                    text = "${svc.description}: ${"%.2f".format(svc.price)} ₽"
                    setPadding(0, 4, 0, 4)
                    setTextAppearance(R.style.DetailTextStyle)
                })
            }
            container.addView(TextView(requireContext()).apply {
                text = "Итого: ${"%.2f".format(totalPrice)} ₽"
                setPadding(0, 10, 0, 4)

            })
        }
    }

    private fun showAddServiceDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_service, null)
        val etDesc = dialogView.findViewById<TextInputEditText>(R.id.etDescription)
        val etPrice = dialogView.findViewById<TextInputEditText>(R.id.etPrice)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Добавить услугу")
            .setView(dialogView)
            .setPositiveButton("Добавить") { dialog, _ ->
                val d = etDesc.text.toString().trim()
                val pText = etPrice.text.toString().trim()
                when {
                    d.isEmpty() || pText.isEmpty() ->
                        Toast.makeText(requireContext(), "Заполните оба поля", Toast.LENGTH_SHORT).show()
                    pText.toDoubleOrNull() == null ->
                        Toast.makeText(requireContext(), "Некорректная цена", Toast.LENGTH_SHORT).show()
                    else -> addServiceToOrder(d, pText.toDouble())
                }
                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { dlg, _ -> dlg.dismiss() }
            .show()
    }

    private fun addServiceToOrder(description: String, price: Double) {
        RetrofitClient.addService(currentOrder.id!!, description, price) { success, _, error ->
            if (success) {
                Toast.makeText(requireContext(), "Услуга добавлена", Toast.LENGTH_SHORT).show()
                loadOrderDetails()
            } else {
                Toast.makeText(requireContext(), "Ошибка: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadOrderDetails() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val updatedOrder = RetrofitClient.apiService.getOrderById(currentOrder.id!!.toString())

                withContext(Dispatchers.Main) {
                    currentOrder = updatedOrder
                    bindOrderToUI(updatedOrder)  // Обновляем весь UI
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Ошибка загрузки заказа", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun generateAndShareReport() {
        lifecycleScope.launch {
            try {
                val pdfBytes: ByteArray = withContext(Dispatchers.IO) {
                    val call = RetrofitClient.apiService.getOrderReport(currentOrder.id!!)
                    val response = call.execute()
                    val body: ResponseBody = response.body() ?: throw Exception("Пустой ответ")
                    body.byteStream().use { it.readBytes() }
                }
                val file = File(requireContext().cacheDir, "report_${currentOrder.id}.pdf")
                FileOutputStream(file).use { it.write(pdfBytes, 0, pdfBytes.size) }
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Поделиться отчётом"))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Не удалось сгенерировать отчёт: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
