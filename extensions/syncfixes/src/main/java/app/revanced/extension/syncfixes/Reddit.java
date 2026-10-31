package app.revanced.extension.syncfixes;

import android.content.ContentValues;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sync for Reddit: since Oct 2026 Reddit answers /api/info and /api/morechildren with 403 "Blocked" for
 * third-party clients, while /comments still works.
 * <p>
 * - Galleries: Sync's OAuthGalleryRequest asks /api/info.json?id=t3_X. {@link #galleryUrl} points it at
 * /comments/X.json?limit=1, and {@link #response} hands the parser element [0] (the post listing), which is
 * exactly what /api/info returned.
 * - Load more comments: Sync's OAuthCommentsMoreRequest POSTs link_id + children to /api/morechildren. The patch
 * makes it a GET of {@link #moreUrl} (the parent's subtree from /comments), and {@link #response} flattens that
 * subtree into morechildren's {"json":{"data":{"things":[...]}}}: the asked-for comments and everything under
 * them, pre-order, with "replies" emptied, like morechildren.
 */
@SuppressWarnings("unused")
public final class Reddit {
    private static final String TAG = "SyncFixes";
    /** Marker query parameter: Reddit ignores unknown parameters, we use it to recognize our requests. */
    static final String MARK = "syncfixes=";

    private static final Pattern INFO = Pattern.compile("^https://([a-z]+)\\.reddit\\.com/api/info(?:\\.json)?/?\\?id=t3_([a-z0-9]+)$");
    /** children (as sent to morechildren) -> parent fullname, filled when the user taps "load more". */
    private static final Map<String, String> PARENTS = new ConcurrentHashMap<>();
    private static final Map<String, String> WANTED = new ConcurrentHashMap<>();
    private static int nextKey;

    private Reddit() {
    }

    // ---------------------------------------------------------------- galleries

    /** Hooked at the return of OAuthGalleryRequest's static URL builder. */
    public static String galleryUrl(String url) {
        if (url == null) return null;
        Matcher m = INFO.matcher(url);
        if (!m.matches()) return url;
        return "https://" + m.group(1) + ".reddit.com/comments/" + m.group(2) + ".json?limit=1&" + MARK + "info";
    }

    // ---------------------------------------------------------------- load more comments

    /** Hooked at the start of the comments presenter's "load more" with the "more" row (xa.d). */
    public static void moreBegin(Object moreRow) {
        try {
            ContentValues values = contentValues(moreRow);
            String children = values.getAsString("commentMoreIds");
            String parent = values.getAsString("parent_id");
            if (children != null && parent != null) {
                if (PARENTS.size() > 100) PARENTS.clear();
                PARENTS.put(children, parent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "moreBegin", t);
        }
    }

    private static ContentValues contentValues(Object row) throws Exception {
        for (Class<?> c = row.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType() == ContentValues.class) {
                    m.setAccessible(true);
                    return (ContentValues) m.invoke(row);
                }
            }
        }
        throw new IllegalStateException("no ContentValues getter on " + row.getClass());
    }

    /**
     * Replaces the morechildren URL in OAuthCommentsMoreRequest's constructor (which the patch also turns into
     * a GET). {@code original} is Sync's morechildren URL, {@code link} the post id, {@code children} the
     * comma-separated ids, {@code sort} the comment sort or null.
     */
    public static String moreUrl(String original, String link, String children, String sort) {
        String parent = children == null ? null : PARENTS.get(children);
        String base = original;
        int api = original == null ? -1 : original.indexOf("api/morechildren");
        base = api < 0 ? "https://oauth.reddit.com/" : original.substring(0, api);
        String linkId = link != null && link.startsWith("t3_") ? link.substring(3) : link;
        boolean topLevel = parent == null || !parent.startsWith("t1_");
        StringBuilder sb = new StringBuilder(base).append("comments/").append(linkId);
        if (!topLevel) sb.append("/_/").append(parent.substring(3));
        // Reddit caps a fetch at 500 comments. At the top level, spend them on top-level comments; deeper
        // replies come back as their own "load more" (the nested case, which loads fully).
        sb.append(".json?limit=500&depth=").append(topLevel ? 2 : 10)
                .append("&always_show_media=1&profile_img=true");
        if (sort != null && !sort.isEmpty()) sb.append("&sort=").append(sort.toLowerCase());
        String key;
        synchronized (Reddit.class) {
            if (WANTED.size() > 50) WANTED.clear();
            key = Integer.toString(++nextKey);
        }
        WANTED.put(key, children == null ? "" : children);
        sb.append('&').append(MARK).append("more&parent=").append(parent == null ? "t3_" + linkId : parent)
                .append("&want=").append(key);
        return sb.toString();
    }

    // ---------------------------------------------------------------- responses

    /**
     * Hooked at the start of both requests' parseNetworkResponse: returns a NetworkResponse whose body is in
     * the shape the original endpoint used, or the original response when it isn't ours.
     */
    public static Object response(Object request, Object networkResponse) {
        try {
            String url = (String) request.getClass().getMethod("getUrl").invoke(request);
            if (url == null || !url.contains(MARK)) return networkResponse;
            byte[] data = (byte[]) networkResponse.getClass().getField("data").get(networkResponse);
            String out = convert(url, new String(data, StandardCharsets.UTF_8));
            return copyWithData(networkResponse, out.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w(TAG, "response", t);
            return networkResponse;
        }
    }

    /** Pure conversion, by the marker in the URL. */
    static String convert(String url, String text) throws JSONException {
        if (url.contains(MARK + "info")) {
            return new JSONArray(text).getJSONObject(0).toString();
        }
        if (url.contains(MARK + "more")) {
            String want = WANTED.get(param(url, "want"));
            return more(text, param(url, "parent"), want == null ? "" : want);
        }
        return text;
    }

    private static Object copyWithData(Object r, byte[] data) throws Exception {
        Class<?> c = r.getClass();
        int status = c.getField("statusCode").getInt(r);
        boolean notModified = c.getField("notModified").getBoolean(r);
        long time = c.getField("networkTimeMs").getLong(r);
        Object headers = c.getField("allHeaders").get(r);
        Constructor<?> k = c.getConstructor(int.class, byte[].class, boolean.class, long.class, List.class);
        return k.newInstance(status, data, notModified, time, headers);
    }

    static String more(String text, String parent, String want) throws JSONException {
        Set<String> wanted = new HashSet<>();
        for (String id : want.split(",")) {
            id = id.trim();
            if (id.startsWith("t1_")) id = id.substring(3);
            if (!id.isEmpty()) wanted.add(id);
        }
        Set<String> shown = new HashSet<>();
        shown.add(parent);
        JSONArray things = new JSONArray();
        JSONArray top = new JSONArray(text).getJSONObject(1).getJSONObject("data").getJSONArray("children");
        walk(top, parent, wanted, shown, things);
        return new JSONObject().put("json", new JSONObject()
                .put("errors", new JSONArray())
                .put("data", new JSONObject().put("things", things))).toString();
    }

    /** Pre-order walk, like morechildren: a parent always comes before its replies. Returns comments added. */
    private static int walk(JSONArray children, String parent, Set<String> wanted, Set<String> shown,
                            JSONArray things) throws JSONException {
        int added = 0;
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            if (child == null) continue;
            JSONObject data = child.optJSONObject("data");
            if (data == null) continue;
            String parentId = data.optString("parent_id");
            if ("t1".equals(child.optString("kind"))) {
                JSONObject replies = data.optJSONObject("replies");
                boolean underAdded = shown.contains(parentId) && !parentId.equals(parent);
                if (underAdded || (wanted.contains(data.optString("id")) && shown.contains(parentId))) {
                    data.put("replies", "");
                    things.put(child);
                    shown.add(data.optString("name"));
                    added++;
                }
                if (replies != null && replies.optJSONObject("data") != null) {
                    JSONArray sub = replies.optJSONObject("data").optJSONArray("children");
                    if (sub != null) added += walk(sub, parent, wanted, shown, things);
                }
            } else if ("more".equals(child.optString("kind")) && shown.contains(parentId)) {
                if (!parentId.equals(parent) || overlaps(data.optJSONArray("children"), wanted)) {
                    things.put(child);
                }
            }
        }
        return added;
    }

    private static boolean overlaps(JSONArray ids, Set<String> wanted) {
        if (ids == null) return false;
        for (int i = 0; i < ids.length(); i++) if (wanted.contains(ids.optString(i))) return true;
        return false;
    }

    private static String param(String url, String name) {
        Matcher m = Pattern.compile("[?&]" + name + "=([^&]*)").matcher(url);
        return m.find() ? m.group(1) : "";
    }
}
