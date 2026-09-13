package com.example.xunleivrplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页 + 迅雷云盘浏览器。
 *
 * 迅雷只负责提供媒体字节，真正的 VR->平面投影由 PlayerActivity 完成。
 */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 2301;
    private static final int REQ_XUNLEI_REVIEW = 2302;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Deque<FolderPos> stack = new ArrayDeque<>();

    private XunleiApi api;
    private SecureStore store;
    private String pendingUser = "", pendingPassword = "";
    private boolean onlyVideo = true;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        api = new XunleiApi();
        store = new SecureStore(this);
        if (!handleCallback(getIntent())) showHome();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); handleCallback(intent);
    }

    private boolean handleCallback(Intent intent) {
        Uri d = intent == null ? null : intent.getData();
        if (d == null || !"xlaccsdk01".equalsIgnoreCase(d.getScheme())) return false;
        String token = d.getQueryParameter("captcha_token");
        if (token == null || token.isBlank() || pendingUser.isBlank()) { showHome(); return true; }
        showBusy("正在完成迅雷验证…");
        io.execute(() -> {
            try {
                Models.Token t = api.completeLogin(pendingUser, pendingPassword, token);
                saveSession(pendingUser, t); pendingPassword="";
                ui.post(this::showBrowserRoot);
            } catch (Exception e) { ui.post(() -> { toast("验证登录失败："+e.getMessage()); showLogin(); }); }
        });
        return true;
    }

    private void showHome() {
        LinearLayout root=column(22);
        TextView title=text("Xunlei VR Player",30,true);
        TextView sub=text("迅雷云盘直读 + 独立 VR 投影播放器\n不需要 DeoVR，也不需要先把整部片下载到手机。",15,false);
        Button cloud=button("打开迅雷云盘");
        Button reset=button("重新登录迅雷 / 清除登录状态");
        Button local=button("打开手机本地视频");
        Button url=button("播放网络视频 URL");
        Button guide=button("支持的 VR 格式 / 操作说明");
        root.addView(title); root.addView(sub); gap(root); root.addView(cloud); root.addView(reset); root.addView(local); root.addView(url); root.addView(guide);
        setContentView(wrap(root));

        cloud.setOnClickListener(v->openCloud());
        reset.setOnClickListener(v->{
            store.clearLogin();
            pendingUser=""; pendingPassword="";
            api=new XunleiApi();
            showLogin();
        });
        local.setOnClickListener(v->{ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("video/*");startActivityForResult(i,REQ_FILE);});
        url.setOnClickListener(v->showUrlDialog());
        guide.setOnClickListener(v->showGuide());
    }

    private void openCloud() {
        String refresh=store.getSecret("refresh"), device=store.getPlain("deviceId","");
        if(refresh.isBlank()){showLogin();return;}
        showBusy("正在恢复迅雷登录…");
        io.execute(()->{
            try{Models.Token t=api.loginWithRefreshToken(refresh,device);saveSession(store.getPlain("username",""),t);ui.post(this::showBrowserRoot);}
            catch(Exception e){store.clearLogin();ui.post(()->{toast("恢复登录失败："+e.getMessage());showLogin();});}
        });
    }

    private void showLogin() {
        LinearLayout root=column(22);
        root.addView(text("登录迅雷",28,true));
        root.addView(text("密码只用于本次登录；本机只加密保存 refresh_token。迅雷接口属于非公开第三方接口，未来若迅雷调整协议需要更新 APP。",13,false));
        EditText user=edit("迅雷账号 / 手机号 / 邮箱"); user.setText(store.getPlain("username",""));
        EditText pass=edit("迅雷密码"); pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button login=button("登录"); Button token=button("高级：使用 refresh_token"); Button back=button("返回");
        root.addView(user);root.addView(pass);root.addView(login);root.addView(token);root.addView(back);
        setContentView(wrap(root));
        back.setOnClickListener(v->showHome());
        token.setOnClickListener(v->showTokenLogin());
        login.setOnClickListener(v->{
            pendingUser=user.getText().toString().trim(); pendingPassword=pass.getText().toString();
            if(pendingUser.isBlank()||pendingPassword.isBlank()){toast("请输入账号和密码");return;}
            showBusy("正在登录迅雷…");
            io.execute(()->{
                try{Models.Token t=api.login(pendingUser,pendingPassword);saveSession(pendingUser,t);pendingPassword="";ui.post(this::showBrowserRoot);}
                catch(XunleiApi.VerificationRequiredException e){ui.post(()->handleVerificationRequired(e));}
                catch(Exception e){ui.post(()->{toast("登录失败："+e.getMessage());showLogin();});}
            });
        });
    }

    private void showTokenLogin(){
        LinearLayout root=column(22); root.addView(text("refresh_token 登录",26,true));
        EditText token=edit("refresh_token"); EditText device=edit("device_id（可留空）"); Button go=button("登录"); Button back=button("返回");
        root.addView(token);root.addView(device);root.addView(go);root.addView(back);setContentView(wrap(root));
        back.setOnClickListener(v->showLogin());
        go.setOnClickListener(v->{String rt=token.getText().toString().trim();if(rt.isBlank()){toast("请输入 refresh_token");return;}showBusy("正在登录…");io.execute(()->{try{Models.Token t=api.loginWithRefreshToken(rt,device.getText().toString().trim());saveSession("",t);ui.post(this::showBrowserRoot);}catch(Exception e){ui.post(()->{toast(e.getMessage());showTokenLogin();});}});});
    }

    private void saveSession(String username, Models.Token t) throws Exception {
        if(username!=null&&!username.isBlank())store.putPlain("username",username);
        store.putPlain("deviceId",api.getDeviceId());
        if(t.refreshToken!=null&&!t.refreshToken.isBlank())store.putSecret("refresh",t.refreshToken);
    }

    private void showBrowserRoot(){stack.clear();stack.push(new FolderPos("",XunleiApi.ROOT_SPACE,"迅雷云盘"));loadCurrentFolder();}
    private void loadCurrentFolder(){
        FolderPos p=stack.peek();showBusy("正在读取 "+(p==null?"云盘":p.name)+"…");
        io.execute(()->{
            try{
                List<Models.CloudItem> list=api.list(p==null?"":p.id,p==null?XunleiApi.ROOT_SPACE:p.space);
                list.sort(Comparator.comparing((Models.CloudItem x)->!x.isDir()).thenComparing(x->x.name.toLowerCase(Locale.ROOT)));
                ui.post(()->showFileList(list));
            }catch(Exception e){
                ui.post(()->{
                    toast("读取失败："+e.getMessage());
                    showHome();
                });
            }
        });
    }

    private void showFileList(List<Models.CloudItem> raw){
        FolderPos pos=stack.peek(); LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(8),dp(8),dp(8),dp(8));
        LinearLayout top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("←"); TextView title=text(pos==null?"迅雷云盘":pos.name,20,true);title.setSingleLine(true);Button home=button("首页");Button logout=button("退出登录");
        top.addView(back,new LinearLayout.LayoutParams(dp(56),dp(50)));top.addView(title,new LinearLayout.LayoutParams(0,dp(50),1));top.addView(home,new LinearLayout.LayoutParams(dp(72),dp(50)));top.addView(logout,new LinearLayout.LayoutParams(dp(96),dp(50)));root.addView(top);
        CheckBox filter=new CheckBox(this);filter.setText("只显示文件夹和视频");filter.setChecked(onlyVideo);root.addView(filter);
        ArrayList<Models.CloudItem> shown=new ArrayList<>();for(Models.CloudItem f:raw)if(!onlyVideo||f.isDir()||f.isVideo())shown.add(f);
        ArrayList<String> labels=new ArrayList<>();for(Models.CloudItem f:shown)labels.add((f.isDir()?"📁 ":"🎬 ")+f.name+(f.isDir()?"":"   "+human(f.sizeBytes())));
        ListView lv=new ListView(this);lv.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,labels));root.addView(lv,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        filter.setOnCheckedChangeListener((b,c)->{onlyVideo=c;showFileList(raw);});
        back.setOnClickListener(v->{if(stack.size()>1){stack.pop();loadCurrentFolder();}else showHome();});home.setOnClickListener(v->showHome());logout.setOnClickListener(v->{store.clearLogin();api=new XunleiApi();showLogin();});
        lv.setOnItemClickListener((p,v,i,id)->{Models.CloudItem f=shown.get(i);if(f.isDir()){stack.push(new FolderPos(f.id,f.space==null?"":f.space,f.name));loadCurrentFolder();}else if(f.isVideo())chooseAndPlayCloud(f);});
    }

    private void chooseAndPlayCloud(Models.CloudItem f){ProjectionSettings guess=ProjectionSettings.guess(f.name);ProjectionDialog.show(this,guess,s->{showBusy("正在获取迅雷播放地址…");io.execute(()->{try{Models.StreamLink link=api.getStreamLink(f);ui.post(()->PlayerActivity.start(this,link.url,link.userAgent,f.name,s));}catch(Exception e){ui.post(()->{toast("获取播放地址失败："+e.getMessage());loadCurrentFolder();});}});});}

    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==REQ_XUNLEI_REVIEW){
            if(result!=RESULT_OK||data==null){toast("迅雷安全验证未完成");showLogin();return;}
            String ck=data.getStringExtra(XunleiVerifyActivity.RESULT_CREDIT_KEY);
            if(ck==null||ck.isBlank()||pendingUser.isBlank()||pendingPassword.isBlank()){toast("验证结果无效，请重新登录");showLogin();return;}
            showBusy("短信验证成功，正在继续登录迅雷…");
            io.execute(()->{
                try{Models.Token t=api.completeSecurityReview(pendingUser,pendingPassword,ck);saveSession(pendingUser,t);pendingPassword="";ui.post(this::showBrowserRoot);}
                catch(XunleiApi.VerificationRequiredException e){ui.post(()->handleVerificationRequired(e));}
                catch(Exception e){ui.post(()->{toast("验证后登录失败："+e.getMessage());showLogin();});}
            });
            return;
        }
        if(req==REQ_FILE&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri u=data.getData();try{getContentResolver().takePersistableUriPermission(u,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));}catch(Exception ignored){}String n=u.getLastPathSegment();ProjectionDialog.show(this,ProjectionSettings.guess(n),s->PlayerActivity.start(this,u.toString(),"",n,s));}
    }

    private void handleVerificationRequired(XunleiApi.VerificationRequiredException e){
        String u=e.verifyUrl==null?"":e.verifyUrl;
        try{
            Uri parsed=Uri.parse(u);
            String ck=parsed.getQueryParameter("creditkey");
            boolean review=u.contains("vertifyPhone.html") || (ck!=null&&!ck.isBlank());
            if(review){
                if(ck==null||ck.isBlank()){toast("迅雷要求短信验证，但没有返回 CreditKey");showLogin();return;}
                String sign=XunleiApi.generateDeviceSign(api.getDeviceId(),XunleiApi.XL_PACKAGE);
                String reviewUrl=u;
                if(parsed.getQueryParameter("deviceid")==null) reviewUrl=parsed.buildUpon().appendQueryParameter("deviceid",sign).build().toString();
                Intent i=new Intent(this,XunleiVerifyActivity.class);
                i.putExtra(XunleiVerifyActivity.EXTRA_REVIEW_URL,reviewUrl);
                i.putExtra(XunleiVerifyActivity.EXTRA_CREDIT_KEY,ck);
                i.putExtra(XunleiVerifyActivity.EXTRA_DEVICE_SIGN,sign);
                startActivityForResult(i,REQ_XUNLEI_REVIEW);
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW,parsed));
        }catch(Exception ex){toast("无法打开迅雷验证："+ex.getMessage());showLogin();}
    }

    private void showUrlDialog(){EditText e=edit("https://.../video.mp4 或 m3u8");new AlertDialog.Builder(this).setTitle("网络视频 URL").setView(e).setNegativeButton("取消",null).setPositiveButton("下一步",(d,w)->{String u=e.getText().toString().trim();if(!u.isBlank())ProjectionDialog.show(this,ProjectionSettings.guess(u),s->PlayerActivity.start(this,u,"",u,s));}).show();}

    private void showGuide(){new AlertDialog.Builder(this).setTitle("这一版支持什么")
            .setMessage("手动投影：\n• Flat 2D\n• 180° half-equirectangular\n• 360° equirectangular\n• Fisheye 180/190/200/220° + 自定义 FOV/中心/径向修正\n• Raw dual-fisheye 360\n• Cubemap 3×2\n• EAC 3×2\n\n立体布局：Mono、SBS-LR、SBS-RL、TB、BT。\n\n自动 Metadata 模式：交给 Android Media3 的 Spherical Video V2 渲染路径，可读取标准投影 metadata / projection mesh，适合真正的 Google VR180 文件。\n\n操作：单指拖动转动视角；双指缩放改变虚拟相机 FOV；双击或“回正”恢复正前方。平面观看立体视频时可在左/右眼之间切换。")
            .setPositiveButton("知道了",null).show();}

    private void showBusy(String s){LinearLayout r=column(24);TextView t=text(s,20,true);r.setGravity(Gravity.CENTER);r.addView(t);setContentView(r);}
    private ScrollView wrap(View v){ScrollView s=new ScrollView(this);s.addView(v);return s;}
    private LinearLayout column(int pad){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(pad),dp(pad),dp(pad),dp(pad));return l;}
    private TextView text(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(Color.rgb(30,30,30));if(bold)t.setTypeface(t.getTypeface(),android.graphics.Typeface.BOLD);t.setPadding(0,dp(6),0,dp(6));return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);return b;}
    private EditText edit(String hint){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine(true);return e;}
    private void gap(LinearLayout r){View v=new View(this);r.addView(v,new LinearLayout.LayoutParams(1,dp(12)));}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private static String human(long b){if(b<=0)return"";double x=b;String[]u={"B","KB","MB","GB","TB"};int i=0;while(x>=1024&&i<u.length-1){x/=1024;i++;}return String.format(Locale.ROOT,"%.1f %s",x,u[i]);}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}

    private static final class FolderPos{final String id,space,name;FolderPos(String i,String s,String n){id=i;space=s;name=n;}}
}
