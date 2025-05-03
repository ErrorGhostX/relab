package egx.relab_app.ui.orders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import egx.relab_app.R
import egx.relab_app.network.RetrofitClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import egx.relab_app.models.Order
class CreateOrderFragment : Fragment() {

    private lateinit var customerNameEditText: EditText
    private lateinit var deviceNameEditText: EditText
    private lateinit var saveButton: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_create_order, container, false)

        customerNameEditText = view.findViewById(R.id.editTextCustomerName)
        deviceNameEditText = view.findViewById(R.id.editTextDeviceName)
        saveButton = view.findViewById(R.id.buttonSave)

        saveButton.setOnClickListener {
            saveOrder()
        }

        return view
    }

    private fun saveOrder() {
        val customerName = customerNameEditText.text.toString()
        val deviceName = deviceNameEditText.text.toString()

        if (customerName.isBlank() || deviceName.isBlank()) {
            Toast.makeText(requireContext(), "Заполните все поля", Toast.LENGTH_SHORT).show()
            return
        }

        val newOrder = Order(
            id = null,
            orderNumber = "N/A", // Сервер может сам сгенерировать
            customer = customerName,
            contactInfo = "",
            extraInfo = "",
            telegram = "",
            deviceName = deviceName,
            deviceType = "",
            manufacturer = "",
            model = "",
            kit = "",
            photoUrl = null,
            description = "",
            date = "",
            orderType = "",
            status = "новый"
        )


        RetrofitClient.apiService.createOrder(newOrder).enqueue(object : Callback<Order> {
            override fun onResponse(call: Call<Order>, response: Response<Order>) {
                if (response.isSuccessful) {
                    Toast.makeText(requireContext(), "Заказ создан!", Toast.LENGTH_SHORT).show()
                    requireActivity().onBackPressedDispatcher.onBackPressed() // назад
                } else {
                    Toast.makeText(requireContext(), "Ошибка при создании", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<Order>, t: Throwable) {
                Toast.makeText(requireContext(), "Ошибка сети: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }
}
