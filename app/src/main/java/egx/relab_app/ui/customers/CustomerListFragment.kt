package egx.relab_app.ui.customers

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.navigation.fragment.findNavController
import egx.relab_app.R
import egx.relab_app.app
import egx.relab_app.models.Customer
import egx.relab_app.network.RetrofitClient
import egx.relab_app.database.entity.CustomerEntity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView

class CustomerListFragment : Fragment() {

    private lateinit var adapter: CustomerAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var searchInput: TextInputEditText
    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var chipAll: Chip
    private lateinit var chipMy: Chip
    
    private val customerDao by lazy { requireContext().app.database.customerDao() }
    
    // true = показать всех, false = только мои (из локальной БД заказов)
    private var showAll = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_customer_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView = view.findViewById(R.id.recyclerViewCustomers)
        emptyView = view.findViewById(R.id.emptyView)
        searchInput = view.findViewById(R.id.editTextSearch)
        chipGroupFilter = view.findViewById(R.id.chipGroupFilter)
        chipAll = view.findViewById(R.id.chipAll)
        chipMy = view.findViewById(R.id.chipMy)

        adapter = CustomerAdapter { customer ->
            val bundle = Bundle().apply { putParcelable("customer", customer) }
            findNavController().navigate(R.id.customerDetailFragment, bundle)
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<View>(R.id.fabAddCustomer).setOnClickListener {
            findNavController().navigate(R.id.action_customerListFragment_to_customerFormFragment)
        }

        // Фильтр
        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            showAll = checkedIds.contains(R.id.chipAll)
            loadCustomers(searchInput.text?.toString() ?: "")
        }

        setupSearch()
        
        // Подтянуть клиентов с сервера, затем показать локальные
        syncCustomersFromServer()
        loadCustomers()
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                loadCustomers(s?.toString() ?: "")
            }
        })
    }

    /**
     * Синхронизировать клиентов с сервера в фоне
     */
    private fun syncCustomersFromServer() {
        lifecycleScope.launch {
            try {
                val customers = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getCustomers()
                }
                withContext(Dispatchers.IO) {
                    for (customer in customers) {
                        if (customer.id != null) {
                            val existing = customerDao.getCustomerByServerId(customer.id)
                            if (existing != null) {
                                val updated = CustomerEntity.fromCustomer(customer).copy(
                                    localId = existing.localId
                                )
                                customerDao.updateCustomer(updated)
                            } else {
                                customerDao.insertCustomer(CustomerEntity.fromCustomer(customer))
                            }
                        }
                    }
                }
                Log.d("CustomerList", "Синхронизировано ${customers.size} клиентов с сервера")
            } catch (e: Exception) {
                Log.w("CustomerList", "Не удалось загрузить клиентов с сервера: ${e.message}")
            }
        }
    }

    private fun loadCustomers(query: String = "") {
        lifecycleScope.launch {
            if (showAll) {
                // Все клиенты из локальной базы (синхронизированной с сервером)
                if (query.isBlank()) {
                    customerDao.getAllCustomers().collectLatest { entities ->
                        updateList(entities.map { it.toCustomer() })
                    }
                } else {
                    customerDao.searchCustomers(query).collectLatest { entities ->
                        updateList(entities.map { it.toCustomer() })
                    }
                }
            } else {
                // Мои клиенты — клиенты из заказов текущего пользователя
                val currentUsername = RetrofitClient.tokenManager.username
                val orderDao = requireContext().app.database.orderDao()
                if (query.isBlank()) {
                    customerDao.getAllCustomers().collectLatest { entities ->
                        // Фильтруем: показываем клиентов, у которых есть заказы от текущего пользователя
                        val myCustomerIds = withContext(Dispatchers.IO) {
                            val allOrders = orderDao.getAllOrdersSync()
                            allOrders
                                .filter { it.createdByUsername == currentUsername && it.customerRefId != null }
                                .map { it.customerRefId }
                                .toSet()
                        }
                        val filtered = entities
                            .filter { myCustomerIds.contains(it.serverId) }
                            .map { it.toCustomer() }
                        updateList(filtered)
                    }
                } else {
                    customerDao.searchCustomers(query).collectLatest { entities ->
                        val myCustomerIds = withContext(Dispatchers.IO) {
                            val allOrders = orderDao.getAllOrdersSync()
                            allOrders
                                .filter { it.createdByUsername == currentUsername && it.customerRefId != null }
                                .map { it.customerRefId }
                                .toSet()
                        }
                        val filtered = entities
                            .filter { myCustomerIds.contains(it.serverId) }
                            .map { it.toCustomer() }
                        updateList(filtered)
                    }
                }
            }
        }
    }

    private fun updateList(customers: List<Customer>) {
        adapter.submitList(customers)
        if (customers.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }
}
