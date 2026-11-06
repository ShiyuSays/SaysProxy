package com.saysproxy.v3.common;

/** Callbacks from the encrypted tunnel to the UI/service. */
public interface TunnelListener {
    void onLog(String message);

    void onDisconnected(String reason);
}
