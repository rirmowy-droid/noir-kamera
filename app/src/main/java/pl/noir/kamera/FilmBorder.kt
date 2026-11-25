package pl.noir.kamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Odbitka z ramką negatywu 35 mm: czarny brzeg, perforacja po długich bokach,
 * numer klatki i napisy na brzegu taśmy, lekko postrzępiona krawędź kadru.
 */
object FilmBorder {

    fun apply(src: Bitmap, frameNo: Int, stockName: String): Bitmap {
        val w = src.width
        val h = src.height
        val shortSide = min(w, h)
        val margin = (shortSide * 0.035f).toInt()
        val strip = (shortSide * 0.12f).toInt()
        val landscape = w >= h
        val outW = if (landscape) w + 2 * margin else w + 2 * strip
        val outH = if (landscape) h + 2 * strip else h + 2 * margin
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val left = if (landscape) margin else strip
        val top = if (landscape) strip else margin
        c.drawBitmap(src, left.toFloat(), top.toFloat(), null)

        // postrzępiona krawędź okienka kadru
        val rnd = Random(frameNo * 31 + 7)
        val black = Paint().apply { color = Color.BLACK }
        val bite = max(2f, shortSide * 0.0025f)
        var x = left.toFloat()
        while (x < left + w) {
            c.drawRect(x, top.toFloat(), x + bite * 3, top + rnd.nextFloat() * bite, black)
            c.drawRect(x, top + h - rnd.nextFloat() * bite, x + bite * 3, (top + h).toFloat(), black)
            x += bite * 3
        }
        var y = top.toFloat()
        while (y < top + h) {
            c.drawRect(left.toFloat(), y, left + rnd.nextFloat() * bite, y + bite * 3, black)
            c.drawRect(left + w - rnd.nextFloat() * bite, y, (left + w).toFloat(), y + bite * 3, black)
            y += bite * 3
        }

        // pasy z perforacją rysujemy zawsze "poziomo"; dla pionowego kadru obracamy płótno
        c.save()
        val longLen: Int
        val crossLen: Int
        val imgStart: Int
        val imgLen: Int
        if (landscape) {
            longLen = outW; crossLen = outH; imgStart = left; imgLen = w
        } else {
            c.translate(outW.toFloat(), 0f)
            c.rotate(90f)
            longLen = outH; crossLen = outW; imgStart = top; imgLen = h
        }
        drawStrip(c, 0f, strip.toFloat(), longLen, imgStart, imgLen, frameNo, stockName, true)
        drawStrip(c, (crossLen - strip).toFloat(), strip.toFloat(), longLen, imgStart, imgLen, frameNo, stockName, false)
        c.restore()
        return out
    }

    private fun drawStrip(
        c: Canvas, y0: Float, stripH: Float, longLen: Int, imgStart: Int, imgLen: Int,
        frameNo: Int, stockName: String, outerTop: Boolean
    ) {
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 205, 200) }
        val pitch = imgLen / 8f
        val holeW = pitch * 0.42f
        val holeH = stripH * 0.38f
        val holeY = y0 + (stripH - holeH) / 2f
        val r = holeW * 0.18f
        var x = imgStart + pitch / 2f - holeW / 2f
        while (x - pitch > -holeW) x -= pitch
        while (x < longLen) {
            c.drawRoundRect(RectF(x, holeY, x + holeW, holeY + holeH), r, r, hole)
            x += pitch
        }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(185, 185, 180)
            textSize = stripH * 0.17f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val textY = if (outerTop) y0 + stripH * 0.2f + text.textSize * 0.4f else y0 + stripH * 0.88f
        if (outerTop) {
            c.drawText("NOIR KAMERA  ${stockName.uppercase()}", imgStart + pitch * 0.3f, textY, text)
        } else {
            c.drawText("▸ $frameNo", imgStart + pitch * 0.3f, textY, text)
            c.drawText("${frameNo}A", imgStart + pitch * 4.2f, textY, text)
            c.drawText("▸ ${frameNo + 1}", imgStart + imgLen - pitch * 0.5f, textY, text)
        }
    }
}
