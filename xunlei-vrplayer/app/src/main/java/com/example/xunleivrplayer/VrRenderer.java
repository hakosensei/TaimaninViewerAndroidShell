package com.example.xunleivrplayer;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ëá™Á†îÁöÑ‚ÄúVR Ê∫êÂ∏ß -> ÊôÆÈÄöÈÄèËßÜÁ™óÂè£‚ÄùOpenGL ES 2.0 Ê∏≤ÊüìÂô®„ÄÇ
 *
 * Media3 Âè™Ë¥üË¥£Á°¨‰ª∂ËßÜÈ¢ëËß£Á†ÅÔºõÊØè‰∏ÄÂ∏ßÁªè SurfaceTexture ÈÄÅÂà∞ËøôÈáåÂêéÔºå
 * Áî± fragment shader Ê†πÊçÆÊäïÂΩ±Ê®°ÂûãÂèçÊü•Ê∫êÂÉèÁ¥†„ÄÇ
 */
final class VrRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    interface SurfaceListener {
        void onVideoSurfaceReady(Surface surface);
        void onVideoSurfaceDestroyed(Surface surface);
    }

    private static final float[] QUAD = {
            -1f, -1f,  0f, 1f,
             1f, -1f,  1f, 1f,
            -1f,  1f,  0f, 0f,
             1f,  1f,  1f, 0f
    };

    private final FloatBuffer quad;
    private final AtomicBoolean frameAvailable = new AtomicBoolean(false);
    private final SurfaceListener surfaceListener;

    private int textureId;
    private SurfaceTexture surfaceTexture;
    private Surface videoSurface;
    private int program;
    private int aPos, aUv;
    private int uTex, uTexMatrix, uProjection, uStereo, uEye, uAspect, uViewFov;
    private int uYaw, uPitch, uRoll, uFishFov, uFishCenter, uFishRadius, uFishScale, uFishK;
    private int uDualLayout, uDualSwap;

    private volatile ProjectionSettings settings = new ProjectionSettings();
    private volatile float touchYawDeg = 0f;
    private volatile float touchPitchDeg = 0f;
    private volatile float touchRollDeg = 0f;
    private volatile float aspect = 16f / 9f;
    private final float[] texMatrix = new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};

    VrRenderer(SurfaceListener listener) {
        surfaceListener = listener;
        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);
    }

    void setSettings(ProjectionSettings s) { settings = s.copy(); }
    ProjectionSettings getSettings() { return settings.copy(); }

    void setTouchOrientation(float yaw, float pitch, float roll) {
        touchYawDeg = yaw;
        touchPitchDeg = Math.max(-89.5f, Math.min(89.5f, pitch));
        touchRollDeg = roll;
    }

    void setViewFov(float fov) {
        ProjectionSettings s = settings.copy();
        s.viewFovDeg = Math.max(25f, Math.min(135f, fov));
        settings = s;
    }

    @Override public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
        textureId = createExternalTexture();
        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(this);
        videoSurface = new Surface(surfaceTexture);
        program = buildProgram(VERTEX, FRAGMENT);

        aPos = GLES20.glGetAttribLocation(program, "aPosition");
        aUv = GLES20.glGetAttribLocation(program, "aTexCoord");
        uTex = GLES20.glGetUniformLocation(program, "uTexture");
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
        uProjection = GLES20.glGetUniformLocation(program, "uProjection");
        uStereo = GLES20.glGetUniformLocation(program, "uStereo");
        uEye = GLES20.glGetUniformLocation(program, "uEye");
        uAspect = GLES20.glGetUniformLocation(program, "uAspect");
        uViewFov = GLES20.glGetUniformLocation(program, "uViewFovDeg");
        uYaw = GLES20.glGetUniformLocation(program, "uYawDeg");
        uPitch = GLES20.glGetUniformLocation(program, "uPitchDeg");
        uRoll = GLES20.glGetUniformLocation(program, "uRollDeg");
        uFishFov = GLES20.glGetUniformLocation(program, "uFishFovDeg");
        uFishCenter = GLES20.glGetUniformLocation(program, "uFishCenter");
        uFishRadius = GLES20.glGetUniformLocation(program, "uFishRadius");
        uFishScale = GLES20.glGetUniformLocation(program, "uFishScale");
        uFishK = GLES20.glGetUniformLocation(program, "uFishK");
        uDualLayout = GLES20.glGetUniformLocation(program, "uDualLayout");
        uDualSwap = GLES20.glGetUniformLocation(program, "uDualSwap");

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        if (surfaceListener != null) surfaceListener.onVideoSurfaceReady(videoSurface);
    }

    @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        if (height > 0) aspect = (float) width / (float) height;
    }

    @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
        if (surfaceTexture != null && frameAvailable.compareAndSet(true, false)) {
            try { surfaceTexture.updateTexImage(); surfaceTexture.getTransformMatrix(texMatrix); } catch (RuntimeException ignored) {}
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (program == 0) return;

        ProjectionSettings s = settings;
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);
        GLES20.glUniform1i(uProjection, s.projection);
        GLES20.glUniform1i(uStereo, s.stereoLayout);
        GLES20.glUniform1i(uEye, s.eye);
        GLES20.glUniform1f(uAspect, aspect);
        GLES20.glUniform1f(uViewFov, s.viewFovDeg);
        GLES20.glUniform1f(uYaw, touchYawDeg + s.sourceYawDeg);
        GLES20.glUniform1f(uPitch, touchPitchDeg + s.sourcePitchDeg);
        GLES20.glUniform1f(uRoll, touchRollDeg + s.sourceRollDeg);
        GLES20.glUniform1f(uFishFov, s.fisheyeFovDeg);
        GLES20.glUniform2f(uFishCenter, s.fishCenterX, s.fishCenterY);
        GLES20.glUniform1f(uFishRadius, s.fishRadius);
        GLES20.glUniform2f(uFishScale, s.fishScaleX, s.fishScaleY);
        GLES20.glUniform3f(uFishK, s.fishK1, s.fishK2, s.fishK3);
        GLES20.glUniform1i(uDualLayout, s.dualLensLayout);
        GLES20.glUniform1i(uDualSwap, s.dualLensSwap ? 1 : 0);

        quad.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        quad.position(2);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aUv);
    }

    @Override public void onFrameAvailable(SurfaceTexture surfaceTexture) { frameAvailable.set(true); }

    void release() {
        Surface s = videoSurface;
        if (surfaceListener != null && s != null) surfaceListener.onVideoSurfaceDestroyed(s);
        if (s != null) s.release();
        videoSurface = null;
        if (surfaceTexture != null) surfaceTexture.release();
        surfaceTexture = null;
    }

    private static int createExternalTexture() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("GL link failed: " + GLES20.glGetProgramInfoLog(p));
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("GL shader failed: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static final String VERTEX =
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main(){ vTexCoord=aTexCoord; gl_Position=vec4(aPosition,0.0,1.0); }\n";

    // Projection IDs must match ProjectionSettings constants.
    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;\n" +
            "uniform samplerExternalOES uTexture; uniform mat4 uTexMatrix;\n" +
            "uniform int uProjection; uniform int uStereo; uniform int uEye;\n" +
            "uniform float uAspect; uniform float uViewFovDeg;\n" +
            "uniform float uYawDeg; uniform float uPitchDeg; uniform float uRollDeg;\n" +
            "uniform float uFishFovDeg; uniform vec2 uFishCenter; uniform float uFishRadius;\n" +
            "u[öYõ‹õHôXÃàQö\⁄ÿÿ[N»[öYõ‹õHôXÃ»Qö\⁄Œ◊àà
¬àù[öYõ‹õH[ùQX[^[›]»[öYõ‹õH[ùQX[›ÿ\◊àà
¬àùò\ûZ[ô»ôXÃàï^€€‹ô◊àà
¬àò€€ú›õÿ]OLÀåMMNLççLÕNMŒLÃåŒé◊H@ë