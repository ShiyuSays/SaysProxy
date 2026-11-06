package com.saysproxy.v3.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.saysproxy.v3.common.LocalProxyServer;
import com.saysproxy.v3.common.LogSink;
import com.saysproxy.v3.common.TunnelClient;
import com.saysproxy.v3.common.TunnelListener;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that owns the encrypted tunnel and the loopback-only
 * SOCKS5/HTTP proxy.
 *
 * Android does not let an ordinary app redirect all device traffic without a
 * VpnService native TCP/IP stack. This app therefore deliberately runs in
 * local-proxy mode; see README for how to point browsers/apps at it.
 */
public final class ProxyService extends Service {
    public static final String ACTION_CONNECT = "com.saysproxy.v3.android.CONNECT";
    public static final String ACTION_DISCONNECT = "com.saysproxy.v3.android.DISCONNECT";
    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_CONNECTION_PASSWORD = "connection_password";
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_LOCAL_PORT = "local_port";

    private static final String CHANNEL_ID = "saysproxy_v3_proxy";
    private static final int NOTIFICATION_ID = 1001;
    private static final int DEFAULT_LOCAL_PORT = 1080;

    private final AtomicBoolean destroyed = new AtomicBoolean();
    private volatile TunnelClient tunnel;
    private volatile LocalProxyServer proxyServer;
    private volatile Thread connectThread;
    private volatile boolean remoteLost;

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
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_CONNECT.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final String server = trim(intent.getStringExtra(EXTRA_SERVER));
        final int port = intent.getIntExtra(EXTRA_PORT, 0);
        final String connectionPassword = intent.getStringExtra(EXTRA_CONNECTION_PASSWORD);
        final String username = trim(intent.getStringExtra(EXTRA_USERNAME));
        final String password = intent.getStringExtra(EXTRA_PASSWORD);
        int requestedLocalPort = intent.getIntExtra(EXTRA_LOCAL_PORT, DEFAULT_LOCAL_PORT);
        final int localPort = requestedLocalPort > 1023 && requestedLocalPort <= 65535
                ? requestedLocalPort : DEFAULT_LOCAL_PORT;

        startForeground(NOTIFICATION_ID, buildNotification("正在连接服务器…"));
        ProxyBus.setState(ProxyBus.State.CONNECTING, "正在连接 " + server + ":" + port, -1);
        ProxyBus.log("正在连接 " + server + ":" + port + " …");

        if (server.isEmpty() || port < 1 || port > 65535 || isBlank(connectionPassword)
                || username.isEmpty() || isBlank(password)) {
            ProxyBus.log("连接参数不完整");
            ProxyBus.setState(ProxyBus.State.ERROR, "连接参数不完整", -1);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (connectThread != null && connectThread.isAlive()) {
            ProxyBus.log("已有连接正在建立，请稍候");
            return START_NOT_STICKY;
        }

        connectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                connect(server, port, connectionPassword, username, password, localPort);
            }
        }, "spv3-android-connect");
        connectThread.setDaemon(true);
        connectThread.start();
        return START_NOT_STICKY;
    }

    private void connect(String server, int port, String connectionPassword, String username,
                         String password, final int localPort) {
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
            synchronized (this) {
                if (destroyed.get() || remoteLost) {
                    newProxy.close();
                    newTunnel.disconnect();
                    return;
                }
                tunnel = newTunnel;
                proxyServer = newProxy;
                int actualPort = newProxy.getPort();
                ProxyBus.setState(ProxyBus.State.CONNECTED,
                        "已连接 · 本地代理 127.0.0.1:" + actualPort, actualPort);
                ProxyBus.log("登录成功，本地代理已启动: 127.0.0.1:" + actualPort);
                updateNotification("已连接 · 127.0.0.1:" + actualPort);
            }
        } catch (Exception e) {
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
    public void onDestroy() {
        destroyed.set(true);
        closeResources();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (ProxyBus.getState() != ProxyBus.State.ERROR) {
            ProxyBus.setState(ProxyBus.State.DISCONNECTED, "未连接", -1);
        }
        ProxyBus.log("代理服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
