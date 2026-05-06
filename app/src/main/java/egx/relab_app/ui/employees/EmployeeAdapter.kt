package egx.relab_app.ui.employees

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import egx.relab_app.R
import egx.relab_app.models.UserResponse

/**
 * Адаптер для списка сотрудников.
 * Отображает аватар, имя, ранг и специализацию.
 */
class EmployeeAdapter(
    private val onEmployeeClick: (UserResponse) -> Unit,
    private val onMessageClick: (UserResponse) -> Unit
) : ListAdapter<UserResponse, EmployeeAdapter.EmployeeViewHolder>(EmployeeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmployeeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_employee, parent, false)
        return EmployeeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EmployeeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class EmployeeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivEmployeeAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvEmployeeName)
        private val tvRank: TextView = itemView.findViewById(R.id.tvEmployeeRank)
        private val tvSpecialization: TextView = itemView.findViewById(R.id.tvEmployeeSpecialization)
        private val btnMessage: View = itemView.findViewById(R.id.btnEmployeeMessage)

        fun bind(employee: UserResponse) {
            tvName.text = employee.full_name ?: employee.username ?: "—"
            tvRank.text = employee.rank_display ?: employee.rank ?: "Сотрудник"
            
            val spec = employee.specialization
            if (!spec.isNullOrBlank()) {
                tvSpecialization.text = spec
                tvSpecialization.visibility = View.VISIBLE
            } else {
                tvSpecialization.visibility = View.GONE
            }

            // Аватар
            if (!employee.avatar.isNullOrBlank()) {
                Glide.with(itemView.context)
                    .load(employee.avatar)
                    .circleCrop()
                    .placeholder(R.drawable.ic_person)
                    .into(ivAvatar)
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person)
            }

            itemView.setOnClickListener { onEmployeeClick(employee) }
            btnMessage.setOnClickListener { onMessageClick(employee) }
        }
    }
}

class EmployeeDiffCallback : DiffUtil.ItemCallback<UserResponse>() {
    override fun areItemsTheSame(oldItem: UserResponse, newItem: UserResponse): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: UserResponse, newItem: UserResponse): Boolean {
        return oldItem == newItem
    }
}
