package com.example.xunleivrplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/** 投影选择/校准面板。所有高级参数都保留，遇到非标准片商参数时仍可手调。 */
final class ProjectionDialog {
    interface Callback { void onApply(ProjectionSettings settings); }

    private static final String[] PROJECTIONS = {
            "自动读取 Metadata / Google VR180 Mesh",
            "Flat 普通 2D",
            "ERP 180°（半球经纬展开）",
            "ERP 360°（球形经纬展开）",
            "Fisheye 180°",
            "Fisheye 190° / Canon 类",
            "Fisheye 200°",
            "Fisheye 220°",
            "Fisheye 自定义",
            "Raw Dual-Fisheye 360°",
            "Cubemap 3×2",
            "EAC 3×2"
    };
    private static final String[] STEREO = {"Mono 单目", "SBS 左|右", "SBS 右|左", "TB 上|下", "BT 下|上"};
    private static final String[] EYES = {"平面观看：取左眼", "平面观看：取右眼"};
    private static final String[] DUAL = {"双鱼眼：左右排列", "双鱼眼：上下排列"};

    static void show(Activity a, ProjectionSettings initial, Callback cb) {
        ProjectionSettings s = initial.copy();
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 16);
        root.setPadding(pad, pad, pad, pad);

        TextView tip = new TextView(a);
        tip.setText("投影和左右眼布局是两件事。日本 VR 片源不确定时建议先试 ERP 180° + SBS；底部仍异常时再试 Fisheye。双指缩放可调整观看 FOV。");
        root.addView(tip);

        Spinner projection = spinner(a, PROJECTIONS);
        projection.setSelection(projectionIndex(s));
        addLabeled(a, root, "源视频投影", projection);

        Spinner stereo = spinner(a, STEREO);
        stereo.setSelection(Math.max(0, Math.min(STEREO.length - 1, s.stereoLayout)));
        addLabeled(a, root, "立体打包", stereo);

        Spinner eye = spinner(a, EYES);
        eye.setSelection(s.eye == ProjectionSettings.EYE_RIGHT ? 1 : 0);
        addLabeled(a, root, "普通手机屏幕使用哪只眼", eye);

        EditText viewFov = number(a, s.viewFovDeg);
        addLabeled(a, root, "虚拟相机 FOV（25–135°）", viewFov);
        EditText fishFov = number(a, s.fisheyeFovDeg);
        addLabeled(a, root, "鱼眼有效 FOV（度）", fishFov);

        TextView adv = new TextView(a);
        adv.setText("高级校正（默认值通常不用改）");
        adv.setTextSize(18);
        adv.setPadding(0, dp(a, 14), 0, dp(a, 4));
        root.addView(adv);

        EditText cx = number(a, s.fishCenterX), cy = number(a, s.fishCenterY), radius = number(a, s.fishRadius);
        addLabeled(a, root, "鱼眼中心 X（0–1）", cx);
        addLabeled(a, root, "鱼眼中心 Y（0–1）", cy);
        addLabeled(a, root, "鱼眼半径（通常 0.5）", radius);

        EditText sx = number(a, s.fishScaleX), sy = number(a, s.fishScaleY);
        addLabeled(a, root, "鱼眼 X 比例", sx); addLabeled(a, root, "鱼眼 Y 比例", sy);
        EditText k1 = number(a, s.fishK1), k2 = number(a, s.fishK2), k3 = number(a, s.fishK3);
        addLabeled(a, root, "径向修正 k1", k1); addLabeled(a, root, "径向修正 k2", k2); addLabeled(a, root, "径向修正 k3", k3);

        EditText yaw = number(a, s.sourceYawDeg), pitch = number(a, s.sourcePitchDeg), roll = number(a, s.sourceRollDeg);
        addLabeled(a, root, "源方向 Yaw 修正（°）", yaw);
        addLabeled(a, root, "源方向 Pitch 修正（°）", pitch);
        addLabeled(a, root, "源方向 Roll 修正（°）", roll);

        Spinner dual = spinner(a, DUAL); dual.setSelection(s.dualLensLayout);
        addLabeled(a, root, "Raw Dual-Fisheye 镜头排列", dual);

        ScrollView scroll = new ScrollView(a); scroll.addView(root);
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("VR 投影模式")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int pi = projection.getSelectedItemPosition();
                applyProjectionIndex(s, pi, parse(fishFov, s.fisheyeFovDeg));
                s.stereoLayout = stereo.getSelectedItemPosition();
                s.eye = eye.getSelectedItemPosition() == 1 ? ProjectionSettings.EYE_RIGHT : ProjectionSettings.EYE_LEFT;
                s.viewFovDeg = clamp(parse(viewFov, s.viewFovDeg), 25f, 135f);
                s.fisheyeFovDeg = clamp(s.fisheyeFovDeg, 90f, 260f);
                s.fishCenterX = parse(cx, s.fishCenterX); s.fishCenterY = parse(cy, s.fishCenterY); s.fishRadius = parse(radius, s.fishRadius);
                s.fishScaleX = parse(sx, s.fishScaleX); s.fishScaleY = parse(sy, s.fishScaleY);
                s.fishK1 = parse(k1, s.fishK1); s.fishK2 = parse(k2, s.fishK2); s.fishK3 = parse(k3, s.fishK3);
                s.sourceYawDeg = parse(yaw, s.sourceYawDeg); s.sourcePitchDeg = parse(pitch, s.sourcePitchDeg); s.sourceRollDeg = parse(roll, s.sourceRollDeg);
                s.dualLensLayout = dual.getSelectedItemPosition();
                cb.onApply(s.copy());
                dlg.dismiss();
            } catch (Exception ex) {
                new AlertDialog.Builder(a).setMessage("参数格式有误：" + ex.getMessage()).setPositiveButton("确定", null).show();
            }
        }));
        dlg.show();
    }

    private static int projectionIndex(ProjectionSettings s) {
        switch (s.projection) {
            case ProjectionSettings.AUTO_METADATA: return 0;
            case ProjectionSettings.FLAT: return 1;
            case ProjectionSettings.ERP_180: return 2;
            case ProjectionSettings.ERP_360: return 3;
            case ProjectionSettings.FISHEYE:
                if (Math.abs(s.fisheyeFovDeg - 180f) < 0.5f) return 4;
                if (Math.abs(s.fisheyeFovDeg - 190f) < 0.5f) return 5;
                if (Math.abs(s.fisheyeFovDeg - 200f) < 0.5f) return 6;
                if (Math.abs(s.fisheyeFovDeg - 220f) < 0.5f) return 7;
                return 8;
            case ProjectionSettings.DUAL_FISHEYE_360: return 9;
            case ProjectionSettings.CUBEMAP_3X2: return 10;
            case ProjectionSettings.EAC_3X2: return 11;
            default: return 2;
        }
    }

    private static void applyProjectionIndex(ProjectionSettings s, int i, float customFish) {
        switch (i) {
            case 0: s.projection = ProjectionSettings.AUTO_METADATA; break;
            case 1: s.projection = ProjectionSettings.FLAT; break;
            case 2: s.projection = ProjectionSettings.ERP_180; break;
            case 3: s.projection = ProjectionSettings.ERP_360; break;
            case 4: s.projection = ProjectionSettings.FISHEYE; s.fisheyeFovDeg = 180f; break;
            case 5: s.projection = ProjectionSettings.FISHEYE; s.fisheyeFovDeg = 190f; break;
            case 6: s.projection = ProjectionSettings.FISHEYE; s.fisheyeFovDeg = 200f; break;
            case 7: s.projection = ProjectionSettings.FISHEYE; s.fisheyeFovDeg = 220f; break;
            case 8: s.projection = ProjectionSettings.FISHEYE; s.fisheyeFovDeg = customFish; break;
            case 9: s.projection = ProjectionSettings.DUAL_FISHEYE_360; s.fisheyeFovDeg = customFish; break;
            case 10: s.projection = ProjectionSettings.CUBEMAP_3X2; break;
            case 11: s.projection = ProjectionSettings.EAC_3X2; break;
        }
    }

    private static Spinner spinner(Activity a, String[] items) {
        Spinner s = new Spinner(a);
        s.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, items));
        return s;
    }
    private static EditText number(Activity a, float v) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setText(Float.toString(v));
        return e;
    }
    private static void addLabeled(Activity a, LinearLayout root, String label, View v) {
        TextView t = new TextView(a); t.setText(label); t.setPadding(0, dp(a, 8), 0, 0); root.addView(t); root.addView(v);
    }
    private static float parse(EditText e, float def) { String s=e.getText().toString().trim(); return s.isEmpty()?def:Float.parseFloat(s); }
    private static float clamp(float x,float a,float b){return Math.max(a,Math.min(b,x));}
    private static int dp(Activity a,int x){return Math.round(x*a.getResources().getDisplayMetrics().density);}
}
