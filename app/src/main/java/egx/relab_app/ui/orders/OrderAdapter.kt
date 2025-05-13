package egx.relab_app.orders

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import egx.relab_app.R
import egx.relab_app.databinding.ItemOrderBinding
import egx.relab_app.models.Order

/**
 * Адаптер для отображения списка заказов в RecyclerView.
 *
 * @param onClick Лямбда-функция, вызываемая при клике на элемент.
 */
class OrderAdapter(
    private val onClick: (Order) -> Unit
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    // Изменяемый список заказов
    var orders: List<Order> = emptyList()

    /**
     * Метод для обновления списка заказов.
     */
    fun updateList(newList: List<Order>) {
        orders = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding = ItemOrderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OrderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        val order = orders[position]
        holder.bind(order)
    }

    override fun getItemCount(): Int = orders.size

    inner class OrderViewHolder(
        private val binding: ItemOrderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(order: Order) {
            binding.orderIdText.text = "ID: ${order.id}"
            binding.deviceNameText.text = "Устройство: ${order.deviceName}"
            binding.orderStatusText.text = "Статус: ${order.status}"

            Glide.with(binding.root.context)
                .load(order.photo)
                .placeholder(R.drawable.placeholder_image)
                .error(R.drawable.placeholder_image)
                .into(binding.orderImage)

            binding.root.setOnClickListener {
                onClick(order)
            }
        }
    }
}
