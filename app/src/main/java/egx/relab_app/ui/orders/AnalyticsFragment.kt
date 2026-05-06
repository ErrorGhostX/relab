package egx.relab_app.ui.orders

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import egx.relab_app.R
import egx.relab_app.network.ApiService
import egx.relab_app.databinding.FragmentAnalyticsBinding
import egx.relab_app.network.RetrofitClient
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.Legend
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import egx.relab_app.storage.TokenManager
import android.widget.ArrayAdapter

class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!
    private lateinit var analyticsCache: AnalyticsCache
    private lateinit var tokenManager: TokenManager
    
    private var targetUserId: Int? = null
    private var isCompanyWide: Boolean = false
    private var staffList: List<ApiService.StaffPerformance> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        analyticsCache = AnalyticsCache(requireContext())
        tokenManager = TokenManager(requireContext())
        
        val argId = arguments?.getInt("userId", -1) ?: -1
        targetUserId = if (argId != -1) argId else null
        
        setupChart()
        setupUI()
        
        // Сначала загружаем данные из кэша для быстрого отображения (только для своей аналитики)
        if (targetUserId == null) {
            loadFromCache()
        }
        
        // Затем загружаем свежие данные с сервера
        loadAnalytics()
    }
    
    private fun setupUI() {
        if (targetUserId != null) {
            val userName = arguments?.getString("userName") ?: "Сотрудник"
            binding.tvAnalyticsTitle.text = "Аналитика: $userName"
            binding.tvAnalyticsTitle.visibility = View.VISIBLE
            binding.toggleGroupAnalytics.visibility = View.GONE
            binding.employeeSelectorLayout.visibility = View.GONE
        } else if (tokenManager.rank == "admin") {
            binding.toggleGroupAnalytics.visibility = View.VISIBLE
            binding.employeeSelectorLayout.visibility = View.VISIBLE
            
            // Загрузка списка сотрудников для выпадающего списка
            loadStaffList()
            
            binding.dropdownEmployee.setOnItemClickListener { _, _, position, _ ->
                if (position == 0) {
                    targetUserId = null
                } else {
                    targetUserId = staffList[position - 1].user_id
                }
                loadAnalytics()
            }
            
            binding.toggleGroupAnalytics.addOnButtonCheckedListener { group, checkedId, isChecked ->
                if (isChecked) {
                    when (checkedId) {
                        R.id.btnMyAnalytics -> {
                            isCompanyWide = false
                            binding.employeeSelectorLayout.visibility = View.VISIBLE
                            loadAnalytics()
                        }
                        R.id.btnCompanyAnalytics -> {
                            isCompanyWide = true
                            binding.employeeSelectorLayout.visibility = View.GONE
                            loadAnalytics()
                        }
                    }
                }
            }
        } else {
            binding.employeeSelectorLayout.visibility = View.GONE
        }
    }
    
    private fun loadStaffList() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val data = RetrofitClient.apiService.getStaffPerformance()
                withContext(Dispatchers.Main) {
                    if (isAdded && _binding != null) {
                        staffList = data
                        val names = mutableListOf("Моя аналитика")
                        names.addAll(data.map { it.full_name ?: it.username ?: "Сотрудник" })
                        
                        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
                        binding.dropdownEmployee.setAdapter(adapter)
                        binding.dropdownEmployee.setText("Моя аналитика", false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Ошибка загрузки списка: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * Загрузить данные из кэша
     */
    private fun loadFromCache() {
        if (!isAdded || _binding == null) return
        
        analyticsCache.getMonthlyEarnings()?.let { binding.tvTotalEarnings.text = it }
        analyticsCache.getCompletedOrders()?.let { binding.tvCompletedOrders.text = it }
        analyticsCache.getCreatedOrders()?.let { binding.tvOrdersCreated.text = it.toString() }
        analyticsCache.getEmployeeEfficiency()?.let { binding.tvEmployeeEfficiency.text = "${"%.1f".format(it)}%" }
        analyticsCache.getAverageComplexity()?.let { binding.tvAverageComplexity.text = "${"%.1f".format(it)} pts" }

        analyticsCache.getDailyEarnings()?.let { dailyEarnings ->
            val chartData = dailyEarnings.map { (day, earnings) -> ApiService.DailyEarningsResponse("", day, earnings) }
            updateChart(chartData)
        }

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
        pieChart.centerText = "Заказы"
        pieChart.setCenterTextSize(16f)
        pieChart.isRotationEnabled = true
        pieChart.isHighlightPerTapEnabled = true
        pieChart.setEntryLabelColor(Color.BLACK)
        pieChart.setEntryLabelTextSize(10f)
        
        val legend = pieChart.legend
        legend.verticalAlignment = Legend.LegendVerticalAlignment.TOP
        legend.horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
        legend.orientation = Legend.LegendOrientation.VERTICAL
        legend.setDrawInside(false)
        legend.isEnabled = true
    }
    
    private fun loadChartData() {
        val uid = if (isCompanyWide) null else targetUserId
        val cw = if (isCompanyWide) true else null
        
        RetrofitClient.apiService.getDailyEarnings(uid, cw).enqueue(object : Callback<List<ApiService.DailyEarningsResponse>> {
            override fun onResponse(call: Call<List<ApiService.DailyEarningsResponse>>, response: Response<List<ApiService.DailyEarningsResponse>>) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val data = response.body() ?: emptyList()
                    updateChart(data)
                    if (uid == null && !isCompanyWide) {
                        val dailyEarnings = data.map { it.day to it.earnings }
                        analyticsCache.saveDailyEarnings(dailyEarnings)
                    }
                } else if (uid == null && !isCompanyWide) {
                    analyticsCache.getDailyEarnings()?.let { cachedData ->
                        val chartData = cachedData.map { (day, earnings) -> ApiService.DailyEarningsResponse("", day, earnings) }
                        updateChart(chartData)
                    } ?: run { updateChart(emptyList()) }
                } else {
                    updateChart(emptyList())
                }
            }

            override fun onFailure(call: Call<List<ApiService.DailyEarningsResponse>>, t: Throwable) {
                if (!isAdded || _binding == null) return
                if (uid == null && !isCompanyWide) {
                    analyticsCache.getDailyEarnings()?.let { cachedData ->
                        val chartData = cachedData.map { (day, earnings) -> ApiService.DailyEarningsResponse("", day, earnings) }
                        updateChart(chartData)
                    } ?: run { updateChart(emptyList()) }
                } else {
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
            mode = LineDataSet.Mode.LINEAR
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
            pieChart.centerText = "Нет заказов"
            pieChart.invalidate()
            return
        }

        pieChart.centerText = "Статусы"
        val dataSet = PieDataSet(entries, "")
        dataSet.sliceSpace = 3f
        dataSet.selectionShift = 5f

        val colors = ArrayList<Int>()
        if (newCount > 0) colors.add(Color.parseColor("#42A5F5"))
        if (inProgressCount > 0) colors.add(Color.parseColor("#FFA726"))
        if (doneCount > 0) colors.add(Color.parseColor("#66BB6A"))
        if (pendingCount > 0) colors.add(Color.parseColor("#BDBDBD"))
        dataSet.colors = colors

        val data = PieData(dataSet)
        data.setValueFormatter(PercentFormatter(pieChart))
        data.setValueTextSize(12f)
        data.setValueTextColor(Color.BLACK)
        
        pieChart.data = data
        pieChart.highlightValues(null)
        pieChart.invalidate()
    }

    private fun loadAnalytics() {
        if (!isAdded) return
        
        loadChartData()
        
        val uid = if (isCompanyWide) null else targetUserId
        val cw = if (isCompanyWide) true else null
        
        RetrofitClient.apiService.getMonthlyEarnings(uid, cw).enqueue(object : Callback<ApiService.EarningsResponse> {
            override fun onResponse(call: Call<ApiService.EarningsResponse>, response: Response<ApiService.EarningsResponse>) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val message = response.body()?.message ?: "Нет данных"
                    val earningsText = message.replace("Вы заработали: ", "").replace("Доход компании: ", "").replace(" ₽", " ₽")
                    binding.tvTotalEarnings.text = earningsText
                    if (uid == null && !isCompanyWide) analyticsCache.saveMonthlyEarnings(earningsText)
                } else {
                    if (uid == null && !isCompanyWide) {
                        analyticsCache.getMonthlyEarnings()?.let { binding.tvTotalEarnings.text = it } ?: run { binding.tvTotalEarnings.text = "0 ₽" }
                    } else {
                        binding.tvTotalEarnings.text = "0 ₽"
                    }
                }
            }

            override fun onFailure(call: Call<ApiService.EarningsResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                if (uid == null && !isCompanyWide) {
                    analyticsCache.getMonthlyEarnings()?.let { binding.tvTotalEarnings.text = it } ?: run { binding.tvTotalEarnings.text = "0 ₽" }
                } else {
                    binding.tvTotalEarnings.text = "0 ₽"
                }
            }
        })

        RetrofitClient.apiService.getMonthlyCompletedOrders(uid, cw).enqueue(object : Callback<ApiService.OrdersCountResponse> {
            override fun onResponse(call: Call<ApiService.OrdersCountResponse>, response: Response<ApiService.OrdersCountResponse>) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val message = response.body()?.message ?: "Нет данных"
                    val countText = message.replace("Вы выполнили заказов: ", "").replace("Выполнено компанией: ", "")
                    binding.tvCompletedOrders.text = countText
                    if (uid == null && !isCompanyWide) analyticsCache.saveCompletedOrders(countText)
                } else {
                    if (uid == null && !isCompanyWide) {
                        analyticsCache.getCompletedOrders()?.let { binding.tvCompletedOrders.text = it } ?: run { binding.tvCompletedOrders.text = "0" }
                    } else {
                        binding.tvCompletedOrders.text = "0"
                    }
                }
            }

            override fun onFailure(call: Call<ApiService.OrdersCountResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                if (uid == null && !isCompanyWide) {
                    analyticsCache.getCompletedOrders()?.let { binding.tvCompletedOrders.text = it } ?: run { binding.tvCompletedOrders.text = "0" }
                } else {
                    binding.tvCompletedOrders.text = "0"
                }
            }
        })
        
        RetrofitClient.apiService.getCreatedOrdersCount(uid, cw).enqueue(object : Callback<ApiService.CreatedOrdersCountResponse> {
            override fun onResponse(call: Call<ApiService.CreatedOrdersCountResponse>, response: Response<ApiService.CreatedOrdersCountResponse>) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val count = response.body()?.count ?: 0
                    binding.tvOrdersCreated.text = count.toString()
                    if (uid == null && !isCompanyWide) analyticsCache.saveCreatedOrders(count)
                } else {
                    if (uid == null && !isCompanyWide) {
                        analyticsCache.getCreatedOrders()?.let { binding.tvOrdersCreated.text = it.toString() } ?: run { binding.tvOrdersCreated.text = "0" }
                    } else {
                        binding.tvOrdersCreated.text = "0"
                    }
                }
            }

            override fun onFailure(call: Call<ApiService.CreatedOrdersCountResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                if (uid == null && !isCompanyWide) {
                    analyticsCache.getCreatedOrders()?.let { binding.tvOrdersCreated.text = it.toString() } ?: run { binding.tvOrdersCreated.text = "0" }
                } else {
                    binding.tvOrdersCreated.text = "0"
                }
            }
        })
        
        RetrofitClient.apiService.getEmployeeEfficiency(uid, cw).enqueue(object : Callback<ApiService.EfficiencyResponse> {
            override fun onResponse(call: Call<ApiService.EfficiencyResponse>, response: Response<ApiService.EfficiencyResponse>) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val efficiency = response.body()?.efficiency ?: 0.0
                    binding.tvEmployeeEfficiency.text = "${"%.1f".format(efficiency)}%"
                    if (uid == null && !isCompanyWide) analyticsCache.saveEmployeeEfficiency(efficiency)
                } else {
                    if (uid == null && !isCompanyWide) {
                        analyticsCache.getEmployeeEfficiency()?.let { binding.tvEmployeeEfficiency.text = "${"%.1f".format(it)}%" } ?: run { binding.tvEmployeeEfficiency.text = "0%" }
                    } else {
                        binding.tvEmployeeEfficiency.text = "0%"
                    }
                }
            }

            override fun onFailure(call: Call<ApiService.EfficiencyResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                if (uid == null && !isCompanyWide) {
                    analyticsCache.getEmployeeEfficiency()?.let { binding.tvEmployeeEfficiency.text = "${"%.1f".format(it)}%" } ?: run { binding.tvEmployeeEfficiency.text = "0%" }
                } else {
                    binding.tvEmployeeEfficiency.text = "0%"
                }
            }
        })

        RetrofitClient.apiService.getAverageComplexity(uid, cw).enqueue(object : Callback<ApiService.AverageComplexityResponse> {
            override fun onResponse(call: Call<ApiService.AverageComplexityResponse>, response: Response<ApiService.AverageComplexityResponse>) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val complexity = response.body()?.average_complexity ?: 0.0
                    binding.tvAverageComplexity.text = "${"%.1f".format(complexity)} pts"
                    if (uid == null && !isCompanyWide) analyticsCache.saveAverageComplexity(complexity)
                } else {
                    if (uid == null && !isCompanyWide) {
                        analyticsCache.getAverageComplexity()?.let { binding.tvAverageComplexity.text = "${"%.1f".format(it)} pts" } ?: run { binding.tvAverageComplexity.text = "0 pts" }
                    } else {
                        binding.tvAverageComplexity.text = "0 pts"
                    }
                }
            }

            override fun onFailure(call: Call<ApiService.AverageComplexityResponse>, t: Throwable) {
                if (!isAdded || _binding == null) return
                if (uid == null && !isCompanyWide) {
                    analyticsCache.getAverageComplexity()?.let { binding.tvAverageComplexity.text = "${"%.1f".format(it)} pts" } ?: run { binding.tvAverageComplexity.text = "0 pts" }
                } else {
                    binding.tvAverageComplexity.text = "0 pts"
                }
            }
        })

        RetrofitClient.apiService.getOrderStatistics(uid, cw).enqueue(object : Callback<ApiService.OrderStatisticsResponse> {
            override fun onResponse(call: Call<ApiService.OrderStatisticsResponse>, response: Response<ApiService.OrderStatisticsResponse>) {
                if (!isAdded || _binding == null) return
                if (response.isSuccessful) {
                    val stats = response.body()?.status_statistics
                    if (stats != null) {
                        updatePieChart(stats.new, stats.in_progress, stats.done, stats.pending)
                        if (uid == null && !isCompanyWide) {
                            val jsonObj = JSONObject()
                            jsonObj.put("new", stats.new)
                            jsonObj.put("in_progress", stats.in_progress)
                            jsonObj.put("done", stats.done)
                            jsonObj.put("pending", stats.pending)
                            analyticsCache.saveOrderStatistics(jsonObj.toString())
                        }
                    } else {
                        updatePieChart(0, 0, 0, 0)
                    }
                } else {
                    updatePieChart(0, 0, 0, 0)
                }
            }

            override fun onFailure(call: Call<ApiService.OrderStatisticsResponse>, t: Throwable) { 
                if (isAdded && _binding != null) {
                    updatePieChart(0, 0, 0, 0)
                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
