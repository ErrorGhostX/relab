package egx.relab_app.ui.messaging

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import egx.relab_app.R

class ImageDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_detail)

        val imageUrl = intent.getStringExtra("IMAGE_URL")
        if (imageUrl == null) {
            finish()
            return
        }

        val imageView: ImageView = findViewById(R.id.ivFullScreenImage)
        val btnBack: ImageButton = findViewById(R.id.btnBack)
        val btnDownload: MaterialButton = findViewById(R.id.btnDownload)

        Glide.with(this)
            .load(imageUrl)
            .into(imageView)

        btnBack.setOnClickListener { finish() }

        btnDownload.setOnClickListener {
            downloadImage(imageUrl)
        }
    }

    private fun downloadImage(url: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
            val fileName = url.substringAfterLast("/")
            
            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                .setAllowedOverRoaming(false)
                .setTitle(fileName)
                .setDescription("Загрузка фото из Relab")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "Загрузка началась", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка загрузки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
