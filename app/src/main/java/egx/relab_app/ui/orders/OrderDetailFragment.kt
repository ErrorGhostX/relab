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
import egx.relab_app.databinding.FragmentOrderDetailBinding
import egx.relab_app.models.Order
import egx.relab_app.network.RetrofitClient
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class OrderDetailFragment : Fragment() {

    private var _binding: FragmentOrderDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var currentOrder: Order

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOrderDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        currentOrder = OrderDetailFragmentArgs.fromBundle(requireArguments()).order
        bindOrderToUI(currentOrder)

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

        binding.buttonAddService.setOnClickListener { showAddServiceDialog() }
        binding.buttonPrint.setOnClickListener { generateAndShareReport() }
    }

    private fun bindOrderToUI(order: Order) = with(binding) {
        orderId.text        = "ID: ${order.id}"
        createdBy.text      = "Создал: ${order.createdByUsername ?: "-"}"
        orderNumber.text    = "Номер заказа: ${order.orderNumber}"
        customer.text       = "Клиент: ${order.customer}"
        contactInfo.text    = "Контакты: ${order.contactInfo}"
        extraInfo.text      = "Доп. инфо: ${order.extraInfo}"
        telegram.text       = "Телеграм: ${order.telegram}"
        deviceName.text     = "Устройство: ${order.deviceName}"
        deviceType.text     = "Тип: ${order.deviceType}"
        manufacturer.text   = "Производитель: ${order.manufacturer}"
        model.text          = "Модель: ${order.model}"
        kit.text            = "Комплектация: ${order.kit}"
        description.text    = "Описание: ${order.description}"
        date.text           = "Дата: ${order.date}"
        orderType.text      = "Тип: ${order.orderType}"
        status.text         = "Статус: ${order.status}"

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
                val updatedOrder = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getOrderById(currentOrder.id!!.toString())
                }
                currentOrder = updatedOrder
                bindOrderToUI(updatedOrder)
            } catch (e: Exception) {
                showToast("Ошибка загрузки заказа")
            }
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

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
