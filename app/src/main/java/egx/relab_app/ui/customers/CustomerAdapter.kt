package egx.relab_app.ui.customers

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import egx.relab_app.R
import egx.relab_app.models.Customer

class CustomerAdapter(
    private val onCustomerClick: (Customer) -> Unit
) : ListAdapter<Customer, CustomerAdapter.CustomerViewHolder>(CustomerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CustomerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_customer, parent, false)
        return CustomerViewHolder(view)
    }

    override fun onBindViewHolder(holder: CustomerViewHolder, position: Int) {
        val customer = getItem(position)
        holder.bind(customer)
    }

    inner class CustomerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCustomerName: TextView = itemView.findViewById(R.id.tvCustomerName)
        private val tvCustomerPhone: TextView = itemView.findViewById(R.id.tvCustomerPhone)
        private val tvOrdersCount: TextView = itemView.findViewById(R.id.tvOrdersCount)
        private val tvLtv: TextView = itemView.findViewById(R.id.tvLtv)
        private val cardBlacklistWarning: View = itemView.findViewById(R.id.cardBlacklistWarning)
        private val tvBlacklistReason: TextView = itemView.findViewById(R.id.tvBlacklistReason)

        fun bind(customer: Customer) {
            tvCustomerName.text = customer.fullName
            
            val phone = customer.phone
            if (!phone.isNullOrBlank()) {
                tvCustomerPhone.text = phone
                tvCustomerPhone.visibility = View.VISIBLE
            } else {
                tvCustomerPhone.visibility = View.GONE
            }

            tvOrdersCount.text = "Заказов: ${customer.totalOrders}"
            
            // LTV (сумма денег)
            val ltvValue = customer.ltv ?: "0"
            tvLtv.text = "$ltvValue ₽"

            if (customer.isBlacklisted) {
                cardBlacklistWarning.visibility = View.VISIBLE
                tvBlacklistReason.text = customer.blacklistReason ?: "В черном списке"
            } else {
                cardBlacklistWarning.visibility = View.GONE
            }

            itemView.setOnClickListener {
                onCustomerClick(customer)
            }
        }
    }
}

class CustomerDiffCallback : DiffUtil.ItemCallback<Customer>() {
    override fun areItemsTheSame(oldItem: Customer, newItem: Customer): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Customer, newItem: Customer): Boolean {
        return oldItem == newItem
    }
}
