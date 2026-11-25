package pl.noir.kamera

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Obraz utajony jednej klatki filmu przy wielokrotnej ekspozycji.
 * Światło kolejnych naświetleń SUMUJE się (jak na prawdziwej emulsji), w orientacji matrycy –
 * obrócenie aparatu między ekspozycjami obraca też drugie zdjęcie, tak jak na kliszy.
 * Suma jest zapisana z zapasem jasności (HEADROOM), żeby światła się nie obcinały.
 */
class LatentFrame(first: Bitmap, val rotation: Int) {

    companion object {
        const val HEADROOM = 4f
        const val GAIN_STOPS = 2f // log2(HEADROOM)
        private val lin = FloatArray(256) { Math.pow(it / 255.0, 2.2).toFloat() }
        private val enc = IntArray(4096) {
            (Math.pow(it / 4095.0, 1.0 / 2.2) * 255.0).roundToInt().coerceIn(0, 255)
        }
    }

    val bitmap: Bitmap = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
    var exposures = 0
        private set

    var mask: ByteBuffer? = null
        private set
    var maskW = 0
        private set
    var maskH = 0
        private set

    init {
        add(first)
    }

    fun add(src0: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val src = if (src0.width != w || src0.height != h) Bitmap.createScaledBitmap(src0, w, h, true) else src0
        val row = IntArray(w)
        val acc = IntArray(w)
        val scale = 4095f / HEADROOM
        val hasPrev = exposures > 0
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            if (hasPrev) bitmap.getPixels(acc, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                var r = lin[(p shr 16) and 0xFF]
                var g = lin[(p shr 8) and 0xFF]
                var b = lin[p and 0xFF]
                if (hasPrev) {
                    val q = acc[x]
                    r += lin[(q shr 16) and 0xFF] * HEADROOM
                    g += lin[(q shr 8) and 0xFF] * HEADROOM
                    b += lin[q and 0xFF] * HEADROOM
                }
                val er = enc[min((r * scale).toInt(), 4095)]
                val eg = enc[min((g * scale).toInt(), 4095)]
                val eb = enc[min((b * scale).toInt(), 4095)]
                acc[x] = (0xFF shl 24) or (er shl 16) or (eg shl 8) or eb
            }
            bitmap.setPixels(acc, 0, w, 0, y, w, 1)
        }
        if (src !== src0) src.recycle()
        exposures++
    }

    /** Postać z każdej ekspozycji zostaje "w świetle" – maski łączą się przez maksimum. */
    fun addMask(m: ByteBuffer?, w: Int, h: Int, rot: Int) {
        if (m == null || w <= 0 || h <= 0) return
        val cur = mask
        if (cur == null) {
            val copy = ByteBuffer.allocateDirect(w * h).order(ByteOrder.nativeOrder())
            m.position(0)
            copy.put(m)
            copy.position(0)
            mask = copy
            maskW = w
            maskH = h
            return
        }
        if (w != maskW || h != maskH || rot != rotation) return
        for (i in 0 until w * h) {
            val a = cur.get(i).toInt() and 0xFF
            val b = m.get(i).toInt() and 0xFF
            cur.put(i, max(a, b).toByte())
        }
    }
}
