package com.saysproxy.v3.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client side of the encrypted tunnel. One TCP connection to the server carries
 * any number of multiplexed logical streams (used by the local SOCKS5/HTTP
 * proxy).
 */
public final class TunnelClient {
    private final String serverHost;
    private final int serverPort;
    private final String username;
    private final TunnelListener listener;
    private final Socket socket;
    private final SecureChannel channel;
    private final ConcurrentHashMap<Integer, TunnelStream> streams = new ConcurrentHashMap<Integer, TunnelStream>();
    private final AtomicInteger nextStreamId = new AtomicInteger(1);
    private final AtomicBoolean finishNotified = new AtomicBoolean();
    private final ScheduledExecutorService keepalive;

    private volatile boolean running = true;
    private volatile boolean closedByUser;
    private volatile long lastInbound = System.currentTimeMillis();

    private TunnelClient(String serverHost, int serverPort, String username, TunnelListener listener,
                         Socket socket, SecureChannel channel) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.username = username;
        this.listener = listener;
        this.socket = socket;
        this.channel = channel;
        this.keepalive = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "spv3-keepalive");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public static TunnelClient connect(String serverHost, int serverPort, String serverPassword,
                                       String username, String password, TunnelListener listener)
            throws IOException {
        if (serverHost == null || serverHost.trim().isEmpty()) {
            throw new IOException("服务器地址不能为空");
        }
        if (serverPort < 1 || serverPort > 65535) {
            throw new IOException("服务器端口无效");
        }
        if (serverPassword == null || serverPassword.isEmpty()) {
            throw new IOException("请输入服务器连接密码");
        }
        if (username == null || username.trim().isEmpty()) {
            throw new IOException("请输入账户名称");
        }
        if (password == null || password.isEmpty()) {
            throw new IOException("请输入账户密码");
        }
        if (username.length() > Protocol.MAX_USERNAME_LENGTH) {
            throw new IOException("账户名称过长");
        }
        if (password.length() > Protocol.MAX_PASSWORD_LENGTH) {
            throw new IOException("账户密码过长");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener == null");
        }

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(serverHost.trim(), serverPort), Protocol.CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(Protocol.HANDSHAKE_TIMEOUT_MS);
            listener.onLog("正在与服务器进行加密握手…");
            SecureChannel channel = SecureChannel.clientHandshake(socket, serverPassword);
            listener.onLog("加密通道已建立，正在验证账户…");
            byte[] login = IoUtil.loginPayload(username.trim(), password);
            channel.sendFrame(Protocol.LOGIN, 0, login, 0, login.length);
            SecureChannel.Frame frame;
            try {
                frame = channel.readFrame();
            } catch (SocketTimeoutException e) {
                throw new IOException("服务器登录响应超时", e);
            }
            if (frame.type != Protocol.AUTH_OK && frame.type != Protocol.AUTH_FAIL) {
                throw new IOException("服务器返回了意外的登录响应");
            }
            String message = IoUtil.parseAuthPayload(frame.payload);
            if (frame.type == Protocol.AUTH_FAIL) {
                throw new IOException(message);
            }
            socket.setSoTimeout(0);
            TunnelClient client = new TunnelClient(serverHost.trim(), serverPort, username.trim(), listener,
                    socket, channel);
            client.start();
            listener.onLog(message == null || message.isEmpty() ? "登录成功" : message);
            return client;
        } catch (SocketTimeoutException e) {
            IoUtil.closeQuietly(socket);
            throw new IOException("连接服务器超时", e);
        } catch (IOException e) {
            IoUtil.closeQuietly(socket);
            throw e;
        } catch (RuntimeException e) {
            IoUtil.closeQuietly(socket);
            throw new IOException("连接服务器失败: " + e.getMessage(), e);
        }
    }

    private void start() {
        Thread receiver = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        }, "spv3-receiver");
        receiver.setDaemon(true);
        receiver.start();

        keepalive.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                keepaliveTick();
            }
        }, Protocol.PING_INTERVAL_MS, Protocol.PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void receiveLoop() {
        String reason = "服务器关闭了连接";
        try {
            while (running) {
                SecureChannel.Frame frame = channel.readFrame();
                lastInbound = System.currentTimeMillis();
                handleFrame(frame);
            }
        } catch (IOException e) {
            if (running) {
                reason = e.getMessage() == null ? "隧道连接中断" : e.getMessage();
            }
        } catch (RuntimeException e) {
            if (running) {
                reason = "隧道异常: " + e.getMessage();
            }
        } finally {
            finish(reason, true);
        }
    }

    private void keepaliveTick() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastInbound > Protocol.PING_INTERVAL_MS * 4L) {
            finish("服务器心跳超时", true);
            return;
        }
        try {
            channel.sendFrame(Protocol.PING, 0, new byte[0], 0, 0);
        } catch (IOException e) {
            finish("心跳发送失败: " + e.getMessage(), true);
        }
    }

    private void handleFrame(SecureChannel.Frame frame) {
        switch (frame.type) {
            case Protocol.AUTH_FAIL:
            case Protocol.AUTH_OK:
            case Protocol.LOGIN:
                // Only one login is allowed per connection.
                finish("服务器发送了非法控制帧", true);
                return;
            case Protocol.PING:
                try {
                    channel.sendFrame(Protocol.PONG, 0, frame.payload, 0, frame.payload.length);
                } catch (IOException e) {
                    finish("心跳响应失败", true);
                }
                return;
            case Protocol.PONG:
                return;
            default:
                break;
        }

        TunnelStream stream = streams.get(frame.streamId);
        if (stream == null) {
            if (frame.type == Protocol.DATA || frame.type == Protocol.FIN) {
                try {
                    channel.sendFrame(Protocol.RST, frame.streamId, new byte[0], 0, 0);
                } catch (IOException ignored) {
                }
            }
            return;
        }
        switch (frame.type) {
            case Protocol.OPEN_OK:
                stream.onOpenOk();
                break;
            case Protocol.OPEN_ERR:
                stream.onOpenError(new String(frame.payload, StandardCharsets.UTF_8));
                streams.remove(frame.streamId);
                break;
            case Protocol.DATA:
                stream.onData(frame.payload);
                break;
            case Protocol.FIN:
                stream.onFin();
                break;
            case Protocol.RST:
                stream.onReset("目标连接已关闭");
                break;
            default:
                listener.onLog("忽略未知帧: " + Protocol.frameName(frame.type));
        }
    }

    public TunnelStream openStream(String host, int port) throws IOException {
        if (host == null || host.isEmpty()) {
            throw new IOException("目标地址为空");
        }
        if (host.length() > Protocol.MAX_HOST_LENGTH) {
            throw new IOException("目标地址过长");
        }
        if (port < 1 || port > 65535) {
            throw new IOException("目标端口无效");
        }
        if (!running) {
            throw new IOException("隧道未连接");
        }
        if (streams.size() >= Protocol.MAX_STREAMS_PER_SESSION) {
            throw new IOException("并发连接过多，请稍后重试");
        }
        int id = nextStreamId.getAndIncrement();
        if (id <= 0) {
            nextStreamId.set(1);
            id = 1;
        }
        TunnelStream stream = new TunnelStream(id, host, port, this);
        TunnelStream previous = streams.putIfAbsent(id, stream);
        if (previous != null) {
            throw new IOException("内部错误：流 ID 冲突");
        }
        try {
            byte[] payload = IoUtil.openPayload(host, port);
            channel.sendFrame(Protocol.OPEN, id, payload, 0, payload.length);
            stream.awaitOpen(Protocol.OPEN_TIMEOUT_MS);
            return stream;
        } catch (IOException e) {
            streams.remove(id);
            stream.discard();
            try {
                channel.sendFrame(Protocol.RST, id, new byte[0], 0, 0);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    void sendStreamFrame(byte type, int streamId, byte[] payload, int offset, int length) throws IOException {
        if (!running) {
            throw new IOException("隧道已断开");
        }
        try {
            channel.sendFrame(type, streamId, payload, offset, length);
        } catch (IOException e) {
            finish("发送数据失败: " + e.getMessage(), true);
            throw e;
        }
    }

    void removeStream(int id) {
        streams.remove(id);
    }

    void abortStream(TunnelStream stream, boolean sendReset) {
        streams.remove(stream.getId());
        if (sendReset && running) {
            try {
                channel.sendFrame(Protocol.RST, stream.getId(), new byte[0], 0, 0);
            } catch (IOException ignored) {
            }
        }
    }

    public void disconnect() {
        closedByUser = true;
        finish("已断开", false);
    }

    public void disconnect(String reason) {
        closedByUser = true;
        finish(reason, false);
    }

    private void finish(String reason, boolean notify) {
        if (!running) {
            if (notify && !closedByUser) {
                notifyFinished(reason);
            }
            return;
        }
        running = false;
        keepalive.shutdownNow();
        channel.close();
        IoUtil.closeQuietly(socket);
        List<TunnelStream> copy = new ArrayList<TunnelStream>(streams.values());
        streams.clear();
        for (TunnelStream stream : copy) {
            stream.abortFromOwner(reason);
        }
        if (notify && !closedByUser) {
            notifyFinished(reason);
        }
    }

    private void notifyFinished(String reason) {
        if (finishNotified.compareAndSet(false, true)) {
            listener.onDisconnected(reason == null ? "连接已断开" : reason);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public String getUsername() {
        return username;
    }

    public String getServerAddress() {
        return serverHost + ":" + serverPort;
    }

    public int getActiveStreamCount() {
        return streams.size();
    }

    /** Test hook: returns a snapshot of active stream ids. */
    public int[] activeStreamIds() {
        List<Integer> ids = new ArrayList<Integer>(streams.keySet());
        int[] out = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            out[i] = ids.get(i);
        }
        return out;
    }
}
