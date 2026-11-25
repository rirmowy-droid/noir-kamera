package pl.noir.kamera

import android.content.Context
import java.io.File
import java.io.OutputStream

/**
 * Rolka filmu w aparacie. Zdjęcia czekają jako "niewywołane klatki" w pamięci aplikacji,
 * do Galerii trafiają dopiero po wywołaniu całej rolki. Stan przetrwa zamknięcie aplikacji.
 */
class Roll(context: Context) {
    private val prefs = context.getSharedPreferences("rolka", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "rolka").apply { mkdirs() }

    /** Długość następnej rolki: 0 = bez rolki (zdjęcia od razu w Galerii), 12, 24 lub 36. */
    var nextSize: Int
        get() = prefs.getInt("nextSize", 36)
        set(v) = prefs.edit().putInt("nextSize", v).apply()

    /** Długość rolki, która jest teraz w aparacie. */
    val loadedSize: Int get() = if (count > 0) prefs.getInt("loadedSize", nextSize) else nextSize

    val count: Int get() = prefs.getInt("count", 0)

    val enabled: Boolean get() = loadedSize > 0

    val full: Boolean get() = enabled && count >= loadedSize

    /** Klisza, która jest założona (blokuje zmianę w połowie rolki). */
    var stockIndex: Int
        get() = prefs.getInt("stock", 0)
        set(v) = prefs.edit().putInt("stock", v).apply()

    /** Licznik klatek, gdy rolka jest wyłączona (numer na ramce negatywu). */
    var digitalCounter: Int
        get() = prefs.getInt("digital", 0)
        set(v) = prefs.edit().putInt("digital", v).apply()

    /** Numer klatki, która zostanie naświetlona jako następna. */
    val nextFrameNumber: Int get() = if (enabled) count + 1 else digitalCounter + 1

    fun store(writer: (OutputStream) -> Unit) {
        val c = count
        if (c == 0) prefs.edit().putInt("loadedSize", nextSize).commit()
        val f = File(dir, "klatka_%03d.jpg".format(c + 1))
        f.outputStream().use(writer)
        prefs.edit().putInt("count", c + 1).commit()
    }

    fun frames(): List<File> =
        dir.listFiles()?.filter { it.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()

    fun reset() {
        frames().forEach { it.delete() }
        prefs.edit().putInt("count", 0).commit()
    }
}
