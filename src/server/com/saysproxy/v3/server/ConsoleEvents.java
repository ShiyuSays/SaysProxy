package com.saysproxy.v3.server;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Live event output for the server terminal. File logs remain the authoritative
 * audit trail; this class only mirrors useful connection events to stdout.
 *
 * Passwords, salts and hashes are never passed to this class.
 */
public final class ConsoleEvents {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Object LOCK = new Object();

    private ConsoleEvents() {
    }

    public static void print(String message) {
        synchronized (LOCK) {
            System.out.println("[" + LocalDateTime.now().format(TIME) + "] " + message);
            System.out.flush();
        }
    }
}
