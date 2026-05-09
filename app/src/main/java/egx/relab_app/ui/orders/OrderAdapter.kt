package egx.relab_app.orders

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import egx.relab_app.R
import egx.relab_app.databinding.ItemOrderBinding
import egx.relab_app.models.Order
import org.json.JSONArray


class OrderAdapter(
    private val onClick: (Order) -> Unit
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    companion object {
        private const val VIEW_TYPE_GRID = 1
        private const val VIEW_TYPE_LIST = 2
    }

    private val statusMap = mapOf(
        "new" to "Новый",
        "working" to "В работе",
        "completed" to "Выполнен",
        "cancelled" to "Отменен",
        "waiting" to "Ожидание"
    )


    var orders: List<Order> = emptyList()
    var isGridView: Boolean = true

    fun updateList(newList: List<Order>) {
        orders = newList
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (isGridView) VIEW_TYPE_GRID else VIEW_TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_GRID) R.layout.item_order else R.layout.item_order_list
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return OrderViewHolder(view, viewType == VIEW_TYPE_GRID)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        val order = orders[position]
        holder.bind(order)
    }

    override fun getItemCount(): Int = orders.size
    
    /**
     * Извлекает первый путь к фото из строки (может быть JSON массив или просто путь)
     */
    private fun getFirstPhotoPath(photoString: String?): String? {
        if (photoString.isNullOrEmpty() || photoString == "null") {
            return null
        }
        
        try {
            // Пытаемся распарсить как JSON массив
            val jsonArray = JSONArray(photoString)
            if (jsonArray.length() > 0) {
                return jsonArray.getString(0)
            }
        } catch (e: Exception) {
            // Если не JSON, значит это одно фото (строка)
            return photoString
        }
        
        return null
    }

    inner class OrderViewHolder(
        itemView: View,
        private val isGrid: Boolean
    ) : RecyclerView.ViewHolder(itemView) {

        // Общие для обеих версток элементы
        private val orderImage: android.widget.ImageView = itemView.findViewById(R.id.orderImage)
        private val orderNameText: android.widget.TextView? = itemView.findViewById(R.id.orderNameText)
        private val orderIdText: android.widget.TextView = itemView.findViewById(R.id.orderIdText)
        private val deviceNameText: android.widget.TextView = itemView.findViewById(R.id.deviceNameText)
        private val orderStatusText: android.widget.TextView = itemView.findViewById(R.id.orderStatusText)
        private val createdByAvatar: android.widget.ImageView = itemView.findViewById(R.id.createdByAvatar)
        private val orderCreatedText: android.widget.TextView? = itemView.findViewById(R.id.orderCreatedText)
        private val badgePublic: View = itemView.findViewById(R.id.badgePublic)
        private val orderDateText: android.widget.TextView? = itemView.findViewById(R.id.orderDateText)

        fun bind(order: Order) {
            val idStr = if (order.id != null && order.id!! > 0) "#${order.id}" else "#Локально"
            val dateStr = order.date ?: ""
            orderIdText.text = if (dateStr.isNotEmpty()) "$idStr | $dateStr" else idStr
            
            val creatorName = order.createdByFullName ?: order.createdByUsername ?: "Relab"
            orderCreatedText?.text = creatorName
            
            orderDateText?.text = dateStr
            
            // Название заказа как главный заголовок, если есть
            if (orderNameText != null) {
                if (!order.orderName.isNullOrBlank()) {
                    orderNameText.text = order.orderName
                    deviceNameText.text = order.deviceName ?: "Устройство"
                } else {
                    orderNameText.text = order.deviceName ?: "Заказ"
                    deviceNameText.text = "Без названия"
                }
            } else {
                // Для старых версток или если в списке только одно поле
                deviceNameText.text = order.orderName ?: order.deviceName ?: "Заказ"
            }

            if (!order.createdByAvatar.isNullOrEmpty() && order.createdByAvatar != "null") {
                Glide.with(itemView.context)
                    .load(order.createdByAvatar)
                    .placeholder(R.mipmap.ic_launcher_round)
                    .error(R.mipmap.ic_launcher_round)
                    .circleCrop()
                    .into(createdByAvatar)
            } else {
                createdByAvatar.setImageResource(R.mipmap.ic_launcher_round)
            }
            
            bindStatusBadge(order.status)
            
            // Complexity
            val complexityBadge: View = itemView.findViewById(R.id.complexityBadge)
            val tvComplexityValue: android.widget.TextView = itemView.findViewById(R.id.tvComplexityValue)
            val ivComplexityIcon: android.widget.ImageView = itemView.findViewById(R.id.ivComplexityIcon)
            
            val complexity = order.complexityPercentage ?: 0.0
            if (complexity > 0) {
                complexityBadge.visibility = View.VISIBLE
                tvComplexityValue.text = "${complexity.toInt()}%"
                
                // Color logic for icon
                val compColor = when {
                    complexity < 30 -> "#10B981"
                    complexity < 70 -> "#F59E0B"
                    else -> "#EF4444"
                }
                ivComplexityIcon.setColorFilter(android.graphics.Color.parseColor(compColor))
            } else {
                complexityBadge.visibility = View.GONE
            }
            
            badgePublic.visibility = if (
                order.isPublic && (order.assignedToUsername.isNullOrEmpty() || order.assignedToUsername == "null")
            ) View.VISIBLE else View.GONE
            
            val firstPhotoPath = getFirstPhotoPath(order.photo)
            if (!firstPhotoPath.isNullOrEmpty()) {
                val imageSource = if (firstPhotoPath.startsWith("http://") || firstPhotoPath.startsWith("https://")) {
                    firstPhotoPath
                } else {
                    java.io.File(firstPhotoPath)
                }
                
                Glide.with(itemView.context)
                    .load(imageSource)
                    .placeholder(R.drawable.ic_menu_camera)
                    .error(android.R.drawable.dark_header)
                    .fallback(R.drawable.ic_menu_camera)
                    .into(orderImage)
            } else {
                orderImage.setImageResource(R.drawable.ic_menu_camera)
            }

            itemView.setOnClickListener {
                onClick(order)
            }
        }

        private fun bindStatusBadge(statusValue: String?) {
            val statusName = statusMap[statusValue] ?: statusValue ?: "—"
            orderStatusText.text = statusName.uppercase()
            
            val (bgColor, textColor) = when (statusValue?.lowercase()) {
                "new", "новый" -> R.color.status_new_bg to R.color.status_new_premium
                "working", "в работе", "work", "in_progress" -> R.color.status_work_bg to R.color.status_in_progress_premium
                "completed", "выполнен", "done", "ready", "finished" -> R.color.status_completed_bg to R.color.status_done_premium
                "cancelled", "отменен", "cancel" -> R.color.status_cancelled_bg to R.color.error_red
                "waiting", "ожидание", "pending" -> R.color.status_waiting_bg to R.color.status_pending_premium
                else -> R.color.gray_100 to R.color.text_premium_secondary
            }
            
            val context = itemView.context
            orderStatusText.setTextColor(context.getColor(textColor))
            
            // For the new design, we use a custom background from XML or dynamic
            // But for simplicity, we can still use GradientDrawable if it's not set in XML
            if (orderStatusText.background == null || orderStatusText.background is android.graphics.drawable.GradientDrawable) {
                val shape = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 12 * context.resources.displayMetrics.density
                    setColor(context.getColor(bgColor))
                }
                orderStatusText.background = shape
            }
        }
        
        /**
         * Получить текстовый уровень сложности на основе процента
         */
        private fun getComplexityLevel(percentage: Double): String {
            return when {
                percentage < 30 -> "Простая"
                percentage < 60 -> "Средняя"
                percentage < 80 -> "Высокая"
                else -> "Очень высокая"
            }
        }
    }
}
