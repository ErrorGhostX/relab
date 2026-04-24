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
import egx.relab_app.cache.AnalyticsCache
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import org.json.JSONObject
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
    }  private lateinit var analyticsCache: AnalyticsCache

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        analyticsCache = AnalyticsCache(requireContext())
        
        // Сначала загружаем данные из кэша для быстрого отображения
        loadFromCache()
        
        // Затем загружаем свежие данные с сервера
        loadAnalytics()
        setupChart()
    }
    
    /**
     * Загрузить данные из кэша
     */
    private fun loadFromCache() {
        if (!isAdded || _binding == null) return
        
        // Загружаем месячный заработок
        analyticsCache.getMonthlyEarnings()?.let {
            binding.tvTotalEarnings.text = it
        }
        
        // Загружаем выполненные заказы
        analyticsCache.getCompletedOrders()?.let {
            binding.tvCompletedOrders.text = it
        }
        
        // Загружаем созданные заказы
        analyticsCache.getCreatedOrders()?.let {
            binding.tvOrdersCreated.text = it.toString()
        }
        
        // Загружаем эффективность
        analyticsCache.getEmployeeEfficiency()?.let {
            binding.tvEmployeeEfficiency.text = "${"%.1f".format(it)}%"
        }

        // Загружаем среднюю сложность
        analyticsCache.getAverageComplexity()?.let {
            binding.tvAverageComplexity.text = "${"%.1f".format(it)} pts"
        }

        // Загружаем ежедневный заработок
        analyticsCache.getDailyEarnings()?.let { dailyEarnings ->
            val chartData = dailyEarnings.map { (day, earnings) ->
                ApiService.DailyEarningsResponse("", day, earnings)
            }
            updateChart(chartData)
        }

        // Загружаем статистику статусов
        analyticsCache.getOrderStatistics()?.let { jsonString ->
            try {
                val json = JSONObject(jsonString)
                val newCount = json.getInt("new")
                val inProgressCount = json.getInt("in_progress")
                val doneCount = json.getInt("done")
                val pendingCount = json.getInt("pending")
                updatePieChart(newCount, inProgressCount, doneCount, pendingCount)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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

        // Настройка PieChart
        val pieChart = binding.chartOrderDistribution
        pieChart.setUsePercentValues(true)
        pieChart.description.isEnabled = false
        pieChart.isDrawHoleEnabled = true
        pieChart.setHoleColor(Color.WHITE)
        pieChart.setTransparentCircleColor(Color.WHITE)
        pieChart.setTransparentCircleAlpha(110)
        pieChart.holeRadius = 58f
        pieChart.transparentCircleRadius = 61f
        pieChart.setDrawCenterText(true)
        pieChart.centerText = "Статусы"
        pieChart.isRotationEnabled = false
        pieChart.isHighlightPerTapEnabled = true
        pieChart.legend.isEnabled = false
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
                    // Сохраняем в кэш
                    val dailyEarnings = data.map { it.day to it.earnings }
                    analyticsCache.saveDailyEarnings(dailyEarnings)
                } else {
                    // Если ошибка, используем кэш
                    analyticsCache.getDailyEarnings()?.let { cachedData ->
                        val chartData = cachedData.map { (day, earnings) ->
                            ApiService.DailyEarningsResponse("", day, earnings)
                        }
                        updateChart(chartData)
                    } ?: run {
                        updateChart(emptyList())
                    }
                }
            }

            override fun onFailure(call: Call<List<ApiService.DailyEarningsResponse>>, t: Throwable) {
                if (!isAdded || _binding == null) return
                // Если ошибка сети, используем кэш
                analyticsCache.getDailyEarnings()?.let { cachedData ->
                    val chartData = cachedData.map { (day, earnings) ->
                        ApiService.DailyEarningsResponse("", day, earnings)
                    }
                    updateChart(chartData)
                } ?: run {
                    updateChart(emptyList())
                }
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

    private fun updatePieChart(newCount: Int, inProgressCount: Int, doneCount: Int, pendingCount: Int) {
        val pieChart = binding.chartOrderDistribution
        val entries = ArrayList<PieEntry>()

        if (newCount > 0) entries.add(PieEntry(newCount.toFloat(), "Новые"))
        if (inProgressCount > 0) entries.add(PieEntry(inProgressCount.toFloat(), "В работе"))
        if (doneCount > 0) entries.add(PieEntry(doneCount.toFloat(), "Готовы"))
        if (pendingCount > 0) entries.add(PieEntry(pendingCount.toFloat(), "Ожидают"))

        if (entries.isEmpty()) {
            pieChart.data = null
            pieChart.invalidate()
            return
        }

        val dataSet = PieDataSet(entries, "Статусы заказов")
        dataSet.sliceSpace = 3f
        dataSet.selectionShift = 5f

        // Цвета: Синий, Оранжевый, Зеленый, Серый
        val colors = ArrayList<Int>()
        colors.add(Color.parseColor("#42A5F5")) // Новые
        colors.add(Color.parseColor("#FFA726")) // В работе
        colors.add(Color.parseColor("#66BB6A")) // Готовы
        colors.add(Color.parseColor("#BDBDBD")) // Ожидают
        dataSet.colors = colors

        val data = PieData(dataSet)
        data.setValueFormatter(PercentFormatter(pieChart))
        data.setValueTextSize(11f)
        data.setValueTextColor(Color.WHITE)
        pieChart.data = data
        pieChart.invalidate()
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
                    val earningsText = message.replace("Вы заработали: ", "").replace(" ₽", " ₽")
                    binding.tvTotalEarnings.text = earningsText
                    // Сохраняем в кэш
                    analyticsCache.saveMonthlyEarnings(earningsText)
                } else {
                    // Если ошибка, используем кэш
                    analyticsCache.getMonthlyEarnings()?.let {
                        binding.tvTotalEarnings.text = it
                    } ?: run {
                        binding.tvTotalEarnings.text = "0 ₽"
                    }
                }
            }

            override fun onFailure(call: Call<ApiService.EarningsResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                // Если ошибка сети, используем кэш
                analyticsCache.getMonthlyEarnings()?.let {
                    binding.tvTotalEarnings.text = it
                } ?: run {
                    binding.tvTotalEarnings.text = "0 ₽"
                }
            }
        })

        RetrofitClient.apiService.getMonthlyCompletedOrders().enqueue(object : Callback<ApiService.OrdersCountResponse> {
            override fun onResponse(call: Call<ApiService.OrdersCountResponse>, response: Response<ApiService.OrdersCountResponse>) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val message = response.body()?.message ?: "Нет данных"
                    // Извлекаем количество из сообщения, если возможно
                    val countText = message.replace("Вы выполнили заказов: ", "")
                    binding.tvCompletedOrders.text = countText
                    // Сохраняем в кэш
                    analyticsCache.saveCompletedOrders(countText)
                } else {
                    // Если ошибка, используем кэш
                    analyticsCache.getCompletedOrders()?.let {
                        binding.tvCompletedOrders.text = it
                    } ?: run {
                        binding.tvCompletedOrders.text = "0"
                    }
                }
            }

            override fun onFailure(call: Call<ApiService.OrdersCountResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                // Если ошибка сети, используем кэш
                analyticsCache.getCompletedOrders()?.let {
                    binding.tvCompletedOrders.text = it
                } ?: run {
                    binding.tvCompletedOrders.text = "0"
                }
            }
        })
        
        // Загружаем количество созданных заказов
        RetrofitClient.apiService.getCreatedOrdersCount().enqueue(object : Callback<ApiService.CreatedOrdersCountResponse> {
            override fun onResponse(
                call: Call<ApiService.CreatedOrdersCountResponse>,
                response: Response<ApiService.CreatedOrdersCountResponse>
            ) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val count = response.body()?.count ?: 0
                    binding.tvOrdersCreated.text = count.toString()
                    // Сохраняем в кэш
                    analyticsCache.saveCreatedOrders(count)
                } else {
                    // Если ошибка, используем кэш
                    analyticsCache.getCreatedOrders()?.let {
                        binding.tvOrdersCreated.text = it.toString()
                    } ?: run {
                        binding.tvOrdersCreated.text = "0"
                    }
                }
            }

            override fun onFailure(call: Call<ApiService.CreatedOrdersCountResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                // Если ошибка сети, используем кэш
                analyticsCache.getCreatedOrders()?.let {
                    binding.tvOrdersCreated.text = it.toString()
                } ?: run {
                    binding.tvOrdersCreated.text = "0"
                }
            }
        })
        
        // Загружаем эффективность сотрудника
        RetrofitClient.apiService.getEmployeeEfficiency().enqueue(object : Callback<ApiService.EfficiencyResponse> {
            override fun onResponse(
                call: Call<ApiService.EfficiencyResponse>,
                response: Response<ApiService.EfficiencyResponse>
            ) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val efficiency = response.body()?.efficiency ?: 0.0
                    binding.tvEmployeeEfficiency.text = "${"%.1f".format(efficiency)}%"
                    // Сохраняем в кэш
                    analyticsCache.saveEmployeeEfficiency(efficiency)
                } else {
                    // Если ошибка, используем кэш
                    analyticsCache.getEmployeeEfficiency()?.let {
                        binding.tvEmployeeEfficiency.text = "${"%.1f".format(it)}%"
                    } ?: run {
                        binding.tvEmployeeEfficiency.text = "0%"
                    }
                }
            }

            override fun onFailure(call: Call<ApiService.EfficiencyResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                // Если ошибка сети, используем кэш
                analyticsCache.getEmployeeEfficiency()?.let {
                    binding.tvEmployeeEfficiency.text = "${"%.1f".format(it)}%"
                } ?: run {
                    binding.tvEmployeeEfficiency.text = "0%"
                }
            }
        })

        // Загружаем среднюю сложность
        RetrofitClient.apiService.getAverageComplexity().enqueue(object : Callback<ApiService.AverageComplexityResponse> {
            override fun onResponse(call: Call<ApiService.AverageComplexityResponse>, response: Response<ApiService.AverageComplexityResponse>) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val complexity = response.body()?.average_complexity ?: 0.0
                    binding.tvAverageComplexity.text = "${"%.1f".format(complexity)} pts"
                    analyticsCache.saveAverageComplexity(complexity)
                } else {
                    analyticsCache.getAverageComplexity()?.let {
                        binding.tvAverageComplexity.text = "${"%.1f".format(it)} pts"
                    } ?: run { binding.tvAverageComplexity.text = "0 pts" }
                }
            }

            override fun onFailure(call: Call<ApiService.AverageComplexityResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                analyticsCache.getAverageComplexity()?.let {
                    binding.tvAverageComplexity.text = "${"%.1f".format(it)} pts"
                } ?: run { binding.tvAverageComplexity.text = "0 pts" }
            }
        })

        // Загружаем статистику по статусам для круговой диаграммы
        RetrofitClient.apiService.getOrderStatistics().enqueue(object : Callback<ApiService.OrderStatisticsResponse> {
            override fun onResponse(call: Call<ApiService.OrderStatisticsResponse>, response: Response<ApiService.OrderStatisticsResponse>) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val stats = response.body()?.status_statistics
                    if (stats != null) {
                        updatePieChart(stats.new, stats.in_progress, stats.done, stats.pending)
                        
                        val jsonObj = JSONObject()
                        jsonObj.put("new", stats.new)
                        jsonObj.put("in_progress", stats.in_progress)
                        jsonObj.put("done", stats.done)
                        jsonObj.put("pending", stats.pending)
                        analyticsCache.saveOrderStatistics(jsonObj.toString())
                    }
                }
            }

            override fun onFailure(call: Call<ApiService.OrderStatisticsResponse>, t: Throwable) {
                // Ничего не делаем, график просто не обновится, если нет сети, 
                // а кэш уже был загружен в onViewCreated
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
