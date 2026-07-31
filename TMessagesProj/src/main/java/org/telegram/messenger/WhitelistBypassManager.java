/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Stores Whitelist Bypass settings and owns the temporary Telegram SOCKS5
 * configuration used while the embedded Joiner is connected.
 */
public final class WhitelistBypassManager {

    public enum State {
        OFF,
        STARTING,
        CONNECTING,
        CAPTCHA,
        CONNECTED,
        RECONNECTING,
        ERROR
    }

    public interface Listener {
        void onWhitelistBypassStateChanged();
    }

    private static final String PREFS = "whitelist_bypass";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LINK = "creator_link";
    private static final String KEY_NAME = "display_name";
    private static final String KEY_MODE = "tunnel_mode";
    private static final String KEY_INTERNAL_PORT = "internal_port";
    private static final String KEY_SNAPSHOT_VALID = "proxy_snapshot_valid";
    private static final String KEY_PREVIOUS_ENABLED = "previous_proxy_enabled";
    private static final String KEY_PREVIOUS_CALLS = "previous_proxy_calls";
    private static final String KEY_PREVIOUS_ADDRESS = "previous_proxy_address";
    private static final String KEY_PREVIOUS_PORT = "previous_proxy_port";
    private static final String KEY_PREVIOUS_USER = "previous_proxy_user";
    private static final String KEY_PREVIOUS_PASSWORD = "previous_proxy_password";
    private static final String KEY_PREVIOUS_SECRET = "previous_proxy_secret";

    public static final String MODE_VIDEO = "video";
    public static final String MODE_DC = "dc";
    public static final String INTERNAL_HOST = "127.0.0.1";

    private static final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private static volatile State state = State.OFF;
    private static volatile String statusDetail = "";
    private static volatile String captchaUrl = "";
    private static volatile boolean serviceRunning;

    private WhitelistBypassManager() {
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getCreatorLink() {
        return preferences().getString(KEY_LINK, "");
    }

    public static String getDisplayName() {
        return preferences().getString(KEY_NAME, "Telegram");
    }

    public static String getTunnelMode() {
        String mode = preferences().getString(KEY_MODE, MODE_VIDEO);
        return MODE_DC.equals(mode) ? MODE_DC : MODE_VIDEO;
    }

    public static void saveSettings(String creatorLink, String displayName, String tunnelMode) {
        preferences().edit()
                .putString(KEY_LINK, creatorLink == null ? "" : creatorLink.trim())
                .putString(KEY_NAME, TextUtils.isEmpty(displayName) ? "Telegram" : displayName.trim())
                .putString(KEY_MODE, MODE_DC.equals(tunnelMode) ? MODE_DC : MODE_VIDEO)
                .apply();
    }

    public static boolean isEnabled() {
        return preferences().getBoolean(KEY_ENABLED, false);
    }

    public static boolean isConnected() {
        return state == State.CONNECTED;
    }

    public static boolean isServiceRunning() {
        return serviceRunning;
    }

    public static State getState() {
        if (state == State.OFF && isEnabled()) {
            return State.STARTING;
        }
        return state;
    }

    public static String getStatusDetail() {
        return statusDetail;
    }

    public static String getCaptchaUrl() {
        return captchaUrl;
    }

    public static int getInternalPort() {
        return preferences().getInt(KEY_INTERNAL_PORT, 0);
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public static String validateCreatorLink(String value) {
        if (TextUtils.isEmpty(value) || TextUtils.isEmpty(value.trim())) {
            return LocaleController.getString(R.string.WhitelistBypassLinkRequired);
        }
        String link = value.trim();
        String lower = link.toLowerCase(Locale.US);
        if (lower.startsWith("wbstream://")) {
            return link.length() > "wbstream://".length() ? null : LocaleController.getString(R.string.WhitelistBypassLinkInvalid);
        }
        if (lower.startsWith("dion://")) {
            return link.length() > "dion://".length() ? null : LocaleController.getString(R.string.WhitelistBypassLinkInvalid);
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return LocaleController.getString(R.string.WhitelistBypassLinkInvalid);
        }
        return null;
    }

    public static boolean isVideoOnlyLink(String value) {
        String platform = detectPlatform(value);
        return "telemost".equals(platform) || "dion".equals(platform);
    }

    public static String start(Context context) {
        String error = validateCreatorLink(getCreatorLink());
        if (error != null) {
            return error;
        }
        synchronized (WhitelistBypassManager.class) {
            if (!preferences().getBoolean(KEY_SNAPSHOT_VALID, false)) {
                snapshotCurrentProxy();
            }
            preferences().edit().putBoolean(KEY_ENABLED, true).commit();
        }
        setState(State.STARTING, "", "");
        try {
            Intent intent = new Intent(context, WhitelistBypassService.class);
            intent.setAction(WhitelistBypassService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return null;
        } catch (Exception e) {
            FileLog.e(e);
            preferences().edit().putBoolean(KEY_ENABLED, false).commit();
            restorePreviousProxy();
            setState(State.ERROR, e.getMessage(), "");
            return LocaleController.getString(R.string.WhitelistBypassStartFailed);
        }
    }

    public static void restartIfNeeded(Context context) {
        if (isEnabled() && !serviceRunning) {
            try {
                Intent intent = new Intent(context, WhitelistBypassService.class);
                intent.setAction(WhitelistBypassService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
            } catch (Exception e) {
                FileLog.e(e);
                preferences().edit().putBoolean(KEY_ENABLED, false).commit();
                restorePreviousProxy();
                setState(State.ERROR, e.getMessage(), "");
            }
        }
    }

    /**
     * A child Joiner cannot survive death of the Telegram process. Avoid
     * leaving Telegram pointed at a dead loopback proxy after a cold start.
     */
    static void recoverAfterProcessRestart() {
        if (!isEnabled()) {
            return;
        }
        preferences().edit().putBoolean(KEY_ENABLED, false).commit();
        restorePreviousProxy();
        setState(State.OFF, "", "");
    }

    public static void stop(Context context) {
        preferences().edit().putBoolean(KEY_ENABLED, false).commit();
        if (serviceRunning) {
            Intent intent = new Intent(context, WhitelistBypassService.class);
            intent.setAction(WhitelistBypassService.ACTION_STOP);
            context.startService(intent);
        } else {
            restorePreviousProxy();
            setState(State.OFF, "", "");
        }
    }

    static void setServiceRunning(boolean running) {
        serviceRunning = running;
    }

    static void onSessionStopped(String error) {
        preferences().edit().putBoolean(KEY_ENABLED, false).commit();
        restorePreviousProxy();
        serviceRunning = false;
        if (TextUtils.isEmpty(error)) {
            setState(State.OFF, "", "");
        } else {
            setState(State.ERROR, error, "");
        }
    }

    static void setState(State newState, String detail, String newCaptchaUrl) {
        state = newState;
        statusDetail = detail == null ? "" : detail;
        captchaUrl = newCaptchaUrl == null ? "" : newCaptchaUrl;
        notifyListeners();
    }

    static String getRelayMode() {
        return detectPlatform(getCreatorLink()) + "-headless-joiner";
    }

    static String getJoinCommand() throws Exception {
        String link = getCreatorLink().trim();
        String platform = detectPlatform(link);
        String mode = getTunnelMode();
        if (("telemost".equals(platform) || "dion".equals(platform)) && MODE_DC.equals(mode)) {
            mode = MODE_VIDEO;
        }

        JSONObject params = new JSONObject();
        params.put("displayName", getDisplayName());
        params.put("tunnelMode", mode);
        params.put("vp8Fps", 24);
        params.put("vp8Batch", 30);
        params.put("dualTrack", false);
        params.put("reliable", false);

        if ("telemost".equals(platform)) {
            params.put("joinLink", link);
        } else if ("wbstream".equals(platform) || "dion".equals(platform)) {
            params.put("roomId", extractRoomId(link));
        } else {
            params.put("joinLink", link);
        }
        return ("vk".equals(platform) ? "AUTH:" : "JOIN:") + params;
    }

    static synchronized boolean activateTelegramProxy(int port) {
        SharedConfig.loadProxyList();
        removeInternalProxies();

        SharedConfig.ProxyInfo proxy = SharedConfig.addProxy(new SharedConfig.ProxyInfo(
                INTERNAL_HOST,
                port,
                "",
                "",
                ""
        ));
        SharedConfig.currentProxy = proxy;
        MessagesController.getGlobalMainSettings().edit()
                .putString("proxy_ip", proxy.address)
                .putString("proxy_pass", proxy.password)
                .putString("proxy_user", proxy.username)
                .putInt("proxy_port", proxy.port)
                .putString("proxy_secret", "")
                .putBoolean("proxy_enabled", true)
                .putBoolean("proxy_enabled_calls", false)
                .commit();
        preferences().edit().putInt(KEY_INTERNAL_PORT, port).commit();
        ConnectionsManager.setProxySettings(true, proxy.address, proxy.port, proxy.username, proxy.password, "");
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        return WhitelistWebViewProxy.setProxy(port);
    }

    static synchronized void restorePreviousProxy() {
        SharedPreferences prefs = preferences();
        WhitelistWebViewProxy.clearProxy();
        if (!prefs.getBoolean(KEY_SNAPSHOT_VALID, false)) {
            return;
        }

        SharedConfig.loadProxyList();
        removeInternalProxies();

        boolean enabled = prefs.getBoolean(KEY_PREVIOUS_ENABLED, false);
        boolean calls = prefs.getBoolean(KEY_PREVIOUS_CALLS, false);
        String address = prefs.getString(KEY_PREVIOUS_ADDRESS, "");
        int port = prefs.getInt(KEY_PREVIOUS_PORT, 1080);
        String user = prefs.getString(KEY_PREVIOUS_USER, "");
        String password = prefs.getString(KEY_PREVIOUS_PASSWORD, "");
        String secret = prefs.getString(KEY_PREVIOUS_SECRET, "");

        SharedConfig.ProxyInfo previous = null;
        if (!TextUtils.isEmpty(address)) {
            for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
                if (sameProxy(info, address, port, user, password, secret)) {
                    previous = info;
                    break;
                }
            }
            if (previous == null) {
                previous = SharedConfig.addProxy(new SharedConfig.ProxyInfo(address, port, user, password, secret));
            }
        }
        SharedConfig.currentProxy = previous;
        SharedConfig.saveProxyList();

        MessagesController.getGlobalMainSettings().edit()
                .putString("proxy_ip", address)
                .putString("proxy_pass", password)
                .putString("proxy_user", user)
                .putInt("proxy_port", port)
                .putString("proxy_secret", secret)
                .putBoolean("proxy_enabled", enabled && previous != null)
                .putBoolean("proxy_enabled_calls", calls && previous != null)
                .commit();
        ConnectionsManager.setProxySettings(enabled && previous != null, address, port, user, password, secret);
        prefs.edit()
                .putBoolean(KEY_SNAPSHOT_VALID, false)
                .remove(KEY_INTERNAL_PORT)
                .apply();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    private static synchronized void snapshotCurrentProxy() {
        SharedConfig.loadProxyList();
        SharedPreferences main = MessagesController.getGlobalMainSettings();
        preferences().edit()
                .putBoolean(KEY_SNAPSHOT_VALID, true)
                .putBoolean(KEY_PREVIOUS_ENABLED, main.getBoolean("proxy_enabled", false))
                .putBoolean(KEY_PREVIOUS_CALLS, main.getBoolean("proxy_enabled_calls", false))
                .putString(KEY_PREVIOUS_ADDRESS, main.getString("proxy_ip", ""))
                .putInt(KEY_PREVIOUS_PORT, main.getInt("proxy_port", 1080))
                .putString(KEY_PREVIOUS_USER, main.getString("proxy_user", ""))
                .putString(KEY_PREVIOUS_PASSWORD, main.getString("proxy_pass", ""))
                .putString(KEY_PREVIOUS_SECRET, main.getString("proxy_secret", ""))
                .commit();
    }

    private static void removeInternalProxies() {
        int internalPort = preferences().getInt(KEY_INTERNAL_PORT, 0);
        if (internalPort == 0) {
            return;
        }
        boolean changed = false;
        for (SharedConfig.ProxyInfo info : new ArrayList<>(SharedConfig.proxyList)) {
            if (INTERNAL_HOST.equals(info.address)
                    && info.port == internalPort
                    && TextUtils.isEmpty(info.username)
                    && TextUtils.isEmpty(info.password)
                    && TextUtils.isEmpty(info.secret)) {
                SharedConfig.proxyList.remove(info);
                if (SharedConfig.currentProxy == info) {
                    SharedConfig.currentProxy = null;
                }
                changed = true;
            }
        }
        if (changed) {
            SharedConfig.saveProxyList();
        }
    }

    private static boolean sameProxy(SharedConfig.ProxyInfo info, String address, int port, String user, String password, String secret) {
        return info.port == port
                && TextUtils.equals(info.address, address)
                && TextUtils.equals(info.username, user)
                && TextUtils.equals(info.password, password)
                && TextUtils.equals(info.secret, secret);
    }

    private static String detectPlatform(String value) {
        String link = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (link.startsWith("dion://") || link.contains("dion.vc/event/")) {
            return "dion";
        }
        if (link.startsWith("wbstream://")) {
            return "wbstream";
        }
        if (link.contains("telemost")) {
            return "telemost";
        }
        return "vk";
    }

    private static String extractRoomId(String value) {
        String link = value.trim();
        if (link.toLowerCase(Locale.US).startsWith("wbstream://")) {
            return link.substring("wbstream://".length()).trim();
        }
        if (link.toLowerCase(Locale.US).startsWith("dion://")) {
            return link.substring("dion://".length()).trim();
        }
        String marker = "dion.vc/event/";
        int index = link.toLowerCase(Locale.US).indexOf(marker);
        if (index >= 0) {
            String room = link.substring(index + marker.length());
            int end = room.indexOf('?');
            if (end >= 0) {
                room = room.substring(0, end);
            }
            end = room.indexOf('/');
            if (end >= 0) {
                room = room.substring(0, end);
            }
            return room.trim();
        }
        return link;
    }

    private static void notifyListeners() {
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener listener : listeners) {
                listener.onWhitelistBypassStateChanged();
            }
        });
    }
}
