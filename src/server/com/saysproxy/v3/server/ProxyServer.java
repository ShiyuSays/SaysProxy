package com.saysproxy.v3.server;

import com.saysproxy.v3.common.Crypto;
import com.saysproxy.v3.common.IoUtil;
import com.saysproxy.v3.common.Protocol;
import com.saysproxy.v3.common.SecureChannel;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Accepts encrypted SaysProxyV3 sessions and relays multiplexed TCP streams
 * to their destinations.
 */
public final class ProxyServer {
    private static final int MAX_STREAM_SLOTS = 1024;
    private static final Object TARGET_EOF = new Object();

    private final ServerConfig config;
    private final UserStore users;
    private final LogManager logs;
    private final SessionRegistry registry;
    private final LoginLimiter loginLimiter;
    private final ExecutorService workers;
    private final ExecutorService streamConnectors;
    private final Set<ClientSession> sessions = Collections.newSetFromMap(
            new ConcurrentHashMap<ClientSession, Boolean>());
    private final Semaphore connectionSlots;
    private final Semaphore handshakeSlots;
    private final Semaphore streamSlots = new Semaphore(MAX_STREAM_SLOTS);
    private final AtomicLong sessionSequence = new AtomicLong(1);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicBoolean stopping = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public ProxyServer(ServerConfig config, UserStore users, LogManager logs) {
        this.config = config;
        this.users = users;
        this.logs = logs;
        this.registry = new SessionRegistry(config.maxSessionsPerUser, config.maxIpsPerUser,
                config.maxSessionsPerIp, config.maxSessionsTotal);
        this.loginLimiter = new LoginLimiter(config.maxLoginFailures, config.loginBanMinutes);
        this.connectionSlots = new Semaphore(Math.max(16, config.maxSessionsTotal + 32));
        this.handshakeSlots = new Semaphore(Math.max(2, Math.min(12, config.maxSessionsTotal / 8)));
        this.workers = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "spv3-client");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.streamConnectors = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "spv3-target-connect");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public void start() throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName(config.bindAddress), config.port), 128);
        serverSocket = server;
        running = true;
        logs.audit("SERVER_START", "system", null,
                "bind=" + config.bindAddress + ":" + config.port + " users=" + users.size());
        Thread acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "spv3-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (SocketException e) {
                if (running) {
                    System.err.println("接受连接失败: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    System.err.println("接受连接失败: " + e.getMessage());
                }
                continue;
            }
            if (!running) {
                IoUtil.closeQuietly(socket);
                break;
            }
            if (!connectionSlots.tryAcquire()) {
                logs.audit("CONNECTION_REJECTED", "system", socket.getInetAddress().getHostAddress(),
                        "reason=connection slots exhausted");
                IoUtil.closeQuietly(socket);
                continue;
            }
            if (loginLimiter.isBanned(socket.getInetAddress().getHostAddress())) {
                logs.audit("CONNECTION_BANNED", "system",
                        socket.getInetAddress().getHostAddress(), "reason=login rate limit");
                connectionSlots.release();
                IoUtil.closeQuietly(socket);
                continue;
            }
            ClientSession session = new ClientSession(socket, "s-" + sessionSequence.getAndIncrement());
            sessions.add(session);
            try {
                workers.execute(session);
            } catch (RejectedExecutionException e) {
                sessions.remove(session);
                connectionSlots.release();
                IoUtil.closeQuietly(socket);
            }
        }
        stopped.countDown();
    }

    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        running = false;
        ServerSocket server = serverSocket;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
        for (ClientSession session : new ArrayList<ClientSession>(sessions)) {
            session.finish("服务器关闭");
        }
        workers.shutdownNow();
        streamConnectors.shutdownNow();
        logs.audit("SERVER_STOP", "system", null, "activeSessions=" + registry.getTotal());
        stopped.countDown();
    }

    public boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
        return stopped.await(timeout, unit);
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        ServerSocket server = serverSocket;
        return server == null ? -1 : server.getLocalPort();
    }

    public int getActiveSessions() {
        return registry.getTotal();
    }

    public int getHandlers() {
        return sessions.size();
    }

    public SessionRegistry getRegistry() {
        return registry;
    }

    public boolean kickUser(String username) {
        boolean found = false;
        for (ClientSession session : new ArrayList<ClientSession>(sessions)) {
            if (session.authenticated && username.equals(session.username)) {
                session.finish("管理员强制下线");
                found = true;
            }
        }
        return found;
    }

    private final class ClientSession implements Runnable {
        private final Socket socket;
        private final String remoteIp;
        private final String sessionId;
        private final ConcurrentHashMap<Integer, ServerStream> streams = new ConcurrentHashMap<Integer, ServerStream>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final long startedAt = System.currentTimeMillis();

        private volatile SecureChannel channel;
        private volatile boolean authenticated;
        private volatile boolean registered;
        private volatile boolean ipRegistered;
        private volatile String username;
        private volatile String finishReason = "连接结束";

        ClientSession(Socket socket, String sessionId) {
            this.socket = socket;
            this.remoteIp = socket.getInetAddress() == null ? "unknown"
                    : socket.getInetAddress().getHostAddress();
            this.sessionId = sessionId;
        }

        @Override
        public void run() {
            String reason = "连接结束";
            boolean handshakePermit = false;
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(Protocol.HANDSHAKE_TIMEOUT_MS);
                if (!handshakeSlots.tryAcquire(5, TimeUnit.SECONDS)) {
                    reason = "服务器繁忙";
                    return;
                }
                handshakePermit = true;
                try {
                    channel = SecureChannel.serverHandshake(socket, config.connectionPassword);
                } finally {
                    handshakeSlots.release();
                    handshakePermit = false;
                }

                SecureChannel.Frame loginFrame = channel.readFrame();
                if (loginFrame.type != Protocol.LOGIN || loginFrame.streamId != 0) {
                    reason = "登录帧无效";
                    return;
                }
                String[] credentials;
                try {
                    credentials = IoUtil.parseLoginPayload(loginFrame.payload);
                } catch (IOException e) {
                    reason = "登录请求无效";
                    return;
                }
                String attemptedUsername = credentials[0] == null ? "" : credentials[0].trim();
                String attemptedPassword = credentials[1];
                if (attemptedUsername.isEmpty() || attemptedUsername.length() > Protocol.MAX_USERNAME_LENGTH
                        || attemptedPassword == null || attemptedPassword.isEmpty()) {
                    sendAuthFailure("账户名或密码错误");
                    reason = "认证失败";
                    return;
                }

                if (loginLimiter.isBanned(remoteIp)) {
                    sendAuthFailure("登录失败次数过多，请稍后再试");
                    reason = "IP 已被临时限制";
                    return;
                }

                char[] passwordChars = attemptedPassword.toCharArray();
                boolean valid;
                try {
                    valid = users.verify(attemptedUsername, passwordChars);
                } finally {
                    Arrays.fill(passwordChars, '\u0000');
                }
                if (!valid) {
                    boolean nowBanned = loginLimiter.recordFailure(remoteIp);
                    sendAuthFailure(nowBanned ? "登录失败次数过多，请稍后再试" : "账户名或密码错误");
                    logs.audit("AUTH_FAIL", IoUtil.logSafe(attemptedUsername), remoteIp,
                            "session=" + sessionId + " banned=" + nowBanned);
                    reason = "认证失败";
                    return;
                }
                loginLimiter.recordSuccess(remoteIp);
                String rejection = registry.register(attemptedUsername, remoteIp);
                if (rejection != null) {
                    sendAuthFailure(rejection);
                    logs.audit("AUTH_REJECTED", IoUtil.logSafe(attemptedUsername), remoteIp,
                            "session=" + sessionId + " reason=" + IoUtil.logSafe(rejection));
                    reason = rejection;
                    return;
                }
                registered = true;
                ipRegistered = true;
                authenticated = true;
                username = attemptedUsername;
                sendAuthSuccess("登录成功");
                logs.access("LOGIN", username, remoteIp, null, "session=" + sessionId);
                logs.audit("LOGIN", username, remoteIp, "session=" + sessionId);
                socket.setSoTimeout(config.idleTimeoutSeconds * 1000);

                while (running && !finished.get()) {
                    SecureChannel.Frame frame = channel.readFrame();
                    if (!handleFrame(frame)) {
                        reason = "会话已结束";
                        return;
                    }
                }
                reason = "服务器关闭";
            } catch (SocketTimeoutException e) {
                reason = authenticated ? "空闲超时" : "握手或登录超时";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reason = "连接被中断";
            } catch (SocketException e) {
                reason = "连接中断: " + e.getMessage();
            } catch (IOException e) {
                reason = "连接中断: " + e.getMessage();
            } catch (RuntimeException e) {
                reason = "服务器处理异常: " + e.getMessage();
                logs.audit("SESSION_ERROR", username == null ? "unknown" : username, remoteIp,
                        "session=" + sessionId + " error=" + IoUtil.logSafe(String.valueOf(e)));
            } finally {
                if (handshakePermit) {
                    handshakeSlots.release();
                }
                finish(reason);
            }
        }

        private boolean handleFrame(SecureChannel.Frame frame) {
            switch (frame.type) {
                case Protocol.PING:
                    sendFrame(Protocol.PONG, 0, new byte[0]);
                    return true;
                case Protocol.PONG:
                    return true;
                case Protocol.OPEN:
                    handleOpen(frame);
                    return true;
                case Protocol.DATA:
                    handleData(frame);
                    return true;
                case Protocol.FIN:
                    handleFin(frame);
                    return true;
                case Protocol.RST:
                    handleReset(frame);
                    return true;
                case Protocol.LOGIN:
                case Protocol.AUTH_OK:
                case Protocol.AUTH_FAIL:
                    logs.audit("PROTOCOL_VIOLATION", username, remoteIp,
                            "session=" + sessionId + " frame=" + Protocol.frameName(frame.type));
                    return false;
                default:
                    sendFrame(Protocol.RST, frame.streamId, new byte[0]);
                    return true;
            }
        }

        private void handleOpen(SecureChannel.Frame frame) {
            if (frame.streamId <= 0 || frame.streamId == 0) {
                return;
            }
            if (streams.size() >= config.maxStreamsPerSession) {
                sendOpenError(frame.streamId, "单个会话的并发连接数已达上限");
                return;
            }
            if (streams.containsKey(frame.streamId)) {
                sendOpenError(frame.streamId, "流 ID 重复");
                return;
            }
            String host;
            int port;
            try {
                String[] parsed = IoUtil.parseOpenPayload(frame.payload);
                host = parsed[0];
                port = Integer.parseInt(parsed[1]);
            } catch (Exception e) {
                sendOpenError(frame.streamId, "目标地址格式无效");
                return;
            }
            if (!streamSlots.tryAcquire()) {
                sendOpenError(frame.streamId, "服务器转发连接数已满，请稍后再试");
                return;
            }
            ServerStream stream = new ServerStream(frame.streamId, host, port, this);
            ServerStream previous = streams.putIfAbsent(frame.streamId, stream);
            if (previous != null) {
                streamSlots.release();
                sendOpenError(frame.streamId, "流 ID 重复");
                return;
            }
            try {
                streamConnectors.execute(new Runnable() {
                    @Override
                    public void run() {
                        stream.connect();
                    }
                });
            } catch (RejectedExecutionException e) {
                streams.remove(frame.streamId, stream);
                streamSlots.release();
                stream.closeSilently();
                sendOpenError(frame.streamId, "服务器正在关闭");
            }
        }

        private void handleData(SecureChannel.Frame frame) {
            ServerStream stream = streams.get(frame.streamId);
            if (stream == null) {
                sendFrame(Protocol.RST, frame.streamId, new byte[0]);
                return;
            }
            if (frame.payload.length > Protocol.MAX_DATA_CHUNK) {
                abortStream(stream, "数据帧过大");
                return;
            }
            stream.fromClientData(frame.payload);
        }

        private void handleFin(SecureChannel.Frame frame) {
            ServerStream stream = streams.get(frame.streamId);
            if (stream == null) {
                sendFrame(Protocol.RST, frame.streamId, new byte[0]);
                return;
            }
            stream.fromClientFin();
        }

        private void handleReset(SecureChannel.Frame frame) {
            ServerStream stream = streams.get(frame.streamId);
            if (stream == null) {
                return;
            }
            abortStream(stream, "客户端重置了连接");
        }

        private void sendOpenError(int streamId, String message) {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            sendFrame(Protocol.OPEN_ERR, streamId, bytes);
        }

        void openConnected(ServerStream stream) {
            logs.access("CONNECT", username, remoteIp, stream.targetText(),
                    "session=" + sessionId + " stream=" + stream.id + " result=OK");
        }

        void openFailed(ServerStream stream, String message) {
            if (streams.remove(stream.id, stream)) {
                streamSlots.release();
                stream.closeSilently();
                if (authenticated && !finished.get()) {
                    sendOpenError(stream.id, message);
                }
                logs.access("CONNECT", username, remoteIp, stream.targetText(),
                        "session=" + sessionId + " stream=" + stream.id
                                + " result=FAIL reason=" + IoUtil.logSafe(message));
            }
        }

        void normalClose(ServerStream stream) {
            if (streams.remove(stream.id, stream)) {
                stream.closeSilently();
                streamSlots.release();
                logClose(stream, "正常结束");
            }
        }

        void abortStream(ServerStream stream, String reason) {
            if (streams.remove(stream.id, stream)) {
                stream.closeSilently();
                streamSlots.release();
                if (authenticated && !finished.get()) {
                    sendFrame(Protocol.RST, stream.id, new byte[0]);
                }
                logClose(stream, reason);
            }
        }

        private void logClose(ServerStream stream, String reason) {
            long duration = System.currentTimeMillis() - stream.createdAt;
            logs.access("CLOSE", username, remoteIp, stream.targetText(),
                    "session=" + sessionId + " stream=" + stream.id
                            + " upBytes=" + stream.upBytes.get()
                            + " downBytes=" + stream.downBytes.get()
                            + " durationMs=" + duration
                            + " reason=" + IoUtil.logSafe(reason));
        }

        boolean sendFrame(byte type, int streamId, byte[] payload) {
            SecureChannel current = channel;
            if (current == null || finished.get()) {
                return false;
            }
            try {
                current.sendFrame(type, streamId, payload, 0, payload == null ? 0 : payload.length);
                return true;
            } catch (IOException e) {
                finish("发送数据失败: " + e.getMessage());
                return false;
            }
        }

        private void sendAuthSuccess(String message) {
            byte[] payload = IoUtil.authPayload(true, message);
            sendFrame(Protocol.AUTH_OK, 0, payload);
        }

        private void sendAuthFailure(String message) {
            if (channel == null) {
                return;
            }
            byte[] payload = IoUtil.authPayload(false, message);
            sendFrame(Protocol.AUTH_FAIL, 0, payload);
        }

        boolean isFinished() {
            return finished.get();
        }

        void finish(String reason) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            finishReason = reason == null ? "连接结束" : reason;
            SecureChannel current = channel;
            if (current != null) {
                current.close();
            }
            IoUtil.closeQuietly(socket);
            for (Map.Entry<Integer, ServerStream> entry : streams.entrySet()) {
                ServerStream stream = entry.getValue();
                if (!streams.remove(entry.getKey(), stream)) {
                    continue;
                }
                stream.closeSilently();
                streamSlots.release();
                logClose(stream, finishReason);
            }
            streams.clear();
            if (registered) {
                registry.unregister(username, remoteIp);
                registered = false;
            }
            if (ipRegistered) {
                ipRegistered = false;
            }
            if (authenticated) {
                logs.access("LOGOUT", username, remoteIp, null,
                        "session=" + sessionId + " reason=" + IoUtil.logSafe(finishReason)
                                + " durationMs=" + (System.currentTimeMillis() - startedAt));
            }
            sessions.remove(this);
            connectionSlots.release();
            finished.set(true);
        }
    }

    private final class ServerStream {
        final int id;
        final String host;
        final int port;
        final ClientSession session;
        final long createdAt = System.currentTimeMillis();
        final AtomicLong upBytes = new AtomicLong();
        final AtomicLong downBytes = new AtomicLong();
        private final LinkedBlockingQueue<Object> toTarget = new LinkedBlockingQueue<Object>(64);

        private volatile Socket target;
        private volatile boolean connected;
        private volatile boolean closed;
        private volatile boolean clientFin;
        private volatile boolean writerDone;
        private volatile boolean remoteFinSent;
        private volatile boolean connectStarted;

        ServerStream(int id, String host, int port, ClientSession session) {
            this.id = id;
            this.host = host;
            this.port = port;
            this.session = session;
        }

        String targetText() {
            return host + ":" + port;
        }

        void connect() {
            connectStarted = true;
            InetSocketAddress address;
            try {
                address = TargetPolicy.resolve(host, port, config.allowPrivateTargets);
            } catch (IOException e) {
                session.openFailed(this, e.getMessage());
                return;
            }
            if (closed || session.isFinished()) {
                session.openFailed(this, "会话已结束");
                return;
            }
            Socket socket = new Socket();
            try {
                socket.setTcpNoDelay(true);
                socket.connect(address, config.targetConnectTimeoutSeconds * 1000);
                if (config.targetReadTimeoutSeconds > 0) {
                    socket.setSoTimeout(config.targetReadTimeoutSeconds * 1000);
                }
            } catch (IOException e) {
                IoUtil.closeQuietly(socket);
                session.openFailed(this, "连接目标失败: " + e.getMessage());
                return;
            }
            synchronized (this) {
                if (closed || session.isFinished()) {
                    IoUtil.closeQuietly(socket);
                    session.openFailed(this, "会话已结束");
                    return;
                }
                target = socket;
                connected = true;
            }
            if (!session.sendFrame(Protocol.OPEN_OK, id, new byte[0])) {
                closeSilently();
                return;
            }
            session.openConnected(this);
            Thread writer = new Thread(new Runnable() {
                @Override
                public void run() {
                    writerLoop();
                }
            }, "spv3-target-write-" + id);
            writer.setDaemon(true);
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    readerLoop();
                }
            }, "spv3-target-read-" + id);
            reader.setDaemon(true);
            writer.start();
            reader.start();
        }

        void fromClientData(byte[] data) {
            if (closed || clientFin) {
                return;
            }
            if (!connected) {
                session.abortStream(this, "目标尚未连接");
                return;
            }
            if (!toTarget.offer(data)) {
                session.abortStream(this, "发送队列已满，连接已重置");
            }
        }

        void fromClientFin() {
            if (closed || clientFin) {
                return;
            }
            clientFin = true;
            if (!connected) {
                session.abortStream(this, "目标尚未连接");
                return;
            }
            if (!toTarget.offer(TARGET_EOF)) {
                session.abortStream(this, "发送队列已满，连接已重置");
            }
        }

        private void writerLoop() {
            Socket socket = target;
            try {
                OutputStream output = socket.getOutputStream();
                while (true) {
                    Object item = toTarget.take();
                    if (item == TARGET_EOF) {
                        break;
                    }
                    byte[] data = (byte[]) item;
                    output.write(data);
                    upBytes.addAndGet(data.length);
                }
                output.flush();
                try {
                    socket.shutdownOutput();
                } catch (IOException ignored) {
                }
                writerDone = true;
                closeIfDone();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                if (!closed) {
                    session.abortStream(this, "写入目标失败: " + e.getMessage());
                }
            }
        }

        private void readerLoop() {
            Socket socket = target;
            byte[] buffer = new byte[Protocol.MAX_DATA_CHUNK];
            try {
                while (!closed) {
                    int read = socket.getInputStream().read(buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    downBytes.addAndGet(read);
                    byte[] data = Arrays.copyOf(buffer, read);
                    if (!session.sendFrame(Protocol.DATA, id, data)) {
                        return;
                    }
                }
                if (!closed) {
                    remoteFinSent = true;
                    session.sendFrame(Protocol.FIN, id, new byte[0]);
                    closeIfDone();
                }
            } catch (IOException e) {
                if (!closed) {
                    session.abortStream(this, "读取目标失败: " + e.getMessage());
                }
            }
        }

        private void closeIfDone() {
            if (writerDone && remoteFinSent) {
                session.normalClose(this);
            }
        }

        void closeSilently() {
            if (closed) {
                return;
            }
            closed = true;
            toTarget.clear();
            toTarget.offer(TARGET_EOF);
            Socket socket = target;
            if (socket != null) {
                IoUtil.closeQuietly(socket);
            }
        }
    }
}
