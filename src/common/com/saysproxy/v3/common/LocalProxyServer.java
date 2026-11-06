package com.saysproxy.v3.common;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local mixed proxy. It listens on 127.0.0.1 only and understands:
 *   - SOCKS5 CONNECT (no local authentication)
 *   - HTTP proxy requests and CONNECT
 *
 * TCP destinations are opened through the encrypted SaysProxyV3 tunnel. UDP
 * ASSOCIATE is intentionally not supported.
 */
public final class LocalProxyServer {
    private static final int MAX_HTTP_HEADER_BYTES = 64 * 1024;
    private static final int MAX_HTTP_LINE_BYTES = 8192;

    private final TunnelClient tunnel;
    private final int requestedPort;
    private final LogSink log;
    private final Set<Socket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private final ExecutorService workers;
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public LocalProxyServer(TunnelClient tunnel, int port, LogSink log) {
        if (tunnel == null) {
            throw new IllegalArgumentException("tunnel == null");
        }
        this.tunnel = tunnel;
        this.requestedPort = port;
        this.log = log;
        this.workers = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "spv3-local-proxy");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public void start() throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort), 64);
        serverSocket = server;
        running = true;
        Thread acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "spv3-proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public int getPort() {
        ServerSocket server = serverSocket;
        return server == null ? -1 : server.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    public void close() {
        running = false;
        ServerSocket server = serverSocket;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
        for (Socket socket : new ArrayList<Socket>(sockets)) {
            IoUtil.closeQuietly(socket);
        }
        sockets.clear();
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                sockets.add(socket);
                workers.execute(new Handler(socket));
            } catch (SocketException e) {
                if (running) {
                    log.log("本地代理监听失败: " + e.getMessage());
                }
                return;
            } catch (IOException e) {
                if (running) {
                    log.log("本地代理 accept 失败: " + e.getMessage());
                }
            }
        }
    }

    private final class Handler implements Runnable {
        private final Socket socket;

        Handler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(30_000);
                PushbackInputStream in = new PushbackInputStream(
                        new BufferedInputStream(socket.getInputStream(), 32 * 1024), 1);
                OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 32 * 1024);
                int first = in.read();
                if (first < 0) {
                    return;
                }
                in.unread(first);
                if (first == 0x05) {
                    handleSocks5(in, out);
                } else if ((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z')) {
                    handleHttp(in, out);
                } else {
                    log.log("本地代理收到未知协议连接");
                }
            } catch (SocketException e) {
                // Normal when the local application closes a proxy connection.
            } catch (IOException e) {
                log.log("本地代理连接结束: " + e.getMessage());
            } finally {
                sockets.remove(socket);
                IoUtil.closeQuietly(socket);
            }
        }

        private void handleSocks5(InputStream in, OutputStream out) throws IOException {
            int version = readByte(in);
            if (version != 5) {
                throw new IOException("不是 SOCKS5 请求");
            }
            int methodCount = readByte(in);
            if (methodCount < 1 || methodCount > 32) {
                throw new IOException("SOCKS5 方法数量无效");
            }
            byte[] methods = new byte[methodCount];
            readFully(in, methods);
            boolean noAuth = false;
            for (byte method : methods) {
                if (method == 0) {
                    noAuth = true;
                }
            }
            if (!noAuth) {
                out.write(new byte[]{5, (byte) 0xff});
                out.flush();
                return;
            }
            out.write(new byte[]{5, 0});
            out.flush();

            int requestVersion = readByte(in);
            int command = readByte(in);
            readByte(in); // reserved
            int addressType = readByte(in);
            if (requestVersion != 5) {
                throw new IOException("SOCKS5 版本错误");
            }
            String host;
            if (addressType == 1) {
                byte[] address = new byte[4];
                readFully(in, address);
                host = InetAddress.getByAddress(address).getHostAddress();
            } else if (addressType == 3) {
                int length = readByte(in);
                if (length == 0) {
                    throw new IOException("SOCKS5 域名长度无效");
                }
                byte[] address = new byte[length];
                readFully(in, address);
                host = new String(address, StandardCharsets.UTF_8);
            } else if (addressType == 4) {
                byte[] address = new byte[16];
                readFully(in, address);
                host = InetAddress.getByAddress(address).getHostAddress();
            } else {
                sendSocksReply(out, 8);
                return;
            }
            int port = (readByte(in) << 8) | readByte(in);
            if (command != 1) {
                sendSocksReply(out, 7);
                return;
            }
            if (port < 1) {
                sendSocksReply(out, 1);
                return;
            }
            TunnelStream remote;
            try {
                remote = tunnel.openStream(host, port);
            } catch (IOException e) {
                log.log("连接 " + host + ":" + port + " 失败: " + e.getMessage());
                sendSocksReply(out, 5);
                return;
            }
            sendSocksReply(out, 0);
            socket.setSoTimeout(0);
            bridge(in, out, remote);
        }

        private void handleHttp(InputStream in, OutputStream out) throws IOException {
            String requestLine = readHttpLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            List<String> headers = new ArrayList<String>();
            int headerBytes = requestLine.length() + 2;
            while (true) {
                String line = readHttpLine(in);
                if (line == null) {
                    sendHttpError(out, 400, "Bad Request");
                    return;
                }
                if (line.isEmpty()) {
                    break;
                }
                headerBytes += line.length() + 2;
                if (headerBytes > MAX_HTTP_HEADER_BYTES) {
                    sendHttpError(out, 431, "Request Header Fields Too Large");
                    return;
                }
                headers.add(line);
            }

            String[] parts = requestLine.split(" ", 3);
            if (parts.length != 3) {
                sendHttpError(out, 400, "Bad Request");
                return;
            }
            String method = parts[0];
            String target = parts[1];
            String protocol = parts[2];
            if (!protocol.startsWith("HTTP/")) {
                sendHttpError(out, 400, "Bad Request");
                return;
            }

            String host;
            int port;
            String path = null;
            if ("CONNECT".equalsIgnoreCase(method)) {
                HostPort hostPort = parseHostPort(target, 443);
                if (hostPort == null) {
                    sendHttpError(out, 400, "Invalid CONNECT target");
                    return;
                }
                host = hostPort.host;
                port = hostPort.port;
            } else {
                HostPort absolute = parseAbsoluteHttpUri(target);
                if (absolute != null) {
                    host = absolute.host;
                    port = absolute.port;
                    path = absolute.path;
                } else {
                    host = findHostHeader(headers);
                    if (host == null || host.isEmpty()) {
                        sendHttpError(out, 400, "Missing Host header");
                        return;
                    }
                    HostPort hostPort = parseHostPort(host, 80);
                    if (hostPort == null) {
                        sendHttpError(out, 400, "Invalid Host header");
                        return;
                    }
                    host = hostPort.host;
                    port = hostPort.port;
                    path = target.startsWith("/") ? target : "/" + target;
                }
            }

            TunnelStream remote;
            try {
                remote = tunnel.openStream(host, port);
            } catch (IOException e) {
                log.log("连接 " + host + ":" + port + " 失败: " + e.getMessage());
                sendHttpError(out, 502, "Bad Gateway");
                return;
            }

            if ("CONNECT".equalsIgnoreCase(method)) {
                out.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: SaysProxyV3\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                socket.setSoTimeout(0);
                bridge(in, out, remote);
                return;
            }

            StringBuilder forwarded = new StringBuilder();
            forwarded.append(method).append(' ').append(path).append(' ').append(protocol).append("\r\n");
            boolean hostWritten = false;
            for (String header : headers) {
                int colon = header.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = header.substring(0, colon).trim();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("proxy-connection") || lower.equals("proxy-authorization")
                        || lower.equals("connection") || lower.equals("keep-alive")) {
                    continue;
                }
                if (lower.equals("host")) {
                    hostWritten = true;
                }
                forwarded.append(header).append("\r\n");
            }
            if (!hostWritten) {
                forwarded.append("Host: ").append(host);
                if (port != 80) {
                    forwarded.append(':').append(port);
                }
                forwarded.append("\r\n");
            }
            forwarded.append("Connection: close\r\n\r\n");
            byte[] requestBytes = forwarded.toString().getBytes(StandardCharsets.ISO_8859_1);
            remote.getOutputStream().write(requestBytes);
            remote.getOutputStream().flush();
            socket.setSoTimeout(0);
            bridge(in, out, remote);
        }

        private void sendSocksReply(OutputStream out, int replyCode) throws IOException {
            out.write(new byte[]{5, (byte) replyCode, 0, 1, 0, 0, 0, 0, 0, 0});
            out.flush();
        }

        private void sendHttpError(OutputStream out, int status, String reason) throws IOException {
            String body = "SaysProxyV3: " + status + " " + reason + "\n";
            String response = "HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                    + "Connection: close\r\n\r\n" + body;
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private void bridge(InputStream localIn, OutputStream localOut, TunnelStream remote) {
            final AtomicReference<String> failure = new AtomicReference<String>();
            final AtomicBoolean closed = new AtomicBoolean();
            Thread upload = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buffer = new byte[32 * 1024];
                    try {
                        int read;
                        while ((read = localIn.read(buffer)) >= 0) {
                            if (read > 0) {
                                remote.getOutputStream().write(buffer, 0, read);
                            }
                        }
                        remote.shutdownOutput();
                    } catch (IOException e) {
                        if (closed.compareAndSet(false, true)) {
                            failure.set(e.getMessage());
                            remote.close();
                            IoUtil.closeQuietly(socket);
                        }
                    }
                }
            }, "spv3-proxy-upload");
            Thread download = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buffer = new byte[32 * 1024];
                    try {
                        int read;
                        while ((read = remote.getInputStream().read(buffer)) >= 0) {
                            if (read > 0) {
                                localOut.write(buffer, 0, read);
                                localOut.flush();
                            }
                        }
                        localOut.flush();
                        socket.shutdownOutput();
                    } catch (IOException e) {
                        if (closed.compareAndSet(false, true)) {
                            failure.set(e.getMessage());
                            remote.close();
                            IoUtil.closeQuietly(socket);
                        }
                    }
                }
            }, "spv3-proxy-download");
            upload.setDaemon(true);
            download.setDaemon(true);
            upload.start();
            download.start();
            try {
                upload.join();
                download.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                remote.close();
            }
            if (failure.get() != null) {
                log.log("本地代理转发结束: " + failure.get());
            }
        }
    }

    private static String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int previous = -1;
        while (true) {
            int b = in.read();
            if (b < 0) {
                return line.size() == 0 ? null : line.toString("ISO-8859-1");
            }
            if (b == '\n') {
                byte[] data = line.toByteArray();
                int length = data.length;
                if (length > 0 && data[length - 1] == '\r') {
                    length--;
                }
                return new String(data, 0, length, StandardCharsets.ISO_8859_1);
            }
            if (b == '\r') {
                previous = b;
                continue;
            }
            if (previous == '\r') {
                line.write('\r');
            }
            previous = b;
            line.write(b);
            if (line.size() > MAX_HTTP_LINE_BYTES) {
                throw new IOException("HTTP 请求行过长");
            }
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new EOFException();
        }
        return value;
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                throw new EOFException();
            }
            offset += read;
        }
    }

    private static String findHostHeader(List<String> headers) {
        for (String header : headers) {
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Host")) {
                return header.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static HostPort parseAbsoluteHttpUri(String value) {
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("http")) {
                return null;
            }
            if (uri.getHost() == null) {
                return null;
            }
            int port = uri.getPort() < 0 ? 80 : uri.getPort();
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (uri.getRawQuery() != null) {
                path += "?" + uri.getRawQuery();
            }
            HostPort hp = new HostPort();
            hp.host = uri.getHost();
            hp.port = port;
            hp.path = path;
            return hp;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static HostPort parseHostPort(String value, int defaultPort) {
        if (value == null) {
            return null;
        }
        String input = value.trim();
        if (input.isEmpty()) {
            return null;
        }
        String host;
        int port = defaultPort;
        if (input.startsWith("[")) {
            int close = input.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = input.substring(1, close);
            if (close + 1 < input.length()) {
                if (input.charAt(close + 1) != ':') {
                    return null;
                }
                try {
                    port = Integer.parseInt(input.substring(close + 2));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        } else {
            int colon = input.lastIndexOf(':');
            if (colon >= 0 && input.indexOf(':') == colon) {
                host = input.substring(0, colon);
                try {
                    port = Integer.parseInt(input.substring(colon + 1));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                host = input;
            }
        }
        if (host.isEmpty() || host.length() > Protocol.MAX_HOST_LENGTH
                || port < 1 || port > 65535) {
            return null;
        }
        HostPort result = new HostPort();
        result.host = host;
        result.port = port;
        return result;
    }

    private static final class HostPort {
        String host;
        int port;
        String path;
    }
}
