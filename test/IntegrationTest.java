import com.saysproxy.v3.common.LocalProxyServer;
import com.saysproxy.v3.common.LogSink;
import com.saysproxy.v3.common.TunnelClient;
import com.saysproxy.v3.common.TunnelListener;
import com.saysproxy.v3.server.LogManager;
import com.saysproxy.v3.server.ProxyServer;
import com.saysproxy.v3.server.ServerConfig;
import com.saysproxy.v3.server.TargetPolicy;
import com.saysproxy.v3.server.UserStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** End-to-end smoke test for the encrypted proxy, both HTTP and SOCKS5 paths. */
public final class IntegrationTest {
    private static final AtomicInteger failures = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("spv3-test-");
        System.out.println("TEST HOME " + home);
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/hello", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] body = ("hello-via-saysproxy " + exchange.getRequestURI().getPath())
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        target.createContext("/large", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] body = new byte[512 * 1024 + 17];
                for (int i = 0; i < body.length; i++) {
                    body[i] = (byte) ('a' + (i % 23));
                }
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        target.start();
        int targetPort = target.getAddress().getPort();

        boolean privateTargetBlocked = false;
        try {
            TargetPolicy.resolve("127.0.0.1", targetPort, false);
        } catch (IOException e) {
            privateTargetBlocked = true;
        }
        check(privateTargetBlocked, "private target blocked by default");
        check(TargetPolicy.resolve("127.0.0.1", targetPort, true) != null,
                "private target allowed when configured");
        check(TargetPolicy.blocked(java.net.InetAddress.getByName("169.254.169.254"), false),
                "cloud metadata address blocked");
        check(TargetPolicy.blocked(java.net.InetAddress.getByName("8.8.8.8"), false) == false,
                "public address not blocked");

        ServerConfig config = ServerConfig.load(home);
        config.port = 0;
        config.allowPrivateTargets = true;
        config.maxSessionsPerUser = 1;
        config.maxIpsPerUser = 3;

        check(Files.exists(config.passwordFile), "password file exists");
        check(Files.exists(config.usersFile), "users file exists");
        check(Files.exists(config.configDir.resolve("max-connections.properties")), "limits file exists");
        check(config.connectionPassword.length() >= 24, "generated password length");

        UserStore users = new UserStore(config.usersFile);
        users.add("alice", "secret123".toCharArray());
        check(users.verify("alice", "secret123".toCharArray()), "correct password verifies");
        check(!users.verify("alice", "wrongpass".toCharArray()), "wrong password rejected");
        check(!users.verify("nobody", "secret123".toCharArray()), "unknown user rejected");

        LogManager logs = new LogManager(config.logDir);
        ProxyServer server = new ProxyServer(config, users, logs);
        server.start();
        System.out.println("server on " + server.getPort());

        TunnelListener listener = new TunnelListener() {
            @Override
            public void onLog(String message) {
                System.out.println("[client] " + message);
            }

            @Override
            public void onDisconnected(String reason) {
                System.out.println("[client disconnected] " + reason);
            }
        };

        TunnelClient tunnel = TunnelClient.connect("127.0.0.1", server.getPort(),
                config.connectionPassword, "alice", "secret123", listener);
        check(tunnel.isRunning(), "tunnel running");

        boolean sessionLimitRejected = false;
        try {
            TunnelClient second = TunnelClient.connect("127.0.0.1", server.getPort(),
                    config.connectionPassword, "alice", "secret123", listener);
            second.disconnect();
        } catch (IOException e) {
            sessionLimitRejected = true;
            System.out.println("expected session-limit rejection: " + e.getMessage());
        }
        check(sessionLimitRejected, "per-user session limit enforced");

        LocalProxyServer proxy = new LocalProxyServer(tunnel, 0, new LogSink() {
            @Override
            public void log(String message) {
                System.out.println("[local] " + message);
            }
        });
        proxy.start();
        int proxyPort = proxy.getPort();
        System.out.println("local proxy on " + proxyPort);

        String httpResponse = httpProxy(proxyPort, targetPort);
        check(httpResponse.contains("200 OK"), "HTTP proxy status 200: " + firstLine(httpResponse));
        check(httpResponse.contains("hello-via-saysproxy /hello"), "HTTP proxy body");

        String socksResponse = socksProxy(proxyPort, targetPort);
        check(socksResponse.contains("200 OK"), "SOCKS5 status 200");
        check(socksResponse.contains("hello-via-saysproxy /hello"), "SOCKS5 body");

        String connectResponse = httpConnectProxy(proxyPort, targetPort);
        check(connectResponse.contains("200 OK"), "HTTP CONNECT status 200");
        check(connectResponse.contains("hello-via-saysproxy /hello"), "HTTP CONNECT body");

        byte[] large = httpProxyLarge(proxyPort, targetPort);
        check(large.length == 512 * 1024 + 17, "large HTTP body length: " + large.length);
        boolean largePattern = true;
        for (int i = 0; i < large.length; i++) {
            if (large[i] != (byte) ('a' + (i % 23))) {
                largePattern = false;
                break;
            }
        }
        check(largePattern, "large HTTP body integrity");

        boolean wrongAccountRejected = false;
        try {
            TunnelClient bad = TunnelClient.connect("127.0.0.1", server.getPort(),
                    config.connectionPassword, "alice", "bad-password", listener);
            bad.disconnect();
        } catch (IOException e) {
            wrongAccountRejected = true;
            System.out.println("expected wrong-account rejection: " + e.getMessage());
        }
        check(wrongAccountRejected, "wrong account password rejected");

        boolean wrongServerPasswordRejected = false;
        try {
            TunnelClient bad = TunnelClient.connect("127.0.0.1", server.getPort(),
                    "definitely-wrong-server-password", "alice", "secret123", listener);
            bad.disconnect();
        } catch (IOException e) {
            wrongServerPasswordRejected = true;
            System.out.println("expected wrong-server-password rejection: " + e.getMessage());
        }
        check(wrongServerPasswordRejected, "wrong server password rejected");

        tunnel.disconnect();
        proxy.close();
        server.stop();
        logs.close();
        target.stop(0);

        Thread.sleep(200);
        List<String> accessLines = Files.readAllLines(
                config.logDir.resolve("access-" + java.time.LocalDate.now() + ".log"),
                StandardCharsets.UTF_8);
        String access = join(accessLines);
        check(access.contains("user=alice"), "access log contains user");
        check(access.contains("ip=127.0.0.1"), "access log contains client ip");
        check(access.contains("target=127.0.0.1:" + targetPort), "access log contains target");
        check(access.contains("CONNECT") && access.contains("CLOSE"), "access log events");
        List<String> auditLines = Files.readAllLines(
                config.logDir.resolve("audit-" + java.time.LocalDate.now() + ".log"),
                StandardCharsets.UTF_8);
        check(join(auditLines).contains("SERVER_START"), "audit server start");
        check(!access.contains("secret123") && !join(auditLines).contains("secret123"),
                "passwords absent from logs");

        if (failures.get() == 0) {
            System.out.println("ALL TESTS PASSED");
            deleteRecursive(home.toFile());
        } else {
            System.out.println("FAILURES: " + failures.get());
            System.exit(1);
        }
    }

    private static String httpConnectProxy(int proxyPort, int targetPort) throws IOException {
        Socket socket = new Socket("127.0.0.1", proxyPort);
        try {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            String connect = "CONNECT 127.0.0.1:" + targetPort + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + targetPort + "\r\n\r\n";
            out.write(connect.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int state = 0;
            while (state < 4) {
                int b = in.read();
                if (b < 0) {
                    throw new IOException("EOF in CONNECT response");
                }
                header.write(b);
                if ((state == 0 || state == 2) && b == '\r') {
                    state++;
                } else if ((state == 1 || state == 3) && b == '\n') {
                    state++;
                } else {
                    state = 0;
                }
            }
            String reply = new String(header.toByteArray(), StandardCharsets.ISO_8859_1);
            check(reply.contains("200"), "CONNECT proxy reply 200");
            String request = "GET /hello HTTP/1.1\r\nHost: 127.0.0.1:" + targetPort
                    + "\r\nConnection: close\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return readAll(in);
        } finally {
            socket.close();
        }
    }

    private static byte[] httpProxyLarge(int proxyPort, int targetPort) throws IOException {
        Socket socket = new Socket("127.0.0.1", proxyPort);
        try {
            socket.setSoTimeout(30_000);
            String request = "GET http://127.0.0.1:" + targetPort + "/large HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + targetPort + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            byte[] raw = readAllBytes(socket.getInputStream());
            int split = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            if (split < 0) {
                throw new IOException("no HTTP header separator");
            }
            String headers = new String(raw, 0, split, StandardCharsets.ISO_8859_1);
            if (!headers.contains("200 OK")) {
                throw new IOException("unexpected response: " + firstLine(headers));
            }
            int bodyStart = split + 4;
            byte[] body = new byte[raw.length - bodyStart];
            System.arraycopy(raw, bodyStart, body, 0, body.length);
            return body;
        } finally {
            socket.close();
        }
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String httpProxy(int proxyPort, int targetPort) throws IOException {
        Socket socket = new Socket("127.0.0.1", proxyPort);
        try {
            socket.setSoTimeout(20_000);
            String request = "GET http://127.0.0.1:" + targetPort + "/hello HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + targetPort + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return readAll(socket.getInputStream());
        } finally {
            socket.close();
        }
    }

    private static String socksProxy(int proxyPort, int targetPort) throws IOException {
        Socket socket = new Socket("127.0.0.1", proxyPort);
        try {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(new byte[]{5, 1, 0});
            out.flush();
            byte[] method = readN(in, 2);
            check(method[0] == 5 && method[1] == 0, "SOCKS5 no-auth selected");
            out.write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1,
                    (byte) (targetPort >>> 8), (byte) targetPort});
            out.flush();
            byte[] reply = readN(in, 10);
            check(reply[1] == 0, "SOCKS5 connect reply success");
            String request = "GET /hello HTTP/1.1\r\nHost: 127.0.0.1:" + targetPort
                    + "\r\nConnection: close\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return readAll(in);
        } finally {
            socket.close();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readN(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) {
                throw new IOException("EOF");
            }
            offset += read;
        }
        return data;
    }

    private static String firstLine(String text) {
        int index = text.indexOf('\n');
        return index < 0 ? text : text.substring(0, index).trim();
    }

    private static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            System.out.println("PASS " + message);
        } else {
            failures.incrementAndGet();
            System.out.println("FAIL " + message);
        }
    }

    private static void deleteRecursive(java.io.File file) {
        java.io.File[] children = file.listFiles();
        if (children != null) {
            for (java.io.File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }
}
