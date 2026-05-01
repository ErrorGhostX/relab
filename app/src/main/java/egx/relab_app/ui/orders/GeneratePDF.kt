package egx.relab_app.ui.orders

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfDocument
import androidx.appcompat.content.res.AppCompatResources
import egx.relab_app.models.Order
import java.io.ByteArrayOutputStream

/**
 * Enhanced PDF generator:
 * - draws all user-facing fields (no technical sync fields)
 * - supports VectorDrawable (`logo_relab` in res/drawable)
 * - leaves signature boxes for manual signing (client & employee)
 * - applies complexity coefficient to final total (total * (1 + complexity%/100))
 * - more even/aligned layout with table-like services area
 */
class GeneratePDF(
    private val context: Context
) {

    fun generate(order: Order, isEmployee: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        val document = PdfDocument()

        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f

        var pageNumber = 1
        var page = document.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        )
        var canvas = page.canvas
        var yPos = margin

        fun startNewPage() {
            // draw footer on current page
            drawFooter(canvas, pageWidth, pageHeight, margin)
            document.finishPage(page)

            pageNumber++
            page = document.startPage(
                PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            )
            canvas = page.canvas
            yPos = margin
        }

        fun checkPage(space: Float) {
            if (yPos + space > pageHeight - margin - 70f) { // reserve footer + signatures
                startNewPage()
            }
        }

        yPos = drawHeader(canvas, order, margin)

        checkPage(120f)
        yPos = drawClientSection(canvas, order, margin, yPos)

        checkPage(140f)
        yPos = drawDeviceSection(canvas, order, margin, yPos)

        checkPage(220f)
        yPos = drawServicesTable(canvas, order, margin, yPos, pageWidth.toFloat(), ::checkPage)

        checkPage(80f)
        yPos = drawTotal(canvas, order, margin, pageWidth.toFloat(), yPos)

        checkPage(120f)
        yPos = drawSignatureSection(canvas, margin, pageWidth.toFloat(), pageHeight.toFloat(), yPos)

        drawFooter(canvas, pageWidth, pageHeight, margin)
        document.finishPage(page)
        document.writeTo(output)
        document.close()

        return output.toByteArray()
    }

    // ===================== DRAW METHODS =====================

    private fun drawHeader(canvas: Canvas, order: Order, margin: Float): Float {
        val resId = context.resources.getIdentifier("logo_relab", "drawable", context.packageName)

        val logoBitmap = try {
            if (resId != 0) {
                val drawable: Drawable? = AppCompatResources.getDrawable(context, resId)
                drawableToBitmap(drawable)
            } else null
        } catch (e: Exception) {
            null
        }

        val titlePaint = Paint().apply {
            textSize = 18f
            isFakeBoldText = true
            color = Color.BLACK
            isAntiAlias = true
        }

        var bottom = margin + 40f

        if (logoBitmap != null) {
            val logoWidth = 110f
            val logoHeight = if (logoBitmap.width != 0) logoBitmap.height * logoWidth / logoBitmap.width else logoBitmap.height.toFloat()

            val dst = RectF(margin, margin, margin + logoWidth, margin + logoHeight)
            canvas.drawBitmap(logoBitmap, null, RectFToRect(dst), null)

            canvas.drawText("${order.orderName ?: "Заказ"}", margin + logoWidth + 20f, margin + 28f, titlePaint)
            bottom = margin + logoHeight + 15f
        } else {
            canvas.drawText("Relab — сервисный центр", margin, margin + 28f, titlePaint)
            bottom = margin + 50f
        }

        // line
        canvas.drawLine(margin, bottom, 595f - margin, bottom, Paint().apply { color = Color.LTGRAY; strokeWidth = 1f })

        return bottom + 20f
    }

    private fun drawClientSection(canvas: Canvas, order: Order, margin: Float, y: Float): Float {
        val label = Paint().apply { textSize = 11f; isFakeBoldText = true; isAntiAlias = true }
        val value = Paint().apply { textSize = 11f; isAntiAlias = true }

        var yy = y

        canvas.drawText("Клиент:", margin, yy, label)
        canvas.drawText(order.customer ?: "—", margin + 100f, yy, value)

        yy += 18f
        canvas.drawText("Контакты:", margin, yy, label)
        canvas.drawText(order.contactInfo ?: "—", margin + 100f, yy, value)

        yy += 18f
        canvas.drawText("Мэсэнджер:", margin, yy, label)
        canvas.drawText(order.messenger ?: "—", margin + 100f, yy, value)

        yy += 18f
        canvas.drawText("Дополнительно:", margin, yy, label)
        drawMultilineText(canvas, order.extraInfo ?: "—", margin + 100f, yy - 12f, value, 400f)

        return yy + 36f
    }

    private fun drawDeviceSection(canvas: Canvas, order: Order, margin: Float, y: Float): Float {
        val label = Paint().apply { textSize = 11f; isFakeBoldText = true; isAntiAlias = true }
        val value = Paint().apply { textSize = 11f; isAntiAlias = true }

        var yy = y

        canvas.drawText("Устройство:", margin, yy, label)
        canvas.drawText(order.deviceName ?: "—", margin + 100f, yy, value)

        yy += 18f
        canvas.drawText("Тип:", margin, yy, label)
        canvas.drawText(order.deviceType ?: "—", margin + 100f, yy, value)

        yy += 18f
        canvas.drawText("Производитель:", margin, yy, label)
        canvas.drawText(order.manufacturer ?: "—", margin + 100f, yy, value)

        yy += 18f
        canvas.drawText("Модель / Комплектация:", margin, yy, label)
        canvas.drawText((order.model ?: "—") + (order.kit?.let { " / $it" } ?: ""), margin + 140f, yy, value)

        yy += 18f
        canvas.drawText("Описание проблемы:", margin, yy, label)
        drawMultilineText(canvas, order.description ?: "—", margin + 140f, yy - 12f, value, 380f)

        yy += 36f
        canvas.drawText("Дата:", margin, yy, label)
        canvas.drawText(order.date ?: "—", margin + 100f, yy, value)

        yy += 18f
        canvas.drawText("Статус:", margin, yy, label)
        canvas.drawText(order.status ?: "—", margin + 100f, yy, value)

        yy += 18f
        canvas.drawText("Создал:", margin, yy, label)
        canvas.drawText(order.createdByFullName ?: order.createdByUsername ?: "—", margin + 100f, yy, value)

        yy += 18f
        canvas.drawText("Сложность:", margin, yy, label)
        val complexity = when {
            order.complexityLevel != null -> "${order.complexityLevel} (${order.complexityPercentage?.let { "%.0f%%".format(it) } ?: "—"})"
            order.complexityPercentage != null -> "${"%.0f%%".format(order.complexityPercentage)}"
            else -> "—"
        }
        canvas.drawText(complexity, margin + 100f, yy, value)

        return yy + 24f
    }

    private fun drawServicesTable(canvas: Canvas, order: Order, margin: Float, yStart: Float, pageWidth: Float, checkPage: (Float) -> Unit): Float {
        var y = yStart
        val paint = Paint().apply { textSize = 11f; isAntiAlias = true }

        // table header
        canvas.drawText("Услуга", margin + 4f, y, Paint().apply { textSize = 12f; isFakeBoldText = true })
        val priceColX = pageWidth - margin - 80f
        canvas.drawText("Цена", priceColX + 4f, y, Paint().apply { textSize = 12f; isFakeBoldText = true })
        y += 18f

        // divider line
        canvas.drawLine(margin, y, pageWidth - margin, y, Paint().apply { strokeWidth = 1f; color = Color.LTGRAY })
        y += 8f

        var total = 0.0
        val descWidth = priceColX - (margin + 8f)

        for (service in order.services) {
            checkPage(40f)
            drawMultilineText(canvas, service.description ?: "—", margin + 4f, y - 12f, paint, descWidth)
            val price = "%.2f ₽".format(service.price ?: 0.0)
            canvas.drawText(price, pageWidth - margin - paint.measureText(price), y, paint)

            y += 22f
            total += service.price ?: 0.0

            // subtle separator
            canvas.drawLine(margin + 4f, y - 6f, pageWidth - margin - 4f, y - 6f, Paint().apply { strokeWidth = 0.5f; color = Color.LTGRAY })
        }

        return y
    }

    private fun drawTotal(canvas: Canvas, order: Order, margin: Float, pageWidth: Float, yStart: Float): Float {
        val paint = Paint().apply { textSize = 12f; isFakeBoldText = true; isAntiAlias = true }

        val baseTotal = order.services.sumOf { it.price ?: 0.0 }
        val complexityPct = order.complexityPercentage ?: 0.0
        val coeff = 1.0 + complexityPct / 100.0 // коэффициент умножения
        val adjustedTotal = baseTotal * coeff

        val baseText = "База: %.2f ₽".format(baseTotal)
        canvas.drawText(baseText, pageWidth - margin - paint.measureText(baseText), yStart, Paint().apply { textSize = 11f })

        val coeffText = "Коэфф. (сложность): %.0f%% → x%.2f".format(complexityPct, coeff)
        canvas.drawText(coeffText, margin, yStart + 16f, Paint().apply { textSize = 10f; color = Color.DKGRAY })

        val totalText = "Итого (с учётом сложности): %.2f ₽".format(adjustedTotal)
        canvas.drawText(totalText, pageWidth - margin - paint.measureText(totalText), yStart + 22f, paint)

        return yStart + 36f
    }

    private fun drawSignatureSection(canvas: Canvas, margin: Float, pageWidth: Float, pageHeight: Float, yStart: Float): Float {
        var y = yStart + 10f
        val labelPaint = Paint().apply { textSize = 11f; isFakeBoldText = true }
        val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.DKGRAY }
        val hintPaint = Paint().apply { textSize = 10f; color = Color.DKGRAY }

        val boxWidth = 220f
        val boxHeight = 70f

        // Client signature (left)
        canvas.drawText("Подпись клиента:", margin, y, labelPaint)
        val clientBoxTop = y + 8f
        val clientBoxLeft = margin
        canvas.drawRect(clientBoxLeft, clientBoxTop, clientBoxLeft + boxWidth, clientBoxTop + boxHeight, boxPaint)
        canvas.drawText("(распишитесь ручкой)", clientBoxLeft + 6f, clientBoxTop + boxHeight - 8f, hintPaint)

        // Employee signature (right)
        val empLeft = pageWidth - margin - boxWidth
        canvas.drawText("Подпись сотрудника:", empLeft, y, labelPaint)
        val empTop = y + 8f
        canvas.drawRect(empLeft, empTop, empLeft + boxWidth, empTop + boxHeight, boxPaint)
        canvas.drawText("(распишитесь ручкой)", empLeft + 6f, empTop + boxHeight - 8f, hintPaint)

        // Date lines below signature boxes
        val dateY = clientBoxTop + boxHeight + 18f
        canvas.drawText("Дата:", clientBoxLeft, dateY, labelPaint)
        canvas.drawLine(clientBoxLeft + 36f, dateY + 1f, clientBoxLeft + 150f, dateY + 1f, Paint().apply { strokeWidth = 1f; color = Color.DKGRAY })

        canvas.drawText("Дата:", empLeft, dateY, labelPaint)
        canvas.drawLine(empLeft + 36f, dateY + 1f, empLeft + 150f, dateY + 1f, Paint().apply { strokeWidth = 1f; color = Color.DKGRAY })

        return dateY + 24f
    }

    private fun drawFooter(canvas: Canvas, pageWidth: Int, pageHeight: Int, margin: Float) {
        val paint = Paint().apply { textSize = 10f; color = Color.DKGRAY; isAntiAlias = true }

        // try to draw small logo at footer left
        val resId = context.resources.getIdentifier("logo_relab", "drawable", context.packageName)
        val logoBitmap = try {
            if (resId != 0) {
                val drawable: Drawable? = AppCompatResources.getDrawable(context, resId)
                drawableToBitmap(drawable)
            } else null
        } catch (e: Exception) {
            null
        }

        if (logoBitmap != null) {
            val h = 20f
            val w = if (logoBitmap.width != 0) logoBitmap.width * h / logoBitmap.height else 40f
            val dst = RectF(margin.toFloat(), pageHeight - 45f, margin.toFloat() + w, pageHeight - 25f)
            canvas.drawBitmap(logoBitmap, null, RectFToRect(dst), null)
            canvas.drawText("Relab — сервисный центр", margin.toFloat() + w + 6f, (pageHeight - 30).toFloat(), paint)
        } else {
            canvas.drawText("Relab — сервисный центр", margin.toFloat(), (pageHeight - 30).toFloat(), paint)
        }
    }

    // ======== HELPERS ========

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        try {
            if (drawable is BitmapDrawable) {
                return drawable.bitmap
            }

            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 100
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 100
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        } catch (e: Exception) {
            return null
        }
    }

    private fun drawMultilineText(canvas: Canvas, text: String, x: Float, yStart: Float, paint: Paint, maxWidth: Float) {
        val words = text.split(Regex("\\s+"))
        var line = StringBuilder()
        var y = yStart
        for (w in words) {
            val test = if (line.isEmpty()) w else line.toString() + " " + w
            if (paint.measureText(test) > maxWidth) {
                canvas.drawText(line.toString(), x, y, paint)
                line = StringBuilder(w)
                y += 16f
            } else {
                if (line.isNotEmpty()) line.append(" ")
                line.append(w)
            }
        }
        if (line.isNotEmpty()) {
            canvas.drawText(line.toString(), x, y, paint)
            y += 16f
        }
    }

    private fun RectFToRect(rf: RectF): Rect {
        return Rect(rf.left.toInt(), rf.top.toInt(), rf.right.toInt(), rf.bottom.toInt())
    }
}