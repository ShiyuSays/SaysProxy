package com.saysproxy.v3.server;

import com.saysproxy.v3.common.IoUtil;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes tab-separated, UTF-8 daily log files:
 *
 *   logs/access-YYYY-MM-DD.log  - logins, proxied destinations, client IPs, bytes
 *   logs/audit-YYYY-MM-DD.log   - startup, admin actions, authentication failures
 *
 * Passwords and password material are never logged.
 */
public final class LogManager {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path logDir;
    private final Object lock = new Object();
    private LocalDate accessDate;
    private LocalDate auditDate;
    private Writer accessWriter;
    private Writer auditWriter;
    private boolean accessErrorPrinted;
    private boolean auditErrorPrinted;

    public LogManager(Path logDir) throws IOException {
        this.logDir = logDir;
        Files.createDirectories(logDir);
    }

    public void access(String event, String username, String clientIp, String target, String details) {
        String line = timestamp() + "\t" + safe(event)
                + "\tuser=" + safe(username)
                + "\tip=" + safe(clientIp)
                + (target == null ? "" : "\ttarget=" + safe(target))
                + (details == null || details.isEmpty() ? "" : "\t" + details);
        synchronized (lock) {
            try {
                Writer writer = accessWriterForToday();
                writer.write(line);
                writer.write("\r\n");
                writer.flush();
            } catch (IOException e) {
                if (!accessErrorPrinted) {
                    accessErrorPrinted = true;
                    System.err.println("写入访问日志失败: " + e.getMessage());
                }
            }
        }
    }

    public void audit(String event, String actor, String clientIp, String details) {
        String line = timestamp() + "\t" + safe(event)
                + "\tactor=" + safe(actor)
                + "\tip=" + safe(clientIp)
                + (details == null || details.isEmpty() ? "" : "\t" + details);
        synchronized (lock) {
            try {
                Writer writer = auditWriterForToday();
                writer.write(line);
                writer.write("\r\n");
                writer.flush();
            } catch (IOException e) {
                if (!auditErrorPrinted) {
                    auditErrorPrinted = true;
                    System.err.println("写入审计日志失败: " + e.getMessage());
                }
            }
        }
    }

    private Writer accessWriterForToday() throws IOException {
        LocalDate today = LocalDate.now();
        if (accessWriter == null || !today.equals(accessDate)) {
            closeQuietly(accessWriter);
            accessDate = today;
            accessWriter = open(logDir.resolve("access-" + today + ".log"));
        }
        return accessWriter;
    }

    private Writer auditWriterForToday() throws IOException {
        LocalDate today = LocalDate.now();
        if (auditWriter == null || !today.equals(auditDate)) {
            closeQuietly(auditWriter);
            auditDate = today;
            auditWriter = open(logDir.resolve("audit-" + today + ".log"));
        }
        return auditWriter;
    }

    private static Writer open(Path path) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(path.toFile(), true), StandardCharsets.UTF_8), 8192);
    }

    private static String timestamp() {
        return LocalDateTime.now().format(TIME);
    }

    private static String safe(String value) {
        return IoUtil.logSafe(value);
    }

    private static void closeQuietly(Writer writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
        }
    }

    public Path getLogDir() {
        return logDir;
    }

    public void close() {
        synchronized (lock) {
            closeQuietly(accessWriter);
            closeQuietly(auditWriter);
            accessWriter = null;
            auditWriter = null;
        }
    }
}
