package com.example.xunleivrplayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 独立 VR -> 平面播放器。
 *
 * 解码：Android MediaCodec/Media3。
 * 手动投影：本项目的 VrRenderer GLSL。
 * AUTO_METADATA：使用 Media3 的标准 Spherical Video V2 / projection mesh 路径，
 * 用来兼容真正带 Google VR180 mesh metadata 的文件。
 */
@UnstableApi
public class PlayerActivity extends Activity {
    private static final String E_URL="url", E_UA="ua", E_NAME="name";
    private static final String E_P="p",E_S="s",E_E="e",E_VF="vf",E_FF="ff",E_CX="cx",E_CY="cy",E_R="r",E_SX="sx",E_SY="sy",E_K1="k1",E_K2="k2",E_K3="k3",E_Y="yaw",E_PI="pitch",E_RO="roll",E_DL="dl",E_DS="ds";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private FrameLayout videoHost;
    private View renderView;
    private VrGlSurfaceView customView;
    private SphericalGLSurfaceView sphericalView;
    private ProjectionSettings settings;
    private TextView modeText, timeText;
    private SeekBar seek;
    private Button playPause;
    private String url, ua, name;
    private boolean seeking;

    public static void start(Context c, String url, String ua, String name, ProjectionSettings s) {
        Intent i = new Intent(c, PlayerActivity.class);
        i.putExtra(E_URL,url); i.putExtra(E_UA,ua==null?"":ua); i.putExtra(E_NAME,name==null?"VR Video":name);
        putSettings(i,s);
        c.startActivity(i);
    }

    private static void putSettings(Intent i, ProjectionSettings s) {
        i.putExtra(E_P,s.projection); i.putExtra(E_S,s.stereoLayout); i.putExtra(E_E,s.eye);
        i.putExtra(E_VF,s.viewFovDeg); i.putExtra(E_FF,s.fisheyeFovDeg); i.putExtra(E_CX,s.fishCenterX); i.putExtra(E_CY,s.fishCenterY); i.putExtra(E_R,s.fishRadius);
        i.putExtra(E_SX,s.fishScaleX); i.putExtra(E_SY,s.fishScaleY); i.putExtra(E_K1,s.fishK1); i.putExtra(E_K2,s.fishK2); i.putExtra(E_K3,s.fishK3);
        i.putExtra(E_Y,s.sourceYawDeg); i.putExtra(E_PI,s.sourcePitchDeg); i.putExtra(E_RO,s.sourceRollDeg); i.putExtra(E_DL,s.dualLensLayout); i.putExtra(E_DS,s.dualLensSwap);
    }
    private static ProjectionSettings readSettings(Intent i) {
        ProjectionSettings s=new ProjectionSettings();
        s.projection=i.getIntExtra(E_P,s.projection); s.stereoLayout=i.getIntExtra(E_S,s.stereoLayout); s.eye=i.getIntExtra(E_E,s.eye);
        s.viewFovDeg=i.getFloatExtra(E_VF,s.viewFovDeg); s.fisheyeFovDeg=i.getFloatExtra(E_FF,s.fisheyeFovDeg); s.fishCenterX=i.getFloatExtra(E_CX,s.fishCenterX); s.fishCenterY=i.getFloatExtra(E_CY,s.fishCenterY); s.fishRadius=i.getFloatExtra(E_R,s.fishRadius);
        s.fishScaleX=i.getFloatExtra(E_SX,s.fishScaleX); s.fishScaleY=i.getFloatExtra(E_SY,s.fishScaleY); s.fishK1=i.getFloatExtra(E_K1,s.fishK1); s.fishK2=i.getFloatExtra(E_K2,s.fishK2); s.fishK3=i.getFloatExtra(E_K3,s.fishK3);
        s.sourceYawDeg=i.getFloatExtra(E_Y,s.sourceYawDeg); s.sourcePitchDeg=i.getFloatExtra(E_PI,s.sourcePitchDeg); s.sourceRollDeg=i.getFloatExtra(E_RO,s.sourceRollDeg); s.dualLensLayout=i.getIntExtra(E_DL,s.dualLensLayout); s.dualLensSwap=i.getBooleanExtra(E_DS,s.dualLensSwap);
        return s;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        url=getIntent().getStringExtra(E_URL); ua=getIntent().getStringExtra(E_UA); name=getIntent().getStringExtra(E_NAME);
        settings=readSettings(getIntent());
        buildUi();
        createPlayer();
        attachRenderer(settings.projection == ProjectionSettings.AUTO_METADATA);
        prepareAndPlay(0L,true);
    }

    private void buildUi() {
        FrameLayout root=new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        videoHost=new FrameLayout(this); root.addView(videoHost,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(10),dp(8),dp(10),dp(8)); top.setBackgroundColor(0x88000000);
        Button back=new Button(this); back.setText("←"); top.addView(back,new LinearLayout.LayoutParams(dp(58),dp(48)));
        TextView title=new TextView(this); title.setTextColor(Color.WHITE); title.setText(name); title.setSingleLine(true); top.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        modeText=new TextView(this); modeText.setTextColor(Color.WHITE); modeText.setText(settings.shortLabel()); top.addView(modeText);
        FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,dp(64),Gravity.TOP); root.addView(top,tp);
        back.setOnClickListener(v->finish());

        LinearLayout bottom=new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setPadding(dp(8),dp(4),dp(8),dp(8)); bottom.setBackgroundColor(0x99000000);
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        playPause=new Button(this); playPause.setText("暂停"); row.addView(playPause,new LinearLayout.LayoutParams(dp(78),dp(48)));
        Button reset=new Button(this); reset.setText("回正"); row.addView(reset,new LinearLayout.LayoutParams(dp(72),dp(48)));
        Button mode=new Button(this); mode.setText("投影"); row.addView(mode,new LinearLayout.LayoutParams(dp(72),dp(48)));
        Button eye=new Button(this); eye.setText("换眼"); row.addView(eye,new LinearLayout.LayoutParams(dp(72),dp(48)));
        timeText=new TextView(this); timeText.setTextColor(Color.WHITE); timeText.setGravity(Gravity.CENTER_VERTICAL|Gravity.END); row.addView(timeText,new LinearLayout.LayoutParams(0,dp(48),1));
        bottom.addView(row);
        seek=new SeekBar(this); bottom.addView(seek,new LinearLayout.LayoutParams(-1,dp(40)));
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,dp(100),Gravity.BOTTOM); root.addView(bottom,bp);
        setContentView(root);

        playPause.setOnClickListener(v->{ if(player==null)return; if(player.isPlaying()) player.pause(); else player.play(); updatePlayButton();});
        reset.setOnClickListener(v->{ if(customView!=null) customView.resetView(); });
        mode.setOnClickListener(v-> ProjectionDialog.show(this,settings,this::applyNewSettings));
        eye.setOnClickListener(v->{ settings.eye = settings.eye==ProjectionSettings.EYE_LEFT?ProjectionSettings.EYE_RIGHT:ProjectionSettings.EYE_LEFT; if(customView!=null)customView.setProjectionSettings(settings); modeText.setText(settings.shortLabel());});
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean from){if(from&&player!=null&&player.getDuration()>0) timeText.setText(format((long)(player.getDuration()*(p/1000f)))+" / "+format(player.getDuration()));}
            public void onStartTrackingTouch(SeekBar b){seeking=true;}
            public void onStopTrackingTouch(SeekBar b){if(player!=null&&player.getDuration()>0)player.seekTo((long)(player.getDuration()*(b.getProgress()/1000f)));seeking=false;}
        });
        ui.post(ticker);
    }

    private void createPlayer() {
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setUserAgent((ua==null||ua.isEmpty())?"XunleiVRPlayer/2.0":ua);
        if(ua!=null&&!ua.isEmpty()) { Map<String,String> h=new HashMap<>(); h.put("User-Agent",ua); http.setDefaultRequestProperties(h); }
        DefaultDataSource.Factory ds=new DefaultDataSource.Factory(this,http);
        player=new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(ds)).build();
        player.addListener(new Player.Listener(){
            @Override public void onIsPlayingChanged(boolean isPlaying){updatePlayButton();}
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error){Toast.makeText(PlayerActivity.this,"播放失败："+error.getMessage(),Toast.LENGTH_LONG).show();}
        });
    }

    private void prepareAndPlay(long pos, boolean autoplay) {
        if(url==null||url.isEmpty()) { Toast.makeText(this,"没有视频地址",Toast.LENGTH_LONG).show(); return; }
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
        player.prepare();
        if(pos>0)player.seekTo(pos);
        if(autoplay)player.play();
    }

    private void attachRenderer(boolean autoMetadata) {
        videoHost.removeAllViews(); customView=null; sphericalView=null;
        if(autoMetadata) {
            sphericalView=new SphericalGLSurfaceView(this);
            sphericalView.setUseSensorRotation(false);
            sphericalView.setDefaultStereoMode(toMedia3Stereo(settings.stereoLayout));
            sphericalView.addVideoSurfaceListener(new SphericalGLSurfaceView.VideoSurfaceListener(){
                @Override public void onVideoSurfaceCreated(Surface surface){ if(player!=null)player.setVideoSurface(surface); }
                @Override public void onVideoSurfaceDestroyed(Surface surface){ if(player!=null)player.clearVideoSurface(surface); }
            });
            player.setVideoFrameMetadataListener(sphericalView.getVideoFrameMetadataListener());
            player.setCameraMotionListener(sphericalView.getCameraMotionListener());
            renderView=sphericalView;
        } else {
            customView=new VrGlSurfaceView(this,settings,new VrGlSurfaceView.Listener(){
                @Override public void onVideoSurfaceReady(Surface surface){if(player!=null)player.setVideoSurface(surface);}
                @Override public void onVideoSurfaceDestroyed(Surface surface){if(player!=null)player.clearVideoSurface(surface);}
                @Override public void onViewChanged(float yaw,float pitch,float fov){}
            });
            renderView=customView;
        }
        videoHost.addView(renderView,new FrameLayout.LayoutParams(-1,-1));
    }

    private void applyNewSettings(ProjectionSettings s) {
        boolean wasAuto=settings.projection==ProjectionSettings.AUTO_METADATA;
        boolean nowAuto=s.projection==ProjectionSettings.AUTO_METADATA;
        settings=s.copy(); modeText.setText(settings.shortLabel());
        if(wasAuto!=nowAuto) {
            long pos=player.getCurrentPosition(); boolean playing=player.isPlaying();
            player.pause(); player.clearVideoSurface();
            attachRenderer(nowAuto);
            player.seekTo(pos); if(playing)player.play();
        } else if(nowAuto) {
            sphericalView.setDefaultStereoMode(toMedia3Stereo(settings.stereoLayout));
        } else if(customView!=null) customView.setProjectionSettings(settings);
    }

    private static int toMedia3Stereo(int layout) {
        if(layout==ProjectionSettings.TB||layout==ProjectionSettings.BT) return C.STEREO_MODE_TOP_BOTTOM;
        if(layout==ProjectionSettings.SBS_LR||layout==ProjectionSettings.SBS_RL) return C.STEREO_MODE_LEFT_RIGHT;
        return C.STEREO_MODE_MONO;
    }

    private final Runnable ticker=new Runnable(){public void run(){
        if(player!=null&&!seeking&&player.getDuration()>0){long d=player.getDuration(),p=player.getCurrentPosition();seek.setProgress((int)Math.min(1000,p*1000L/Math.max(1,d)));timeText.setText(format(p)+" / "+format(d));}
        ui.postDelayed(this,500);
    }};

    private void updatePlayButton(){if(playPause!=null&&player!=null)playPause.setText(player.isPlaying()?"暂停":"播放");}
    private static String format(long ms){if(ms<0)return"--:--";long s=ms/1000;return String.format(Locale.ROOT,"%02d:%02d:%02d",s/3600,(s/60)%60,s%60);}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}

    @Override protected void onResume(){super.onResume();if(customView!=null)customView.onResume();if(sphericalView!=null)sphericalView.onResume();}
    @Override protected void onPause(){if(customView!=null)customView.onPause();if(sphericalView!=null)sphericalView.onPause();super.onPause();}
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);if(customView!=null)customView.release();if(player!=null){player.release();player=null;}super.onDestroy();}
}
