package com.example.xunleivrplayer;

import java.util.Locale;

/**
 * 播放器的投影参数。
 *
 * 这里故意把“投影方式”和“左右眼打包方式”分开：
 * 例如同一个 180° ERP 可以是单目，也可以是 SBS-LR、SBS-RL、TB 或 BT。
 */
final class ProjectionSettings {
    static final int AUTO_METADATA = 0;      // Google/Media3 Spherical Video metadata / mesh
    static final int FLAT = 1;               // 普通 2D
    static final int ERP_180 = 2;            // 180° half-equirectangular
    static final int ERP_360 = 3;            // 360° equirectangular
    static final int FISHEYE = 4;            // 参数化鱼眼（180~220°及自定义）
    static final int DUAL_FISHEYE_360 = 5;   // 原始双鱼眼 360，两只镜头拼成一个画面
    static final int CUBEMAP_3X2 = 6;        // 3×2 标准 cubemap atlas
    static final int EAC_3X2 = 7;            // 3×2 equi-angular cubemap

    static final int MONO = 0;
    static final int SBS_LR = 1;
    static final int SBS_RL = 2;
    static final int TB = 3;
    static final int BT = 4;

    static final int EYE_LEFT = 0;
    static final int EYE_RIGHT = 1;

    static final int DUAL_LENS_SBS = 0;
    static final int DUAL_LENS_TB = 1;

    int projection = ERP_180;
    int stereoLayout = SBS_LR;
    int eye = EYE_LEFT;

    // 虚拟平面相机视场角。越小越像长焦，越大越像广角。
    float viewFovDeg = 85f;

    // 鱼眼镜头参数。默认等距鱼眼；k1~k3 是可选径向修正。
    float fisheyeFovDeg = 180f;
    float fishCenterX = 0.5f;
    float fishCenterY = 0.5f;
    float fishRadius = 0.5f;
    float fishScaleX = 1.0f;
    float fishScaleY = 1.0f;
    float fishK1 = 0f;
    float fishK2 = 0f;
    float fishK3 = 0f;

    // 片源安装姿态修正（度）。触摸视角是在此基础上叠加。
    float sourceYawDeg = 0f;
    float sourcePitchDeg = 0f;
    float sourceRollDeg = 0f;

    // Raw dual-fisheye 360 的两只镜头在源帧中的排布。
    int dualLensLayout = DUAL_LENS_SBS;
    boolean dualLensSwap = false;

    ProjectionSettings copy() {
        ProjectionSettings x = new ProjectionSettings();
        x.projection = projection;
        x.stereoLayout = stereoLayout;
        x.eye = eye;
        x.viewFovDeg = viewFovDeg;
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
            // 日本 VR 发行片源里 180° SBS 很常见，作为无标记视频的默认值。
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
        return p + " / " + l + (stereoLayout == MONO ? "" : (eye == EYE_LEFT ? " / L" : " / R"));
    }
}
