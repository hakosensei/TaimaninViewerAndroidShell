package com.example.xunleivrplayer;

import java.util.Locale;

/**
 * 播放器的投影参数。
 *
 * 这里故意拆成三层：
 * 1) 源视频投影几何（ERP / fisheye / cubemap...）
 * 2) 立体打包与双目融合（取单眼 / 固定球壳 / 智能优势眼 / 局部视差）
 * 3) 最终平面显示投影（普通透视 / 人眼宽视野 / Panini）
 * 这样可以在同一片源上独立比较“左右眼问题”和“边缘拉伸问题”。
 */
final class ProjectionSettings {
    static final int AUTO_METADATA = 0;
    static final int FLAT = 1;
    static final int ERP_180 = 2;
    static final int ERP_360 = 3;
    static final int FISHEYE = 4;
    static final int DUAL_FISHEYE_360 = 5;
    static final int CUBEMAP_3X2 = 6;
    static final int EAC_3X2 = 7;

    static final int MONO = 0;
    static final int SBS_LR = 1;
    static final int SBS_RL = 2;
    static final int TB = 3;
    static final int BT = 4;

    static final int EYE_LEFT = 0;
    static final int EYE_RIGHT = 1;

    // 双目处理。STEREO_EYE 使用 eye 字段选择左/右眼。
    static final int STEREO_EYE = 0;
    static final int STEREO_SHELL = 1;
    static final int STEREO_SMART = 2;
    static final int STEREO_DISPARITY = 3;

    // 最终“虚拟相机 -> 手机平面”的显示投影。
    static final int OUTPUT_RECTILINEAR = 0;
    static final int OUTPUT_HUMAN_WIDE = 1;
    static final int OUTPUT_PANINI = 2;

    static final int DUAL_LENS_SBS = 0;
    static final int DUAL_LENS_TB = 1;

    int projection = ERP_180;
    int stereoLayout = SBS_LR;
    int eye = EYE_LEFT;
    int stereoViewMode = STEREO_EYE;
    int outputProjection = OUTPUT_RECTILINEAR;

    float viewFovDeg = 85f;

    // 双目实验参数。64 mm / 0.55 m 是一个温和的默认值；
    // 它们只用于固定球壳、智能融合和局部视差模式。
    float stereoIpdMm = 64f;
    float shellDepthM = 0.55f;
    float disparityRange = 0.06f;   // 眼内归一化 UV，局部搜索半范围
    float smartThreshold = 0.12f;   // 左右颜色差低于此值时允许平滑混合
    float paniniD = 1.0f;           // Panini d，越大越接近柱面压缩

    float fisheyeFovDeg = 180f;
    float fishCenterX = 0.5f;
    float fishCenterY = 0.5f;
    float fishRadius = 0.5f;
    float fishScaleX = 1.0f;
    float fishScaleY = 1.0f;
    float fishK1 = 0f;
    float fishK2 = 0f;
    float fishK3 = 0f;

    float sourceYawDeg = 0f;
    float sourcePitchDeg = 0f;
    float sourceRollDeg = 0f;

    boolean sourceFlipX = false;
    boolean sourceFlipY = false;

    int dualLensLayout = DUAL_LENS_SBS;
    boolean dualLensSwap = false;

    ProjectionSettings copy() {
        ProjectionSettings x = new ProjectionSettings();
        x.projection = projection;
        x.stereoLayout = stereoLayout;
        x.eye = eye;
        x.stereoViewMode = stereoViewMode;
        x.outputProjection = outputProjection;
        x.viewFovDeg = viewFovDeg;
        x.stereoIpdMm = stereoIpdMm;
        x.shellDepthM = shellDepthM;
        x.disparityRange = disparityRange;
        x.smartThreshold = smartThreshold;
        x.paniniD = paniniD;
        x.fisheyeFovDeg = fisheyeFovDeg;
        x.fishCenterX = fishCenterX;
        x.fishCenterY = fishCenterY;
        x.fishRadius = fishRadius;
        x.fishScaleX = fishScaleX;
        x.fishScaleY = fishScaleY;
        x.fishK1 = fishK1;
        x.fishK2 = fishK2;
        x.fishK3 = fishK3;
        x.sourceYawDeg = sourceYawDeg;
        x.sourcePitchDeg = sourcePitchDeg;
        x.sourceRollDeg = sourceRollDeg;
        x.sourceFlipX = sourceFlipX;
        x.sourceFlipY = sourceFlipY;
        x.dualLensLayout = dualLensLayout;
        x.dualLensSwap = dualLensSwap;
        return x;
    }

    static ProjectionSettings guess(String fileName) {
        ProjectionSettings s = new ProjectionSettings();
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);

        if (n.contains("flat") || n.contains("2d")) {
            s.projection = FLAT;
            s.stereoLayout = MONO;
        } else if (n.contains("eac")) {
            s.projection = EAC_3X2;
            s.stereoLayout = MONO;
        } else if (n.contains("cubemap") || n.contains("cube_3x2") || n.contains("cube3x2")) {
            s.projection = CUBEMAP_3X2;
            s.stereoLayout = MONO;
        } else if (n.contains("dualfisheye") || n.contains("dual_fisheye") || n.contains("dual-fisheye")) {
            s.projection = DUAL_FISHEYE_360;
            s.stereoLayout = MONO;
            s.fisheyeFovDeg = 190f;
        } else if (n.contains("fisheye190") || n.contains("rf52")) {
            s.projection = FISHEYE;
            s.fisheyeFovDeg = 190f;
        } else if (n.contains("mkx200") || n.contains("fisheye200")) {
            s.projection = FISHEYE;
            s.fisheyeFovDeg = 200f;
        } else if (n.contains("vrca220") || n.contains("fisheye220")) {
            s.projection = FISHEYE;
            s.fisheyeFovDeg = 220f;
        } else if (n.contains("fisheye")) {
            s.projection = FISHEYE;
            s.fisheyeFovDeg = 180f;
        } else if (n.contains("360")) {
            s.projection = ERP_360;
        } else {
            s.projection = ERP_180;
        }

        if (containsAny(n, "_rl", "3dhr", "rightleft", "right-left")) s.stereoLayout = SBS_RL;
        else if (containsAny(n, "_tb", "3dv", "overunder", "over-under", "topbottom", "top-bottom")) s.stereoLayout = TB;
        else if (containsAny(n, "_bt", "bottomtop", "bottom-top")) s.stereoLayout = BT;
        else if (containsAny(n, "_lr", "_sbs", "3dh", "sidebyside", "side-by-side")) s.stereoLayout = SBS_LR;
        else if (s.projection == ERP_180 || s.projection == FISHEYE) s.stereoLayout = SBS_LR;

        return s;
    }

    private static boolean containsAny(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    String shortLabel() {
        String p;
        switch (projection) {
            case AUTO_METADATA: p = "自动/Metadata"; break;
            case FLAT: p = "Flat"; break;
            case ERP_180: p = "ERP 180°"; break;
            case ERP_360: p = "ERP 360°"; break;
            case FISHEYE: p = "Fisheye " + Math.round(fisheyeFovDeg) + "°"; break;
            case DUAL_FISHEYE_360: p = "Dual-Fisheye 360"; break;
            case CUBEMAP_3X2: p = "Cubemap 3×2"; break;
            case EAC_3X2: p = "EAC 3×2"; break;
            default: p = "Unknown";
        }
        String l;
        switch (stereoLayout) {
            case SBS_LR: l = "SBS-LR"; break;
            case SBS_RL: l = "SBS-RL"; break;
            case TB: l = "TB"; break;
            case BT: l = "BT"; break;
            default: l = "Mono";
        }
        String sv;
        if (stereoLayout == MONO) sv = "单目";
        else switch (stereoViewMode) {
            case STEREO_SHELL: sv = "中央球壳"; break;
            case STEREO_SMART: sv = "智能融合"; break;
            case STEREO_DISPARITY: sv = "视差中眼"; break;
            default: sv = eye == EYE_LEFT ? "左眼" : "右眼";
        }
        String out;
        switch (outputProjection) {
            case OUTPUT_HUMAN_WIDE: out = "人眼宽视野"; break;
            case OUTPUT_PANINI: out = "Panini"; break;
            default: out = "普通透视";
        }
        String flip = (sourceFlipX ? " / FlipX" : "") + (sourceFlipY ? " / FlipY" : "");
        return p + " / " + l + " / " + sv + " / " + out + flip;
    }
}
