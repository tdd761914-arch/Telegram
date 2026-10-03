package org.telegram.utils.proxy;

import android.net.Uri;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.openflux.bridge.mobile.Mobile;

/** The same cookie handoff used by OpenFluxAndroid, without a Javascript bridge. */
final class OpenFluxCaptcha {
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static boolean active;
    private static AlertDialog dialog;
    private static WebView web;
    private static boolean proxyOverridden;
    private static final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active) return;
            try {
                String url = Mobile.pendingCaptchaURL();
                if ((url == null || url.isEmpty()) && dialog != null) {
                    close();
                } else if (dialog == null && validUrl(url)) {
                    BaseFragment fragment = LaunchActivity.getLastFragment();
                    if (fragment != null && fragment.getParentActivity() != null
                            && fragment.getParentActivity().hasWindowFocus()) {
                        show(fragment, url, Mobile.pendingCaptchaProxy());
                    }
                }
            } catch (Throwable ignored) {
                // The core may be stopping or have failed to load on this device.
            }
            AndroidUtilities.runOnUIThread(this, 1000);
        }
    };

    static void start() {
        AndroidUtilities.runOnUIThread(() -> {
            active = true;
            AndroidUtilities.cancelRunOnUIThread(poll);
            AndroidUtilities.runOnUIThread(poll, 1000);
        });
    }

    static void stop() {
        AndroidUtilities.runOnUIThread(() -> {
            active = false;
            AndroidUtilities.cancelRunOnUIThread(poll);
            close();
        });
    }

    private static boolean validUrl(String value) {
        if (value == null) return false;
        Uri uri = Uri.parse(value);
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null) return false;
        host = host.toLowerCase(java.util.Locale.US);
        return host.equals("yandex.ru") || host.endsWith(".yandex.ru")
                || host.equals("yandex.com") || host.endsWith(".yandex.com");
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private static void show(BaseFragment fragment, String url, String proxy) {
        WebView view = new WebView(fragment.getParentActivity());
        web = view;
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(false);
        view.getSettings().setUserAgentString("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:153.0) Gecko/20100101 Firefox/153.0");
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String next) {
                return !validUrl(next);
            }
        });
        dialog = new AlertDialog.Builder(fragment.getParentActivity())
                .setTitle(LocaleController.getString(R.string.DocsTunnelCheck))
                .setView(view)
                .setPositiveButton(LocaleController.getString(R.string.DocsTunnelCheckDone), null)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), (d, which) -> Mobile.cancelCaptcha())
                .create();
        AlertDialog currentDialog = dialog;
        currentDialog.setOnDismissListener(d -> {
            if (dialog == currentDialog) {
                Mobile.cancelCaptcha();
                release();
            }
        });
        fragment.showDialog(currentDialog);
        currentDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            CookieManager cookies = CookieManager.getInstance();
            cookies.flush();
            String first = cookies.getCookie(url);
            String second = validUrl(view.getUrl()) && !url.equals(view.getUrl()) ? cookies.getCookie(view.getUrl()) : null;
            String header = (first == null ? "" : first) + (second == null ? "" : "; " + second);
            if (header.trim().isEmpty()) {
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.DocsTunnelCheckRetry)).show();
                return;
            }
            button.setEnabled(false);
            worker.execute(() -> {
                String result;
                try { result = Mobile.submitCaptchaCookies(header); }
                catch (Throwable ignored) { result = "failed"; }
                boolean success = result == null || result.isEmpty();
                AndroidUtilities.runOnUIThread(() -> {
                    if (dialog != currentDialog) return;
                    button.setEnabled(true);
                    if (success) close();
                    else BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.DocsTunnelCheckRetry)).show();
                });
            });
        });
        view.getLayoutParams().height = Math.min(AndroidUtilities.dp(480), AndroidUtilities.displaySize.y / 2);
        view.requestLayout();
        if (proxy == null || proxy.isEmpty()) {
            view.loadUrl(url);
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            proxyOverridden = true;
            ProxyController.getInstance().setProxyOverride(new ProxyConfig.Builder().addProxyRule(proxy).build(), Runnable::run,
                    () -> { if (web == view && active) view.loadUrl(url); });
        } else {
            view.loadData(LocaleController.getString(R.string.DocsTunnelWebViewRequired), "text/plain", "UTF-8");
        }
    }

    private static void close() {
        AlertDialog old = dialog;
        release();
        if (old != null) old.dismiss();
    }

    private static void release() {
        dialog = null;
        if (web != null) {
            web.stopLoading();
            if (web.getParent() instanceof ViewGroup) {
                ((ViewGroup) web.getParent()).removeView(web);
            }
            web.destroy();
            web = null;
        }
        if (proxyOverridden) {
            proxyOverridden = false;
            ProxyController.getInstance().clearProxyOverride(Runnable::run, () -> {});
        }
    }
}
