package egx.relab_app.ui.orders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import egx.relab_app.databinding.FragmentOrderDetailBinding

class OrderDetailFragment : Fragment() {

    private var _binding: FragmentOrderDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOrderDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args = arguments
        val orderId = args?.getInt("orderId")
        val deviceName = args?.getString("deviceName")
        val status = args?.getString("status")

        binding.textOrderId.text = "ID: $orderId"
        binding.textDeviceName.text = "Устройство: $deviceName"
        binding.textStatus.text = "Статус: $status"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
