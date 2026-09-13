package com.example.xunleivrplayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

final class Models {
    private Models() {}

    static final String FOLDER_KIND = "drive#folder";

    /**
     * Xunlei/PikPak file details contain useful duration metadata even when a cloud-play
     * URL does not expose a usable duration to Media3 immediately. Keep the duration
     * keyed by the returned URL so StreamLink can carry an exact/fallback timeline into
     * PlayerActivity without changing the Xunlei API request layer.
     */
    private static final ConcurrentHashMap<String, Long> KNOWN_DURATION_MS = new ConcurrentHashMap<>();

    private static void rememberDuration(String url, long durationMs) {
        if (url != null && !url.isBlank() && durationMs > 0) KNOWN_DURATION_MS.put(url, durationMs);
    }

    private static long knownDuration(String url) {
        if (url == null || url.isBlank()) return 0L;
        Long x = KNOWN_DURATION_MS.get(url);
        return x == null ? 0L : x;
    }

    private static long parseLong(Object value) {
        if (value == null || value == JSONObject.NULL) return 0L;
        try { return Long.parseLong(String.valueOf(value).trim()); }
        catch (Exception e) { return 0L; }
    }

    /**
     * File params.duration is documented by PikPak-compatible clients as seconds.
     * media.video.duration has appeared in both second-like and millisecond-like data,
     * so prefer the file-level duration whenever it is available.  The heuristic below
     * is only a last-resort fallback for an otherwise missing duration.
     */
    private static long mediaDurationToMs(long raw) {
        if (raw <= 0) return 0L;
        // A value above ~27.8 h is far more likely to be milliseconds than seconds for
        // ordinary video. This safely distinguishes typical feature/VR runtimes.
        return raw >= 100_000L ? raw : raw * 1000L;
    }

    static class Token {
        String tokenType;
        String accessToken;
        String refreshToken;
        String userId;

        String authorization() {
            String type = (tokenType == null || tokenType.isBlank()) ? "Bearer" : tokenType;
            return type + " " + accessToken;
        }

        static Token fromJson(JSONObject o) {
            Token t = new Token();
            t.tokenType = o.optString("token_type", "Bearer");
            t.accessToken = o.optString("access_token", "");
            t.refreshToken = o.optString("refresh_token", "");
            t.userId = o.optString("user_id", "");
            return t;
        }
    }

    /** One Xunlei cloud-play/transcode variant from files/{id}.medias[]. */
    static class MediaVariant {
        String url = "";
        String mediaName = "";
        String resolutionName = "";
        String videoCodec = "";
        boolean origin;
        boolean isDefault;
        int priority;
        int width;
        int height;
        int bitRate;
        long durationMs;

        static MediaVariant fromJson(JSONObject o) {
            MediaVariant v = new MediaVariant();
            if (o == null) return v;
            JSONObject link = o.optJSONObject("link");
            if (link != null) v.url = link.optString("url", "");
            v.mediaName = o.optString("media_name", "");
            v.resolutionName = o.optString("resolution_name", "");
            v.origin = o.optBoolean("is_origin", false);
            v.isDefault = o.optBoolean("is_default", false);
            v.priority = o.optInt("priority", 0);
            JSONObject video = o.optJSONObject("video");
            if (video != null) {
                v.width = video.optInt("width", 0);
                v.height = video.optInt("height", 0);
                v.bitRate = video.optInt("bit_rate", 0);
                v.videoCodec = video.optString("video_codec", "");
                v.durationMs = mediaDurationToMs(parseLong(video.opt("duration")));
            }
            return v;
        }

        String label(int index) {
            String q = firstNonBlank(resolutionName, mediaName);
            if (q.isBlank() && height > 0) q = height + "p";
            if (q.isBlank()) q = "云播线路 " + (index + 1);
            StringBuilder s = new StringBuilder(q);
            if (origin && !q.contains("原画")) s.append(" · 原画");
            if (width > 0 && height > 0 && !q.contains(Integer.toString(height))) {
                s.append(" · ").append(width).append('×').append(height);
            }
            if (bitRate > 0) {
                s.append(String.format(Locale.ROOT, " · %.1f Mbps", bitRate / 1_000_000f));
            }
            return s.toString();
        }
    }

    static class CloudItem {
        String id;
        String parentId;
        String name;
        String kind;
        String size;
        String space;
        String folderType;
        String thumbnail;
        String webContentLink;
        long durationMs;
        final List<String> mediaUrls = new ArrayList<>();
        final List<MediaVariant> mediaVariants = new ArrayList<>();

        boolean isDir() { return FOLDER_KIND.equals(kind); }

        boolean isVideo() {
            String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
            return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") ||
                    n.endsWith(".m4v") || n.endsWith(".mov") || n.endsWith(".ts") ||
                    n.endsWith(".avi");
        }

        long sizeBytes() {
            try { return Long.parseLong(size); } catch (Exception e) { return 0L; }
        }

        static CloudItem fromJson(JSONObject o) {
            CloudItem f = new CloudItem();
            f.id = o.optString("id", "");
            f.parentId = o.optString("parent_id", "");
            f.name = o.optString("name", "(未命名)");
            f.kind = o.optString("kind", "");
            f.size = o.optString("size", "0");
            f.space = o.optString("space", "");
            f.folderType = o.optString("folder_type", "");
            f.thumbnail = o.optString("thumbnail_link", "");
            f.webContentLink = o.optString("web_content_link", "");

            // File-level params.duration is the most reliable duration fallback and is
            // represented in seconds by compatible Xunlei/PikPak APIs.
            JSONObject params = o.optJSONObject("params");
            if (params != null) {
                long seconds = parseLong(params.opt("duration"));
                if (seconds > 0) f.durationMs = seconds * 1000L;
            }

            JSONArray medias = o.optJSONArray("medias");
            if (medias != null) {
                for (int i = 0; i < medias.length(); i++) {
                    JSONObject m = medias.optJSONObject(i);
                    if (m == null) continue;
                    MediaVariant v = MediaVariant.fromJson(m);
                    // All transcodes represent the same program. Prefer the file-level
                    // duration over any ambiguous variant-level unit.
                    if (f.durationMs > 0) v.durationMs = f.durationMs;
                    else if (f.durationMs <= 0 && v.durationMs > 0) f.durationMs = v.durationMs;
                    if (!v.url.isBlank()) {
                        f.mediaVariants.add(v);
                        f.mediaUrls.add(v.url);
                        rememberDuration(v.url, v.durationMs > 0 ? v.durationMs : f.durationMs);
                    }
                }
            }
            rememberDuration(f.webContentLink, f.durationMs);
            return f;
        }
    }

    static class StreamLink {
        final String url;
        final String userAgent;
        final String label;
        final boolean cloudPlay;
        final int bitRate;
        final int width;
        final int height;
        final long durationMs;

        StreamLink(String url, String userAgent) {
            this(url, userAgent, "播放地址", false, 0, 0, 0);
        }

        StreamLink(String url, String userAgent, String label, boolean cloudPlay,
                   int bitRate, int width, int height) {
            this.url = url == null ? "" : url;
            this.userAgent = userAgent == null ? "" : userAgent;
            this.label = label == null ? "播放地址" : label;
            this.cloudPlay = cloudPlay;
            this.bitRate = bitRate;
            this.width = width;
            this.height = height;
            this.durationMs = knownDuration(this.url);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values != null) for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
