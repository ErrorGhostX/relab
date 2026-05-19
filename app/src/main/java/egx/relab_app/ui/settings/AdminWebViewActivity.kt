package egx.relab_app.ui.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import egx.relab_app.databinding.ActivityAdminWebviewBinding

class AdminWebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminWebviewBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra("EXTRA_URL") ?: ""

        // Обработка системного жеста "Назад"
        val backPressedCallback = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleWebViewBack()
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        // Настройка кнопки назад в UI
        binding.btnBack.setOnClickListener {
            handleWebViewBack()
        }

        // Настройка WebView
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            
            // Включаем поддержку cookies для сохранения сессии Django
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }

        if (url.isNotEmpty()) {
            binding.webView.loadUrl(url)
        }
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }

    private fun handleWebViewBack() {
        val currentUrl = binding.webView.url
        // Если можно вернуться назад И мы не на главной странице админки
        if (binding.webView.canGoBack() && currentUrl != null && !currentUrl.endsWith("/admin/")) {
            binding.webView.goBack()
        } else {
            finish()
        }
    }
}
