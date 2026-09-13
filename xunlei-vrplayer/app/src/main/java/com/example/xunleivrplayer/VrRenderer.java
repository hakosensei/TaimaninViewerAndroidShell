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
 * 自研 VR -> 平面渲染器。
 *
 * 这版把两个原本混在一起的问题分层处理：
 * 1) Stereo reconstruction：单眼 / 固定球壳 / 智能优势眼 / 局部视差中眼。
 * 2) Display projection：Rectilinear / 人眼宽视野混合 / Panini。
 *
 * “局部视差中眼”是为了手机实时测试而做的轻量近似：在固定球壳给出的初始几何附近，
 * 对左右眼做 7 点一维局部对应搜索，再把最佳残余视差各向中央移动一半后融合。
 * 它不是离线科研级深度网络，但和简单 50/50 叠图有本质区别。
 */
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
    private int uTex,uTexMatrix,uProjection,uStereo,uEye,uStereoView,uOutputProjection,uAspect,uViewFov;
    private int uYaw,uPitch,uRoll,uFishFov,uFishCenter,uFishRadius,uFishScale,uFishK,uDualLayout,uDualSwap,uFlipX,uFlipY;
    private int uIpdMm,uShellDepth,uDispRange,uSmartThreshold,uPaniniD;
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
        uStereoView=GLES20.glGetUniformLocation(program,"uStereoView");
        uOutputProjection=GLES20.glGetUniformLocation(program,"uOutputProjection");
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
        uFlipX=GLES20.glGetUniformLocation(program,"uFlipX");
        uFlipY=GLES20.glGetUniformLocation(program,"uFlipY");
        uIpdMm=GLES20.glGetUniformLocation(program,"uIpdMm");
        uShellDepth=GLES20.glGetUniformLocation(program,"uShellDepthM");
        uDispRange=GLES20.glGetUniformLocation(program,"uDispRange");
        uSmartThreshold=GLES20.glGetUniformLocation(program,"uSmartThreshold");
        uPaniniD=GLES20.glGetUniformLocation(program,"uPaniniD");
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
        GLES20.glUniform1i(uStereoView,s.stereoViewMode);
        GLES20.glUniform1i(uOutputProjection,s.outputProjection);
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
        GLES20.glUniform1i(uFlipX,s.sourceFlipX?1:0);
        GLES20.glUniform1i(uFlipY,s.sourceFlipY?1:0);
        GLES20.glUniform1f(uIpdMm,s.stereoIpdMm);
        GLES20.glUniform1f(uShellDepth,s.shellDepthM);
        GLES20.glUniform1f(uDispRange,s.disparityRange);
        GLES20.glUniform1f(uSmartThreshold,s.smartThreshold);
        GLES20.glUniform1f(uPaniniD,s.paniniD);
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
            "uniform int uProjection;uniform int uStereo;uniform int uEye;uniform int uStereoView;uniform int uOutputProjection;uniform int uFlipX;uniform int uFlipY;\n"+
            "uniform float uAspect;uniform float uViewFovDeg;uniform float uYawDeg;uniform float uPitchDeg;uniform float uRollDeg;\n"+
            "uniform float uFishFovDeg;uniform vec2 uFishCenter;uniform float uFishRadius;uniform vec2 uFishScale;uniform vec3 uFishK;\n"+
            "uniform int uDualLayout;uniform int uDualSwap;uniform float uIpdMm;uniform float uShellDepthM;uniform float uDispRange;uniform float uSmartThreshold;uniform float uPaniniD;\n"+
            "varying vec2 vTexCoord;const float PI=3.14159265358979323846;\n"+
            "float rad(float d){return d*PI/180.0;}\n"+
            "float lum(vec3 c){return dot(c,vec3(0.299,0.587,0.114));}\n"+
            "vec3 rx(vec3 p,float a){float c=cos(a),s=sin(a);return vec3(p.x,c*p.y-s*p.z,s*p.y+c*p.z);}\n"+
            "vec3 ry(vec3 p,float a){float c=cos(a),s=sin(a);return vec3(c*p.x+s*p.z,p.y,-s*p.x+c*p.z);}\n"+
            "vec3 rz(vec3 p,float a){float c=cos(a),s=sin(a);return vec3(c*p.x-s*p.y,s*p.x+c*p.y,p.z);}\n"+
            "vec3 orient(vec3 d){d=rz(d,rad(uRollDeg));d=rx(d,rad(uPitchDeg));d=ry(d,rad(uYawDeg));return d;}\n"+
            "vec2 packEye(vec2 uv,int e){if(uStereo==1)return vec2((uv.x+float(e))*0.5,uv.y);if(uStereo==2)return vec2((uv.x+float(1-e))*0.5,uv.y);if(uStereo==3)return vec2(uv.x,(uv.y+float(e))*0.5);if(uStereo==4)return vec2(uv.x,(uv.y+float(1-e))*0.5);return uv;}\n"+
            "vec4 sampleImage(vec2 src){vec2 gluv=vec2(src.x,1.0-src.y);vec2 tuv=(uTexMatrix*vec4(gluv,0.0,1.0)).xy;return texture2D(uTexture,tuv);}\n"+
            "vec4 sampleEyeUv(vec2 uv,int e,out float ok){ok=1.0;if(uv.x<0.0||uv.x>1.0||uv.y<0.0||uv.y>1.0){ok=0.0;return vec4(0.0);}if(uFlipX==1)uv.x=1.0-uv.x;if(uFlipY==1)uv.y=1.0-uv.y;vec2 src=(uProjection==5||uStereo==0)?uv:packEye(uv,e);if(src.x<0.0||src.x>1.0||src.y<0.0||src.y>1.0){ok=0.0;return vec4(0.0);}return sampleImage(src);}\n"+
            "vec2 fish(vec3 d,out float ok){float th=acos(clamp(d.z,-1.0,1.0));float m=rad(uFishFovDeg*0.5);if(th>m){ok=0.0;return vec2(0.0);}float st=sin(th);vec2 q=st<0.00001?vec2(0.0):vec2(d.x,d.y)/st;float r=th/max(m,0.00001);float r2=r*r;float rc=r*(1.0+uFishK.x*r2+uFishK.y*r2*r2+uFishK.z*r2*r2*r2);vec2 uv=uFishCenter+vec2(q.x*uFishScale.x,-q.y*uFishScale.y)*(uFishRadius*rc);if(uv.x<0.0||uv.x>1.0||uv.y<0.0||uv.y>1.0)ok=0.0;return uv;}\n"+
            "vec2 cubeFace(vec3 d,bool eac,out float face){vec3 a=abs(d);float u=0.0,v=0.0;if(a.x>=a.y&&a.x>=a.z){if(d.x>0.0){face=0.0;u=-d.z/a.x;v=d.y/a.x;}else{face=1.0;u=d.z/a.x;v=d.y/a.x;}}else if(a.y>=a.x&&a.y>=a.z){if(d.y>0.0){face=2.0;u=d.x/a.y;v=-d.z/a.y;}else{face=3.0;u=d.x/a.y;v=d.z/a.y;}}else{if(d.z>0.0){face=4.0;u=d.x/a.z;v=d.y/a.z;}else{face=5.0;u=-d.x/a.z;v=d.y/a.z;}}if(eac){u=atan(u)/(PI*0.25);v=atan(v)/(PI*0.25);}return vec2((u+1.0)*0.5,(1.0-v)*0.5);}\n"+
            "vec2 cube(vec3 d,bool eac){float face;vec2 f=cubeFace(d,eac,face);float col=mod(face,3.0),row=floor(face/3.0);return(f+vec2(col,row))/vec2(3.0,2.0);}\n"+
            "vec2 mapDir(vec3 d,out float ok){ok=1.0;if(uProjection==2){float lon=atan(d.x,d.z),lat=asin(clamp(d.y,-1.0,1.0));if(abs(lon)>PI*0.5)ok=0.0;return vec2(lon/PI+0.5,0.5-lat/PI);}if(uProjection==3){float lon=atan(d.x,d.z),lat=asin(clamp(d.y,-1.0,1.0));return vec2(lon/(2.0*PI)+0.5,0.5-lat/PI);}if(uProjection==4)return fish(d,ok);if(uProjection==5){bool back=d.z<0.0;vec3 q=back?vec3(-d.x,d.y,-d.z):d;vec2 fuv=fish(q,ok);int lens=back?1:0;if(uDualSwap==1)lens=1-lens;if(uDualLayout==0)return vec2((fuv.x+float(lens))*0.5,fuv.y);return vec2(fuv.x,(fuv.y+float(lens))*0.5);}if(uProjection==6)return cube(d,false);if(uProjection==7)return cube(d,true);return vTexCoord;}\n"+
            "vec3 rayRect(vec2 tc){float t=tan(rad(uViewFovDeg)*0.5);vec2 p=vec2((tc.x*2.0-1.0)*uAspect*t,(1.0-tc.y*2.0)*t);return normalize(vec3(p,1.0));}\n"+
            "vec3 rayAngular(vec2 tc){float vh=rad(uViewFovDeg*0.5);float hh=atan(uAspect*tan(vh));float ya=(tc.x*2.0-1.0)*hh;float pi=(1.0-tc.y*2.0)*vh;float cp=cos(pi);return normalize(vec3(sin(ya)*cp,sin(pi),cos(ya)*cp));}\n"+
            "vec3 rayPanini(vec2 tc){float vh=rad(uViewFovDeg*0.5);float hh=atan(uAspect*tan(vh));float d=max(0.1,uPaniniD);float sx=tc.x*2.0-1.0;float sy=1.0-tc.y*2.0;float xmax=(d+1.0)*sin(hh)/(d+cos(hh));float target=sx*xmax;float th=sx*hh;for(int i=0;i<4;i++){float den=d+cos(th);float f=(d+1.0)*sin(th)/den-target;float df=(d+1.0)*(d*cos(th)+1.0)/(den*den);th-=f/max(df,0.001);}float py=sy*tan(vh);float ph=atan(py*(d+cos(th))/(d+1.0));float cp=cos(ph);return normalize(vec3(sin(th)*cp,sin(ph),cos(th)*cp));}\n"+
            "vec3 outputRay(vec2 tc){if(uOutputProjection==1)return normalize(mix(rayRect(tc),rayAngular(tc),0.72));if(uOutputProjection==2)return rayPanini(tc);return rayRect(tc);}\n"+
            "vec4 sampleDirEye(vec3 d,int e,out float ok){vec2 uv=mapDir(d,ok);if(ok<0.5)return vec4(0.0);return sampleEyeUv(uv,e,ok);}\n"+
            "vec4 dominantColor(vec4 l,vec4 r,vec3 d){float chooseR;if(abs(d.x)<0.12)chooseR=float(uEye);else chooseR=step(0.0,d.x);return mix(l,r,chooseR);}\n"+
            "float matchCost(vec2 ul,vec2 ur,float sh,out float valid){float a=1.0,b=1.0;vec4 l=sampleEyeUv(ul+vec2(sh*0.5,0.0),0,a);vec4 r=sampleEyeUv(ur-vec2(sh*0.5,0.0),1,b);valid=a*b;if(valid<0.5)return 9.0;return abs(lum(l.rgb)-lum(r.rgb));}\n"+
            "void testShift(vec2 ul,vec2 ur,float sh,inout float best,inout float bestSh){float v=0.0;float c=matchCost(ul,ur,sh,v);if(v>0.5&&c<best){best=c;bestSh=sh;}}\n"+
            "vec4 combinePair(vec2 ul,vec2 ur,vec3 d,int mode,out float ok){float ol=1.0,orr=1.0;vec4 l=sampleEyeUv(ul,0,ol);vec4 r=sampleEyeUv(ur,1,orr);if(ol<0.5&&orr<0.5){ok=0.0;return vec4(0.0);}if(ol<0.5){ok=orr;return r;}if(orr<0.5){ok=ol;return l;}ok=1.0;if(mode==1)return 0.5*(l+r);float diff=dot(abs(l.rgb-r.rgb),vec3(0.333333));if(mode==2){if(diff<uSmartThreshold)return 0.5*(l+r);return dominantColor(l,r,d);}float rg=max(0.0,uDispRange);float best=9.0;float bs=0.0;testShift(ul,ur,-rg,best,bs);testShift(ul,ur,-rg*0.666667,best,bs);testShift(ul,ur,-rg*0.333333,best,bs);testShift(ul,ur,0.0,best,bs);testShift(ul,ur,rg*0.333333,best,bs);testShift(ul,ur,rg*0.666667,best,bs);testShift(ul,ur,rg,best,bs);float a=1.0,b=1.0;vec4 ll=sampleEyeUv(ul+vec2(bs*0.5,0.0),0,a);vec4 rr=sampleEyeUv(ur-vec2(bs*0.5,0.0),1,b);if(a<0.5)return rr;if(b<0.5)return ll;if(best<max(0.035,uSmartThreshold*1.6))return 0.5*(ll+rr);return dominantColor(ll,rr,d);}\n"+
            "vec4 renderStereo(vec3 d,out float ok){if(uStereo==0||uProjection==5){return sampleDirEye(d,uEye,ok);}if(uStereoView==0){return sampleDirEye(d,uEye,ok);}float ratio=clamp((uIpdMm*0.0005)/max(uShellDepthM,0.05),0.0,0.35);vec3 dl=normalize(d+vec3(ratio,0.0,0.0));vec3 dr=normalize(d-vec3(ratio,0.0,0.0));float ml=1.0,mr=1.0;vec2 ul=mapDir(dl,ml);vec2 ur=mapDir(dr,mr);if(ml<0.5&&mr<0.5){ok=0.0;return vec4(0.0);}if(ml<0.5){float x=1.0;vec4 c=sampleEyeUv(ur,1,x);ok=x;return c;}if(mr<0.5){float x=1.0;vec4 c=sampleEyeUv(ul,0,x);ok=x;return c;}return combinePair(ul,ur,d,uStereoView,ok);}\n"+
            "vec4 renderFlat(out float ok){vec2 base=vTexCoord;if(uStereo==0||uStereoView==0)return sampleEyeUv(base,uEye,ok);float ratio=clamp((uIpdMm*0.0005)/max(uShellDepthM,0.05),0.0,0.35);vec2 ul=base+vec2(ratio*0.25,0.0);vec2 ur=base-vec2(ratio*0.25,0.0);vec3 d=normalize(vec3((base.x-0.5)*2.0,0.0,1.0));return combinePair(ul,ur,d,uStereoView,ok);}\n"+
            "void main(){float ok=1.0;vec4 c;if(uProjection==1){c=renderFlat(ok);}else{vec3 d=orient(outputRay(vTexCoord));c=renderStereo(d,ok);}if(ok<0.5)gl_FragColor=vec4(0.0,0.0,0.0,1.0);else gl_FragColor=vec4(c.rgb,1.0);}\n";
}
