package egx.relab_app.ui.orders

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import egx.relab_app.R
import egx.relab_app.network.ApiService.StaffPerformance

class StaffPerformanceAdapter(
    private var staffList: List<StaffPerformance> = emptyList(),
    private val onStaffClick: (StaffPerformance) -> Unit
) : RecyclerView.Adapter<StaffPerformanceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvRank: TextView = view.findViewById(R.id.tvRank)
        val tvRevenue: TextView = view.findViewById(R.id.tvRevenue)
        val tvOrders: TextView = view.findViewById(R.id.tvOrders)
        val tvAvgTime: TextView = view.findViewById(R.id.tvAvgTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_staff_performance, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = staffList[position]

        holder.tvName.text = item.full_name ?: item.username ?: "Неизвестно"
        
        val rankDisplay = when (item.rank) {
            "admin" -> "Администратор"
            "employee_2" -> "Сотрудник 2 ранга"
            else -> "Сотрудник"
        }
        holder.tvRank.text = rankDisplay

        holder.tvRevenue.text = "${item.total_revenue.toInt()} ₽"
        holder.tvOrders.text = "Заказов: ${item.completed_orders_count}"
        
        if (item.average_completion_time_days != null) {
            holder.tvAvgTime.text = "${item.average_completion_time_days} дн"
        } else {
            holder.tvAvgTime.text = "—"
        }

        if (!item.avatar.isNullOrEmpty() && item.avatar != "null") {
            Glide.with(holder.itemView.context)
                .load(item.avatar)
                .placeholder(R.drawable.relab)
                .error(R.drawable.relab)
                .circleCrop()
                .into(holder.ivAvatar)
        } else {
            holder.ivAvatar.setImageResource(R.drawable.relab)
        }

        holder.itemView.setOnClickListener {
            onStaffClick(item)
        }
    }

    override fun getItemCount() = staffList.size

    fun updateData(newList: List<StaffPerformance>) {
        staffList = newList
        notifyDataSetChanged()
    }
}
