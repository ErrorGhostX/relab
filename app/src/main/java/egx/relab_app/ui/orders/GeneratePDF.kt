package egx.relab_app.ui.orders

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfDocument
import androidx.appcompat.content.res.AppCompatResources
import egx.relab_app.R
import egx.relab_app.models.Order
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream

/**
 * Генератор PDF-отчётов заказов
 *
 * - Иконка Relab сверху слева
 * - Чистый, структурированный документ
 * - Секции: клиент, устройство, услуги (таблица), итого
 * - Поля для подписей клиента и сотрудника
 */
class GeneratePDF(
    private val context: Context
) {

    companion object {
        private const val PAGE_W = 595
        private const val PAGE_H = 842
        private const val MARGIN = 40f
        private const val LINE_H = 18f
        
        // Цвета
        private val COLOR_PRIMARY = Color.parseColor("#1E293B")
        private val COLOR_SECONDARY = Color.parseColor("#64748B")
        private val COLOR_ACCENT = Color.parseColor("#3B82F6")
        private val COLOR_DIVIDER = Color.parseColor("#E2E8F0")
        private val COLOR_LIGHT_BG = Color.parseColor("#F8FAFC")
    }

    // Переиспользуемые Paint-объекты
    private val paintTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f; color = COLOR_PRIMARY; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val paintSection = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f; color = COLOR_ACCENT; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f; color = COLOR_SECONDARY
    }
    private val paintValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f; color = COLOR_PRIMARY
    }
    private val paintValueBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f; color = COLOR_PRIMARY; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val paintSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f; color = COLOR_SECONDARY
    }
    private val paintDivider = Paint().apply {
        color = COLOR_DIVIDER; strokeWidth = 1f
    }

    enum class DocType { REPORT, RECEIPT }

    fun generate(order: Order, isEmployee: Boolean, type: DocType = DocType.REPORT): ByteArray {
        val output = ByteArrayOutputStream()
        val document = PdfDocument()

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN

        fun newPage() {
            drawFooter(canvas, pageNumber)
            document.finishPage(page)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
            canvas = page.canvas
            y = MARGIN
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > PAGE_H - MARGIN - 60f) newPage()
        }

        // ============ HEADER ============
        y = drawHeader(canvas, order, type)

        // ============ КЛИЕНТ ============
        ensureSpace(100f)
        y = drawSectionTitle(canvas, "КЛИЕНТ", y)
        y = drawField(canvas, "ФИО / Название", order.customer ?: "—", y)
        y = drawField(canvas, "Контакты", order.contactInfo ?: "—", y)
        if (type == DocType.REPORT) {
            y = drawField(canvas, "Мессенджер", order.messenger ?: "—", y)
            if (!order.extraInfo.isNullOrBlank()) {
                y = drawField(canvas, "Дополнительно", order.extraInfo, y)
            }
        }
        y += 8f

        // ============ УСТРОЙСТВО ============
        ensureSpace(140f)
        y = drawSectionTitle(canvas, "УСТРОЙСТВО", y)
        y = drawField(canvas, "Название", order.deviceName ?: "—", y)
        if (!order.deviceType.isNullOrBlank()) y = drawField(canvas, "Тип", order.deviceType, y)
        if (!order.manufacturer.isNullOrBlank()) y = drawField(canvas, "Производитель", order.manufacturer, y)
        if (!order.model.isNullOrBlank()) y = drawField(canvas, "Модель", order.model, y)
        if (type == DocType.REPORT && !order.kit.isNullOrBlank()) y = drawField(canvas, "Комплектация", order.kit, y)
        
        val descLabel = if (type == DocType.RECEIPT) "Неисправность" else "Описание проблемы"
        y = drawField(canvas, descLabel, order.description ?: "—", y)
        y += 8f

        // ============ ИНФОРМАЦИЯ О ЗАКАЗЕ ============
        ensureSpace(100f)
        y = drawSectionTitle(canvas, "ИНФОРМАЦИЯ", y)
        y = drawField(canvas, "Дата создания", order.date ?: "—", y)
        if (type == DocType.REPORT && isEmployee) {
            y = drawField(canvas, "Статус", translateStatus(order.status), y)
            y = drawField(canvas, "Создал", order.createdByFullName ?: order.createdByUsername ?: "—", y)
            val complexity = order.complexityPercentage?.let { "%.0f%%".format(it) } ?: "—"
            y = drawField(canvas, "Сложность", complexity, y)
        }
        if (!order.address.isNullOrBlank()) y = drawField(canvas, "Адрес", order.address, y)
        y += 8f

        // ============ УСЛУГИ (ТАБЛИЦА) ============
        ensureSpace(80f)
        y = drawSectionTitle(canvas, "ОКАЗАННЫЕ УСЛУГИ", y)
        y = drawServicesTable(canvas, order, y) { ensureSpace(it) }

        // ============ ИТОГО ============
        ensureSpace(60f)
        y = drawTotalSection(canvas, order, y)

        // ============ QR CODE ============
        // QR-код теперь рисуется в заголовке (drawHeader), но если нужно дополнительно внизу:
        /*
        ensureSpace(110f)
        drawQrCode(canvas, order, y)
        */


        // ============ ПОДПИСИ ============
        ensureSpace(130f)
        y = drawSignatures(canvas, y)

        drawFooter(canvas, pageNumber)
        document.finishPage(page)
        document.writeTo(output)
        document.close()

        return output.toByteArray()
    }

    fun generate(order: Order, isEmployee: Boolean): ByteArray {
        return generate(order, isEmployee, DocType.REPORT)
    }

    // ======================= DRAW METHODS =======================

    private fun drawHeader(canvas: Canvas, order: Order, type: DocType): Float {
        var y = MARGIN

        // Логотип Relab
        val logoBitmap = loadLogoBitmap()
        if (logoBitmap != null) {
            val logoH = 44f
            val logoW = logoBitmap.width * logoH / logoBitmap.height
            val dst = Rect(MARGIN.toInt(), y.toInt(), (MARGIN + logoW).toInt(), (y + logoH).toInt())
            canvas.drawBitmap(logoBitmap, null, dst, null)
            
            // Название справа от лого
            canvas.drawText("Relab", MARGIN + logoW + 12f, y + 20f, paintTitle)
            val subTitle = if (type == DocType.RECEIPT) "Квитанция приема" else "Отчёт по заказу"
            canvas.drawText(subTitle, MARGIN + logoW + 12f, y + 36f, paintSmall)
            y += logoH + 8f
        } else {
            val title = if (type == DocType.RECEIPT) "Relab — Квитанция" else "Relab — Отчёт"
            canvas.drawText(title, MARGIN, y + 20f, paintTitle)
            y += 30f
        }

        // Номер заказа справа (чуть ниже QR)
        val orderLabel = order.orderName ?: if (order.id != null && order.id > 0) "Заказ #${order.id}" else "Локальный заказ"
        val orderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f; color = COLOR_ACCENT; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val orderW = orderPaint.measureText(orderLabel)
        
        // Рисуем QR код справа вверху
        val qrSize = 100
        val qrX = PAGE_W - MARGIN - qrSize
        val qrY = MARGIN
        drawQrCodeAt(canvas, order, qrX, qrY, qrSize)

        // Номер заказа под QR
        canvas.drawText(orderLabel, PAGE_W - MARGIN - orderW, qrY + qrSize + 16f, orderPaint)
        
        // Обновляем Y чтобы заголовок не наезжал
        val headerEnd = qrY + qrSize + 20f
        if (y < headerEnd) y = headerEnd


        // Линия-разделитель
        y += 6f
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, paintDivider)
        y += 16f

        return y
    }

    private fun drawSectionTitle(canvas: Canvas, title: String, yStart: Float): Float {
        var y = yStart
        canvas.drawText(title, MARGIN, y, paintSection)
        y += 4f
        canvas.drawLine(MARGIN, y, MARGIN + paintSection.measureText(title), y, Paint().apply {
            color = COLOR_ACCENT; strokeWidth = 1.5f
        })
        y += 14f
        return y
    }

    private fun drawField(canvas: Canvas, label: String, value: String, yStart: Float): Float {
        var y = yStart
        canvas.drawText(label, MARGIN, y, paintLabel)
        
        // Если значение длинное — переносим
        val valueX = MARGIN + 120f
        val maxW = PAGE_W - MARGIN - valueX
        y = drawWrappedText(canvas, value, valueX, y, paintValue, maxW)
        y += LINE_H
        return y
    }

    private fun drawServicesTable(canvas: Canvas, order: Order, yStart: Float, ensureSpace: (Float) -> Unit): Float {
        var y = yStart

        if (order.services.isEmpty()) {
            canvas.drawText("Нет услуг", MARGIN + 4f, y, paintValue)
            return y + LINE_H + 8f
        }

        val colDesc = MARGIN
        val colPrice = PAGE_W - MARGIN - 80f

        // Заголовок таблицы
        val bgRect = RectF(MARGIN, y - 12f, PAGE_W - MARGIN, y + 6f)
        canvas.drawRoundRect(bgRect, 4f, 4f, Paint().apply { color = COLOR_LIGHT_BG })
        canvas.drawText("Описание", colDesc + 8f, y, paintValueBold)
        canvas.drawText("Цена", colPrice + 4f, y, paintValueBold)
        y += 14f

        // Строки
        var total = 0.0
        for (service in order.services) {
            ensureSpace(30f)
            
            // Описание
            val desc = service.description ?: "—"
            val descMaxW = colPrice - colDesc - 16f
            val descEnd = drawWrappedText(canvas, desc, colDesc + 8f, y, paintValue, descMaxW)
            
            // Цена
            val price = service.price ?: 0.0
            val priceStr = "%.0f ₽".format(price)
            val priceW = paintValue.measureText(priceStr)
            canvas.drawText(priceStr, PAGE_W - MARGIN - priceW - 4f, y, paintValue)
            
            total += price
            y = descEnd + 6f

            // Тонкая линия
            canvas.drawLine(colDesc + 8f, y, PAGE_W - MARGIN - 4f, y, Paint().apply {
                strokeWidth = 0.5f; color = COLOR_DIVIDER
            })
            y += 8f
        }

        return y
    }

    private fun drawTotalSection(canvas: Canvas, order: Order, yStart: Float): Float {
        var y = yStart

        val baseTotal = order.services.sumOf { it.price ?: 0.0 }
        val complexityPct = order.complexityPercentage ?: 0.0
        val coeff = 1.0 + complexityPct / 100.0
        val adjustedTotal = baseTotal * coeff

        // Линия перед итого
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, paintDivider)
        y += 16f

        if (complexityPct > 0) {
            val baseStr = "Сумма услуг: %.0f ₽".format(baseTotal)
            canvas.drawText(baseStr, PAGE_W - MARGIN - paintValue.measureText(baseStr), y, paintValue)
            y += LINE_H

            val coeffStr = "Коэффициент сложности: %.0f%% (×%.2f)".format(complexityPct, coeff)
            canvas.drawText(coeffStr, PAGE_W - MARGIN - paintSmall.measureText(coeffStr), y, paintSmall)
            y += LINE_H
        }

        val totalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f; color = COLOR_PRIMARY; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val totalStr = "ИТОГО: %.0f ₽".format(adjustedTotal)
        val totalW = totalPaint.measureText(totalStr)
        canvas.drawText(totalStr, PAGE_W - MARGIN - totalW, y, totalPaint)
        y += 24f

        return y
    }

    private fun drawSignatures(canvas: Canvas, yStart: Float): Float {
        var y = yStart + 12f

        // Линия-разделитель
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, paintDivider)
        y += 20f

        val boxW = 200f
        val boxH = 60f
        val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = COLOR_SECONDARY }

        // Подпись клиента (слева)
        canvas.drawText("Подпись клиента:", MARGIN, y, paintLabel)
        y += 8f
        canvas.drawRoundRect(RectF(MARGIN, y, MARGIN + boxW, y + boxH), 6f, 6f, boxPaint)
        canvas.drawText("(распишитесь ручкой)", MARGIN + 8f, y + boxH - 8f, paintSmall)

        // Подпись сотрудника (справа)
        val empLeft = PAGE_W - MARGIN - boxW
        canvas.drawText("Подпись сотрудника:", empLeft, y - 8f, paintLabel)
        canvas.drawRoundRect(RectF(empLeft, y, empLeft + boxW, y + boxH), 6f, 6f, boxPaint)
        canvas.drawText("(распишитесь ручкой)", empLeft + 8f, y + boxH - 8f, paintSmall)

        y += boxH + 14f

        // Дата
        canvas.drawText("Дата: __________________", MARGIN, y, paintLabel)
        canvas.drawText("Дата: __________________", empLeft, y, paintLabel)

        return y + 20f
    }

    private fun drawQrCodeAt(canvas: Canvas, order: Order, x: Float, y: Float, size: Int) {
        val qrContent = if (order.id != null && order.id > 0) {
            "relab://order/${order.id}"
        } else {
            "relab://order/local/${order.localId ?: 0}"
        }
        val qrBitmap = generateQrCodeBitmap(qrContent, size)
        
        if (qrBitmap != null) {
            canvas.drawBitmap(qrBitmap, x, y, null)
            // Подпись под QR
            val idText = if (order.id != null && order.id > 0) "ID: ${order.id}" else "Локальный ID"
            val idW = paintSmall.measureText(idText)
            canvas.drawText(idText, x + (size - idW) / 2f, y + size + 10f, paintSmall)
        }
    }

    private fun drawQrCode(canvas: Canvas, order: Order, yStart: Float): Float {
        val size = 100
        val x = PAGE_W - MARGIN - size
        drawQrCodeAt(canvas, order, x, yStart, size)
        return yStart + size + 20f
    }

    private fun generateQrCodeBitmap(content: String, size: Int): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun drawFooter(canvas: Canvas, pageNum: Int) {
        val y = PAGE_H - 30f
        canvas.drawLine(MARGIN, y - 8f, PAGE_W - MARGIN, y - 8f, paintDivider)
        
        val logoBitmap = loadLogoBitmap()
        if (logoBitmap != null) {
            val h = 16f
            val w = logoBitmap.width * h / logoBitmap.height
            val dst = Rect(MARGIN.toInt(), (y - 4f).toInt(), (MARGIN + w).toInt(), (y + h - 4f).toInt())
            canvas.drawBitmap(logoBitmap, null, dst, null)
            canvas.drawText("Relab — Сервисный центр", MARGIN + w + 6f, y + 6f, paintSmall)
        } else {
            canvas.drawText("Relab — Сервисный центр", MARGIN, y + 6f, paintSmall)
        }

        val pageStr = "стр. $pageNum"
        val pageW = paintSmall.measureText(pageStr)
        canvas.drawText(pageStr, PAGE_W - MARGIN - pageW, y + 6f, paintSmall)
    }

    // ======================= HELPERS =======================

    private fun translateStatus(status: String?): String = when (status) {
        "new" -> "Новый"
        "in_progress" -> "В работе"
        "waiting_parts" -> "Ожидание запчастей"
        "done" -> "Выполнен"
        "cancelled" -> "Отменён"
        "delivered" -> "Выдан"
        else -> status ?: "—"
    }

    private fun loadLogoBitmap(): Bitmap? {
        return try {
            val drawable: Drawable? = AppCompatResources.getDrawable(context, R.drawable.relab)
            drawableToBitmap(drawable)
        } catch (e: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable) return drawable.bitmap
        return try {
            val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 100
            val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 100
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun drawWrappedText(canvas: Canvas, text: String, x: Float, yStart: Float, paint: Paint, maxWidth: Float): Float {
        val words = text.split(Regex("\\s+"))
        var line = StringBuilder()
        var y = yStart
        for (w in words) {
            val test = if (line.isEmpty()) w else "${line} $w"
            if (paint.measureText(test) > maxWidth && line.isNotEmpty()) {
                canvas.drawText(line.toString(), x, y, paint)
                line = StringBuilder(w)
                y += LINE_H - 2f
            } else {
                if (line.isNotEmpty()) line.append(" ")
                line.append(w)
            }
        }
        if (line.isNotEmpty()) {
            canvas.drawText(line.toString(), x, y, paint)
        }
        return y
    }
}