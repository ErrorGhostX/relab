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

// Фрагмент для отображения списка заказов
class OrderListFragment : Fragment() {

    // Приватное свойство для хранения биндинга, чтобы избежать утечек памяти
    private var _binding: FragmentOrdersBinding? = null
    private val binding get() = _binding!!  // Небезопасный геттер, но _binding проверяется на null

    // Словарь для перевода статусов с сервера на человеко-читаемые
    private val statusMap = mapOf(
        "new" to "Новый",
        "in_progress" to "В процессе",
        "done" to "Завершён",
        "pending" to "Ожидает"
    )

    // Словарь для перевода типов заказов
    private val orderTypeMap = mapOf(
        "repair" to "Ремонт",
        "diagnosis" to "Диагностика"
    )

    // Вызывается для создания иерархии представлений фрагмента
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Инициализация биндинга для фрагмента
        _binding = FragmentOrdersBinding.inflate(inflater, container, false)
        return binding.root  // Возвращаем корневой View
    }

    // Вызывается после того, как представление было создано
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Настраиваем RecyclerView для отображения двух колонок
        binding.ordersRecyclerView.layoutManager = GridLayoutManager(requireContext(), 2)

        // Обработчик нажатия на кнопку добавления заказа
        binding.add.setOnClickListener {
            // Навигация к экрану создания нового заказа
            val action = OrderListFragmentDirections
                .actionOrderListFragmentToCreateOrderFragment()
            findNavController().navigate(action)
        }

        // Получаем экземпляр API сервиса
        val api = RetrofitClient.apiService

        // Асинхронный GET-запрос списка заказов
        api.getOrders().enqueue(object : Callback<List<Order>> {
            override fun onResponse(call: Call<List<Order>>, response: Response<List<Order>>) {
                if (response.isSuccessful) {
                    // Получаем тело ответа или пустой список
                    val orders = response.body() ?: emptyList()
                    Log.d("OrderListFragment", "Полученные заказы: $orders")

                    // Преобразуем коды в человеко-читаемые значения
                    val updatedOrders = orders.map { order ->
                        order.copy(
                            orderType = orderTypeMap[order.orderType] ?: order.orderType,
                            status = statusMap[order.status] ?: order.status
                        )
                    }

                    // Создаем адаптер и передаем callback на клик по элементу
                    val adapter = OrderAdapter(updatedOrders) { clickedOrder ->
                        val direction = OrderListFragmentDirections
                            .actionOrderListFragmentToOrderDetailFragment(clickedOrder)
                        findNavController().navigate(direction)
                    }

                    // Привязываем адаптер к RecyclerView
                    binding.ordersRecyclerView.adapter = adapter

                    // Показываем сообщение о количестве полученных заказов
                    Toast.makeText(
                        requireContext(),
                        "Получено ${updatedOrders.size} заказов",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // Ошибка сервера
                    Toast.makeText(requireContext(), "Ошибка сервера", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<List<Order>>, t: Throwable) {
                // Ошибка сети или иная
                Toast.makeText(
                    requireContext(),
                    "Ошибка сети: ${t.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    // Вызывается, когда представление фрагмента уничтожается
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null  // Очищаем биндинг, чтобы избежать утечки памяти
    }
}
