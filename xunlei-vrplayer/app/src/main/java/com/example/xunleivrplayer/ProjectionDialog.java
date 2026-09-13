package com.example.xunleivrplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * 投影选择/校准面板。
 *
 * 这版把“源视频是什么”“左右眼怎么合”“最后怎么摊到手机屏幕”三件事彻底拆开，
 * 方便在同一帧做真正的 A/B 对比。
 */
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
    private static final String[] STEREO_VIEW = {
            "取左眼（基准）",
            "取右眼（基准）",
            "固定中央球壳（快速）",
            "智能优势眼融合（快速）",
            "局部视差中央重建（实验）"
    };
    private static final String[] OUTPUT = {
            "普通透视 Rectilinear（基准）",
            "人眼宽视野混合（减轻边缘拉伸）",
            "Panini（强力抑制超广角边缘拉伸）"
    };
    private static final String[] DUAL = {"双鱼眼：左右排列", "双鱼眼：上下排列"};

    static void show(Activity a, ProjectionSettings initial, Callback cb) {
        ProjectionSettings s = initial.copy();
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 16);
        root.setPadding(pad, pad, pad, pad);

        TextView tip = new TextView(a);
        tip.setText("实验顺序建议：先固定双目处理，只切换平面投影；再固定平面投影，只切换双目处理。这样能分清“边缘拉伸”和“左右眼视差”分别改善了多少。自动 Metadata 模式由 Media3 接管，不使用下面的自研融合算法。");
        root.addView(tip);

        Spinner projection = spinner(a, PROJECTIONS);
        projection.setSelection(projectionIndex(s));
        addLabeled(a, root, "1. 源视频投影", projection);

        Spinner stereo = spinner(a, STEREO);
        stereo.setSelection(Math.max(0, Math.min(STEREO.length - 1, s.stereoLayout)));
        addLabeled(a, root, "2. 立体打包", stereo);

        Spinner stereoView = spinner(a, STEREO_VIEW);
        stereoView.setSelection(stereoViewIndex(s));
        addLabeled(a, root, "3. 双目处理 / 中影视角", stereoView);

        Spinner output = spinner(a, OUTPUT);
        output.setSelection(Math.max(0, Math.min(OUTPUT.length - 1, s.outputProjection)));
        addLabeled(a, root, "4. 手机平面投影", output);

        EditText viewFov = number(a, s.viewFovDeg);
        addLabeled(a, root, "虚拟相机 FOV（25–135°）", viewFov);

        TextView stereoAdv = new TextView(a);
        stereoAdv.setText("双目实验参数（默认值先别动）");
        stereoAdv.setTextSize(18);
        stereoAdv.setPadding(0, dp(a, 14), 0, dp(a, 4));
        root.addView(stereoAdv);

        EditText ipd = number(a, s.stereoIpdMm);
        addLabeled(a, root, "源双眼基线 / IPD（mm，默认 64）", ipd);
        EditText shellDepth = number(a, s.shellDepthM);
        addLabeled(a, root, "固定中央球壳假定距离（m，默认 0.55）", shellDepth);
        EditText dispRange = number(a, s.disparityRange);
        addLabeled(a, root, "局部视差搜索范围（0–0.15，默认 0.06）", dispRange);
        EditText smartThreshold = number(a, s.smartThreshold);
        addLabeled(a, root, "智能融合颜色差阈值（0.02–0.40）", smartThreshold);
        EditText paniniD = number(a, s.paniniD);
        addLabeled(a, root, "Panini d（0.1–3.0，默认 1.0）", paniniD);

        EditText fishFov = number(a, s.fisheyeFovDeg);
        addLabeled(a, root, "鱼眼有效 FOV（度）", fishFov);

        TextView adv = new TextView(a);
        adv.setText("源视频高级校正（默认值通常不用改）");
        adv.setTextSize(18);
        adv.setPadding(0, dp(a, 14), 0, dp(a, 4));
        root.addView(adv);

        CheckBox flipX = new CheckBox(a);
        flipX.setText("水平镜像 X（特殊片源）");
        flipX.setChecked(s.sourceFlipX);
        root.addView(flipX);
        CheckBox flipY = new CheckBox(a);
        flipY.setText("垂直翻转 Y（特殊片源）");
        flipY.setChecked(s.sourceFlipY);
        root.addView(flipY);

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
                .setTitle("VR 投影 / 双目实验")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int pi = projection.getSelectedItemPosition();
                applyProjectionIndex(s, pi, parse(fishFov, s.fisheyeFovDeg));
                s.stereoLayout = stereo.getSelectedItemPosition();
                applyStereoViewIndex(s, stereoView.getSelectedItemPosition());
                s.outputProjection = output.getSelectedItemPosition();
                s.viewFovDeg = clamp(parse(viewFov, s.viewFovDeg), 25f, 135f);
                s.stereoIpdMm = clamp(parse(ipd, s.stereoIpdMm), 20f, 120f);
                s.shellDepthM = clamp(parse(shellDepth, s.shellDepthM), 0.08f, 10f);
                s.disparityRange = clamp(parse(dispRange, s.disparityRange), 0f, 0.15f);
                s.smartThreshold = clamp(parse(smartThreshold, s.smartThreshold), 0.02f, 0.40f);
                s.paniniD = clamp(parse(paniniD, s.paniniD), 0.1f, 3f);
                s.fisheyeFovDeg = clamp(s.fisheyeFovDeg, 90f, 260f);
                s.sourceFlipX = flipX.isChecked();
                s.sourceFlipY = flipY.isChecked();
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

    private static int stereoViewIndex(ProjectionSettings s) {
        if (s.stereoViewMode == ProjectionSettings.STEREO_SHELL) return 2;
        if (s.stereoViewMode == ProjectionSettings.STEREO_SMART) return 3;
        if (s.stereoViewMode == ProjectionSettings.STEREO_DISPARITY) return 4;
        return s.eye == ProjectionSettings.EYE_RIGHT ? 1 : 0;
    }

    private static void applyStereoViewIndex(ProjectionSettings s, int i) {
        if (i == 0) { s.stereoViewMode = ProjectionSettings.STEREO_EYE; s.eye = ProjectionSettings.EYE_LEFT; }
        else if (i == 1) { s.stereoViewMode = ProjectionSettings.STEREO_EYE; s.eye = ProjectionSettings.EYE_RIGHT; }
        else if (i == 2) s.stereoViewMode = ProjectionSettings.STEREO_SHELL;
        else if (i == 3) s.stereoViewMode = ProjectionSettings.STEREO_SMART;
        else s.stereoViewMode = ProjectionSettings.STEREO_DISPARITY;
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
