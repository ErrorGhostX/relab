package egx.relab_app.ui.orders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import egx.relab_app.network.ApiService

import egx.relab_app.databinding.FragmentAnalyticsBinding

import egx.relab_app.network.RetrofitClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadAnalytics()
    }

    private fun loadAnalytics() {
        // Проверяем что фрагмент еще прикреплен к Activity
        if (!isAdded) return
        
        RetrofitClient.apiService.getMonthlyEarnings().enqueue(object : Callback<ApiService.EarningsResponse> {
            override fun onResponse(call: Call<ApiService.EarningsResponse>, response: Response<ApiService.EarningsResponse>) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    binding.tvTotalEarnings.text = response.body()?.message ?: "Нет данных"
                } else {
                    binding.tvTotalEarnings.text = "Ошибка загрузки заработка"
                }
            }

            override fun onFailure(call: Call<ApiService.EarningsResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                binding.tvTotalEarnings.text = "Ошибка загрузки заработка"
            }
        })

        RetrofitClient.apiService.getMonthlyCompletedOrders().enqueue(object : Callback<ApiService.OrdersCountResponse> {
            override fun onResponse(call: Call<ApiService.OrdersCountResponse>, response: Response<ApiService.OrdersCountResponse>) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    binding.tvCompletedOrders.text = response.body()?.message ?: "Нет данных"
                } else {
                    binding.tvCompletedOrders.text = "Ошибка загрузки заказов"
                }
            }

            override fun onFailure(call: Call<ApiService.OrdersCountResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                binding.tvCompletedOrders.text = "Ошибка загрузки заказов"
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
