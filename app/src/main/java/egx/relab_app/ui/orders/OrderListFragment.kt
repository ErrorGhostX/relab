package egx.relab_app.ui.orders

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
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

    private lateinit var adapter: OrderAdapter
    private var allOrders: List<Order> = emptyList()

    private val statusMap = mapOf(
        "new" to "Новый",
        "in_progress" to "В процессе",
        "done" to "Завершён",
        "pending" to "Ожидает"
    )
    private val reverseStatusMap = statusMap.entries.associate { it.value to it.key }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOrdersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupRecyclerView()
        setupFilterSpinner()
        binding.add.setOnClickListener {
            findNavController().navigate(
                OrderListFragmentDirections.actionOrderListFragmentToOrderFormFragment()
            )
        }
        loadOrders()
    }

    private fun setupRecyclerView() {
        adapter = OrderAdapter { order ->
            val nav = OrderListFragmentDirections
                .actionOrderListFragmentToOrderDetailFragment(order)
            findNavController().navigate(nav)
        }
        binding.ordersRecyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.ordersRecyclerView.adapter = adapter
    }

    private fun setupFilterSpinner() {
        val options = listOf("Все") + statusMap.values
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options)
        binding.statusFilterSpinner.adapter = spinnerAdapter

        binding.statusFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = options[position]
                val filtered = if (selected == "Все") {
                    allOrders
                } else {
                    val code = reverseStatusMap[selected]
                    allOrders.filter { it.status == code }
                }
                showOrders(filtered)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }




    private fun loadOrders() {
        RetrofitClient.apiService.getOrders().enqueue(object : Callback<List<Order>> {
            override fun onResponse(call: Call<List<Order>>, response: Response<List<Order>>) {
                when {
                    response.code() == 401 -> {
                        // Только уведомляем — НЕ перенаправляем
                        Toast.makeText(requireContext(),
                            "Сессия истекла, пожалуйста войдите снова",
                            Toast.LENGTH_LONG).show()
                    }
                    response.isSuccessful -> {
                        allOrders = response.body().orEmpty()
                        showOrders(allOrders)
                    }
                    else -> {
                        Toast.makeText(requireContext(),
                            "Ошибка сервера: ${response.code()}",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
            override fun onFailure(call: Call<List<Order>>, t: Throwable) {
                Toast.makeText(requireContext(),
                    "Ошибка сети: ${t.localizedMessage}",
                    Toast.LENGTH_SHORT).show()
            }
        })
    }





    private fun showOrders(orders: List<Order>) {
        val displayOrders = orders.map { order ->
            order.copy(
                status = statusMap[order.status] ?: order.status,
                orderType = order.orderType
            )
        }
        adapter.updateList(displayOrders)
        Toast.makeText(requireContext(), "Показано ${displayOrders.size} заказов", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
