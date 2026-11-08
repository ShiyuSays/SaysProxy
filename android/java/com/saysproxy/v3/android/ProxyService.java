package com.saysproxy.v3.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;

import com.saysproxy.v3.common.LocalProxyServer;
import com.saysproxy.v3.common.LogSink;
import com.saysproxy.v3.common.TunnelClient;
import com.saysproxy.v3.common.TunnelListener;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that owns the encrypted tunnel and the loopback-only
 * SOCKS5/HTTP proxy.
 *
 * Android does not let an ordinary app redirect all device traffic without a
 * VpnService native TCP/IP stack. This app therefore deliberately runs in
 * local-proxy mode; see README for how to point browsers/apps at it.
 */
public final class ProxyService extends VpnService {
    public static final String ACTION_CONNECT = "com.saysproxy.v3.android.CONNECT";
    public static final String ACTION_DISCONNECT = "com.saysproxy.v3.android.DISCONNECT";
    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_CONNECTION_PASSWORD = "connection_password";
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_LOCAL_PORT = "local_port";
    public static final String EXTRA_VPN_MODE = "vpn_mode";

    private static final String CHANNEL_ID = "saysproxy_v3_proxy";
    private static final int NOTIFICATION_ID = 1001;
    private static final int DEFAULT_LOCAL_PORT = 1080;

    private final AtomicBoolean destroyed = new AtomicBoolean();
    private volatile TunnelClient tunnel;
    private volatile LocalProxyServer proxyServer;
    private volatile Thread connectThread;
    private volatile boolean remoteLost;
    private PowerManager.WakeLock wakeLock;
    private ParcelFileDescriptor vpnFd;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            ProxyBus.log("正在断开代理…");
            destroyed.set(true);
            closeResources();
            releaseWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            ProxyBus.setState(ProxyBus.State.DISCONNECTED, "未连接", -1);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_CONNECT.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        destroyed.set(false);
        remoteLost = false;

        final String server = trim(intent.getStringExtra(EXTRA_SERVER));
        final int port = intent.getIntExtra(EXTRA_PORT, 0);
        final String connectionPassword = intent.getStringExtra(EXTRA_CONNECTION_PASSWORD);
        final String username = trim(intent.getStringExtra(EXTRA_USERNAME));
        final String password = intent.getStringExtra(EXTRA_PASSWORD);
        int requestedLocalPort = intent.getIntExtra(EXTRA_LOCAL_PORT, DEFAULT_LOCAL_PORT);
        final int localPort = requestedLocalPort > 1023 && requestedLocalPort <= 65535
                ? requestedLocalPort : DEFAULT_LOCAL_PORT;
        final boolean vpnMode = intent.getBooleanExtra(EXTRA_VPN_MODE, false);

        startForeground(NOTIFICATION_ID, buildNotification("正在连接服务器…"));
        ProxyBus.setState(ProxyBus.State.CONNECTING,
                vpnMode ? "正在连接并准备 VPN…" : "正在连接 " + server + ":" + port, -1);
        ProxyBus.log("正在连接 " + server + ":" + port + " …");

        if (server.isEmpty() || port < 1 || port > 65535 || isBlank(connectionPassword)
                || username.isEmpty() || isBlank(password)) {
            ProxyBus.log("连接参数不完整");
            ProxyBus.setState(ProxyBus.State.ERROR, "连接参数不完整", -1);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (vpnMode && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ProxyBus.log("VPN 模式需要 Android 10 及以上");
            ProxyBus.setState(ProxyBus.State.ERROR, "VPN 模式需要 Android 10 及以上", -1);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (connectThread != null && connectThread.isAlive()) {
            ProxyBus.log("已有连接正在建立，请稍候");
            return START_REDELIVER_INTENT;
        }

        acquireWakeLock();
        connectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                connect(server, port, connectionPassword, username, password, localPort, vpnMode);
            }
        }, "spv3-android-connect");
        connectThread.setDaemon(true);
        connectThread.start();
        return START_REDELIVER_INTENT;
    }

    private void connect(String server, int port, String connectionPassword, String username,
                         String password, final int localPort, boolean vpnMode) {
        TunnelClient newTunnel = null;
        LocalProxyServer newProxy = null;
        try {
            newTunnel = TunnelClient.connect(server, port, connectionPassword, username, password,
                    new TunnelListener() {
                        @Override
                        public void onLog(String message) {
                            ProxyBus.log(message);
                        }

                        @Override
                        public void onDisconnected(String reason) {
                            remoteLost = true;
                            synchronized (ProxyService.this) {
                                if (destroyed.get()) {
                                    return;
                                }
                                ProxyBus.log("与服务器的连接已断开: " + reason);
                                ProxyBus.setState(ProxyBus.State.ERROR, "连接已断开: " + reason, -1);
                                closeResources();
                            }
                            stopSelf();
                        }
                    });
            if (destroyed.get() || remoteLost) {
                newTunnel.disconnect();
                return;
            }
            LogSink proxyLog = new LogSink() {
                @Override
                public void log(String message) {
                    ProxyBus.log(message);
                }
            };
            newProxy = new LocalProxyServer(newTunnel, localPort, proxyLog);
            newProxy.start();
            if (vpnMode) {
                try {
                    establishVpn(localPort);
                } catch (Exception e) {
                    throw new IOException("VPN 启动失败: " + e.getMessage(), e);
                }
            }
            synchronized (this) {
                if (destroyed.get() || remoteLost) {
                    newProxy.close();
                    newTunnel.disconnect();
                    closeVpn();
                    return;
                }
                tunnel = newTunnel;
                proxyServer = newProxy;
                int actualPort = newProxy.getPort();
                String mode = vpnMode ? "VPN 已启用 · " : "";
                ProxyBus.setState(ProxyBus.State.CONNECTED,
                        "已连接 · " + mode + "本地代理 127.0.0.1:" + actualPort, actualPort);
                ProxyBus.log("登录成功，本地代理已启动: 127.0.0.1:" + actualPort
                        + (vpnMode ? "，系统 VPN 已启用（HTTP 代理模式）" : ""));
                updateNotification((vpnMode ? "VPN 已启用 · " : "") + "127.0.0.1:" + actualPort);
            }
        } catch (Exception e) {
            closeVpn();
            if (newProxy != null) {
                newProxy.close();
            }
            if (newTunnel != null) {
                newTunnel.disconnect();
            }
            synchronized (this) {
                if (!destroyed.get()) {
                    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    ProxyBus.log("连接失败: " + message);
                    ProxyBus.setState(ProxyBus.State.ERROR, "连接失败: " + message, -1);
                }
            }
            stopSelf();
        }
    }

    private synchronized void closeResources() {
        closeVpn();
        LocalProxyServer proxy = proxyServer;
        proxyServer = null;
        if (proxy != null) {
            proxy.close();
        }
        TunnelClient client = tunnel;
        tunnel = null;
        if (client != null) {
            client.disconnect();
        }
    }

    private synchronized void establishVpn(int localPort) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("VPN 模式需要 Android 10 及以上");
        }
        closeVpn();
        Builder builder = new Builder();
        builder.setSession("SaysProxyV3");
        builder.setMtu(1500);
        builder.addAddress("10.8.0.2", 32);
        builder.addRoute("0.0.0.0", 0);
        builder.addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128);
        builder.addRoute("::", 0);
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("无法将本应用排除出 VPN", e);
        }
        builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", localPort));
        ParcelFileDescriptor fd = builder.establish();
        if (fd == null) {
            throw new IOException("系统拒绝建立 VPN，请检查 VPN 授权");
        }
        vpnFd = fd;
        ProxyBus.log("系统 VPN 已建立，HTTP 代理 127.0.0.1:" + localPort);
    }

    private synchronized void closeVpn() {
        ParcelFileDescriptor fd = vpnFd;
        vpnFd = null;
        if (fd != null) {
            try {
                fd.close();
            } catch (Exception ignored) {
            }
        }
    }

    private synchronized void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        try {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "SaysProxyV3:proxy");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            ProxyBus.log("已启用后台保活唤醒锁，屏幕关闭时继续运行（会增加耗电）");
        } catch (RuntimeException e) {
            wakeLock = null;
            ProxyBus.log("启用后台保活失败: " + e.getMessage());
        }
    }

    private synchronized void releaseWakeLock() {
        PowerManager.WakeLock lock = wakeLock;
        wakeLock = null;
        if (lock != null) {
            try {
                if (lock.isHeld()) {
                    lock.release();
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_proxy)
                .setContentTitle("SaysProxyV3")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "SaysProxyV3 代理服务",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("加密代理连接状态");
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onRevoke() {
        ProxyBus.log("系统已撤销 VPN 授权，代理服务停止");
        destroyed.set(true);
        closeResources();
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        ProxyBus.setState(ProxyBus.State.DISCONNECTED, "未连接", -1);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        destroyed.set(true);
        closeResources();
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (ProxyBus.getState() != ProxyBus.State.ERROR) {
            ProxyBus.setState(ProxyBus.State.DISCONNECTED, "未连接", -1);
        }
        ProxyBus.log("代理服务已停止");
        super.onDestroy();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
