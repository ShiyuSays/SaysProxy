package com.saysproxy.v3.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Small in-memory failed-login rate limiter keyed by client IP. */
public final class LoginLimiter {
    private final int maxFailures;
    private final long banMillis;
    private final long windowMillis;
    private final Map<String, State> states = new HashMap<String, State>();

    public LoginLimiter(int maxFailures, int banMinutes) {
        this.maxFailures = maxFailures;
        this.banMillis = banMinutes * 60_000L;
        this.windowMillis = banMinutes * 60_000L;
    }

    public synchronized boolean isBanned(String ip) {
        State state = states.get(ip);
        if (state == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (state.bannedUntil > now) {
            return true;
        }
        if (state.bannedUntil > 0) {
            states.remove(ip);
            return false;
        }
        if (now - state.lastFailure > windowMillis) {
            states.remove(ip);
        }
        return false;
    }

    /**
     * @return true if this failure triggered a ban.
     */
    public synchronized boolean recordFailure(String ip) {
        long now = System.currentTimeMillis();
        State state = states.get(ip);
        if (state == null) {
            state = new State();
            states.put(ip, state);
        }
        if (now - state.lastFailure > windowMillis) {
            state.failures = 0;
        }
        state.lastFailure = now;
        state.failures++;
        if (state.failures >= maxFailures) {
            state.bannedUntil = now + banMillis;
            state.failures = 0;
            pruneIfNeeded(now);
            return true;
        }
        pruneIfNeeded(now);
        return false;
    }

    public synchronized void recordSuccess(String ip) {
        states.remove(ip);
    }

    public synchronized long remainingBanMillis(String ip) {
        State state = states.get(ip);
        if (state == null) {
            return 0;
        }
        return Math.max(0, state.bannedUntil - System.currentTimeMillis());
    }

    private void pruneIfNeeded(long now) {
        if (states.size() < 10_000) {
            return;
        }
        Iterator<Map.Entry<String, State>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            State state = iterator.next().getValue();
            if (state.bannedUntil <= now && now - state.lastFailure > windowMillis) {
                iterator.remove();
            }
        }
        if (states.size() > 20_000) {
            states.clear();
        }
    }

    private static final class State {
        int failures;
        long lastFailure;
        long bannedUntil;
    }
}
