package com.saysproxy.v3.server;

import com.saysproxy.v3.common.Crypto;
import com.saysproxy.v3.common.Protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Persistent account store. Only the server console can create accounts.
 * Passwords are stored as PBKDF2-HMAC-SHA256 with a per-user random salt.
 */
public final class UserStore {
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{1,31}$");
    private static final String HASH_ALGORITHM = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    private final Path file;
    private final Object lock = new Object();
    private final Map<String, UserRecord> users = new LinkedHashMap<String, UserRecord>();
    private final byte[] dummySalt;

    public UserStore(Path file) throws IOException {
        this.file = file;
        this.dummySalt = Crypto.randomBytes(SALT_BYTES);
        load();
    }

    private void load() throws IOException {
        synchronized (lock) {
            users.clear();
            if (!Files.exists(file)) {
                return;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int lineNumber = 0;
            for (String line : lines) {
                lineNumber++;
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                int equals = value.indexOf('=');
                if (equals <= 0) {
                    throw new IOException("用户文件第 " + lineNumber + " 行格式错误");
                }
                String username = value.substring(0, equals).trim();
                String record = value.substring(equals + 1).trim();
                UserRecord parsed = parseRecord(record);
                if (parsed == null) {
                    throw new IOException("用户文件第 " + lineNumber + " 行密码记录损坏");
                }
                if (users.containsKey(username)) {
                    throw new IOException("用户文件包含重复账户: " + username);
                }
                users.put(username, parsed);
            }
        }
    }

    private static UserRecord parseRecord(String value) {
        String[] parts = value.split("\\$", -1);
        if (parts.length != 5 || !HASH_ALGORITHM.equals(parts[0])) {
            return null;
        }
        try {
            UserRecord record = new UserRecord();
            record.iterations = Integer.parseInt(parts[1]);
            if (record.iterations < 10_000 || record.iterations > Protocol.PBKDF2_MAX_ITERATIONS) {
                return null;
            }
            record.salt = Base64.getDecoder().decode(parts[2]);
            record.hash = Base64.getDecoder().decode(parts[3]);
            record.createdMillis = Long.parseLong(parts[4]);
            if (record.salt.length != SALT_BYTES || record.hash.length != HASH_BYTES) {
                return null;
            }
            return record;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public boolean exists(String username) {
        synchronized (lock) {
            return users.containsKey(username);
        }
    }

    public void add(String username, char[] password) throws IOException {
        validateUsername(username);
        validatePassword(password);
        synchronized (lock) {
            if (users.containsKey(username)) {
                throw new IOException("账户已存在: " + username);
            }
            byte[] salt = Crypto.randomBytes(SALT_BYTES);
            byte[] hash = Crypto.pbkdf2(password, salt, Protocol.PBKDF2_ITERATIONS, HASH_BYTES);
            UserRecord record = new UserRecord();
            record.iterations = Protocol.PBKDF2_ITERATIONS;
            record.salt = salt;
            record.hash = hash;
            record.createdMillis = System.currentTimeMillis();
            users.put(username, record);
            try {
                save();
            } catch (IOException e) {
                users.remove(username);
                throw e;
            }
        }
    }

    public boolean remove(String username) throws IOException {
        synchronized (lock) {
            UserRecord removed = users.remove(username);
            if (removed == null) {
                return false;
            }
            try {
                save();
            } catch (IOException e) {
                users.put(username, removed);
                throw e;
            }
            return true;
        }
    }

    public void changePassword(String username, char[] password) throws IOException {
        validatePassword(password);
        synchronized (lock) {
            UserRecord old = users.get(username);
            if (old == null) {
                throw new IOException("账户不存在: " + username);
            }
            byte[] salt = Crypto.randomBytes(SALT_BYTES);
            byte[] hash = Crypto.pbkdf2(password, salt, Protocol.PBKDF2_ITERATIONS, HASH_BYTES);
            UserRecord replacement = new UserRecord();
            replacement.iterations = Protocol.PBKDF2_ITERATIONS;
            replacement.salt = salt;
            replacement.hash = hash;
            replacement.createdMillis = old.createdMillis;
            users.put(username, replacement);
            try {
                save();
            } catch (IOException e) {
                users.put(username, old);
                throw e;
            }
        }
    }

    /**
     * Verifies a password. Unknown users still run a PBKDF2 calculation so the
     * response time does not reveal whether an account exists.
     */
    public boolean verify(String username, char[] password) {
        UserRecord record;
        synchronized (lock) {
            record = users.get(username);
        }
        if (record == null) {
            byte[] ignored = Crypto.pbkdf2(password, dummySalt, Protocol.PBKDF2_ITERATIONS, HASH_BYTES);
            Crypto.wipe(ignored);
            return false;
        }
        byte[] candidate = Crypto.pbkdf2(password, record.salt, record.iterations, HASH_BYTES);
        boolean matches = Crypto.constantTimeEquals(candidate, record.hash);
        Crypto.wipe(candidate);
        return matches;
    }

    public int size() {
        synchronized (lock) {
            return users.size();
        }
    }

    public List<UserInfo> list() {
        synchronized (lock) {
            List<UserInfo> result = new ArrayList<UserInfo>();
            for (Map.Entry<String, UserRecord> entry : users.entrySet()) {
                result.add(new UserInfo(entry.getKey(), entry.getValue().createdMillis));
            }
            Collections.sort(result, new java.util.Comparator<UserInfo>() {
                @Override
                public int compare(UserInfo a, UserInfo b) {
                    return a.username.compareToIgnoreCase(b.username);
                }
            });
            return result;
        }
    }

    private void save() throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# SaysProxyV3 users - managed with /add and /passwd, never edit while the server is running.\r\n");
        out.append("# Format: username=pbkdf2-sha256$iterations$saltBase64$hashBase64$createdMillis\r\n");
        for (Map.Entry<String, UserRecord> entry : users.entrySet()) {
            UserRecord record = entry.getValue();
            out.append(entry.getKey()).append('=')
                    .append(HASH_ALGORITHM).append('$')
                    .append(record.iterations).append('$')
                    .append(Base64.getEncoder().encodeToString(record.salt)).append('$')
                    .append(Base64.getEncoder().encodeToString(record.hash)).append('$')
                    .append(record.createdMillis).append("\r\n");
        }
        Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.write(temp, out.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validateUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("用户名必须为 2-32 位字母、数字、点、下划线或短横线，且以字母或数字开头");
        }
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length < 8) {
            throw new IllegalArgumentException("账户密码至少需要 8 个字符");
        }
        if (password.length > 128) {
            throw new IllegalArgumentException("账户密码最多 128 个字符");
        }
        for (char c : password) {
            if (c <= 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("账户密码不能包含空白或控制字符");
            }
        }
    }

    private static final class UserRecord {
        int iterations;
        byte[] salt;
        byte[] hash;
        long createdMillis;
    }

    public static final class UserInfo {
        public final String username;
        public final long createdMillis;

        UserInfo(String username, long createdMillis) {
            this.username = username;
            this.createdMillis = createdMillis;
        }

        public String createdText() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                    .format(new Date(createdMillis));
        }
    }
}
