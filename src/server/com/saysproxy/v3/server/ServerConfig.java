package com.saysproxy.v3.server;

import com.saysproxy.v3.common.Crypto;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Properties;

/**
 * Loads and creates the server-side configuration. First start generates:
 *
 *   config/server.properties
 *   config/max-connections.properties
 *   config/connection-password.txt
 *   config/users.properties
 *
 * The generated connection password is 144 bits of randomness encoded as 24
 * URL-safe characters. It is stored in a plain file because the administrator
 * must be able to read it; protect that file accordingly.
 */
public final class ServerConfig {
    public String bindAddress = "0.0.0.0";
    public int port = 8388;
    public boolean allowPrivateTargets = false;
    public int targetConnectTimeoutSeconds = 15;
    public int idleTimeoutSeconds = 180;
    public int maxSessionsPerUser = 3;
    public int maxIpsPerUser = 2;
    public int maxSessionsPerIp = 10;
    public int maxSessionsTotal = 200;
    public int maxStreamsPerSession = 64;
    public int maxLoginFailures = 5;
    public int loginBanMinutes = 10;
    public int targetReadTimeoutSeconds = 0;

    public final Path home;
    public final Path configDir;
    public final Path logDir;
    public final Path passwordFile;
    public final Path usersFile;
    public String connectionPassword;
    public boolean passwordCreated;

    private ServerConfig(Path home) {
        this.home = home;
        this.configDir = home.resolve("config");
        this.logDir = home.resolve("logs");
        this.passwordFile = configDir.resolve("connection-password.txt");
        this.usersFile = configDir.resolve("users.properties");
    }

    public static ServerConfig load(Path home) throws IOException {
        Path normalized = home.toAbsolutePath().normalize();
        ServerConfig config = new ServerConfig(normalized);
        Files.createDirectories(config.configDir);
        Files.createDirectories(config.logDir);
        config.ensureServerProperties();
        config.ensureLimitsProperties();
        config.ensurePassword();
        config.ensureUsersFile();
        config.readServerProperties();
        config.readLimitsProperties();
        config.validate();
        return config;
    }

    public static String generatePassword() {
        byte[] raw = Crypto.randomBytes(18);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private void ensureServerProperties() throws IOException {
        Path file = configDir.resolve("server.properties");
        if (Files.exists(file)) {
            return;
        }
        String content = "# SaysProxyV3 server configuration\r\n"
                + "# Address to listen on. 0.0.0.0 listens on all interfaces.\r\n"
                + "bindAddress=0.0.0.0\r\n"
                + "# TCP port used by clients.\r\n"
                + "port=8388\r\n"
                + "# Refuse targets in private, loopback, link-local and reserved ranges.\r\n"
                + "allowPrivateTargets=false\r\n"
                + "targetConnectTimeoutSeconds=15\r\n"
                + "# Close a client that sends no frames for this many seconds.\r\n"
                + "# Clients send a keepalive every 30 seconds.\r\n"
                + "idleTimeoutSeconds=180\r\n";
        Files.write(file, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private void ensureLimitsProperties() throws IOException {
        Path file = configDir.resolve("max-connections.properties");
        if (Files.exists(file)) {
            return;
        }
        String content = "# SaysProxyV3 connection limits\r\n"
                + "# Maximum authenticated client sessions for one account.\r\n"
                + "maxSessionsPerUser=3\r\n"
                + "# Maximum distinct client IP addresses used by one account at the same time.\r\n"
                + "maxIpsPerUser=2\r\n"
                + "# Maximum authenticated sessions accepted from one IP address.\r\n"
                + "maxSessionsPerIp=10\r\n"
                + "# Maximum authenticated sessions across the entire server.\r\n"
                + "maxSessionsTotal=200\r\n"
                + "# Maximum simultaneous proxied TCP streams inside one client session.\r\n"
                + "maxStreamsPerSession=64\r\n"
                + "# Failed logins allowed from one IP before a temporary ban.\r\n"
                + "maxLoginFailures=5\r\n"
                + "loginBanMinutes=10\r\n"
                + "# Optional idle read timeout for target sockets; 0 disables it.\r\n"
                + "targetReadTimeoutSeconds=0\r\n";
        Files.write(file, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private void ensurePassword() throws IOException {
        if (Files.exists(passwordFile)) {
            return;
        }
        String password = generatePassword();
        byte[] content = (password + "\r\n").getBytes(StandardCharsets.UTF_8);
        Files.write(passwordFile, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        passwordCreated = true;
        try {
            Files.setPosixFilePermissions(passwordFile,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows does not expose POSIX permissions through this API.
        } catch (IOException ignored) {
            // Best effort only.
        }
        this.connectionPassword = password;
    }

    private void ensureUsersFile() throws IOException {
        if (Files.exists(usersFile)) {
            return;
        }
        String content = "# SaysProxyV3 users - managed with /add and /passwd, never edit while the server is running.\r\n"
                + "# Format: username=pbkdf2-sha256$iterations$saltBase64$hashBase64$createdMillis\r\n";
        Files.write(usersFile, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private void readServerProperties() throws IOException {
        Properties properties = readProperties(configDir.resolve("server.properties"));
        bindAddress = properties.getProperty("bindAddress", bindAddress).trim();
        port = parseInt(properties, "port", port);
        allowPrivateTargets = parseBoolean(properties, "allowPrivateTargets", allowPrivateTargets);
        targetConnectTimeoutSeconds = parseInt(properties, "targetConnectTimeoutSeconds", targetConnectTimeoutSeconds);
        idleTimeoutSeconds = parseInt(properties, "idleTimeoutSeconds", idleTimeoutSeconds);
    }

    private void readLimitsProperties() throws IOException {
        Properties properties = readProperties(configDir.resolve("max-connections.properties"));
        maxSessionsPerUser = parseInt(properties, "maxSessionsPerUser", maxSessionsPerUser);
        maxIpsPerUser = parseInt(properties, "maxIpsPerUser", maxIpsPerUser);
        maxSessionsPerIp = parseInt(properties, "maxSessionsPerIp", maxSessionsPerIp);
        maxSessionsTotal = parseInt(properties, "maxSessionsTotal", maxSessionsTotal);
        maxStreamsPerSession = parseInt(properties, "maxStreamsPerSession", maxStreamsPerSession);
        maxLoginFailures = parseInt(properties, "maxLoginFailures", maxLoginFailures);
        loginBanMinutes = parseInt(properties, "loginBanMinutes", loginBanMinutes);
        targetReadTimeoutSeconds = parseInt(properties, "targetReadTimeoutSeconds", targetReadTimeoutSeconds);
    }

    private static Properties readProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static int parseInt(Properties properties, String key, int defaultValue) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IOException("配置项 " + key + " 不是整数: " + value);
        }
    }

    private static boolean parseBoolean(Properties properties, String key, boolean defaultValue)
            throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        throw new IOException("配置项 " + key + " 必须是 true 或 false");
    }

    private void validate() throws IOException {
        if (bindAddress.isEmpty()) {
            throw new IOException("bindAddress 不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new IOException("端口必须在 1-65535 之间");
        }
        if (targetConnectTimeoutSeconds < 1 || targetConnectTimeoutSeconds > 300) {
            throw new IOException("targetConnectTimeoutSeconds 必须在 1-300 之间");
        }
        if (idleTimeoutSeconds < 60 || idleTimeoutSeconds > 86_400) {
            throw new IOException("idleTimeoutSeconds 必须在 60-86400 之间");
        }
        if (maxSessionsPerUser < 1 || maxIpsPerUser < 1 || maxSessionsPerIp < 1
                || maxSessionsTotal < 1 || maxStreamsPerSession < 1) {
            throw new IOException("连接限制必须为正整数");
        }
        if (maxLoginFailures < 1 || loginBanMinutes < 1) {
            throw new IOException("登录失败限制必须为正整数");
        }
        if (targetReadTimeoutSeconds < 0 || targetReadTimeoutSeconds > 86_400) {
            throw new IOException("targetReadTimeoutSeconds 必须在 0-86400 之间");
        }
        connectionPassword = readPassword();
        if (connectionPassword.length() < 12) {
            throw new IOException("服务器连接密码至少需要 12 个字符，请修改 config/connection-password.txt");
        }
        if (connectionPassword.length() > 256) {
            throw new IOException("服务器连接密码过长");
        }
    }

    private String readPassword() throws IOException {
        String password = new String(Files.readAllBytes(passwordFile), StandardCharsets.UTF_8).trim();
        if (password.isEmpty()) {
            throw new IOException("连接密码文件为空: " + passwordFile);
        }
        return password;
    }

    public void writePasswordAtomically(String password) throws IOException {
        Path temp = passwordFile.resolveSibling(passwordFile.getFileName() + ".tmp");
        Files.write(temp, (password + "\r\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, passwordFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, passwordFile, StandardCopyOption.REPLACE_EXISTING);
        }
        this.connectionPassword = password;
    }
}
