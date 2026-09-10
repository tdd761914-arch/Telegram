/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;

import org.telegram.ui.LaunchActivity;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class WhitelistBypassService extends Service {

    public static final String ACTION_START = "org.telegram.messenger.WHITELIST_BYPASS_START";
    public static final String ACTION_STOP = "org.telegram.messenger.WHITELIST_BYPASS_STOP";

    private static final String CHANNEL_ID = "whitelist_bypass";
    private static final int NOTIFICATION_ID = 74;
    private static final int FIRST_SOCKS_PORT = 10808;
    private static final int LAST_SOCKS_PORT = 10828;

    private final Object processLock = new Object();
    private volatile boolean stopRequested;
    private volatile boolean sessionFinished;
    private Process relayProcess;
    private BufferedWriter relayWriter;
    private Thread relayThread;
    private int socksPort;

    @Override
    public void onCreate() {
        super.onCreate();
        WhitelistBypassManager.setServiceRunning(true);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSession(null);
            return START_NOT_STICKY;
        }
        if (!WhitelistBypassManager.isEnabled()) {
            stopSession(null);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.WhitelistBypassStatusStarting)));
        if (relayThread == null || !relayThread.isAlive()) {
            startRelay();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyRelayProcess();
        if (!sessionFinished) {
            sessionFinished = true;
            WhitelistBypassManager.onSessionStopped(null);
        }
        super.onDestroy();
    }

    private void startRelay() {
        stopRequested = false;
        relayThread = new Thread(() -> {
            try {
                String relayMode = WhitelistBypassManager.getRelayMode();
                // Bale Meet links are handled by the separate whitelist-bypass-iran
                // library (same stdio protocol, different CLI flags).
                boolean iranLibrary = relayMode.startsWith("bale");
                File relayBinary = new File(getApplicationInfo().nativeLibraryDir,
                        iranLibrary ? "libwhitelist_relay_iran.so" : "libwhitelist_relay.so");
                if (!relayBinary.isFile()) {
                    throw new IllegalStateException(getString(R.string.WhitelistBypassUnsupportedAbi));
                }

                socksPort = findAvailablePort();
                if (socksPort == 0) {
                    throw new IllegalStateException(getString(R.string.WhitelistBypassPortBusy));
                }

                List<String> command = new ArrayList<>();
                command.add(relayBinary.getAbsolutePath());
                command.add("--mode");
                command.add(relayMode);
                if (!iranLibrary) {
                    command.add("--ws-port");
                    command.add("9001");
                    command.add("--socks-host");
                    command.add(WhitelistBypassManager.INTERNAL_HOST);
                }
                command.add("--socks-port");
                command.add(String.valueOf(socksPort));
                command.add("--socks-user");
                command.add("");
                command.add("--socks-pass");
                command.add("");

                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true);
                Process process = builder.start();
                synchronized (processLock) {
                    relayProcess = process;
                    relayWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while (!stopRequested && (line = reader.readLine()) != null) {
                    handleRelayLine(line);
                }
                int exitCode = process.waitFor();
                if (!stopRequested) {
                    throw new IllegalStateException("Joiner exited (" + exitCode + ")");
                }
            } catch (Exception e) {
                if (!stopRequested) {
                    FileLog.e(e);
                    stopSession(TextUtils.isEmpty(e.getMessage()) ? getString(R.string.WhitelistBypassStartFailed) : e.getMessage());
                }
            }
        }, "WhitelistBypassJoiner");
        relayThread.start();
    }

    private void handleRelayLine(String line) throws Exception {
        if (line.startsWith("RESOLVE:")) {
            resolveForRelay(line.substring("RESOLVE:".length()));
            return;
        }
        if (!line.startsWith("STATUS:")) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("WhitelistBypass: " + line);
            }
            return;
        }

        String status = line.substring("STATUS:".length());
        if ("READY".equals(status)) {
            WhitelistBypassManager.setState(WhitelistBypassManager.State.CONNECTING, "", "");
            updateNotification(getString(R.string.WhitelistBypassStatusConnecting));
            writeRelayLine(WhitelistBypassManager.getJoinCommand());
        } else if ("CONNECTING".equals(status)) {
            WhitelistBypassManager.setState(WhitelistBypassManager.State.CONNECTING, "", "");
            updateNotification(getString(R.string.WhitelistBypassStatusConnecting));
        } else if ("RECONNECTING".equals(status) || "TUNNEL_LOST".equals(status)) {
            WhitelistBypassManager.setState(WhitelistBypassManager.State.RECONNECTING, "", "");
            updateNotification(getString(R.string.WhitelistBypassStatusReconnecting));
        } else if ("TUNNEL_CONNECTED".equals(status)) {
            if (!waitForSocksListener()) {
                throw new IllegalStateException(getString(R.string.WhitelistBypassLocalProxyFailed));
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (stopRequested) {
                    return;
                }
                boolean webViewProxied = WhitelistBypassManager.activateTelegramProxy(socksPort);
                String detail = webViewProxied ? "" : getString(R.string.WhitelistBypassWebViewUnsupported);
                WhitelistBypassManager.setState(WhitelistBypassManager.State.CONNECTED, detail, "");
                updateNotification(getString(R.string.WhitelistBypassStatusConnected));
            });
        } else if (status.startsWith("CAPTCHA:")) {
            String url = status.substring("CAPTCHA:".length());
            WhitelistBypassManager.setState(WhitelistBypassManager.State.CAPTCHA, "", url);
            updateNotification(getString(R.string.WhitelistBypassStatusCaptcha));
        } else if (status.startsWith("ERROR:")) {
            throw new IllegalStateException(status.substring("ERROR:".length()));
        }
    }

    private void resolveForRelay(String hostname) {
        String result = "";
        try {
            InetAddress[] addresses = InetAddress.getAllByName(hostname);
            InetAddress selected = null;
            for (InetAddress address : addresses) {
                if (address instanceof Inet4Address) {
                    selected = address;
                    break;
                }
            }
            if (selected == null && addresses.length > 0) {
                selected = addresses[0];
            }
            if (selected != null) {
                result = selected.getHostAddress();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        writeRelayLine(result == null ? "" : result);
    }

    private void writeRelayLine(String value) {
        synchronized (processLock) {
            if (relayWriter == null) {
                return;
            }
            try {
                relayWriter.write(value == null ? "" : value);
                relayWriter.newLine();
                relayWriter.flush();
            } catch (Exception e) {
                if (!stopRequested) {
                    FileLog.e(e);
                }
            }
        }
    }

    private int findAvailablePort() {
        for (int port = FIRST_SOCKS_PORT; port <= LAST_SOCKS_PORT; port++) {
            try (ServerSocket ignored = new ServerSocket(port, 1, InetAddress.getByName(WhitelistBypassManager.INTERNAL_HOST))) {
                return port;
            } catch (Exception ignore) {
            }
        }
        return 0;
    }

    private boolean waitForSocksListener() {
        for (int attempt = 0; attempt < 50 && !stopRequested; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(WhitelistBypassManager.INTERNAL_HOST, socksPort), 100);
                return true;
            } catch (Exception ignore) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private void stopSession(String error) {
        if (sessionFinished) {
            return;
        }
        sessionFinished = true;
        stopRequested = true;
        destroyRelayProcess();
        AndroidUtilities.runOnUIThread(() -> WhitelistBypassManager.onSessionStopped(error));
        stopForeground(true);
        stopSelf();
    }

    private void destroyRelayProcess() {
        stopRequested = true;
        synchronized (processLock) {
            try {
                if (relayWriter != null) {
                    relayWriter.close();
                }
            } catch (Exception ignore) {
            }
            relayWriter = null;
            if (relayProcess != null) {
                relayProcess.destroy();
                relayProcess = null;
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.WhitelistBypassNotificationChannel),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String status) {
        Intent openIntent = new Intent(this, LaunchActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                74,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, WhitelistBypassService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                75,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle(getString(R.string.WhitelistBypassTitle))
                .setContentText(status)
                .setSmallIcon(R.drawable.notification)
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .addAction(new Notification.Action.Builder(null, getString(R.string.WhitelistBypassDisconnect), stopPendingIntent).build())
                .build();
    }

    private void updateNotification(String status) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(status));
    }
}
