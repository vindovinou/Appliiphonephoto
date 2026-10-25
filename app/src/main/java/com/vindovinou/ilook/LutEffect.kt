package com.vindovinou.ilook

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * CameraX effect that renders camera frames through the iPhone-look 3D LUT on the GPU.
 * [targets] is PREVIEW, or PREVIEW | VIDEO_CAPTURE so recorded video gets the same look.
 * Several effects can share one [LutSurfaceProcessor]; only one is bound at a time.
 *
 * In Portrait and Cinematic modes the processor also blurs the background, using the latest
 * person mask from [setMask] (computed on an analysis stream, so it trails by a frame or two).
 */
class LutEffect(
    targets: Int,
    processor: LutSurfaceProcessor,
) : CameraEffect(targets, processor.executor, processor, { Log.e("LutEffect", "Effect error", it) })

class LutSurfaceProcessor(initialLut: Lut3D) : SurfaceProcessor {

    private val thread = HandlerThread("LutGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor: Executor = Executor { handler.post(it) }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var oesTex = 0
    private var lutTex = 0
    private var maskTex = 0
    private var uTexMatrix = 0
    private var uBlur = 0
    private var uMaskRot = 0
    private var uRadius = 0
    private var pendingLut: Lut3D? = initialLut
    private var blur = false
    private var blurRadius = 0.02f
    private var pendingMask: Mask? = null
    private var maskRotation = 0
    private var hasMask = false

    /** Foreground confidence (0..255), upright: the camera buffer rotated by [rotation] degrees. */
    class Mask(val bytes: ByteBuffer, val width: Int, val height: Int, val rotation: Int)

    private var surfaceTexture: SurfaceTexture? = null
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()
    private val stMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)
    private var released = false

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            // x, y, u, v
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
            position(0)
        }

    init {
        handler.post { initGl() }
    }

    fun setLut(lut: Lut3D) = handler.post { pendingLut = lut }

    /** Turns the background blur on or off; [radius] is a fraction of the frame. */
    fun setBlur(enabled: Boolean, radius: Float = 0.02f) = handler.post {
        blur = enabled
        blurRadius = radius
        if (!enabled) hasMask = false
    }

    fun setMask(mask: Mask) = handler.post { pendingMask = mask }

    fun release() = handler.post {
        released = true
        outputs.keys.toList().forEach { closeOutput(it) }
        surfaceTexture?.release()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, pbuffer)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        thread.quitSafely()
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (released) {
            request.willNotProvideSurface()
            return
        }
        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(st)
        surfaceTexture?.release()
        surfaceTexture = st
        st.setOnFrameAvailableListener({ drawFrame(it) }, handler)
        request.provideSurface(surface, executor) {
            st.setOnFrameAvailableListener(null)
            if (surfaceTexture === st) surfaceTexture = null
            st.release()
            surface.release()
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released) {
            surfaceOutput.close()
            return
        }
        val surface = surfaceOutput.getSurface(executor) { closeOutput(surfaceOutput) }
        val attrs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attrs, 0)
        outputs[surfaceOutput] = eglSurface
    }

    private fun closeOutput(out: SurfaceOutput) {
        outputs.remove(out)?.let {
            EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
            EGL14.eglDestroySurface(eglDisplay, it)
        }
        out.close()
    }

    private fun drawFrame(st: SurfaceTexture) {
        if (released) return
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
        st.updateTexImage()
        uploadPendingLut()
        uploadPendingMask()
        st.getTransformMatrix(stMatrix)
        for ((out, eglSurface) in outputs) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            out.updateTransformMatrix(outMatrix, stMatrix)
            GLES30.glViewport(0, 0, out.size.width, out.size.height)
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, outMatrix, 0)
            GLES30.glUniform1f(uBlur, if (blur && hasMask) 1f else 0f)
            GLES30.glUniform1i(uMaskRot, maskRotation)
            GLES30.glUniform1f(uRadius, blurRadius)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, st.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    private fun uploadPendingLut() {
        val lut = pendingLut ?: return
        pendingLut = null
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val buf = ByteBuffer.allocateDirect(lut.bytes.size).put(lut.bytes).apply { position(0) }
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, lut.size, lut.size, lut.size, 0,
            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, buf,
        )
    }

    private fun uploadPendingMask() {
        val m = pendingMask ?: return
        pendingMask = null
        if (!blur) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        m.bytes.position(0)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, m.width, m.height, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, m.bytes,
        )
        maskRotation = m.rotation
        hasMask = true
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, configs, 0, 1, num, 0)
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)

        program = buildProgram(VERTEX, FRAGMENT)
        uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uMask"), 2)
        uBlur = GLES30.glGetUniformLocation(program, "uBlur")
        uMaskRot = GLES30.glGetUniformLocation(program, "uMaskRot")
        uRadius = GLES30.glGetUniformLocation(program, "uRadius")

        val stride = 4 * 4
        quad.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, quad)
        GLES30.glEnableVertexAttribArray(0)
        quad.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, quad)
        GLES30.glEnableVertexAttribArray(1)
        quad.position(0)

        val tex = IntArray(3)
        GLES30.glGenTextures(3, tex, 0)
        oesTex = tex[0]
        lutTex = tex[1]
        maskTex = tex[2]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        for ((k, v) in listOf(
            GLES30.GL_TEXTURE_MIN_FILTER to GLES30.GL_LINEAR, GLES30.GL_TEXTURE_MAG_FILTER to GLES30.GL_LINEAR,
            GLES30.GL_TEXTURE_WRAP_S to GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_TEXTURE_WRAP_T to GLES30.GL_CLAMP_TO_EDGE,
        )) GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, k, v)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        for (p in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE)
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        uploadPendingLut()
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("Shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glBindAttribLocation(p, 1, "aUv")
        GLES30.glLinkProgram(p)
        return p
    }

    private companion object {
        const val VERTEX = """#version 300 es
in vec2 aPos;
in vec2 aUv;
uniform mat4 uTexMatrix;
out vec2 vUv;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUv = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy;
}
"""
        const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
precision mediump sampler3D;
uniform samplerExternalOES uTex;
uniform sampler3D uLut;
uniform sampler2D uMask;
uniform float uBlur;
uniform int uMaskRot;
uniform float uRadius;
in vec2 vUv;
out vec4 fragColor;
const float N = 33.0;
const int TAPS = 24;

// Camera buffer coordinates -> upright mask coordinates.
vec2 maskUv(vec2 uv) {
    if (uMaskRot == 90) return vec2(1.0 - uv.y, uv.x);
    if (uMaskRot == 180) return vec2(1.0 - uv.x, 1.0 - uv.y);
    if (uMaskRot == 270) return vec2(uv.y, 1.0 - uv.x);
    return uv;
}

void main() {
    vec3 c = texture(uTex, vUv).rgb;
    if (uBlur > 0.5) {
        float fg = texture(uMask, maskUv(vUv)).r;
        float bg = 1.0 - smoothstep(0.35, 0.75, fg);
        if (bg > 0.01) {
            // Golden-angle disc: a cheap, round bokeh-like blur.
            vec3 acc = vec3(0.0);
            for (int i = 0; i < TAPS; i++) {
                float a = float(i) * 2.39996;
                float r = sqrt((float(i) + 0.5) / float(TAPS)) * uRadius;
                acc += texture(uTex, vUv + vec2(cos(a), sin(a)) * r).rgb;
            }
            c = mix(c, acc / float(TAPS), bg);
        }
    }
    c = clamp(c, 0.0, 1.0);
    vec3 lc = c * ((N - 1.0) / N) + 0.5 / N;
    fragColor = vec4(texture(uLut, lc).rgb, 1.0);
}
"""
    }
}
