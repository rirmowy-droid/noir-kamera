package pl.noir.kamera

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

class NoirRenderer(private val params: LookParams) : GLSurfaceView.Renderer {

    private var program = 0
    private var frameTex = 0
    private var maskTex = 0
    private var whiteTex = 0
    private val uniforms = HashMap<String, Int>()
    private val quad: FloatBuffer
    private var viewW = 1
    private var viewH = 1

    @Volatile var maxTextureSize = 4096
        private set

    // potrójne buforowanie klatek: analizator pisze, GL czyta, nigdy ten sam bufor
    private val lock = Any()
    private var writeBuf: ByteBuffer? = null
    private var readyBuf: ByteBuffer? = null
    private var glBuf: ByteBuffer? = null
    private var readyW = 0
    private var readyH = 0
    private var readyRot = 0
    private var readyGain = 0f
    private var newFrame = false

    private var frameW = 0
    private var frameH = 0
    private var frameRot = 0
    private var frameGain = 0f
    private var texW = 0
    private var texH = 0
    private var hasFrame = false

    private var pendingMask: ByteBuffer? = null
    private var pendingMaskW = 0
    private var pendingMaskH = 0
    @Volatile private var maskValid = false

    private val startNs = System.nanoTime()

    init {
        val data = floatArrayOf(
            -1f, 1f, 0f, 0f,
            -1f, -1f, 0f, 1f,
            1f, 1f, 1f, 0f,
            1f, -1f, 1f, 1f
        )
        quad = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(data)
        quad.position(0)
    }

    /** Wywoływane tylko z wątku analizy. */
    fun frameBuffer(size: Int): ByteBuffer {
        val b = writeBuf
        if (b != null && b.capacity() == size) {
            b.clear()
            return b
        }
        val n = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        writeBuf = n
        return n
    }

    /** Wywoływane tylko z wątku analizy, po wypełnieniu frameBuffer(). */
    fun commitFrame(w: Int, h: Int, rotation: Int, inputGain: Float = 0f) {
        synchronized(lock) {
            val t = readyBuf
            readyBuf = writeBuf
            writeBuf = t
            readyW = w
            readyH = h
            readyRot = rotation
            readyGain = inputGain
            newFrame = true
        }
    }

    fun submitMask(buf: ByteBuffer, w: Int, h: Int) {
        synchronized(lock) {
            pendingMask = buf
            pendingMaskW = w
            pendingMaskH = h
        }
    }

    fun clearMask() {
        maskValid = false
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(Shaders.VERTEX, Shaders.FRAGMENT)
        val names = listOf(
            "uFrame", "uMask", "uRotation", "uFit", "uSpectral", "uExposure", "uContrast", "uToe",
            "uGrain", "uGrainSize", "uGlow", "uBloomLod", "uVignette", "uMaskAmount", "uDamage",
            "uTime", "uAspect", "uTint", "uFlip", "uInputGain", "uDepthOn", "uFocus", "uAperture",
            "uSwirl", "uEdge", "uDiffusion", "uExpired", "uSeed"
        )
        uniforms.clear()
        for (n in names) uniforms[n] = GLES30.glGetUniformLocation(program, n)

        val arr = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, arr, 0)
        maxTextureSize = arr[0]

        frameTex = createTexture(true)
        maskTex = createTexture(false)
        whiteTex = createTexture(false)
        val white = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
        white.put(255.toByte())
        white.position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 1, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, white)
        texW = 0
        texH = 0
        hasFrame = false
        maskValid = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = max(width, 1)
        viewH = max(height, 1)
    }

    override fun onDrawFrame(gl: GL10?) {
        var upload: ByteBuffer? = null
        var mask: ByteBuffer? = null
        var mw = 0
        var mh = 0
        synchronized(lock) {
            if (newFrame) {
                val t = glBuf
                glBuf = readyBuf
                readyBuf = t
                frameW = readyW
                frameH = readyH
                frameRot = readyRot
                frameGain = readyGain
                newFrame = false
                upload = glBuf
            }
            if (pendingMask != null) {
                mask = pendingMask
                mw = pendingMaskW
                mh = pendingMaskH
                pendingMask = null
            }
        }

        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val up = upload
        if (up != null) {
            up.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
            if (texW != frameW || texH != frameH) {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, frameW, frameH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, up)
                texW = frameW
                texH = frameH
            } else {
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, frameW, frameH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, up)
            }
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            hasFrame = true
        }
        val mk = mask
        if (mk != null && mw > 0 && mh > 0) {
            mk.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, mw, mh, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, mk)
            maskValid = true
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!hasFrame) return

        val upW = if (frameRot % 180 != 0) frameH else frameW
        val upH = if (frameRot % 180 != 0) frameW else frameH
        val af = upW.toFloat() / upH
        val asr = viewW.toFloat() / viewH
        val fitX: Float
        val fitY: Float
        if (af > asr) {
            fitX = 1f; fitY = af / asr
        } else {
            fitX = asr / af; fitY = 1f
        }
        val time = (System.nanoTime() - startNs) / 1e9f
        draw(frameTex, upW, upH, frameRot, fitX, fitY, false, time, if (maskValid && params.aiEnabled) maskTex else whiteTex, frameGain)
    }

    /**
     * Wywołanie pełnej rozdzielczości zdjęcia tym samym "chemicznym" procesem co podgląd.
     * Musi być wywołane na wątku GL (glView.queueEvent).
     */
    fun renderStill(bitmap: Bitmap, rotation: Int, mask: ByteBuffer?, mw: Int, mh: Int, inputGain: Float = 0f): Bitmap {
        val bw = bitmap.width
        val bh = bitmap.height
        val upW = if (rotation % 180 != 0) bh else bw
        val upH = if (rotation % 180 != 0) bw else bh

        val src = createTexture(true)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

        var stillMask = whiteTex
        var tmpMask = 0
        if (mask != null && mw > 0 && mh > 0 && params.aiEnabled) {
            tmpMask = createTexture(false)
            mask.position(0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tmpMask)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, mw, mh, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, mask)
            stillMask = tmpMask
        }

        val outTex = createTexture(false)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, upW, upH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outTex, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)

        val out = ByteBuffer.allocateDirect(upW * upH * 4).order(ByteOrder.nativeOrder())
        try {
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) throw IllegalStateException("FBO $status")
            GLES30.glViewport(0, 0, upW, upH)
            val seed = (System.nanoTime() % 100000L) / 37.0f
            draw(src, upW, upH, rotation, 1f, 1f, true, seed, stillMask, inputGain)
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
            GLES30.glReadPixels(0, 0, upW, upH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, out)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            val del = if (tmpMask != 0) intArrayOf(src, outTex, tmpMask) else intArrayOf(src, outTex)
            GLES30.glDeleteTextures(del.size, del, 0)
            GLES30.glViewport(0, 0, viewW, viewH)
        }

        out.position(0)
        val result = Bitmap.createBitmap(upW, upH, Bitmap.Config.ARGB_8888)
        result.copyPixelsFromBuffer(out)
        return result
    }

    private fun draw(
        tex: Int, upW: Int, upH: Int, rotation: Int,
        fitX: Float, fitY: Float, flip: Boolean, time: Float, mask: Int, inputGain: Float
    ) {
        val s = params.stock
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(u("uFrame"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mask)
        GLES30.glUniform1i(u("uMask"), 1)

        val shortSide = min(upW, upH).toFloat()
        val bloomLod = max(1f, (ln(shortSide / 60f) / ln(2f)))

        GLES30.glUniform1i(u("uRotation"), (rotation / 90) % 4)
        GLES30.glUniform2f(u("uFit"), fitX, fitY)
        GLES30.glUniform3f(u("uSpectral"), s.spectral[0], s.spectral[1], s.spectral[2])
        GLES30.glUniform1f(u("uExposure"), params.pushPull + params.reciprocityStops)
        GLES30.glUniform1f(u("uInputGain"), inputGain)
        GLES30.glUniform1f(
            u("uContrast"),
            s.contrast * params.contrastMul * params.paperContrast() * params.reciprocityContrast
        )
        GLES30.glUniform1f(u("uToe"), s.toe)
        GLES30.glUniform1f(u("uGrain"), s.grain * params.grainMul)
        GLES30.glUniform1f(u("uGrainSize"), s.grainSize)
        GLES30.glUniform1f(u("uGlow"), s.glow * params.glowMul)
        GLES30.glUniform1f(u("uBloomLod"), bloomLod)
        GLES30.glUniform1f(u("uVignette"), (s.vignette * params.vignetteMul).coerceIn(0f, 1f))
        GLES30.glUniform1f(u("uMaskAmount"), if (params.aiEnabled && mask != whiteTex) params.aiAmount else 0f)
        GLES30.glUniform1f(u("uDamage"), if (params.damage) 1f else 0f)
        GLES30.glUniform1f(u("uTime"), time)
        GLES30.glUniform1f(u("uAspect"), upW.toFloat() / upH)
        GLES30.glUniform3f(u("uTint"), s.tint[0], s.tint[1], s.tint[2])
        GLES30.glUniform1f(u("uFlip"), if (flip) 1f else 0f)
        val depthOn = params.aiEnabled && mask != whiteTex
        GLES30.glUniform1f(u("uDepthOn"), if (depthOn) 1f else 0f)
        GLES30.glUniform1f(u("uFocus"), params.focus)
        GLES30.glUniform1f(u("uAperture"), if (depthOn) params.aperture else 0f)
        GLES30.glUniform1f(u("uSwirl"), if (params.swirl) 1f else 0f)
        GLES30.glUniform1f(u("uEdge"), params.edge)
        GLES30.glUniform1f(u("uDiffusion"), params.diffusion)
        GLES30.glUniform1f(u("uExpired"), params.expired)
        GLES30.glUniform1f(u("uSeed"), params.expiredSeed)

        quad.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glEnableVertexAttribArray(0)
        quad.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        quad.position(0)
    }

    private fun u(name: String): Int = uniforms[name] ?: -1

    private fun createTexture(mipmap: Boolean): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
            if (mipmap) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            Log.e(TAG, "Link error: $log")
            throw RuntimeException("Shader link error: $log")
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            Log.e(TAG, "Compile error: $log")
            throw RuntimeException("Shader compile error: $log")
        }
        return s
    }

    companion object {
        private const val TAG = "NoirRenderer"
    }
}
