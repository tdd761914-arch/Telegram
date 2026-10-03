package org.telegram.utils.proxy;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestTimeDelegate;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.openflux.bridge.mobile.Mobile;

/** OpenFluxAndroid's embedded core, used only as a loopback SOCKS5 client. */
public final class OpenFluxTransport {
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static volatile Session current;
    private static final class Session {
        final ProxySettings settings;
        volatile int port;
        volatile boolean stopped;
        volatile boolean failed;
        Session(ProxySettings settings) { this.settings = settings; }
    }

    private OpenFluxTransport() {}

    /** Returns a loopback port, or 0 while starting. Callers must never fall back to direct. */
    public static synchronized int start(ProxySettings settings) {
        if (settings == null || !settings.getType().isDocsTunnel() || !settings.isValid()) {
            return 0;
        }
        if (current != null && current.settings.equals(settings) && !current.stopped && !current.failed) {
            return current.port;
        }
        stop();
        Session session = new Session(settings);
        current = session;
        worker.execute(() -> {
            if (session.stopped) return;
            try {
                go.Seq.setContext(ApplicationLoader.applicationContext);
                Mobile.setDebugLevel(0);
                Mobile.setCookieStorePath(new File(ApplicationLoader.applicationContext.getFilesDir(), "openflux-cookies.json").getPath());
                if (session.stopped) return;
                String error = "";
                int port = 0;
                // Reserve a random loopback port; retry if another process wins the bind race.
                for (int attempt = 0; attempt < 3 && !session.stopped; attempt++) {
                    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                        port = socket.getLocalPort();
                    }
                    String listen = "127.0.0.1:" + port;
                    error = Mobile.startProxy(settings.getType() == ProxySettings.Type.YANDEX_DOCS ? "yandex" : "mailru",
                            settings.getAddress(), settings.getSecret(), "batched", "", "", listen, "", "", "");
                    if (error == null || error.isEmpty()) break;
                }
                if (session.stopped || (error != null && !error.isEmpty())) {
                    stopCore();
                    if (!session.stopped) fail(session);
                    return;
                }
                session.port = port;
                AndroidUtilities.runOnUIThread(() -> {
                    if (current != session || session.stopped) return;
                    ProxySettings selected = ProxySettings.fromSharedPreferences(MessagesController.getGlobalMainSettings());
                    if (selected.equals(settings)
                            && MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false)) {
                        ConnectionsManager.setProxySettings(true, settings);
                    }
                });
            } catch (Throwable ignored) {
                stopCore();
                if (!session.stopped) fail(session);
            }
        });
        OpenFluxCaptcha.start();
        return 0;
    }

    public static synchronized void stop() {
        Session old = current;
        current = null;
        if (old != null) {
            old.stopped = true;
            worker.execute(OpenFluxTransport::stopCore);
            OpenFluxCaptcha.stop();
        }
    }

    private static void stopCore() {
        try { Mobile.stopProxy(); } catch (Throwable ignored) {}
    }

    private static void fail(Session session) {
        session.failed = true;
        AndroidUtilities.runOnUIThread(() -> {
            if (current != session || session.stopped) return;
            OpenFluxCaptcha.stop();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            BaseFragment fragment = LaunchActivity.getLastFragment();
            if (fragment != null && fragment.getParentActivity() != null) {
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.DocsTunnelStartFailed)).show();
            }
        });
    }

    public static boolean hasFailed(ProxySettings settings) {
        Session session = current;
        return session != null && session.settings.equals(settings) && session.failed;
    }

    public interface ConnectionCheck {
        void check(int port, RequestTimeDelegate callback);
    }

    /** Never start a different profile merely to refresh list latency: the core is a singleton. */
    public static void checkProxy(ProxySettings settings, RequestTimeDelegate callback, ConnectionCheck check) {
        Session session = current;
        if (session == null || !session.settings.equals(settings)) {
            callback.run(-1);
            return;
        }
        AndroidUtilities.runOnUIThread(new Runnable() {
            private int attempts;
            @Override public void run() {
                if (current != session || session.stopped || session.failed) {
                    callback.run(-1);
                } else if (session.port != 0 && Mobile.proxyIsConnected()) {
                    check.check(session.port, callback);
                } else if (++attempts >= 60) {
                    callback.run(-1);
                } else {
                    AndroidUtilities.runOnUIThread(this, 500);
                }
            }
        });
    }
}
