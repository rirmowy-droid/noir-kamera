package pl.noir.kamera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Historyczne lampy błyskowe. Każda świeci z jednego punktu przy aparacie, więc światło spada
 * z kwadratem odległości: pierwszy plan jest jasny, a tło zapada się w ciemność. To właśnie
 * "wygląd fleszu" zdjęć prasowych lat 30.–50.
 *
 * color        – barwa światła względem światła dziennego 5500 K (R,G,B)
 * power        – siła błysku względem diody telefonu
 * ambientKeep  – ile światła zastanego zostaje przy krótkim czasie synchronizacji
 * falloff      – jak szybko światło gaśnie z odległością (wykładnik na mapie błysku)
 * shadowX/Y    – przesunięcie twardego cienia za postacią (zależy od tego, gdzie wisiała lampa)
 * shadowSoft   – miękkość cienia (duże źródło = miękki cień)
 * glowAdd      – dodatkowa poświata (dym po proszku magnezjowym)
 * recycleMs    – ile trwa przygotowanie następnego błysku
 */
class FlashType(
    val name: String,
    val note: String,
    val power: Float,
    val ambientKeep: Float,
    val falloff: Float,
    val shadowX: Float,
    val shadowY: Float,
    val shadowSoft: Float,
    val glowAdd: Float,
    val recycleMs: Long,
    val recycleText: String,
    val kind: Kind
) {
    enum class Kind { OFF, MAGNESIUM, BULB, ELECTRONIC, CUBE }

    /** Barwa błysku zależnie od kliszy, tak jak dobierali ją fotografowie. */
    fun color(stock: FilmStock): FloatArray = when (kind) {
        Kind.OFF -> floatArrayOf(1f, 1f, 1f)
        // magnez spalał się światłem zbliżonym do dziennego, lekko niebieskawym
        Kind.MAGNESIUM -> norm(0.98f, 1.0f, 1.04f)
        // przezroczysta żarówka ok. 3800 K do czarno-białej i "T"; niebieska do kolorowej dziennej
        Kind.BULB, Kind.CUBE ->
            if (stock.color && !stock.tungsten) norm(1.0f, 0.95f, 0.88f) else norm(1.0f, 0.80f, 0.58f)
        // flesz elektronowy ok. 5500–6000 K
        Kind.ELECTRONIC -> norm(0.97f, 1.0f, 1.05f)
    }

    /** Niebieska powłoka żarówki zjada ok. pół do jednej działki światła. */
    fun effectivePower(stock: FilmStock): Float =
        if ((kind == Kind.BULB || kind == Kind.CUBE) && stock.color && !stock.tungsten) power * 0.75f else power

    private fun norm(r: Float, g: Float, b: Float): FloatArray {
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return floatArrayOf(r / l, g / l, b / l)
    }
}

object Flashes {
    val all = listOf(
        FlashType(
            "wyłączony", "", 0f, 1f, 1f, 0f, 0f, 0f, 0f, 0L, "",
            FlashType.Kind.OFF
        ),
        FlashType(
            "Proszek magnezjowy (do ok. 1930)",
            "Ogromny, szeroki błysk trzymany wysoko w górze; miękkie cienie i dym w powietrzu",
            3.2f, 0.55f, 1.15f, 0.02f, 0.05f, 0.02f, 0.5f, 6000L, "nasypuję proszek…",
            FlashType.Kind.MAGNESIUM
        ),
        FlashType(
            "Żarówka błyskowa (1930–60)",
            "Lampa prasowa z reflektorem na bocznym ramieniu: twardy cień z boku, czarne tło",
            2.6f, 0.30f, 1.6f, 0.035f, 0.02f, 0.004f, 0.1f, 2500L, "wymieniam żarówkę…",
            FlashType.Kind.BULB
        ),
        FlashType(
            "Flesz elektronowy (1955–75)",
            "Krótki, zimny błysk nad obiektywem; cień tuż pod postacią",
            2.0f, 0.20f, 1.8f, 0.0f, 0.022f, 0.003f, 0.0f, 3000L, "ładuję kondensator…",
            FlashType.Kind.ELECTRONIC
        ),
        FlashType(
            "Kostka błyskowa (1965–75)",
            "Cztery małe żarówki tuż nad obiektywem: płaskie światło, szybki spadek, 4 zdjęcia na kostkę",
            1.3f, 0.30f, 2.1f, 0.0f, 0.012f, 0.003f, 0.0f, 1000L, "kostka się obraca…",
            FlashType.Kind.CUBE
        )
    )
}

/** Mapa błysku dla jednego zdjęcia: jaka część światła w każdym miejscu kadru pochodziła z lampy. */
class FlashRender(
    val map: ByteBuffer?,
    val w: Int,
    val h: Int,
    val synthetic: Boolean,
    val type: FlashType,
    val color: FloatArray,
    val power: Float
)

object FlashMap {
    /**
     * Porównanie kadru bez błysku (a) i z błyskiem (b) – oba w orientacji matrycy, w małej rozdzielczości.
     * Wynik w orientacji "pionowej" zdjęcia (jak maska AI), wygładzony, 0..255.
     */
    fun compute(a: FloatArray, b: FloatArray, sw: Int, sh: Int, rotation: Int): ByteBuffer? {
        if (a.size != sw * sh || b.size != sw * sh) return null
        val f = FloatArray(sw * sh)
        for (i in 0 until sw * sh) {
            val bb = b[i]
            f[i] = ((bb - a[i]) / max(bb, 0.01f)).coerceIn(0f, 1f)
        }
        // wygładzenie 3×3, żeby szum nie udawał błysku
        val s = FloatArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var sum = 0f
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until sw && yy in 0 until sh) {
                    sum += f[yy * sw + xx]; n++
                }
            }
            s[y * sw + x] = sum / n
        }
        val rot = (rotation / 90) % 4
        val uw = if (rot % 2 == 1) sh else sw
        val uh = if (rot % 2 == 1) sw else sh
        val out = ByteBuffer.allocateDirect(uw * uh).order(ByteOrder.nativeOrder())
        for (uy in 0 until uh) for (ux in 0 until uw) {
            val u = (ux + 0.5f) / uw
            val v = (uy + 0.5f) / uh
            // to samo odwzorowanie co toTex() w shaderze
            val tx: Float
            val ty: Float
            when (rot) {
                1 -> { tx = v; ty = 1f - u }
                2 -> { tx = 1f - u; ty = 1f - v }
                3 -> { tx = 1f - v; ty = u }
                else -> { tx = u; ty = v }
            }
            val sx = min((tx * sw).toInt(), sw - 1)
            val sy = min((ty * sh).toInt(), sh - 1)
            out.put((s[sy * sw + sx] * 255f).toInt().coerceIn(0, 255).toByte())
        }
        out.position(0)
        return out
    }

    fun width(sw: Int, sh: Int, rotation: Int) = if (((rotation / 90) % 4) % 2 == 1) sh else sw
    fun height(sw: Int, sh: Int, rotation: Int) = if (((rotation / 90) % 4) % 2 == 1) sw else sh
}
