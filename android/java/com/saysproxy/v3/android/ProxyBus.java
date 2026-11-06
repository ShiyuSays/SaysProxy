package com.saysproxy.v3.android;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-process status/log bus shared by the foreground proxy service and the
 * activity. The service and activity always run in the same process. Keeping
 * this tiny avoids any AndroidX dependency.
 */
public final class ProxyBus {
    public enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    public interface Listener {
        void onState(State state, String detail, int localPort);

        void onLog(String line);
    }

    public static final class Snapshot {
        public final State state;
        public final String detail;
        public final int localPort;
        public final List<String> logs;

        Snapshot(State state, String detail, int localPort, List<String> logs) {
            this.state = state;
            this.detail = detail;
            this.localPort = localPort;
            this.logs = logs;
        }
    }

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<Listener>();
    private static final List<String> LOGS = new ArrayList<String>();
    private static final int MAX_LOGS = 400;

    private static State state = State.DISCONNECTED;
    private static String detail = "未连接";
    private static int localPort = -1;

    private ProxyBus() {
    }

    public static Snapshot register(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener == null");
        }
        synchronized (LOCK) {
            if (!LISTENERS.contains(listener)) {
                LISTENERS.add(listener);
            }
            return snapshotLocked();
        }
    }

    public static void unregister(Listener listener) {
        synchronized (LOCK) {
            LISTENERS.remove(listener);
        }
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return snapshotLocked();
        }
    }

    private static Snapshot snapshotLocked() {
        return new Snapshot(state, detail, localPort, new ArrayList<String>(LOGS));
    }

    public static State getState() {
        synchronized (LOCK) {
            return state;
        }
    }

    public static void setState(final State newState, final String newDetail, final int newPort) {
        final List<Listener> copy;
        synchronized (LOCK) {
            state = newState;
            detail = newDetail == null ? "" : newDetail;
            localPort = newPort;
            copy = new ArrayList<Listener>(LISTENERS);
        }
        if (copy.isEmpty()) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : copy) {
                    listener.onState(newState, detail, localPort);
                }
            }
        });
    }

    public static void log(final String message) {
        final String line = "[" + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date())
                + "] " + (message == null ? "" : message);
        final List<Listener> copy;
        synchronized (LOCK) {
            LOGS.add(line);
            while (LOGS.size() > MAX_LOGS) {
                LOGS.remove(0);
            }
            copy = new ArrayList<Listener>(LISTENERS);
        }
        if (copy.isEmpty()) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : copy) {
                    listener.onLog(line);
                }
            }
        });
    }

    public static List<String> logs() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<String>(LOGS));
        }
    }

    public static void resetForTests() {
        synchronized (LOCK) {
            LISTENERS.clear();
            LOGS.clear();
            state = State.DISCONNECTED;
            detail = "未连接";
            localPort = -1;
        }
    }
}
