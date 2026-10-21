package com.limelight.binding.video

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Super resolution with AMD FidelityFX Super Resolution 1.0 (FSR 1, MIT licence, see assets/licences).
 *
 * The decoder renders into [inputSurface] (a SurfaceTexture). Each frame then takes three GPU passes on our own thread:
 *  1. copy the decoder's frame (an external OES texture) into a plain texture at the stream's size,
 *  2. EASU: upscale it to the screen surface's size (AMD's edge-adaptive Lanczos),
 *  3. RCAS: sharpen at [sharpness] (0..1) and draw to the screen surface.
 * The frame keeps the decoder's presentation time, so frame pacing still applies. The screen surface can come and go
 * (a foldable's fold) with [attachOutput]/[detachOutput] while the decoder keeps its own surface.
 *
 * The shaders are ports of ffx_fsr1.h's 32-bit FsrEasuF and FsrRcasF to OpenGL ES 3.0. ES 3.0 has no textureGather,
 * so each gather is four texelFetches in gather order.
 */
class FsrRenderer(private val videoWidth: Int, private val videoHeight: Int, sharpness: Float) {
    private val thread = HandlerThread("fsr-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outW = 0
    private var outH = 0
    private var oesTex = 0
    private var copyTex = 0
    private var copyFbo = 0
    private var easuTex = 0
    private var easuFbo = 0
    private var easuW = 0
    private var easuH = 0
    private var copyProg = 0
    private var easuProg = 0
    private var rcasProg = 0
    private lateinit var texture: SurfaceTexture
    private val stMatrix = FloatArray(16)
    private val sharp = Math.pow(2.0, -((1.0 - sharpness.coerceIn(0f, 1f)) * 2.0)).toFloat()   // stops: 100% = 0, 0% = 2
    @Volatile private var released = false
    var frames = 0L
        private set

    /** The surface the decoder renders into. */
    lateinit var inputSurface: Surface
        private set

    init {
        val ready = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try { setUp() } catch (t: Throwable) { error = t }
            ready.countDown()
        }
        ready.await(5, TimeUnit.SECONDS)
        error?.let { release(); throw RuntimeException("FSR setup failed: ${it.message}", it) }
        if (!this::inputSurface.isInitialized) { release(); throw RuntimeException("FSR setup timed out") }
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize" }
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent" }

        copyProg = program(VERTEX, COPY)
        easuProg = program(VERTEX, EASU)
        rcasProg = program(VERTEX, RCAS)

        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0); oesTex = t[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val (ct, cf) = target(videoWidth, videoHeight); copyTex = ct; copyFbo = cf

        texture = SurfaceTexture(oesTex).apply {
            setDefaultBufferSize(videoWidth, videoHeight)
            setOnFrameAvailableListener({ draw() }, handler)
        }
        inputSurface = Surface(texture)
        Log.i(TAG, "ready: ${videoWidth}x$videoHeight, sharpness $sharp, GL ${GLES30.glGetString(GLES30.GL_RENDERER)}")
    }

    /** Draw to this screen surface from now on (blocks until it's in use). */
    fun attachOutput(surface: Surface) = onGl {
        destroyWindow()
        window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (window == EGL14.EGL_NO_SURFACE) Log.w(TAG, "eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
    }

    /** Stop drawing to the screen surface before it goes away (blocks); frames are dropped until [attachOutput]. */
    fun detachOutput() = onGl { destroyWindow() }

    private fun destroyWindow() {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            EGL14.eglDestroySurface(display, window)
            window = EGL14.EGL_NO_SURFACE
        }
    }

    private fun draw() {
        if (released) return
        if (window == EGL14.EGL_NO_SURFACE) {
            // nowhere to show it: take the frame anyway so the decoder doesn't stall
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            texture.updateTexImage()
            return
        }
        if (!EGL14.eglMakeCurrent(display, window, window, context)) { Log.w(TAG, "eglMakeCurrent: ${EGL14.eglGetError()}"); return }
        texture.updateTexImage()
        texture.getTransformMatrix(stMatrix)
        val q = IntArray(1)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, q, 0); outW = q[0]
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, q, 0); outH = q[0]
        if (outW <= 0 || outH <= 0) return
        if (easuW != outW || easuH != outH) {
            if (easuTex != 0) { GLES30.glDeleteTextures(1, intArrayOf(easuTex), 0); GLES30.glDeleteFramebuffers(1, intArrayOf(easuFbo), 0) }
            val (et, ef) = target(outW, outH); easuTex = et; easuFbo = ef; easuW = outW; easuH = outH
            Log.i(TAG, "output ${outW}x$outH (from ${videoWidth}x$videoHeight)")
        }

        // 1. the decoder's frame into a plain texture at the stream's size
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, copyFbo)
        GLES30.glViewport(0, 0, videoWidth, videoHeight)
        GLES30.glUseProgram(copyProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProg, "uTex"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(copyProg, "uSt"), 1, false, stMatrix, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // 2. EASU up to the screen surface's size (constants as FsrEasuCon, kept as floats)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, easuFbo)
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glUseProgram(easuProg)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, copyTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(easuProg, "uTex"), 0)
        val iw = videoWidth.toFloat(); val ih = videoHeight.toFloat()
        GLES30.glUniform4f(GLES30.glGetUniformLocation(easuProg, "uCon0"), iw / outW, ih / outH, 0.5f * iw / outW - 0.5f, 0.5f * ih / outH - 0.5f)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(easuProg, "uCon1"), 1f / iw, 1f / ih, 1f / iw, -1f / ih)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(easuProg, "uCon2"), -1f / iw, 2f / ih, 1f / iw, 2f / ih)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(easuProg, "uCon3"), 0f, 4f / ih, 0f, 0f)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(easuProg, "uInSize"), videoWidth, videoHeight)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // 3. RCAS sharpening, to the screen
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glUseProgram(rcasProg)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, easuTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(rcasProg, "uTex"), 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(rcasProg, "uSharp"), sharp)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(rcasProg, "uSize"), outW, outH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // the decoder's presentation time: frame pacing keeps working
        EGLExt.eglPresentationTimeANDROID(display, window, texture.timestamp)
        if (!EGL14.eglSwapBuffers(display, window)) Log.w(TAG, "eglSwapBuffers: ${EGL14.eglGetError()}")
        frames++
    }

    /** End it (blocks briefly). Call after the decoder has let go of [inputSurface]. */
    fun release() {
        if (released) return
        released = true
        onGl {
            destroyWindow()
            if (this::inputSurface.isInitialized) inputSurface.release()
            if (this::texture.isInitialized) texture.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
        }
        thread.quitSafely()
    }

    private fun onGl(block: () -> Unit) {
        if (Thread.currentThread() === thread) { block(); return }
        val done = CountDownLatch(1)
        if (!handler.post { try { block() } finally { done.countDown() } }) return
        done.await(2, TimeUnit.SECONDS)
    }

    private fun target(w: Int, h: Int): Pair<Int, Int> {
        val t = IntArray(1); val f = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[0], 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "framebuffer ${w}x$h" }
        return t[0] to f[0]
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES30.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        private const val TAG = "MoonCast-FSR"

        /** Settings: Super resolution (off / fsr1) and its Sharpening (0..100). */
        const val PREF = "mc_super_resolution"
        const val SHARPNESS_PREF = "mc_fsr_sharpness"
        const val DEFAULT_SHARPNESS = 60

        /** Worth it only when the stream is clearly smaller than the screen surface. */
        fun worthIt(videoHeight: Int, outHeight: Int) = outHeight > videoHeight * 1.1

        // a full-screen triangle; no vertex buffers
        private const val VERTEX = """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""

        private const val COPY = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
uniform mat4 uSt;
in vec2 vUv;
out vec4 o;
void main() { o = vec4(texture(uTex, (uSt * vec4(vUv, 0.0, 1.0)).xy).rgb, 1.0); }"""

        // ffx_fsr1.h FsrEasuF (32-bit), with FsrEasuTapF and FsrEasuSetF; textureGather replaced by 4 texelFetches
        private const val EASU = """#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uTex;
uniform vec4 uCon0;
uniform vec4 uCon1;
uniform vec4 uCon2;
uniform vec4 uCon3;
uniform ivec2 uInSize;
out vec4 o;

vec3 texel(ivec2 p) { return texelFetch(uTex, clamp(p, ivec2(0), uInSize - 1), 0).rgb; }
// textureGather at a texel corner p: x = (left, upper+1), y = (left+1, upper+1), z = (left+1, upper), w = (left, upper)
void gather(vec2 p, out vec3 x, out vec3 y, out vec3 z, out vec3 w) {
    ivec2 tl = ivec2(floor(p * vec2(uInSize) + 0.5)) - 1;
    x = texel(tl + ivec2(0, 1)); y = texel(tl + ivec2(1, 1)); z = texel(tl + ivec2(1, 0)); w = texel(tl);
}
float luma(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }
float rcp(float x) { return 1.0 / max(x, 1.0e-8); }

void tap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    vec2 v;
    v.x = (off.x * ( dir.x)) + (off.y * dir.y);
    v.y = (off.x * (-dir.y)) + (off.y * dir.x);
    v *= len;
    float d2 = min(v.x * v.x + v.y * v.y, clp);
    float wB = 2.0 / 5.0 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w; aW += w;
}

void set(inout vec2 dir, inout float len, vec2 pp, int corner, float lA, float lB, float lC, float lD, float lE) {
    float w = 0.0;
    if (corner == 0) w = (1.0 - pp.x) * (1.0 - pp.y);
    if (corner == 1) w = pp.x * (1.0 - pp.y);
    if (corner == 2) w = (1.0 - pp.x) * pp.y;
    if (corner == 3) w = pp.x * pp.y;
    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = rcp(max(abs(dc), abs(cb)));
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
    lenX *= lenX;
    len += lenX * w;
    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = rcp(max(abs(ec), abs(ca)));
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
    lenY *= lenY;
    len += lenY * w;
}

void main() {
    vec2 pp = floor(gl_FragCoord.xy) * uCon0.xy + uCon0.zw;
    vec2 fp = floor(pp);
    pp -= fp;
    vec2 p0 = fp * uCon1.xy + uCon1.zw;
    vec2 p1 = p0 + uCon2.xy;
    vec2 p2 = p0 + uCon2.zw;
    vec2 p3 = p0 + uCon3.xy;
    vec3 b, c, z0, w0, i, j, f, e, k, l, h, g, x3, y3, oo, n;
    gather(p0, b, c, z0, w0);
    gather(p1, i, j, f, e);
    gather(p2, k, l, h, g);
    gather(p3, x3, y3, oo, n);
    float bL = luma(b), cL = luma(c), iL = luma(i), jL = luma(j), fL = luma(f), eL = luma(e);
    float kL = luma(k), lL = luma(l), hL = luma(h), gL = luma(g), oL = luma(oo), nL = luma(n);
    vec2 dir = vec2(0.0);
    float len = 0.0;
    set(dir, len, pp, 0, bL, eL, fL, gL, jL);
    set(dir, len, pp, 1, cL, fL, gL, hL, kL);
    set(dir, len, pp, 2, fL, iL, jL, kL, nL);
    set(dir, len, pp, 3, gL, jL, kL, lL, oL);
    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    bool zro = dirR < 1.0 / 32768.0;
    dirR = inversesqrt(max(dirR, 1.0e-12));
    dirR = zro ? 1.0 : dirR;
    dir.x = zro ? 1.0 : dir.x;
    dir *= vec2(dirR);
    len = len * 0.5;
    len *= len;
    float stretch = (dir.x * dir.x + dir.y * dir.y) * rcp(max(abs(dir.x), abs(dir.y)));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = rcp(lob);
    vec3 min4 = min(min(min(f, g), j), k);
    vec3 max4 = max(max(max(f, g), j), k);
    vec3 aC = vec3(0.0);
    float aW = 0.0;
    tap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, b);
    tap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, c);
    tap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, i);
    tap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, j);
    tap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, f);
    tap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, e);
    tap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, k);
    tap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, l);
    tap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, h);
    tap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, g);
    tap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, oo);
    tap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, n);
    o = vec4(min(max4, max(min4, aC * rcp(aW))), 1.0);
}"""

        // ffx_fsr1.h FsrRcasF (32-bit, no denoise)
        private const val RCAS = """#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uTex;
uniform float uSharp;
uniform ivec2 uSize;
out vec4 o;
vec3 texel(ivec2 p) { return texelFetch(uTex, clamp(p, ivec2(0), uSize - 1), 0).rgb; }
void main() {
    ivec2 sp = ivec2(gl_FragCoord.xy);
    vec3 b = texel(sp + ivec2(0, -1));
    vec3 d = texel(sp + ivec2(-1, 0));
    vec3 e = texel(sp);
    vec3 f = texel(sp + ivec2(1, 0));
    vec3 h = texel(sp + ivec2(0, 1));
    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));
    vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1.0e-5));
    vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1.0e-5));
    vec3 lobeRGB = max(-hitMin, hitMax);
    float lobe = max(-(0.25 - 1.0 / 16.0), min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * uSharp;
    o = vec4((lobe * (b + d + h + f) + e) / (4.0 * lobe + 1.0), 1.0);
}"""
    }
}
