package egx.relab_app.ui.orders

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import egx.relab_app.network.ApiService

import egx.relab_app.databinding.FragmentAnalyticsBinding

import egx.relab_app.network.RetrofitClient
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
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
        setupChart()
    }
    
    private fun setupChart() {
        val chart = binding.chartMonthlyEarnings
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setBackgroundColor(Color.WHITE)
        
        // Настройка осей
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.textColor = Color.BLACK
        xAxis.textSize = 10f
        xAxis.setDrawGridLines(false)
        xAxis.granularity = 1f
        
        val yAxisLeft = chart.axisLeft
        yAxisLeft.textColor = Color.BLACK
        yAxisLeft.setDrawGridLines(true)
        yAxisLeft.gridColor = Color.LTGRAY
        
        val yAxisRight = chart.axisRight
        yAxisRight.isEnabled = false
        
        chart.legend.isEnabled = false
    }
    
    private fun loadChartData() {
        RetrofitClient.apiService.getDailyEarnings().enqueue(object : Callback<List<ApiService.DailyEarningsResponse>> {
            override fun onResponse(
                call: Call<List<ApiService.DailyEarningsResponse>>,
                response: Response<List<ApiService.DailyEarningsResponse>>
            ) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val data = response.body() ?: emptyList()
                    updateChart(data)
                } else {
                    // Если ошибка, показываем пустой график
                    updateChart(emptyList())
                }
            }

            override fun onFailure(call: Call<List<ApiService.DailyEarningsResponse>>, t: Throwable) {
                if (!isAdded || _binding == null) return
                updateChart(emptyList())
            }
        })
    }
    
    private fun updateChart(data: List<ApiService.DailyEarningsResponse>) {
        val chart = binding.chartMonthlyEarnings
        val entries = mutableListOf<Entry>()
        val labels = mutableListOf<String>()
        
        data.forEachIndexed { index, item ->
            entries.add(Entry(index.toFloat(), item.earnings.toFloat()))
            labels.add(item.day.toString())
        }
        
        if (entries.isEmpty()) {
            // Если данных нет, создаем пустой график
            chart.data = null
            chart.invalidate()
            return
        }
        
        val dataSet = LineDataSet(entries, "Заработок").apply {
            color = Color.parseColor("#1976D2")
            valueTextColor = Color.BLACK
            lineWidth = 2f
            setCircleColor(Color.parseColor("#1976D2"))
            circleRadius = 4f
            setDrawCircleHole(false)
            valueTextSize = 9f
            setDrawFilled(true)
            fillColor = Color.parseColor("#BBDEFB")
            fillAlpha = 100
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        
        val lineData = LineData(dataSet)
        chart.data = lineData
        chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        chart.invalidate()
    }

    private fun loadAnalytics() {
        // Проверяем что фрагмент еще прикреплен к Activity
        if (!isAdded) return
        
        loadChartData()
        
        RetrofitClient.apiService.getMonthlyEarnings().enqueue(object : Callback<ApiService.EarningsResponse> {
            override fun onResponse(call: Call<ApiService.EarningsResponse>, response: Response<ApiService.EarningsResponse>) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val message = response.body()?.message ?: "Нет данных"
                    // Извлекаем сумму из сообщения, если возможно
                    binding.tvTotalEarnings.text = message.replace("Вы заработали: ", "").replace(" ₽", " ₽")
                } else {
                    binding.tvTotalEarnings.text = "0 ₽"
                }
            }

            override fun onFailure(call: Call<ApiService.EarningsResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                binding.tvTotalEarnings.text = "0 ₽"
            }
        })

        RetrofitClient.apiService.getMonthlyCompletedOrders().enqueue(object : Callback<ApiService.OrdersCountResponse> {
            override fun onResponse(call: Call<ApiService.OrdersCountResponse>, response: Response<ApiService.OrdersCountResponse>) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val message = response.body()?.message ?: "Нет данных"
                    // Извлекаем количество из сообщения, если возможно
                    binding.tvCompletedOrders.text = message.replace("Вы выполнили заказов: ", "")
                } else {
                    binding.tvCompletedOrders.text = "0"
                }
            }

            override fun onFailure(call: Call<ApiService.OrdersCountResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                binding.tvCompletedOrders.text = "0"
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
