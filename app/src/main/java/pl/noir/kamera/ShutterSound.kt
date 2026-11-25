package pl.noir.kamera

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Dźwięki mechaniki syntetyzowane w kodzie (bez plików): dwie kurtyny migawki szczelinowej
 * – "klak ... klak" rozdzielone rzeczywistym czasem naświetlania – oraz zapadka naciągu filmu.
 */
class ShutterSound {
    private val rate = 44100
    private val first = track(curtain(0.9f, 170.0, 1))
    private val second = track(curtain(0.75f, 120.0, 2))
    private val ratchet = track(ratchetClicks())

    @Volatile var enabled = true

    fun firstCurtain() = play(first)
    fun secondCurtain() = play(second)
    fun advance() = play(ratchet)

    private fun play(t: AudioTrack?) {
        if (!enabled || t == null) return
        try {
            t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: Exception) {
            // dźwięk nie jest niezbędny
        }
    }

    fun release() {
        first?.release()
        second?.release()
        ratchet?.release()
    }

    private fun curtain(amp: Float, thumpHz: Double, seed: Int): ShortArray {
        val n = (rate * 0.045).toInt()
        val rnd = Random(seed)
        val out = ShortArray(n)
        var prev = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val noise = rnd.nextFloat() * 2f - 1f
            val hp = noise - prev * 0.6f // trochę "metalicznego" pasma
            prev = noise
            val click = hp * exp(-t / 0.0035).toFloat()
            val thump = (sin(2 * PI * thumpHz * t) * exp(-t / 0.012)).toFloat() * 0.6f
            val v = ((click + thump) * amp).coerceIn(-1f, 1f)
            out[i] = (v * 32000).toInt().toShort()
        }
        return out
    }

    private fun ratchetClicks(): ShortArray {
        val n = (rate * 0.36).toInt()
        val out = ShortArray(n)
        val rnd = Random(7)
        val clicks = 7
        for (k in 0 until clicks) {
            val start = (k * rate * 0.045).toInt()
            val last = k == clicks - 1
            val len = (rate * (if (last) 0.03 else 0.012)).toInt()
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= n) break
                val t = i.toDouble() / rate
                val noise = rnd.nextFloat() * 2f - 1f
                val decay = exp(-t / (if (last) 0.006 else 0.0018)).toFloat()
                val v = noise * decay * (if (last) 0.8f else 0.45f)
                out[idx] = (out[idx] + (v * 32000).toInt()).coerceIn(-32000, 32000).toShort()
            }
        }
        return out
    }

    private fun track(data: ShortArray): AudioTrack? = try {
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
        t.write(data, 0, data.size)
        t
    } catch (e: Exception) {
        null
    }
}
