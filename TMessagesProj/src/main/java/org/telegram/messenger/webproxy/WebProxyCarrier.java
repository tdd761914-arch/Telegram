/*
 * WEB Proxy carrier for RedoGram.
 *
 * Protocol-compatible port of the WEB proxy transport from
 * Telegram Desktop (MTP::WebProxy::WebviewCarrier + Transport).
 * A hidden WebView loads the bridge page served by the proxy host;
 * the page relays MTProto traffic to the relay endpoint and talks to
 * the native network stack through the window.TelegramWebProxy object
 * and the @JavascriptInterface bridge defined here.
 */
package org.telegram.messenger.webproxy;

import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewCompat;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WebProxyCarrier {

    // frame wire format (must match Telegram Desktop MTP::WebProxy)
    private static final int FRAME_HEADER_SIZE = 8;
    private static final int MAX_FRAME_PAYLOAD = 1024 * 1024;
    private static final int MAX_BATCH_FRAMES = 4096;
    private static final int INITIAL_STREAM_WINDOW = 4 * 1024 * 1024;
    private static final int DATA_FRAME_SIZE = 64 * 1024;

    private static final long HANDSHAKE_TIMEOUT = 10_000;
    private static final long HANDSHAKE_TOTAL_TIMEOUT = 45_000;
    private static final long HEALTH_TIMEOUT = 10_000;
    private static final long PROBE_INTERVAL = 3_000;
    private static final long WRITE_TIMEOUT = 10_000;
    private static final long WEBVIEW_CLOSE_GRACE = 200;
    private static final int MAX_PENDING_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PENDING_ITEMS = 1024;
    private static final int MAX_MESSAGE_BYTES = 2 * 1024 * 1024;
    private static final int WINDOW_FLUSH_BYTES = 256 * 1024;
    private static final long WINDOW_FLUSH_DELAY = 20;
    private static final int MAX_CLOSED_STREAM_IDS = 4096;

    private static final int FRAME_OPEN = 0x01;
    private static final int FRAME_DATA = 0x02;
    private static final int FRAME_CLOSE = 0x03;
    private static final int FRAME_WINDOW = 0x04;
    private static final int FRAME_PING = 0x05;
    private static final int FRAME_PONG = 0x06;
    private static final int FRAME_HELLO = 0x10;
    private static final int FRAME_WELCOME = 0x11;
    private static final int FRAME_AUTH_CHALLENGE = 0x12;
    private static final int FRAME_AUTH_RESPONSE = 0x13;
    private static final int FRAME_BYE = 0x1F;

    public interface Callbacks {
        void onCarrierReady(WebProxyCarrier carrier);

        void onCarrierFailed(WebProxyCarrier carrier, String reason);
    }

    private final Callbacks callbacks;
    private final String host;
    private final String capability;
    private final String nonce;
    private final String bridgeUrl;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, Stream> streams = new HashMap<>();
    private final ArrayDeque<Integer> readyStreams = new ArrayDeque<>();
    private final Set<Integer> readySet = new HashSet<>();
    private final Set<Integer> closedStreams = new HashSet<>();
    private final ArrayDeque<Integer> closedStreamOrder = new ArrayDeque<>();
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final Bridge bridge = new Bridge();

    private WebView webView;
    private Pending inFlight;
    private long writeSequence;
    private int pendingBytes;
    private long handshakeStarted;
    private boolean bridgeInitialized;
    private boolean adopted;
    private boolean failed;
    private boolean closing;

    private static final class Pending {
        byte[] frame;
    }

    private static final class Stream {
        final int id;
        final int instance;
        long sendWindow = INITIAL_STREAM_WINDOW;
        int receiveWindow = INITIAL_STREAM_WINDOW;
        int pendingWindow;
        final ArrayDeque<byte[]> outQueue = new ArrayDeque<>();
        int outBytes;

        Stream(int id, int instance) {
            this.id = id;
            this.instance = instance;
        }
    }

    private static final class Frame {
        final int type;
        final int streamId;
        final byte[] payload;

        Frame(int type, int streamId, byte[] payload) {
            this.type = type;
            this.streamId = streamId;
            this.payload = payload;
        }
    }

    private final Runnable handshakeTimeoutRunnable = () -> fail("handshake timeout");
    private final Runnable healthTimeoutRunnable = () -> fail("health timeout");
    private final Runnable writeTimeoutRunnable = () -> fail("write timeout");
    private final Runnable windowFlushRunnable = this::flushWindows;
    private final Runnable probeRunnable = () -> {
        if (!failed && !closing) {
            evaluate("window.redogramWebProxyBridge && window.redogramWebProxyBridge.invoke('h')");
            scheduleProbe();
        }
    };

    public WebProxyCarrier(Callbacks callbacks, String host, String capability) {
        this.callbacks = callbacks;
        this.host = host;
        this.capability = capability;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        this.nonce = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        this.bridgeUrl = Uri.parse("https://" + host).buildUpon()
                .path("/")
                .appendQueryParameter("bridge", capability)
                .fragment("android=" + nonce)
                .build()
                .toString();
    }

    public String getHost() {
        return host;
    }

    public String getCapability() {
        return capability;
    }

    public boolean isValid() {
        return !failed && !closing;
    }

    public void start() {
        LaunchActivity activity = LaunchActivity.instance;
        try {
            WebView wv = new WebView(ApplicationLoader.applicationContext);
            WebSettings settings = wv.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            wv.setBackgroundColor(0);
            wv.setAlpha(0.0f);
            wv.setVisibility(View.INVISIBLE);
            wv.setFocusable(false);
            wv.setFocusableInTouchMode(false);
            wv.setClickable(false);
            wv.setLongClickable(false);
            wv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return url == null || !Uri.parse(url).toString().equals(bridgeUrl);
                }
            });
            try {
                WebViewCompat.addDocumentStartJavaScript(wv, BridgeScript.SCRIPT, Collections.singletonList("https://" + host));
            } catch (Throwable t) {
                FileLog.e(t);
            }
            wv.addJavascriptInterface(bridge, "redogramWebProxyBridge");
            webView = wv;
            if (activity != null && activity.drawerLayoutContainer != null) {
                activity.drawerLayoutContainer.addView(wv, new ViewGroup.LayoutParams(1, 1));
            }
            handshakeStarted = System.currentTimeMillis();
            handler.postDelayed(handshakeTimeoutRunnable, HANDSHAKE_TIMEOUT);
            handler.postDelayed(healthTimeoutRunnable, HEALTH_TIMEOUT);
            scheduleProbe();
            wv.loadUrl(bridgeUrl);
        } catch (Throwable t) {
            FileLog.e(t);
            fail("webview init failed");
        }
    }

    private void scheduleProbe() {
        handler.postDelayed(probeRunnable, PROBE_INTERVAL);
    }

    // ---------------- bridge messages ----------------

    private class Bridge {
        @JavascriptInterface
        public void invoke(String value) {
            handler.post(() -> handleMessage(value));
        }
    }

    private void handleMessage(String value) {
        if (closing || failed) {
            return;
        }
        if (value == null || value.isEmpty() || value.length() > MAX_MESSAGE_BYTES) {
            fail("invalid message size");
            return;
        }
        heartbeat();
        char type = value.charAt(0);
        switch (type) {
            case 'h':
                if (value.length() != 1) {
                    fail("invalid heartbeat");
                }
                return;
            case 'f':
                fail("bridge script failure");
                return;
            case 'a': {
                long sequence;
                try {
                    sequence = Long.parseLong(value.substring(1));
                } catch (Exception e) {
                    fail("invalid write acknowledgement");
                    return;
                }
                if (inFlight == null || sequence != writeSequence) {
                    fail("invalid write acknowledgement");
                    return;
                }
                handler.removeCallbacks(writeTimeoutRunnable);
                pendingBytes -= inFlight.frame.length;
                inFlight = null;
                drain();
                flushStreams();
                return;
            }
            case 'c':
                handleControl(value.substring(1));
                return;
            case 'b': {
                byte[] decoded;
                try {
                    decoded = Base64.decode(value.substring(1), Base64.DEFAULT);
                } catch (Exception e) {
                    fail("invalid binary message");
                    return;
                }
                if (decoded.length == 0) {
                    fail("invalid binary message");
                    return;
                }
                handleFrames(decoded);
                return;
            }
            default:
                fail("invalid message type");
        }
    }

    private void heartbeat() {
        handler.removeCallbacks(healthTimeoutRunnable);
        handler.postDelayed(healthTimeoutRunnable, HEALTH_TIMEOUT);
    }

    private void handleControl(String control) {
        try {
            JSONObject object = new JSONObject(control);
            String type = object.optString("t");
            if (!bridgeInitialized && "tproxy-android-init".equals(type)) {
                if (object.optInt("v", -1) != 1 || !nonce.equals(object.optString("nonce"))) {
                    fail("invalid bridge initialization");
                    return;
                }
                bridgeInitialized = true;
                enqueueFrame(FRAME_HELLO, 0, new byte[]{1});
            } else if ("close".equals(type)) {
                fail("bridge closed");
            } else if ("status".equals(type)) {
                String state = object.optString("state");
                if ("failed".equals(state)) {
                    fail("bridge reported failure");
                } else if (!adopted && ("connecting".equals(state) || "reconnecting".equals(state))) {
                    extendHandshake();
                }
            }
        } catch (Exception e) {
            fail("invalid control message");
        }
    }

    private void extendHandshake() {
        long left = HANDSHAKE_TOTAL_TIMEOUT - (System.currentTimeMillis() - handshakeStarted);
        if (left <= 0) {
            fail("total handshake timeout");
            return;
        }
        handler.removeCallbacks(handshakeTimeoutRunnable);
        handler.postDelayed(handshakeTimeoutRunnable, Math.min(HANDSHAKE_TIMEOUT, left));
    }

    private void handleFrames(byte[] input) {
        if (!bridgeInitialized) {
            fail("binary message before initialization");
            return;
        }
        List<Frame> frames = new ArrayList<>();
        if (!parseFrames(input, frames)) {
            fail("invalid bridge frame batch");
            return;
        }
        int consumed = 0;
        for (Frame frame : frames) {
            consumed += FRAME_HEADER_SIZE + frame.payload.length;
        }
        if (consumed != input.length || frames.isEmpty()) {
            fail("invalid bridge frame batch");
            return;
        }
        for (Frame frame : frames) {
            if (!processRelayFrame(frame)) {
                fail("invalid bridge frame");
                return;
            }
        }
    }

    private boolean parseFrames(byte[] input, List<Frame> out) {
        int offset = 0;
        while (input.length - offset >= FRAME_HEADER_SIZE) {
            if (!isKnownFrameType(input[offset] & 0xFF)) {
                return false;
            }
            int streamId = ((input[offset + 1] & 0xFF) << 16)
                    | ((input[offset + 2] & 0xFF) << 8)
                    | (input[offset + 3] & 0xFF);
            int size = ((input[offset + 4] & 0xFF) << 24)
                    | ((input[offset + 5] & 0xFF) << 16)
                    | ((input[offset + 6] & 0xFF) << 8)
                    | (input[offset + 7] & 0xFF);
            if (size > MAX_FRAME_PAYLOAD) {
                return false;
            }
            int full = FRAME_HEADER_SIZE + size;
            if (input.length - offset < full) {
                break;
            }
            if (out.size() >= MAX_BATCH_FRAMES) {
                return false;
            }
            byte[] payload = size > 0 ? Arrays.copyOfRange(input, offset + FRAME_HEADER_SIZE, offset + full) : new byte[0];
            out.add(new Frame(input[offset] & 0xFF, streamId, payload));
            offset += full;
        }
        return true;
    }

    private static boolean isKnownFrameType(int type) {
        switch (type) {
            case FRAME_OPEN:
            case FRAME_DATA:
            case FRAME_CLOSE:
            case FRAME_WINDOW:
            case FRAME_PING:
            case FRAME_PONG:
            case FRAME_HELLO:
            case FRAME_WELCOME:
            case FRAME_AUTH_CHALLENGE:
            case FRAME_AUTH_RESPONSE:
            case FRAME_BYE:
                return true;
            default:
                return false;
        }
    }

    private boolean processRelayFrame(Frame frame) {
        if (!adopted && (frame.type != FRAME_WELCOME || frame.streamId != 0)) {
            return false;
        }
        if (frame.streamId == 0) {
            switch (frame.type) {
                case FRAME_WELCOME: {
                    if (adopted || frame.payload.length != 0) {
                        return false;
                    }
                    adopt();
                    return true;
                }
                case FRAME_PING: {
                    if (frame.payload.length > 64) {
                        return false;
                    }
                    enqueueFrame(FRAME_PONG, 0, frame.payload);
                    return true;
                }
                case FRAME_BYE: {
                    fail("bye frame received");
                    return true;
                }
                default:
                    return false;
            }
        }
        Stream stream = streams.get(frame.streamId);
        if (stream == null) {
            if (!closedStreams.contains(frame.streamId)) {
                return false;
            }
            switch (frame.type) {
                case FRAME_DATA:
                    return frame.payload.length != 0;
                case FRAME_WINDOW:
                    return frame.payload.length == 4 && readWindow(frame.payload) != 0;
                case FRAME_CLOSE:
                    return frame.payload.length == 0;
                default:
                    return false;
            }
        }
        switch (frame.type) {
            case FRAME_DATA: {
                if (frame.payload.length == 0 || frame.payload.length > stream.receiveWindow) {
                    return false;
                }
                stream.receiveWindow -= frame.payload.length;
                notifyStreamData(stream, frame.payload);
                return true;
            }
            case FRAME_WINDOW: {
                if (frame.payload.length != 4) {
                    return false;
                }
                long amount = readWindow(frame.payload);
                if (amount == 0) {
                    return false;
                }
                stream.sendWindow = Math.min(stream.sendWindow + amount, Integer.MAX_VALUE);
                markStreamReady(stream.id);
                flushStreams();
                return true;
            }
            case FRAME_CLOSE: {
                if (frame.payload.length != 0) {
                    return false;
                }
                failStream(stream, true);
                return true;
            }
            default:
                return false;
        }
    }

    private void adopt() {
        adopted = true;
        handler.removeCallbacks(handshakeTimeoutRunnable);
        callbacks.onCarrierReady(this);
        for (Stream stream : streams.values()) {
            enqueueFrame(FRAME_OPEN, stream.id, new byte[0]);
        }
        flushStreams();
    }

    private void notifyStreamData(Stream stream, byte[] payload) {
        try {
            ConnectionsManager.native_webProxyDeliver(stream.instance, stream.id, payload);
        } catch (Throwable t) {
            FileLog.e(t);
            fail("native delivery failed");
            return;
        }
        stream.pendingWindow += payload.length;
        if (stream.pendingWindow >= WINDOW_FLUSH_BYTES) {
            grantStreamWindow(stream);
        } else {
            handler.removeCallbacks(windowFlushRunnable);
            handler.postDelayed(windowFlushRunnable, WINDOW_FLUSH_DELAY);
        }
    }

    private void grantStreamWindow(Stream stream) {
        int amount = stream.pendingWindow;
        if (amount <= 0) {
            return;
        }
        stream.pendingWindow = 0;
        enqueueFrame(FRAME_WINDOW, stream.id, windowPayload(amount));
    }

    private void flushWindows() {
        for (Stream stream : streams.values()) {
            grantStreamWindow(stream);
        }
    }

    private static byte[] windowPayload(int amount) {
        return new byte[]{
                (byte) (amount >> 24), (byte) (amount >> 16), (byte) (amount >> 8), (byte) amount
        };
    }

    private static long readWindow(byte[] payload) {
        return ((long) (payload[0] & 0xFF) << 24)
                | ((long) (payload[1] & 0xFF) << 16)
                | ((long) (payload[2] & 0xFF) << 8)
                | (long) (payload[3] & 0xFF);
    }

    // ---------------- native -> page ----------------

    public void writeNativeData(int streamId, byte[] data) {
        if (failed || closing || data == null || data.length == 0) {
            return;
        }
        Stream stream = streams.get(streamId);
        if (stream == null) {
            return;
        }
        byte[] last = stream.outQueue.peekLast();
        if (last == null || last.length + data.length > DATA_FRAME_SIZE) {
            if (stream.outQueue.size() >= MAX_PENDING_ITEMS || stream.outBytes + data.length > MAX_PENDING_BYTES) {
                FileLog.d("[webproxy] pending write limit exceeded for stream " + streamId);
                failStream(stream, true);
                return;
            }
            stream.outQueue.add(Arrays.copyOf(data, data.length));
            stream.outBytes += data.length;
        } else {
            byte[] merged = Arrays.copyOf(last, last.length + data.length);
            System.arraycopy(data, 0, merged, last.length, data.length);
            stream.outQueue.pollLast();
            stream.outQueue.add(merged);
        }
        markStreamReady(streamId);
        flushStreams();
    }

    private void markStreamReady(int streamId) {
        Stream stream = streams.get(streamId);
        if (stream == null || stream.sendWindow <= 0 || stream.outQueue.isEmpty() || readySet.contains(streamId)) {
            return;
        }
        readySet.add(streamId);
        readyStreams.add(streamId);
    }

    private void flushStreams() {
        if (!adopted || failed || closing) {
            return;
        }
        while (!readyStreams.isEmpty()) {
            int streamId = readyStreams.peek();
            Stream stream = streams.get(streamId);
            if (stream == null || stream.sendWindow <= 0 || stream.outQueue.isEmpty()) {
                readyStreams.poll();
                readySet.remove(streamId);
                continue;
            }
            byte[] first = stream.outQueue.peek();
            int chunk = (int) Math.min(first.length, Math.min(stream.sendWindow, DATA_FRAME_SIZE));
            byte[] data;
            if (chunk == first.length) {
                data = stream.outQueue.poll();
            } else {
                data = Arrays.copyOfRange(first, 0, chunk);
                byte[] rest = Arrays.copyOfRange(first, chunk, first.length);
                stream.outQueue.poll();
                stream.outQueue.addFirst(rest);
            }
            stream.sendWindow -= chunk;
            stream.outBytes -= chunk;
            enqueueFrame(FRAME_DATA, streamId, data);
            // rotate the stream to the back for round-robin
            readyStreams.poll();
            if (!readyStreams.isEmpty() || stream.outQueue.isEmpty()) {
                readySet.remove(streamId);
                if (!stream.outQueue.isEmpty() && stream.sendWindow > 0) {
                    readySet.add(streamId);
                    readyStreams.add(streamId);
                }
            } else {
                readyStreams.add(streamId);
            }
            break; // one frame per turn; the ack keeps the pipeline going
        }
    }

    private void failStream(Stream stream, boolean notifyNative) {
        if (adopted) {
            enqueueFrame(FRAME_CLOSE, stream.id, new byte[0]);
        }
        removeStream(stream);
        if (notifyNative) {
            ConnectionsManager.native_webProxyFail(stream.instance, stream.id);
        }
    }

    private void removeStream(Stream stream) {
        streams.remove(stream.id);
        readySet.remove(stream.id);
        for (Iterator<Integer> it = readyStreams.iterator(); it.hasNext(); ) {
            if (it.next() == stream.id) {
                it.remove();
            }
        }
        rememberClosedStream(stream.id);
    }

    private void rememberClosedStream(int streamId) {
        if (!closedStreams.add(streamId)) {
            return;
        }
        closedStreamOrder.add(streamId);
        while (closedStreamOrder.size() > MAX_CLOSED_STREAM_IDS) {
            Integer old = closedStreamOrder.poll();
            if (old != null) {
                closedStreams.remove(old);
            }
        }
    }

    // ---------------- native stream registry ----------------

    void registerNativeStream(int instance, int streamId) {
        if (streams.containsKey(streamId)) {
            return;
        }
        Stream stream = new Stream(streamId, instance);
        streams.put(streamId, stream);
        if (adopted) {
            enqueueFrame(FRAME_OPEN, streamId, new byte[0]);
        }
    }

    void closeNativeStream(int instance, int streamId) {
        Stream stream = streams.get(streamId);
        if (stream == null) {
            return;
        }
        if (adopted) {
            enqueueFrame(FRAME_CLOSE, streamId, new byte[0]);
        }
        removeStream(stream);
    }

    // ---------------- frame writes ----------------

    private void enqueueFrame(int type, int streamId, byte[] payload) {
        if (failed || closing || payload == null) {
            return;
        }
        if (payload.length > MAX_FRAME_PAYLOAD || streamId > 0x00FFFFFF) {
            fail("invalid frame");
            return;
        }
        Pending item = new Pending();
        item.frame = serializeFrame(type, streamId, payload);
        int pendingItems = pending.size() + (inFlight != null ? 1 : 0);
        if (pendingItems >= MAX_PENDING_ITEMS
                || item.frame.length > MAX_PENDING_BYTES
                || pendingBytes > MAX_PENDING_BYTES - item.frame.length) {
            fail("pending write limit exceeded");
            return;
        }
        pendingBytes += item.frame.length;
        pending.add(item);
        drain();
    }

    private void drain() {
        if (failed || !bridgeInitialized || inFlight != null || pending.isEmpty()) {
            return;
        }
        inFlight = pending.poll();
        ++writeSequence;
        handler.removeCallbacks(writeTimeoutRunnable);
        handler.postDelayed(writeTimeoutRunnable, WRITE_TIMEOUT);
        String base64 = Base64.encodeToString(inFlight.frame, Base64.NO_WRAP);
        evaluate("window.TelegramWebProxy && window.TelegramWebProxy.receive(" + writeSequence + ",'" + base64 + "')");
    }

    private static byte[] serializeFrame(int type, int streamId, byte[] payload) {
        byte[] result = new byte[FRAME_HEADER_SIZE + payload.length];
        result[0] = (byte) type;
        result[1] = (byte) (streamId >> 16);
        result[2] = (byte) (streamId >> 8);
        result[3] = (byte) streamId;
        int size = payload.length;
        result[4] = (byte) (size >> 24);
        result[5] = (byte) (size >> 16);
        result[6] = (byte) (size >> 8);
        result[7] = (byte) size;
        System.arraycopy(payload, 0, result, FRAME_HEADER_SIZE, payload.length);
        return result;
    }

    private void evaluate(String script) {
        WebView wv = webView;
        if (wv == null || failed || closing) {
            return;
        }
        try {
            wv.evaluateJavascript(script, null);
        } catch (Throwable t) {
            FileLog.e(t);
            fail("evaluate failed");
        }
    }

    private void fail(String reason) {
        if (failed || closing) {
            return;
        }
        FileLog.d("[webproxy] carrier failed: " + reason);
        failed = true;
        handler.removeCallbacks(handshakeTimeoutRunnable);
        handler.removeCallbacks(healthTimeoutRunnable);
        handler.removeCallbacks(writeTimeoutRunnable);
        handler.removeCallbacks(windowFlushRunnable);
        handler.removeCallbacks(probeRunnable);
        callbacks.onCarrierFailed(this, reason);
        destroyWebview();
    }

    public void close() {
        if (closing) {
            return;
        }
        if (adopted && !failed) {
            evaluate("window.TelegramWebProxy && window.TelegramWebProxy.receiveControl(0,'{\"t\":\"close\"}')");
        }
        closing = true;
        handler.removeCallbacks(handshakeTimeoutRunnable);
        handler.removeCallbacks(healthTimeoutRunnable);
        handler.removeCallbacks(writeTimeoutRunnable);
        handler.removeCallbacks(windowFlushRunnable);
        handler.removeCallbacks(probeRunnable);
        destroyWebview();
    }

    private void destroyWebview() {
        long grace = closing && !failed ? WEBVIEW_CLOSE_GRACE : 0;
        handler.postDelayed(() -> {
            WebView wv = webView;
            webView = null;
            if (wv != null) {
                try {
                    ViewGroup parent = (ViewGroup) wv.getParent();
                    if (parent != null) {
                        parent.removeView(wv);
                    }
                    wv.stopLoading();
                    wv.loadUrl("about:blank");
                    wv.destroy();
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
        }, grace);
    }
}
