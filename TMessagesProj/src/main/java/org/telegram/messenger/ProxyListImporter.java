/*
 * Mass proxy list importer for RedoGram.
 *
 * Accepts JSON (from a file or from a URL) containing proxy entries of
 * mixed formats:
 *  - WEB proxies (RedoGram WebView proxy)
 *  - MTProto proxies
 *  - SOCKS5 proxies
 *  - Whitelist Bypass links (wbstream://, dion://, telemost, vk, https)
 *
 * Supported shapes:
 *  - a JSON array of entries; entries may be objects or string links;
 *  - an object with a wrapper key: "proxies", "proxy", "list", "items",
 *    "data", "entries", "results" holding the array (or a single entry);
 *  - a single entry object at the root;
 *  - string entries: tg://proxy?, https://t.me/proxy?, tg://socks?,
 *    https://t.me/socks?, wbstream://, dion://, https:// (bypass links)
 *    and classic "server:port:secret" / "server:port:user:pass" rows.
 */
package org.telegram.messenger;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import org.telegram.messenger.webproxy.WebProxyController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ProxyListImporter {

    public static final int MAX_SOURCE_SIZE = 8 * 1024 * 1024;

    public interface ImportCallback {
        void onDone(Result result, String error);
    }

    public static class Result {
        public int proxiesAdded;
        public int proxiesSkipped;
        public int proxiesInvalid;
        public int linksAdded;
        public int linksSkipped;
        public int linksInvalid;

        public int totalAdded() {
            return proxiesAdded + linksAdded;
        }

        public int totalSkipped() {
            return proxiesSkipped + linksSkipped;
        }

        public int totalInvalid() {
            return proxiesInvalid + linksInvalid;
        }
    }

    private ProxyListImporter() {
    }

    // ---- entry points (run on the global background queue) ----

    public static void importFromUrl(String url, ImportCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            Result result = null;
            String error = null;
            try {
                String trimmed = url == null ? "" : url.trim();
                byte[] data;
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    data = trimmed.getBytes(StandardCharsets.UTF_8);
                } else {
                    data = fetchUrl(trimmed);
                }
                result = parseAndImport(new String(data, StandardCharsets.UTF_8));
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage();
            }
            final Result finalResult = result;
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> callback.onDone(finalResult, finalError));
        });
    }

    public static void importFromStream(InputStream stream, ImportCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            Result result = null;
            String error = null;
            try {
                byte[] data = readCapped(stream, MAX_SOURCE_SIZE);
                result = parseAndImport(new String(data, StandardCharsets.UTF_8));
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage();
            } finally {
                try {
                    stream.close();
                } catch (Exception ignore) {
                }
            }
            final Result finalResult = result;
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> callback.onDone(finalResult, finalError));
        });
    }

    private static byte[] fetchUrl(String url) throws Exception {
        if (TextUtils.isEmpty(url)) {
            throw new IOException("empty url");
        }
        String lower = url.toLowerCase(Locale.US);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            throw new IOException("unsupported url");
        }
        HttpURLConnection connection = null;
        try {
            URL target = new URL(url);
            for (int redirect = 0; redirect < 5; redirect++) {
                connection = (HttpURLConnection) target.openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(15_000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "RedoGram/" + BuildVars.BUILD_VERSION_STRING);
                connection.connect();
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (TextUtils.isEmpty(location)) {
                        throw new IOException("HTTP " + code);
                    }
                    target = new URL(target, location);
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code);
                }
                InputStream stream = connection.getInputStream();
                return readCapped(stream, MAX_SOURCE_SIZE);
            }
            throw new IOException("too many redirects");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readCapped(InputStream stream, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = stream.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            if (out.size() > cap) {
                throw new IOException("source too large");
            }
        }
        if (out.size() == 0) {
            throw new IOException("empty response");
        }
        return out.toByteArray();
    }

    // ---- parsing ----

    public static Result parseAndImport(String json) throws Exception {
        if (TextUtils.isEmpty(json)) {
            throw new IOException("empty source");
        }
        Object root = new JSONTokener(json).nextValue();
        List<Object> entries = new ArrayList<>();
        collectEntries(root, entries, 0);
        if (entries.isEmpty()) {
            throw new IOException("no entries found in json");
        }

        Result result = new Result();
        for (Object entry : entries) {
            importEntry(entry, result);
        }

        if (result.proxiesAdded > 0) {
            AndroidUtilities.runOnUIThread(() ->
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged));
        }
        return result;
    }

    private static void collectEntries(Object value, List<Object> entries, int depth) {
        if (depth > 4 || value == null) {
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collectEntries(array.opt(i), entries, depth + 1);
            }
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (isEntryObject(object)) {
                entries.add(object);
                return;
            }
            for (String key : WRAPPER_KEYS) {
                if (object.has(key)) {
                    collectEntries(object.opt(key), entries, depth + 1);
                }
            }
            return;
        }
        if (value instanceof String) {
            String string = ((String) value).trim();
            if (string.isEmpty()) {
                return;
            }
            if (string.charAt(0) == '{' || string.charAt(0) == '[') {
                try {
                    collectEntries(new JSONTokener(string).nextValue(), entries, depth + 1);
                    return;
                } catch (Exception ignore) {
                }
            }
            entries.add(string);
        }
    }

    private static final String[] WRAPPER_KEYS = {
            "proxies", "proxy", "list", "items", "data", "entries", "results",
            "wbypass", "wb_bypass", "wbypass_links", "bypass_links", "links"
    };

    private static boolean isEntryObject(JSONObject object) {
        if (!TextUtils.isEmpty(firstString(object, "link", "url", "creator_link"))) {
            return true;
        }
        return !TextUtils.isEmpty(firstString(object, "server", "host", "address", "addr", "ip", "hostname"))
                || !TextUtils.isEmpty(firstString(object, "room", "room_id", "wbstream", "secret"));
    }

    private static void importEntry(Object entry, Result result) {
        if (entry instanceof JSONObject) {
            importProxyObject((JSONObject) entry, result);
        } else if (entry instanceof String) {
            String string = ((String) entry).trim();
            if (string.isEmpty()) {
                return;
            }
            if (looksLikeLink(string)) {
                importLink(string, result);
            } else {
                importColonProxy(string, result);
            }
        }
    }

    private static void importProxyObject(JSONObject object, Result result) {
        String link = firstString(object, "link", "url", "creator_link");
        if (!TextUtils.isEmpty(link)) {
            importLink(link, result);
            return;
        }
        String type = normalizeType(firstString(object, "type", "kind", "protocol", "proxy_type"));
        if (type == null) {
            String room = firstString(object, "room", "room_id", "wbstream", "telemost", "dion");
            if (!TextUtils.isEmpty(room)) {
                importBypassLink(room, result);
                return;
            }
            String secret = firstString(object, "secret");
            if (!TextUtils.isEmpty(secret)) {
                type = WebProxyController.isWebProxySecret(secret) ? "web" : "mtproto";
            } else {
                type = "socks";
            }
        }
        switch (type) {
            case "web": {
                String server = firstString(object, "server", "host", "address", "addr", "ip", "hostname");
                String key = firstString(object, "secret", "key", "password", "pass");
                int port = optInt(object, "port", 443);
                if (TextUtils.isEmpty(server) || TextUtils.isEmpty(key) || port <= 0) {
                    result.proxiesInvalid++;
                    return;
                }
                String encoded = WebProxyController.buildWebProxySecret(key);
                if (encoded == null) {
                    result.proxiesInvalid++;
                    return;
                }
                addProxyEntry(server, port, "", "", encoded, result);
                return;
            }
            case "mtproto": {
                String server = firstString(object, "server", "host", "address", "addr", "ip", "hostname");
                String secret = firstString(object, "secret", "password", "pass");
                int port = optInt(object, "port", 443);
                if (TextUtils.isEmpty(server) || TextUtils.isEmpty(secret) || port <= 0) {
                    result.proxiesInvalid++;
                    return;
                }
                addProxyEntry(server, port, "", "", secret, result);
                return;
            }
            case "socks": {
                String server = firstString(object, "server", "host", "address", "addr", "ip", "hostname");
                int port = optInt(object, "port", 1080);
                if (TextUtils.isEmpty(server) || port <= 0) {
                    result.proxiesInvalid++;
                    return;
                }
                String user = firstString(object, "user", "username", "login");
                String pass = firstString(object, "pass", "password");
                addProxyEntry(server, port, TextUtils.isEmpty(user) ? "" : user, TextUtils.isEmpty(pass) ? "" : pass, "", result);
                return;
            }
            case "wbypass": {
                String link = firstString(object, "wbstream", "room", "room_id", "telemost", "dion", "join");
                importBypassLink(link, result);
                return;
            }
            default: {
                result.proxiesInvalid++;
            }
        }
    }

    private static void importLink(String raw, Result result) {
        String link = raw.trim();
        String lower = link.toLowerCase(Locale.US);
        if (lower.contains("t.me/proxy") || lower.startsWith("tg://proxy")) {
            Uri uri = Uri.parse(link);
            String server = uri.getQueryParameter("server");
            int port = parsePort(uri.getQueryParameter("port"), 443);
            String secret = uri.getQueryParameter("secret");
            if (TextUtils.isEmpty(server) || port <= 0) {
                result.proxiesInvalid++;
                return;
            }
            addProxyEntry(server, port, "", "", TextUtils.isEmpty(secret) ? "" : secret, result);
        } else if (lower.contains("t.me/socks") || lower.startsWith("tg://socks")) {
            Uri uri = Uri.parse(link);
            String server = uri.getQueryParameter("server");
            int port = parsePort(uri.getQueryParameter("port"), 1080);
            if (TextUtils.isEmpty(server) || port <= 0) {
                result.proxiesInvalid++;
                return;
            }
            String user = uri.getQueryParameter("user");
            String pass = uri.getQueryParameter("pass");
            addProxyEntry(server, port, TextUtils.isEmpty(user) ? "" : user, TextUtils.isEmpty(pass) ? "" : pass, "", result);
        } else {
            importBypassLink(link, result);
        }
    }

    private static void importBypassLink(String value, Result result) {
        if (TextUtils.isEmpty(value)) {
            result.linksInvalid++;
            return;
        }
        String link = value.trim();
        String lower = link.toLowerCase(Locale.US);
        if (!lower.startsWith("wbstream://") && !lower.startsWith("dion://")
                && !lower.startsWith("http://") && !lower.startsWith("https://")
                && !lower.startsWith("tg://")) {
            // bare room id -> wbstream link
            link = "wbstream://" + link;
        }
        if (lower.startsWith("tg://") || (lower.startsWith("http") && lower.contains("t.me/"))) {
            result.linksInvalid++;
            return;
        }
        if (WhitelistBypassManager.validateCreatorLink(link) != null) {
            result.linksInvalid++;
            return;
        }
        if (WhitelistBypassManager.addSavedLink(link)) {
            result.linksAdded++;
        } else {
            result.linksSkipped++;
        }
    }

    private static void importColonProxy(String value, Result result) {
        int first = value.indexOf(':');
        int second = first < 0 ? -1 : value.indexOf(':', first + 1);
        if (first <= 0 || second <= first) {
            result.proxiesInvalid++;
            return;
        }
        String server = value.substring(0, first).trim();
        int port = parsePort(value.substring(first + 1, second), 0);
        String rest = value.substring(second + 1);
        if (TextUtils.isEmpty(server) || port <= 0) {
            result.proxiesInvalid++;
            return;
        }
        int divider = rest.indexOf(':');
        if (divider >= 0) {
            String user = rest.substring(0, divider);
            String pass = rest.substring(divider + 1);
            addProxyEntry(server, port, user, pass, "", result);
        } else if (!rest.isEmpty()) {
            addProxyEntry(server, port, "", "", rest, result);
        } else {
            addProxyEntry(server, port, "", "", "", result);
        }
    }

    private static void addProxyEntry(String server, int port, String user, String pass, String secret, Result result) {
        if (TextUtils.isEmpty(server) || port <= 0) {
            result.proxiesInvalid++;
            return;
        }
        SharedConfig.ProxyInfo candidate = new SharedConfig.ProxyInfo(server, port, user, pass, secret);
        SharedConfig.ProxyInfo existing = SharedConfig.addProxy(candidate);
        if (existing == candidate) {
            result.proxiesAdded++;
        } else {
            result.proxiesSkipped++;
        }
    }

    // ---- helpers ----

    private static boolean looksLikeLink(String value) {
        String lower = value.toLowerCase(Locale.US);
        return lower.startsWith("tg://")
                || lower.startsWith("wbstream://")
                || lower.startsWith("dion://")
                || lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.contains("t.me/proxy")
                || lower.contains("t.me/socks");
    }

    private static String normalizeType(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        String lower = value.trim().toLowerCase(Locale.US).replace("-", "").replace("_", "");
        switch (lower) {
            case "web":
            case "webproxy":
            case "webproxies":
            case "redogram":
                return "web";
            case "mtproto":
            case "mtproxy":
            case "mt":
            case "telegram":
                return "mtproto";
            case "socks":
            case "socks5":
            case "socks5h":
                return "socks";
            case "wbypass":
            case "wlbypass":
            case "wb":
            case "bypass":
            case "whitelist":
            case "whitelistbypass":
            case "creator":
            case "link":
                return "wbypass";
            default:
                return null;
        }
    }

    private static String firstString(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, null);
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return null;
    }

    private static int optInt(JSONObject object, String key, int fallback) {
        int value = object.optInt(key, fallback);
        return value > 0 ? value : fallback;
    }

    private static int parsePort(String value, int fallback) {
        if (TextUtils.isEmpty(value)) {
            return fallback;
        }
        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535 ? port : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
