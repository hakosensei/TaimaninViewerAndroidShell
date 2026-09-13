package com.example.xunleivrplayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class Models {
    private Models() {}

    static final String FOLDER_KIND = "drive#folder";

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
        final List<String> mediaUrls = new ArrayList<>();

        boolean isDir() { return FOLDER_KIND.equals(kind); }

        boolean isVideo() {
            String n = name == null ? "" : name.toLowerCase();
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
            JSONArray medias = o.optJSONArray("medias");
            if (medias != null) {
                for (int i = 0; i < medias.length(); i++) {
                    JSONObject m = medias.optJSONObject(i);
                    if (m == null) continue;
                    JSONObject link = m.optJSONObject("link");
                    if (link == null) continue;
                    String u = link.optString("url", "");
                    if (!u.isBlank()) f.mediaUrls.add(u);
                }
            }
            return f;
        }
    }

    static class StreamLink {
        final String url;
        final String userAgent;
        StreamLink(String url, String userAgent) {
            this.url = url;
            this.userAgent = userAgent;
        }
    }
}
