package egx.relab_app.orders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import egx.relab_app.R
import egx.relab_app.databinding.ItemOrderTileBinding
import egx.relab_app.models.Order

class OrderAdapter(
    private val orders: List<Order>,
    private val onClick: (Order) -> Unit
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding = ItemOrderTileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OrderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        holder.bind(orders[position])
    }

    override fun getItemCount(): Int = orders.size

    inner class OrderViewHolder(private val binding: ItemOrderTileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(order: Order) {
            binding.orderIdText.text = "ID: ${order.id ?: "??"}"
            binding.deviceNameText.text = "Устройство: ${order.deviceName}"
            binding.statusText.text = "Статус: ${order.status}"

            Glide.with(binding.root)
                .load(order.photoUrl ?: R.drawable.placeholder_image)
                .into(binding.orderImage)

            binding.root.setOnClickListener {
                val bundle = Bundle().apply {
                    putParcelable("order", order)
                }
                it.findNavController().navigate(R.id.action_orderListFragment_to_orderDetailFragment, bundle)
            }

        }
    }
}
