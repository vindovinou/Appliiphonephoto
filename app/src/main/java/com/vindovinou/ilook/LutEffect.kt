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
    private var uTexMatrix = 0
    private var pendingLut: Lut3D? = initialLut

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
        st.getTransformMatrix(stMatrix)
        for ((out, eglSurface) in outputs) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            out.updateTransformMatrix(outMatrix, stMatrix)
            GLES30.glViewport(0, 0, out.size.width, out.size.height)
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, outMatrix, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
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

        val stride = 4 * 4
        quad.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, quad)
        GLES30.glEnableVertexAttribArray(0)
        quad.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, quad)
        GLES30.glEnableVertexAttribArray(1)
        quad.position(0)

        val tex = IntArray(2)
        GLES30.glGenTextures(2, tex, 0)
        oesTex = tex[0]
        lutTex = tex[1]
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
in vec2 vUv;
out vec4 fragColor;
const float N = 33.0;
void main() {
    vec3 c = clamp(texture(uTex, vUv).rgb, 0.0, 1.0);
    vec3 lc = c * ((N - 1.0) / N) + 0.5 / N;
    fragColor = vec4(texture(uLut, lc).rgb, 1.0);
}
"""
    }
}
