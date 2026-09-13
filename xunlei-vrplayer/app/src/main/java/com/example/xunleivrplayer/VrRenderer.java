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

final class VrRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    interface SurfaceListener {
        void onVideoSurfaceReady(Surface surface);
        void onVideoSurfaceDestroyed(Surface surface);
    }

    private static final float[] QUAD = {
            -1f,-1f,0f,1f,  1f,-1f,1f,1f,
            -1f, 1f,0f,0f,  1f, 1f,1f,0f
    };

    private final FloatBuffer quad;
    private final AtomicBoolean frameAvailable = new AtomicBoolean(false);
    private final SurfaceListener listener;
    private final float[] texMatrix = new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};

    private int textureId, program, aPos, aUv;
    private int uTex,uTexMatrix,uProjection,uStereo,uEye,uAspect,uViewFov;
    private int uYaw,uPitch,uRoll,uFishFov,uFishCenter,uFishRadius,uFishScale,uFishK,uDualLayout,uDualSwap;
    private SurfaceTexture surfaceTexture;
    private Surface videoSurface;
    private volatile ProjectionSettings settings = new ProjectionSettings();
    private volatile float touchYawDeg, touchPitchDeg, touchRollDeg;
    private volatile float aspect = 16f/9f;

    VrRenderer(SurfaceListener listener) {
        this.listener = listener;
        quad = ByteBuffer.allocateDirect(QUAD.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);
    }

    void setSettings(ProjectionSettings s){ settings=s.copy(); }
    void setTouchOrientation(float yaw,float pitch,float roll){
        touchYawDeg=yaw; touchPitchDeg=Math.max(-89.5f,Math.min(89.5f,pitch)); touchRollDeg=roll;
    }

    public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
        textureId=createExternalTexture();
        surfaceTexture=new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(this);
        videoSurface=new Surface(surfaceTexture);
        program=buildProgram(VERTEX,FRAGMENT);
        aPos=GLES20.glGetAttribLocation(program,"aPosition");
        aUv=GLES20.glGetAttribLocation(program,"aTexCoord");
        uTex=GLES20.glGetUniformLocation(program,"uTexture");
        uTexMatrix=GLES20.glGetUniformLocation(program,"uTexMatrix");
        uProjection=GLES20.glGetUniformLocation(program,"uProjection");
        uStereo=GLES20.glGetUniformLocation(program,"uStereo");
        uEye=GLES20.glGetUniformLocation(program,"uEye");
        uAspect=GLES20.glGetUniformLocation(program,"uAspect");
        uViewFov=GLES20.glGetUniformLocation(program,"uViewFovDeg");
        uYaw=GLES20.glGetUniformLocation(program,"uYawDeg");
        uPitch=GLES20.glGetUniformLocation(program,"uPitchDeg");
        uRoll=GLES20.glGetUniformLocation(program,"uRollDeg");
        uFishFov=GLES20.glGetUniformLocation(program,"uFishFovDeg");
        uFishCenter=GLES20.glGetUniformLocation(program,"uFishCenter");
        uFishRadius=GLES20.glGetUniformLocation(program,"uFishRadius");
        uFishScale=GLES20.glGetUniformLocation(program,"uFishScale");
        uFishK=GLES20.glGetUniformLocation(program,"uFishK");
        uDualLayout=GLES20.glGetUniformLocation(program,"uDualLayout");
        uDualSwap=GLES20.glGetUniformLocation(program,"uDualSwap");
        GLES20.glClearColor(0f,0f,0f,1f);
        if(listener!=null) listener.onVideoSurfaceReady(videoSurface);
    }

    public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl,int width,int height){
        GLES20.glViewport(0,0,width,height); if(height>0) aspect=(float)width/(float)height;
    }

    public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl){
        if(surfaceTexture!=null && frameAvailable.compareAndSet(true,false)){
            try{ surfaceTexture.updateTexImage(); surfaceTexture.getTransformMatrix(texMatrix); }catch(RuntimeException ignored){}
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if(program==0)return;
        ProjectionSettings s=settings;
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
        GLES20.glUniform1i(uTex,0);
        GLES20.glUniformMatrix4fv(uTexMatrix,1,false,texMatrix,0);
        GLES20.glUniform1i(uProjection,s.projection);
        GLES20.glUniform1i(uStereo,s.stereoLayout);
        GLES20.glUniform1i(uEye,s.eye);
        GLES20.glUniform1f(uAspect,aspect);
        GLES20.glUniform1f(uViewFov,s.viewFovDeg);
        GLES20.glUniform1f(uYaw,touchYawDeg+s.sourceYawDeg);
        GLES20.glUniform1f(uPitch,touchPitchDeg+s.sourcePitchDeg);
        GLES20.glUniform1f(uRoll,touchRollDeg+s.sourceRollDeg);
        GLES20.glUniform1f(uFishFov,s.fisheyeFovDeg);
        GLES20.glUniform2f(uFishCenter,s.fishCenterX,s.fishCenterY);
        GLES20.glUniform1f(uFishRadius,s.fishRadius);
        GLES20.glUniform2f(uFishScale,s.fishScaleX,s.fishScaleY);
        GLES20.glUniform3f(uFishK,s.fishK1,s.fishK2,s.fishK3);
        GLES20.glUniform1i(uDualLayout,s.dualLensLayout);
        GLES20.glUniform1i(uDualSwap,s.dualLensSwap?1:0);
        quad.position(0); GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos,2,GLES20.GL_FLOAT,false,16,quad);
        quad.position(2); GLES20.glEnableVertexAttribArray(aUv); GLES20.glVertexAttribPointer(aUv,2,GLES20.GL_FLOAT,false,16,quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aUv);
    }

    public void onFrameAvailable(SurfaceTexture st){ frameAvailable.set(true); }

    void release(){
        Surface s=videoSurface;
        if(listener!=null&&s!=null)listener.onVideoSurfaceDestroyed(s);
        if(s!=null)s.release(); videoSurface=null;
        if(surfaceTexture!=null)surfaceTexture.release(); surfaceTexture=null;
    }

    private static int createExternalTexture(){
        int[] t=new int[1]; GLES20.glGenTextures(1,t,0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,t[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    private static int compile(int type,String src){
        int s=GLES20.glCreateShader(type); GLES20.glShaderSource(s,src); GLES20.glCompileShader(s);
        int[] ok=new int[1]; GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0)throw new RuntimeException("GL shader failed: "+GLES20.glGetShaderInfoLog(s)); return s;
    }
    private static int buildProgram(String vs,String fs){
        int v=compile(GLES20.GL_VERTEX_SHADER,vs), f=compile(GLES20.GL_FRAGMENT_SHADER,fs), p=GLES20.glCreateProgram();
        GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p);
        int[] ok=new int[1]; GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0)throw new RuntimeException("GL link failed: "+GLES20.glGetProgramInfoLog(p));
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f); return p;
    }

    private static final String VERTEX=
            "attribute vec2 aPosition;\nattribute vec2 aTexCoord;\nvarying vec2 vTexCoord;\n"+
            "void main(){vTexCoord=aTexCoord;gl_Position=vec4(aPosition,0.0,1.0);}\n";

    private static final String FRAGMENT=
            "#extension GL_OES_EGL_image_external : require\n"+
            "precision highp float;\n"+
            "uniform samplerExternalOES uTexture;uniform mat4 uTexMatrix;\n"+
            "uniform int uProjection;uniform int uStereo;uniform int uEye;\n"+
            "uniform float uAspect;uniform float uViewFovDeg;uniform float uYawDeg;uniform float uPitchDeg;uniform float uRollDeg;\n"+
            "uniform float uFishFovDeg;uniform vec2 uFishCenter;uniform float uFishRadius;uniform vec2 uFishScale;uniform vec3 uFishK;\n"+
            "uniform int uDualLayout;uniform int uDualSwap;varying vec2 vTexCoord;const float PI=3.14159265358979323846;\n"+
            "float rad(float d){return d*PI/180.0;}\n"+
            "vec3 rx(vec3 p,float a){float c=cos(a),s=sin(a);return vec3(p.x,c*p.y-s*p.z,s*p.y+c*p.z);}\n"+
            "vec3 ry(vec3 p,float a){float c=cos(a),s=sin(a);return vec3(c*p.x+s*p.z,p.y,-s*p.x+c*p.z);}\n"+
            "vec3 rz(vec3 p,float a){float c=cos(a),s=sin(a);return vec3(c*p.x-s*p.y,s*p.x+c*p.y,p.z);}\n"+
            "vec2 stereo(vec2 uv){if(uStereo==1)return vec2((uv.x+float(uEye))*0.5,uv.y);if(uStereo==2)return vec2((uv.x+float(1-uEye))*0.5,uv.y);if(uStereo==3)return vec2(uv.x,(uv.y+float(uEye))*0.5);if(uStereo==4)return vec2(uv.x,(uv.y+float(1-uEye))*0.5);return uv;}\n"+
            "vec2 fish(vec3 d,out float ok){float th=acos(clamp(d.z,-1.0,1.0));float m=rad(uFishFovDeg*0.5);if(th>m){ok=0.0;return vec2(0.0);}float st=sin(th);vec2 q=st<0.00001?vec2(0.0):vec2(d.x,d.y)/st;float r=th/max(m,0.00001);float r2=r*r;float rc=r*(1.0+uFishK.x*r2+uFishK.y*r2*r2+uFishK.z*r2*r2*r2);vec2 uv=uFishCenter+vec2(q.x*uFishScale.x,-q.y*uFishScale.y)*(uFishRadius*rc);if(uv.x<0.0||uv.x>1.0||uv.y<0.0||uv.y>1.0)ok=0.0;return uv;}\n"+
            "vec2 cubeFace(vec3 d,bool eac,out float face){vec3 a=abs(d);float u=0.0,v=0.0;if(a.x>=a.y&&a.x>=a.z){if(d.x>0.0){face=0.0;u=-d.z/a.x;v=d.y/a.x;}else{face=1.0;u=d.z/a.x;v=d.y/a.x;}}else if(a.y>=a.x&&a.y>=a.z){if(d.y>0.0){face=2.0;u=d.x/a.y;v=-d.z/a.y;}else{face=3.0;u=d.x/a.y;v=d.z/a.y;}}else{if(d.z>0.0){face=4.0;u=d.x/a.z;v=d.y/a.z;}else{face=5.0;u=-d.x/a.z;v=d.y/a.z;}}if(eac){u=atan(u)/(PI*0.25);v=atan(v)/(PI*0.25);}return vec2((u+1.0)*0.5,(1.0-v)*0.5);}\n"+
            "vec2 cube(vec3 d,bool eac){float face;vec2 f=cubeFace(d,eac,face);float col=mod(face,3.0),row=floor(face/3.0);return(f+vec2(col,row))/vec2(3.0,2.0);}\n"+
            "void main(){vec2 src;float ok=1.0;if(uProjection==1){src=stereo(vTexCoord);}else{float t=tan(rad(uViewFovDeg)*0.5);vec2 p=vec2((vTexCoord.x*2.0-1.0)*uAspect*t,(1.0-vTexCoord.y*2.0)*t);vec3 d=normalize(vec3(p,1.0));d=rz(d,rad(uRollDeg));d=rx(d,rad(uPitchDeg));d=ry(d,rad(uYawDeg));if(uProjection==2){float lon=atan(d.x,d.z),lat=asin(clamp(d.y,-1.0,1.0));if(abs(lon)>PI*0.5)ok=0.0;src=stereo(vec2(lon/PI+0.5,0.5-lat/PI));}else if(uProjection==3){float lon=atan(d.x,d.z),lat=asin(clamp(d.y,-1.0,1.0));src=stereo(vec2(lon/(2.0*PI)+0.5,0.5-lat/PI));}else if(uProjection==4){src=stereo(fish(d,ok));}else if(uProjection==5){bool back=d.z<0.0;vec3 q=back?vec3(-d.x,d.y,-d.z):d;vec2 fuv=fish(q,ok);int lens=back?1:0;if(uDualSwap==1)lens=1-lens;if(uDualLayout==0)src=vec2((fuv.x+float(lens))*0.5,fuv.y);else src=vec2(fuv.x,(fuv.y+float(lens))*0.5);}else if(uProjection==6){src=stereo(cube(d,false));}else if(uProjection==7){src=stereo(cube(d,true));}else{src=stereo(vTexCoord);}}if(ok<0.5||src.x<0.0||src.x>1.0||src.y<0.0||src.y>1.0){gl_FragColor=vec4(0.0,0.0,0.0,1.0);}else{vec2 tuv=(uTexMatrix*vec4(src,0.0,1.0)).xy;gl_FragColor=texture2D(uTexture,tuv);}}\n";
}
