/*
 * WEB Proxy controller for RedoGram.
 *
 * Owns the lifecycle of the single WebProxyCarrier (hidden WebView)
 * and the registry of native streams that are routed through it.
 * Called from the native tgnet network stack through the JNI bridge
 * in ConnectionsManager.
 */
package org.telegram.messenger.webproxy;

import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class WebProxyController implements WebProxyCarrier.Callbacks {

    private static final long RETRY_MIN_TIMEOUT = 2_000;
    private static final long RETRY_MAX_TIMEOUT = 30_000;

    // Web proxy secrets are stored natively as 0xf0-prefixed raw key bytes.
    // The user enters the key as a hex or base64url string (the same formats
    // used for MTProto proxy passwords). This encodes it for tgnet.
    public static String buildWebProxySecret(String password) {
        if (password == null) {
            return null;
        }
        password = password.trim();
        if (password.isEmpty()) {
            return null;
        }
        byte[] key;
        try {
            key = decodeSecretText(password);
        } catch (Exception e) {
            return null;
        }
        if (key == null || key.length == 0) {
            return null;
        }
        StringBuilder hex = new StringBuilder();
        hex.append("f0");
        for (byte b : key) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    // Mirrors the tgnet decodeSecret: hex when every char is a hex digit,
    // otherwise base64url.
    public static byte[] decodeSecretText(String secret) {
        boolean allHex = true;
        for (int i = 0; i < secret.length(); i++) {
            char c = secret.charAt(i);
            if (!Character.isDigit(c) && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) {
                allHex = false;
                break;
            }
        }
        if (allHex) {
            if (secret.length() % 2 != 0) {
                return null;
            }
            byte[] result = new byte[secret.length() / 2];
            for (int i = 0; i < result.length; i++) {
                result[i] = (byte) ((Character.digit(secret.charAt(i * 2), 16) << 4)
                        | Character.digit(secret.charAt(i * 2 + 1), 16));
            }
            return result;
        }
        try {
            return android.util.Base64.decode(secret, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isWebProxySecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            return false;
        }
        try {
            byte[] raw = decodeSecretText(secret);
            return raw != null && raw.length >= 2 && raw[0] == (byte) 0xf0;
        } catch (Exception e) {
            return false;
        }
    }

    private static WebProxyController instance;
    private static final Object lock = new Object();

    public static WebProxyController get() {
        synchronized (lock) {
            if (instance == null) {
                instance = new WebProxyController();
            }
            return instance;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebProxyCarrier carrier;
    private String host;
    private String capability;

    // streams waiting to be routed through the carrier: instance -> stream ids
    private final SparseArray<Set<Integer>> registeredStreams = new SparseArray<>();

    private boolean stopped;
    private long lastFailedAt;
    private long retryDelay = RETRY_MIN_TIMEOUT;
    private boolean startScheduled;

    private WebProxyController() {}

    // ---- called from native via ConnectionsManager (main thread) ----

    public static void start(int instance, int streamId, String host, String capability) {
        get().registerStream(instance, streamId, host, capability);
    }

    public static void closeStream(int instance, int streamId) {
        get().unregisterStream(instance, streamId);
    }

    public static void stop() {
        get().shutdown();
    }

    public static void writeData(int instance, int streamId, byte[] data) {
        get().dispatchWrite(instance, streamId, data);
    }

    // ---- implementation ----

    private void registerStream(int instance, int streamId, String host, String capability) {
        stopped = false;
        boolean paramsChanged = paramsChanged(host, capability);
        if (paramsChanged) {
            restartCarrier(host, capability);
        } else if (carrier == null && !startScheduled) {
            maybeStartCarrier();
        }
        Set<Integer> set = registeredStreams.get(instance);
        if (set == null) {
            set = new HashSet<>();
            registeredStreams.put(instance, set);
        }
        set.add(streamId);
        WebProxyCarrier current = carrier;
        if (current != null && current.isValid()) {
            current.registerNativeStream(instance, streamId);
        }
    }

    private boolean paramsChanged(String host, String capability) {
        return !equals(this.host, host) || !equals(this.capability, capability);
    }

    private static boolean equals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void unregisterStream(int instance, int streamId) {
        Set<Integer> set = registeredStreams.get(instance);
        if (set != null) {
            set.remove(streamId);
        }
        WebProxyCarrier current = carrier;
        if (current != null) {
            current.closeNativeStream(instance, streamId);
        }
    }

    private void dispatchWrite(int instance, int streamId, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        WebProxyCarrier current = carrier;
        if (current == null || !current.isValid()) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (current.isValid()) {
                current.writeNativeData(streamId, data);
            }
        });
    }

    private void maybeStartCarrier() {
        if (stopped) {
            return;
        }
        long sinceFail = System.currentTimeMillis() - lastFailedAt;
        if (sinceFail < retryDelay) {
            startScheduled = true;
            handler.postDelayed(() -> {
                startScheduled = false;
                maybeStartCarrier();
            }, retryDelay - sinceFail);
            return;
        }
        WebProxyCarrier c = new WebProxyCarrier(this, host, capability);
        carrier = c;
        retryDelay = RETRY_MIN_TIMEOUT;
        c.start();
    }

    private void restartCarrier(String newHost, String newCapability) {
        WebProxyCarrier old = carrier;
        carrier = null;
        if (old != null) {
            old.close();
        }
        host = newHost;
        capability = newCapability;
        retryDelay = RETRY_MIN_TIMEOUT / 2;
        for (int i = 0; i < registeredStreams.size(); i++) {
            int instance = registeredStreams.keyAt(i);
            for (int streamId : new ArrayList<>(registeredStreams.valueAt(i))) {
                ConnectionsManager.native_webProxyFail(instance, streamId);
            }
        }
    }

    private void shutdown() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        WebProxyCarrier old = carrier;
        carrier = null;
        if (old != null) {
            old.close();
        }
        registeredStreams.clear();
        streamKeys.clear();
        host = null;
        capability = null;
        retryDelay = RETRY_MIN_TIMEOUT / 2;
    }

    // ---- carrier callbacks (main thread) ----

    @Override
    public void onCarrierReady(WebProxyCarrier ready) {
        retryDelay = RETRY_MIN_TIMEOUT;
        WebProxyCarrier current = carrier;
        if (current != ready) {
            return;
        }
        for (int i = 0; i < registeredStreams.size(); i++) {
            int instance = registeredStreams.keyAt(i);
            for (int streamId : registeredStreams.valueAt(i)) {
                ready.registerNativeStream(instance, streamId);
                ConnectionsManager.native_webProxyConnected(instance, streamId);
            }
        }
    }

    @Override
    public void onCarrierFailed(WebProxyCarrier failedCarrier, String reason) {
        WebProxyCarrier current = carrier;
        if (current != failedCarrier) {
            return;
        }
        carrier = null;
        lastFailedAt = System.currentTimeMillis();
        retryDelay = Math.min(Math.max(retryDelay * 2, RETRY_MIN_TIMEOUT), RETRY_MAX_TIMEOUT);
        FileLog.d("[webproxy] carrier failed (" + reason + "), retry in " + retryDelay);
        for (int i = 0; i < registeredStreams.size(); i++) {
            int instance = registeredStreams.keyAt(i);
            for (int streamId : new ArrayList<>(registeredStreams.valueAt(i))) {
                ConnectionsManager.native_webProxyFail(instance, streamId);
            }
        }
        maybeStartCarrier();
    }
}
