package com.saysproxy.v3.common;

/** Minimal logger callback so the core has no dependency on a UI toolkit. */
public interface LogSink {
    void log(String message);
}
