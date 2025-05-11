package egx.relab_app.ui.orders

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import egx.relab_app.R
import egx.relab_app.databinding.FragmentOrdersBinding
import egx.relab_app.models.Order
import egx.relab_app.network.RetrofitClient
import egx.relab_app.orders.OrderAdapter
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class OrderListFragment : Fragment() {

    private var _binding: FragmentOrdersBinding? = null
    private val binding get() = _binding!!

    // Карты для преобразования кодов в русский текст
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOrdersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Отображаем плитки по 2 в ряд
        binding.ordersRecyclerView.layoutManager = GridLayoutManager(requireContext(), 2)

        binding.add.setOnClickListener {
            val action = OrderListFragmentDirections.actionOrderListFragmentToCreateOrderFragment()
            findNavController().navigate(action)
        }

        val api = RetrofitClient.apiService

        api.getOrders().enqueue(object : Callback<List<Order>> {
            override fun onResponse(call: Call<List<Order>>, response: Response<List<Order>>) {
                if (response.isSuccessful) {
                    val orders = response.body() ?: emptyList()
                    Log.d("OrderListFragment", "Полученные заказы: $orders")  // Логирование

                    // Преобразуем коды в русский текст
                    val updatedOrders = orders.map { order ->
                        order.copy(
                            orderType = orderTypeMap[order.orderType] ?: order.orderType,  // преобразуем orderType
                            status = statusMap[order.status] ?: order.status  // преобразуем status
                        )
                    }

                    val adapter = OrderAdapter(updatedOrders) { order ->
                        val action = OrderListFragmentDirections.actionOrderListFragmentToOrderDetailFragment(order)
                        findNavController().navigate(action) // Обработка нажатия на заказ
                    }
                    binding.ordersRecyclerView.adapter = adapter
                    Toast.makeText(requireContext(), "Получено ${updatedOrders.size} заказов", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Ошибка сервера", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<List<Order>>, t: Throwable) {
                Toast.makeText(requireContext(), "Ошибка сети: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
