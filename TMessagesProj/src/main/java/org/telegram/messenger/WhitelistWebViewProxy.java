/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.messenger;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.util.concurrent.Executor;

/** Process-wide WebView proxy used for Telegram mini apps and embedded pages. */
public final class WhitelistWebViewProxy {

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private WhitelistWebViewProxy() {
    }

    public static boolean setProxy(int port) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            return false;
        }
        try {
            ProxyConfig config = new ProxyConfig.Builder()
                    .addProxyRule("socks://127.0.0.1:" + port)
                    .build();
            ProxyController.getInstance().setProxyOverride(config, DIRECT_EXECUTOR, () -> {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("WhitelistBypass: WebView proxy enabled");
                }
            });
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void clearProxy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            return;
        }
        try {
            ProxyController.getInstance().clearProxyOverride(DIRECT_EXECUTOR, () -> {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("WhitelistBypass: WebView proxy cleared");
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
