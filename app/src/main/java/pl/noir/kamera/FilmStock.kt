package pl.noir.kamera

/**
 * Jedna "klisza": jak emulsja reaguje na światło.
 *
 * spectral  – czułość na czerwień / zieleń / niebieski (emulsja ortochromatyczna prawie nie widzi czerwieni)
 * contrast  – nachylenie krzywej charakterystycznej (gamma kliszy)
 * toe       – jak mocno najgłębsze cienie zapadają się w czerń
 * grain     – siła ziarna, grainSize – liczba ziaren na wysokość kadru (mniej = grubsze ziarno)
 * glow      – poświata wokół świateł (halacja / miękki obiektyw)
 * vignette  – naturalne ściemnienie rogów starego obiektywu
 * tint      – ton papieru odbitki
 * iso       – czułość kliszy, którą aparat ustawia w trybie KLISZA
 */
class FilmStock(
    val name: String,
    val spectral: FloatArray,
    val contrast: Float,
    val toe: Float,
    val grain: Float,
    val grainSize: Float,
    val glow: Float,
    val vignette: Float,
    val tint: FloatArray,
    val iso: Int
)

object FilmStocks {
    val all = listOf(
        FilmStock(
            "Orto 1927 · ISO 25",
            floatArrayOf(0.02f, 0.38f, 0.60f), 1.30f, 0.05f, 0.10f, 420f, 0.55f, 0.45f,
            floatArrayOf(1.00f, 0.97f, 0.90f), 25
        ),
        FilmStock(
            "Pan 1938 · ISO 100",
            floatArrayOf(0.30f, 0.56f, 0.14f), 1.05f, 0.03f, 0.08f, 520f, 0.40f, 0.35f,
            floatArrayOf(1.00f, 0.98f, 0.94f), 100
        ),
        FilmStock(
            "Noir 1946 · filtr czerwony",
            floatArrayOf(0.78f, 0.20f, 0.02f), 1.50f, 0.10f, 0.10f, 480f, 0.30f, 0.50f,
            floatArrayOf(0.97f, 0.98f, 1.00f), 200
        ),
        FilmStock(
            "Filtr żółty · ISO 100",
            floatArrayOf(0.44f, 0.47f, 0.09f), 1.15f, 0.04f, 0.08f, 520f, 0.35f, 0.35f,
            floatArrayOf(1.00f, 0.98f, 0.95f), 100
        ),
        FilmStock(
            "Nocny reportaż · ISO 800",
            floatArrayOf(0.33f, 0.52f, 0.15f), 1.35f, 0.08f, 0.22f, 300f, 0.50f, 0.40f,
            floatArrayOf(1.00f, 0.99f, 0.97f), 800
        )
    )
}

/** Ustawienia współdzielone przez interfejs i renderer. */
class LookParams {
    @Volatile var stock: FilmStock = FilmStocks.all[0]
    @Volatile var contrastMul = 1f
    @Volatile var grainMul = 1f
    @Volatile var glowMul = 1f
    @Volatile var vignetteMul = 1f
    @Volatile var pushPull = 0f
    @Volatile var aiEnabled = true
    @Volatile var aiAmount = 0.7f
    @Volatile var damage = false
}
