package egx.relab_app.ui.customers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import egx.relab_app.R
import egx.relab_app.app
import egx.relab_app.models.Customer
import egx.relab_app.orders.OrderAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CustomerDetailFragment : Fragment() {

    private lateinit var customer: Customer
    private lateinit var orderAdapter: OrderAdapter
    private val repository by lazy { requireContext().app.orderRepository }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_customer_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        customer = arguments?.getParcelable("customer") ?: return

        val tvFullName = view.findViewById<TextView>(R.id.tvFullName)
        val tvPhone = view.findViewById<TextView>(R.id.tvPhone)
        val tvMessenger = view.findViewById<TextView>(R.id.tvMessenger)
        val tvEmail = view.findViewById<TextView>(R.id.tvEmail)
        val tvExtraInfo = view.findViewById<TextView>(R.id.tvExtraInfo)
        
        val tvOrdersCount = view.findViewById<TextView>(R.id.tvOrdersCount)
        val tvLtv = view.findViewById<TextView>(R.id.tvLtv)
        
        val cardBlacklistWarning = view.findViewById<View>(R.id.cardBlacklistWarning)
        val tvBlacklistReason = view.findViewById<TextView>(R.id.tvBlacklistReason)

        val toolbar = view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        tvFullName.text = customer.fullName
        tvPhone.text = "Телефон: ${customer.phone?.takeIf { it.isNotBlank() } ?: "—"}"
        tvMessenger.text = "Мессенджер: ${customer.messenger?.takeIf { it.isNotBlank() } ?: "—"}"
        tvEmail.text = "Email: ${customer.email?.takeIf { it.isNotBlank() } ?: "—"}"
        tvExtraInfo.text = "Доп. инфо: ${customer.extraInfo?.takeIf { it.isNotBlank() } ?: "—"}"

        tvOrdersCount.text = customer.totalOrders.toString()
        tvLtv.text = "${customer.ltv ?: "0"} ₽"

        if (customer.isBlacklisted) {
            cardBlacklistWarning.visibility = View.VISIBLE
            tvBlacklistReason.text = "Причина: ${customer.blacklistReason ?: "Не указана"}"
        } else {
            cardBlacklistWarning.visibility = View.GONE
        }

        view.findViewById<View>(R.id.fabEditCustomer).setOnClickListener {
            val bundle = Bundle().apply { putParcelable("customer", customer) }
            findNavController().navigate(R.id.action_customerDetailFragment_to_customerFormFragment, bundle)
        }

        setupOrdersList(view)
    }

    private fun setupOrdersList(view: View) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewOrders)
        val emptyView = view.findViewById<TextView>(R.id.emptyOrdersView)
        
        orderAdapter = OrderAdapter { order ->
            // Навигация на детали заказа (если есть action)
            try {
                // Если мы можем, переходим. Иначе просто тост
                val bundle = Bundle().apply { putParcelable("order", order) }
                findNavController().navigate(R.id.orderDetailFragment, bundle)
            } catch (e: Exception) {
                android.widget.Toast.makeText(requireContext(), "Заказ: ${order.orderNumber}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = orderAdapter
        
        lifecycleScope.launch {
            repository.getAllOrders().collectLatest { allOrders ->
                // Фильтруем заказы этого клиента
                val customerOrders = allOrders.filter { 
                    if (customer.id != null) {
                        it.customerRef == customer.id
                    } else {
                        it.customerRef == null && it.customer == customer.fullName
                    }
                }
                
                orderAdapter.updateList(customerOrders)
                
                if (customerOrders.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyView.visibility = View.GONE
                }
            }
        }
    }
}
