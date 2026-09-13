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
import androidx.media3.exoplayer.DefaultLoadControl;
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
 * AUTO_METADATA：使用 Media3 的标准 Spherical Video V2 / projection mesh 路径。
 */
@UnstableApi
public class PlayerActivity extends Activity {
    private static final String E_URL="url", E_UA="ua", E_NAME="name", E_DURATION="durationMs";
    private static final String E_P="p",E_S="s",E_E="e",E_VF="vf",E_FF="ff",E_CX="cx",E_CY="cy",E_R="r",E_SX="sx",E_SY="sy",E_K1="k1",E_K2="k2",E_K3="k3",E_Y="yaw",E_PI="pitch",E_RO="roll",E_DL="dl",E_DS="ds",E_FX="flipx",E_FY="flipy";
    private static final int SEEK_MAX = 10_000;

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
    private long fallbackDurationMs;
    private long pendingUserSeekMs = C.TIME_UNSET;
    private int pendingSeekRetries;

    public static void start(Context c, String url, String ua, String name, ProjectionSettings s) {
        start(c, url, ua, name, s, 0L);
    }

    /**
     * durationMs is a Xunlei metadata fallback.  Cloud-play URLs sometimes do not expose
     * a usable duration to Media3 immediately; the metadata timeline keeps seeking and
     * the time display functional while Media3 is still resolving the stream.
     */
    public static void start(Context c, String url, String ua, String name, ProjectionSettings s, long durationMs) {
        Intent i = new Intent(c, PlayerActivity.class);
        i.putExtra(E_URL,url); i.putExtra(E_UA,ua==null?"":ua); i.putExtra(E_NAME,name==null?"VR Video":name);
        i.putExtra(E_DURATION, Math.max(0L, durationMs));
        putSettings(i,s);
        c.startActivity(i);
    }

    private static void putSettings(Intent i, ProjectionSettings s) {
        i.putExtra(E_P,s.projection); i.putExtra(E_S,s.stereoLayout); i.putExtra(E_E,s.eye);
        i.putExtra(E_VF,s.viewFovDeg); i.putExtra(E_FF,s.fisheyeFovDeg); i.putExtra(E_CX,s.fishCenterX); i.putExtra(E_CY,s.fishCenterY); i.putExtra(E_R,s.fishRadius);
        i.putExtra(E_SX,s.fishScaleX); i.putExtra(E_SY,s.fishScaleY); i.putExtra(E_K1,s.fishK1); i.putExtra(E_K2,s.fishK2); i.putExtra(E_K3,s.fishK3);
        i.putExtra(E_Y,s.sourceYawDeg); i.putExtra(E_PI,s.sourcePitchDeg); i.putExtra(E_RO,s.sourceRollDeg); i.putExtra(E_DL,s.dualLensLayout); i.putExtra(E_DS,s.dualLensSwap);
        i.putExtra(E_FX,s.sourceFlipX); i.putExtra(E_FY,s.sourceFlipY);
    }
    private static ProjectionSettings readSettings(Intent i) {
        ProjectionSettings s=new ProjectionSettings();
        s.projection=i.getIntExtra(E_P,s.projection); s.stereoLayout=i.getIntExtra(E_S,s.stereoLayout); s.eye=i.getIntExtra(E_E,s.eye);
        s.viewFovDeg=i.getFloatExtra(E_VF,s.viewFovDeg); s.fisheyeFovDeg=i.getFloatExtra(E_FF,s.fisheyeFovDeg); s.fishCenterX=i.getFloatExtra(E_CX,s.fishCenterX); s.fishCenterY=i.getFloatExtra(E_CY,s.fishCenterY); s.fishRadius=i.getFloatExtra(E_R,s.fishRadius);
        s.fishScaleX=i.getFloatExtra(E_SX,s.fishScaleX); s.fishScaleY=i.getFloatExtra(E_SY,s.fishScaleY); s.fishK1=i.getFloatExtra(E_K1,s.fishK1); s.fishK2=i.getFloatExtra(E_K2,s.fishK2); s.fishK3=i.getFloatExtra(E_K3,s.fishK3);
        s.sourceYawDeg=i.getFloatExtra(E_Y,s.sourceYawDeg); s.sourcePitchDeg=i.getFloatExtra(E_PI,s.sourcePitchDeg); s.sourceRollDeg=i.getFloatExtra(E_RO,s.sourceRollDeg); s.dualLensLayout=i.getIntExtra(E_DL,s.dualLensLayout); s.dualLensSwap=i.getBooleanExtra(E_DS,s.dualLensSwap);
        s.sourceFlipX=i.getBooleanExtra(E_FX,s.sourceFlipX); s.sourceFlipY=i.getBooleanExtra(E_FY,s.sourceFlipY);
        return s;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        url=getIntent().getStringExtra(E_URL); ua=getIntent().getStringExtra(E_UA); name=getIntent().getStringExtra(E_NAME);
        fallbackDurationMs=Math.max(0L,getIntent().getLongExtra(E_DURATION,0L));
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

        LinearLayout bottom=new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setPadding(dp(8),dp(3),dp(8),dp(6)); bottom.setBackgroundColor(0x99000000);

        // Keep time on its own full-width row. Previously it shared a narrow row with
        // four buttons and could be visually squeezed away on a phone screen.
        timeText=new TextView(this);
        timeText.setTextColor(Color.WHITE);
        timeText.setTextSize(14);
        timeText.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
        timeText.setText("00:00:00 / --:--");
        bottom.addView(timeText,new LinearLayout.LayoutParams(-1,dp(26)));

        seek=new SeekBar(this);
        seek.setMax(SEEK_MAX); // Never rely on Android's default max=100.
        seek.setProgress(0);
        bottom.addView(seek,new LinearLayout.LayoutParams(-1,dp(38)));

        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        playPause=new Button(this); playPause.setText("暂停"); row.addView(playPause,new LinearLayout.LayoutParams(dp(78),dp(48)));
        Button reset=new Button(this); reset.setText("回正"); row.addView(reset,new LinearLayout.LayoutParams(dp(72),dp(48)));
        Button mode=new Button(this); mode.setText("投影"); row.addView(mode,new LinearLayout.LayoutParams(dp(72),dp(48)));
        Button eye=new Button(this); eye.setText("换眼"); row.addView(eye,new LinearLayout.LayoutParams(dp(72),dp(48)));
        bottom.addView(row);

        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,dp(121),Gravity.BOTTOM); root.addView(bottom,bp);
        setContentView(root);

        playPause.setOnClickListener(v->{ if(player==null)return; if(player.isPlaying()) player.pause(); else player.play(); updatePlayButton();});
        reset.setOnClickListener(v->{ if(customView!=null) customView.resetView(); });
        mode.setOnClickListener(v-> ProjectionDialog.show(this,settings,this::applyNewSettings));
        eye.setOnClickListener(v->{ settings.eye = settings.eye==ProjectionSettings.EYE_LEFT?ProjectionSettings.EYE_RIGHT:ProjectionSettings.EYE_LEFT; if(customView!=null)customView.setProjectionSettings(settings); modeText.setText(settings.shortLabel());});
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean from){
                if(!from) return;
                long d=effectiveDurationMs();
                if(d>0){
                    long target=progressToTime(p,d,b.getMax());
                    timeText.setText(format(target)+" / "+format(d)+bufferSuffix());
                }
            }
            public void onStartTrackingTouch(SeekBar b){seeking=true;}
            public void onStopTrackingTouch(SeekBar b){
                long d=effectiveDurationMs();
                if(player!=null&&d>0){
                    long target=progressToTime(b.getProgress(),d,b.getMax());
                    requestUserSeek(target);
                }
                seeking=false;
            }
        });
        ui.post(ticker);
    }

    private void createPlayer() {
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent((ua==null||ua.isEmpty())?"XunleiVRPlayer/2.0":ua);
        if(ua!=null&&!ua.isEmpty()) { Map<String,String> h=new HashMap<>(); h.put("User-Agent",ua); http.setDefaultRequestProperties(h); }
        DefaultDataSource.Factory ds=new DefaultDataSource.Factory(this,http);

        DefaultLoadControl loadControl=new DefaultLoadControl.Builder()
                .setBufferDurationsMs(20_000,60_000,2_500,7_000)
                .setBackBuffer(5_000,true)
                .build();

        player=new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(ds))
                .setLoadControl(loadControl)
                .build();
        player.addListener(new Player.Listener(){
            @Override public void onIsPlayingChanged(boolean isPlaying){updatePlayButton();}
            @Override public void onPlaybackStateChanged(int state){
                // A cloud-play timeline may become seekable only after its manifest/index
                // has been parsed. If the user already dragged, re-issue that requested seek.
                if((state==Player.STATE_READY||state==Player.STATE_BUFFERING)&&pendingUserSeekMs!=C.TIME_UNSET){
                    retryPendingSeek();
                }
            }
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

    /**
     * User seeking used to be guarded by player.getDuration()>0, which made dragging a
     * complete no-op for Xunlei cloud-play streams while Media3 still reported TIME_UNSET.
     * Use the Xunlei metadata duration as a fallback and keep the requested position until
     * Media3's timeline/index is ready.
     */
    private void requestUserSeek(long targetMs){
        if(player==null) return;
        long d=effectiveDurationMs();
        if(d>0) targetMs=Math.max(0L,Math.min(targetMs,d));
        pendingUserSeekMs=targetMs;
        pendingSeekRetries=0;
        player.seekTo(targetMs);
        ui.postDelayed(this::retryPendingSeek,350);
    }

    private void retryPendingSeek(){
        if(player==null||pendingUserSeekMs==C.TIME_UNSET) return;
        long target=pendingUserSeekMs;
        long now=player.getCurrentPosition();
        if(Math.abs(now-target)<=2500L||player.getPlaybackState()==Player.STATE_ENDED){
            pendingUserSeekMs=C.TIME_UNSET;
            return;
        }
        if(pendingSeekRetries>=4){
            // Leave playback alone after a few retries; the ticker still keeps the UI sane.
            pendingUserSeekMs=C.TIME_UNSET;
            return;
        }
        pendingSeekRetries++;
        player.seekTo(target);
        ui.postDelayed(this::retryPendingSeek,450);
    }

    private long effectiveDurationMs(){
        if(player!=null){
            long d=player.getDuration();
            if(d!=C.TIME_UNSET&&d>0) return d;
        }
        return fallbackDurationMs>0?fallbackDurationMs:0L;
    }

    private static long progressToTime(int progress,long duration,int max){
        if(duration<=0||max<=0) return 0L;
        return Math.round(duration*(progress/(double)max));
    }

    private static int timeToProgress(long position,long duration,int max){
        if(duration<=0||max<=0) return 0;
        double x=Math.max(0d,Math.min(1d,position/(double)duration));
        return (int)Math.round(x*max);
    }

    private String bufferSuffix(){
        return player==null?"":"  缓冲 "+player.getBufferedPercentage()+"%";
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
        if(player!=null&&!seeking){
            long d=effectiveDurationMs();
            long p=Math.max(0L,player.getCurrentPosition());
            if(d>0){
                seek.setEnabled(true);
                seek.setProgress(timeToProgress(p,d,seek.getMax()));
                timeText.setText(format(p)+" / "+format(d)+bufferSuffix());
            }else{
                seek.setEnabled(false);
                timeText.setText(format(p)+" / --:--"+bufferSuffix());
            }
        }
        ui.postDelayed(this,400);
    }};

    private void updatePlayButton(){if(playPause!=null&&player!=null)playPause.setText(player.isPlaying()?"暂停":"播放");}
    private static String format(long ms){if(ms<0)return"--:--";long s=ms/1000;return String.format(Locale.ROOT,"%02d:%02d:%02d",s/3600,(s/60)%60,s%60);}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}

    @Override protected void onResume(){super.onResume();if(customView!=null)customView.onResume();if(sphericalView!=null)sphericalView.onResume();}
    @Override protected void onPause(){if(customView!=null)customView.onPause();if(sphericalView!=null)sphericalView.onPause();super.onPause();}
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);if(customView!=null)customView.release();if(player!=null){player.release();player=null;}super.onDestroy();}
}
