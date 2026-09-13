package com.example.xunleivrplayer;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;

/**
 * 自研投影渲染 View：单指拖动改变 yaw/pitch，双指缩放改变虚拟相机 FOV，双击回正。
 */
final class VrGlSurfaceView extends GLSurfaceView {
    interface Listener {
        void onVideoSurfaceReady(Surface surface);
        void onVideoSurfaceDestroyed(Surface surface);
        void onViewChanged(float yaw, float pitch, float fov);
    }

    private final VrRenderer renderer;
    private final ScaleGestureDetector scale;
    private final GestureDetector gestures;
    private float yaw = 0f, pitch = 0f;
    private float lastX, lastY;
    private boolean dragging;
    private ProjectionSettings settings;
    private final Listener listener;

    VrGlSurfaceView(Context context, ProjectionSettings initial, Listener l) {
        super(context);
        listener = l;
        settings = initial.copy();
        setEGLContextClientVersion(2);
        renderer = new VrRenderer(new VrRenderer.SurfaceListener() {
            @Override public void onVideoSurfaceReady(Surface surface) { if (listener != null) post(() -> listener.onVideoSurfaceReady(surface)); }
            @Override public void onVideoSurfaceDestroyed(Surface surface) { if (listener != null) post(() -> listener.onVideoSurfaceDestroyed(surface)); }
        });
        renderer.setSettings(settings);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setPreserveEGLContextOnPause(true);

        scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                ProjectionSettings s = settings.copy();
                s.viewFovDeg = clamp(s.viewFovDeg / detector.getScaleFactor(), 25f, 135f);
                setProjectionSettings(s);
                notifyView();
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { resetView(); return true; }
        });
    }

    ProjectionSettings getProjectionSettings() { return settings.copy(); }

    void setProjectionSettings(ProjectionSettings s) {
        settings = s.copy();
        queueEvent(() -> renderer.setSettings(settings));
    }

    void resetView() {
        yaw = 0f; pitch = 0f;
        queueEvent(() -> renderer.setTouchOrientation(0f, 0f, 0f));
        notifyView();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scale.onTouchEvent(e);
        gestures.onTouchEvent(e);
        if (e.getPointerCount() > 1 || scale.isInProgress()) {
            dragging = false;
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(); lastY = e.getY(); dragging = true; return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    float dx = e.getX() - lastX, dy = e.getY() - lastY;
                    lastX = e.getX(); lastY = e.getY();
                    float scaleDeg = settings.viewFovDeg / Math.max(1f, getHeight());
                    yaw -= dx * scaleDeg;
                    pitch += dy * scaleDeg;
                    pitch = clamp(pitch, -89.5f, 89.5f);
                    float fy = yaw, fp = pitch;
                    queueEvent(() -> renderer.setTouchOrientation(fy, fp, 0f));
                    notifyView();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false; return true;
        }
        return true;
    }

    private void notifyView() {
        if (listener != null) listener.onViewChanged(yaw, pitch, settings.viewFovDeg);
    }

    void release() { queueEvent(renderer::release); }

    private static float clamp(float x, float lo, float hi) { return Math.max(lo, Math.min(hi, x)); }
}
