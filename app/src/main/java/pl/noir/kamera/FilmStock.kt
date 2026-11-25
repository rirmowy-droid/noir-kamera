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
 *
 * Tylko klisze kolorowe:
 * crosstalk     – macierz 3×3 (wiersze = warstwy R,G,B): ile każda warstwa "widzi" z czerwieni/zieleni/błękitu
 * layerContrast – gamma każdej warstwy osobno (różne gammy = przesunięcia barwne w cieniach i światłach)
 * layerOffset   – przesunięcie naświetlenia warstw w działkach (balans barwny emulsji)
 * dyeMix        – nieczystość barwników (3×3)
 * saturation    – nasycenie odbitki
 * fadeLift      – kolor najgłębszej czerni (starzenie / zadymienie barwników)
 * highTint      – kolor najjaśniejszej bieli (papier, wyblaknięcie)
 * halation      – kolor poświaty wokół świateł
 * tungsten      – klisza do światła sztucznego (aparat ustawia stały balans "żarówka")
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
    val iso: Int,
    val color: Boolean = false,
    val crosstalk: FloatArray = ID3,
    val layerContrast: FloatArray = ONES,
    val layerOffset: FloatArray = ZEROS,
    val dyeMix: FloatArray = ID3,
    val saturation: Float = 1f,
    val fadeLift: FloatArray = ZEROS,
    val highTint: FloatArray = ONES,
    val halation: FloatArray = ONES,
    val tungsten: Boolean = false
)

private val ID3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
private val ONES = floatArrayOf(1f, 1f, 1f)
private val ZEROS = floatArrayOf(0f, 0f, 0f)
private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
private val NEUTRAL = floatArrayOf(1f, 1f, 1f)

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
        ),

        // ---------------------------------------------------------- KOLOR (do lat 70.)

        // trzy taśmy barwne, jak w wielkich produkcjach kina końca lat 30.: soczyste barwy podstawowe, głęboka czerń
        FilmStock(
            "Kolor · Trójbarwny 1939 · ISO 10",
            LUMA, 1.35f, 0.06f, 0.05f, 600f, 0.45f, 0.35f, NEUTRAL, 10,
            color = true,
            crosstalk = floatArrayOf(
                1.15f, -0.10f, -0.05f,
                -0.08f, 1.12f, -0.04f,
                -0.04f, -0.12f, 1.16f
            ),
            layerContrast = floatArrayOf(1.05f, 1.0f, 1.0f),
            layerOffset = floatArrayOf(0.15f, 0.0f, -0.15f),
            saturation = 1.35f,
            highTint = floatArrayOf(1.0f, 0.98f, 0.94f),
            halation = floatArrayOf(1.0f, 0.55f, 0.35f)
        ),

        // przezrocze z lat 50.: bardzo drobne ziarno, mocny kontrast, głęboka czerwień, czyste niebo
        FilmStock(
            "Kolor · Odwracalny 1955 · ISO 10",
            LUMA, 1.50f, 0.08f, 0.04f, 700f, 0.25f, 0.30f, NEUTRAL, 10,
            color = true,
            crosstalk = floatArrayOf(
                1.08f, -0.05f, -0.03f,
                -0.04f, 1.06f, -0.02f,
                -0.02f, -0.06f, 1.08f
            ),
            layerContrast = floatArrayOf(1.05f, 1.0f, 0.95f),
            layerOffset = floatArrayOf(0.20f, 0.0f, -0.05f),
            dyeMix = floatArrayOf(
                1.0f, 0.0f, 0.0f,
                0.02f, 0.98f, 0.0f,
                0.0f, 0.04f, 0.96f
            ),
            saturation = 1.25f,
            highTint = floatArrayOf(1.0f, 0.99f, 0.96f),
            halation = floatArrayOf(1.0f, 0.6f, 0.45f)
        ),

        // wczesny negatyw kolorowy: miękki, przygaszony, zielonkawe cienie, ciepłe światła
        FilmStock(
            "Kolor · Negatyw 1962 · ISO 64",
            LUMA, 0.95f, 0.03f, 0.08f, 480f, 0.40f, 0.35f, NEUTRAL, 64,
            color = true,
            crosstalk = floatArrayOf(
                0.95f, 0.05f, 0.0f,
                0.04f, 0.92f, 0.04f,
                0.0f, 0.08f, 0.92f
            ),
            layerContrast = floatArrayOf(0.95f, 1.0f, 1.08f),
            layerOffset = floatArrayOf(0.05f, 0.12f, -0.10f),
            saturation = 0.9f,
            fadeLift = floatArrayOf(0.02f, 0.04f, 0.03f),
            highTint = floatArrayOf(1.0f, 0.97f, 0.90f),
            halation = floatArrayOf(1.0f, 0.5f, 0.3f)
        ),

        // klisza zza żelaznej kurtyny: chłodna, turkusowo-zielona, wyraźne ziarno
        FilmStock(
            "Kolor · Wschodni 1971 · ISO 50",
            LUMA, 1.05f, 0.04f, 0.10f, 450f, 0.40f, 0.40f, NEUTRAL, 50,
            color = true,
            crosstalk = floatArrayOf(
                0.92f, 0.06f, 0.02f,
                0.05f, 0.95f, 0.0f,
                0.0f, 0.06f, 0.94f
            ),
            layerContrast = floatArrayOf(0.90f, 1.05f, 1.05f),
            layerOffset = floatArrayOf(-0.15f, 0.10f, 0.15f),
            saturation = 0.85f,
            fadeLift = floatArrayOf(0.0f, 0.03f, 0.04f),
            highTint = floatArrayOf(1.0f, 0.96f, 0.98f),
            halation = floatArrayOf(1.0f, 0.5f, 0.35f)
        ),

        // negatyw kinowy do światła sztucznego z połowy lat 70.: czerwona halacja wokół lamp, nocne miasto
        FilmStock(
            "Kolor · Kinowy 1975 · 100T (żarówka)",
            LUMA, 1.10f, 0.04f, 0.09f, 460f, 0.70f, 0.35f, NEUTRAL, 100,
            color = true,
            crosstalk = floatArrayOf(
                1.04f, -0.03f, -0.01f,
                -0.02f, 1.03f, -0.01f,
                -0.01f, -0.04f, 1.05f
            ),
            layerOffset = floatArrayOf(0.08f, 0.0f, 0.0f),
            saturation = 1.05f,
            halation = floatArrayOf(1.0f, 0.35f, 0.2f),
            tungsten = true
        ),

        // odbitka z rodzinnego albumu z lat 70., po latach w szufladzie: barwniki wyblakły, została czerwień
        FilmStock(
            "Kolor · Wyblakła odbitka 1974 · ISO 100",
            LUMA, 0.85f, 0.0f, 0.08f, 480f, 0.40f, 0.40f, NEUTRAL, 100,
            color = true,
            layerContrast = floatArrayOf(1.05f, 0.85f, 0.70f),
            layerOffset = floatArrayOf(0.35f, 0.0f, -0.25f),
            saturation = 0.75f,
            fadeLift = floatArrayOf(0.08f, 0.04f, 0.06f),
            highTint = floatArrayOf(1.0f, 0.93f, 0.88f),
            halation = floatArrayOf(1.0f, 0.6f, 0.5f)
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
    @Volatile var fixedWhiteBalance = true  // kolorowa klisza ma stały balans bieli (dzień / żarówka)

    // ciemnia
    @Volatile var paperGrade = 2            // 0 (miękki) … 5 (bardzo twardy)
    @Volatile var edge = 0.35f              // efekt krawędziowy wywoływacza
    @Volatile var expired = 0f              // 0 = świeża klisza, 1 = mocno przeterminowana
    val expiredSeed = (Math.random() * 100.0).toFloat()

    // obiektyw
    @Volatile var diffusion = 0f            // filtr dyfuzyjny
    @Volatile var aperture = 0f             // 0 = f/16 (wszystko ostre) … 1 = f/1.4
    @Volatile var focus = 0f                // 0 = ostrość na postaci, 1 = na tle
    @Volatile var swirl = false             // wirujące rozmycie (Helios)

    // doświadczenie aparatu
    @Volatile var viewfinder = true         // matówka z klinem ostrości w podglądzie
    @Volatile var manualAdvance = true      // film trzeba naciągnąć po każdym zdjęciu
    @Volatile var multiExposure = false     // spust bez naciągu = kolejna ekspozycja na tej samej klatce
    @Volatile var filmBorder = false        // odbitka z ramką negatywu

    // fizyka naświetlania (ustawiane przez aparat)
    @Volatile var reciprocityStops = 0f     // utrata czułości przy długich czasach (≤ 0)
    @Volatile var reciprocityContrast = 1f

    fun paperContrast(): Float = PAPER_GRADES[paperGrade.coerceIn(0, 5)]

    companion object {
        val PAPER_GRADES = floatArrayOf(0.65f, 0.82f, 1.0f, 1.18f, 1.38f, 1.62f)
    }
}
