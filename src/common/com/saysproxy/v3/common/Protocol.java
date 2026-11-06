package com.saysproxy.v3.common;

/**
 * Wire protocol constants shared by the server and the clients.
 *
 * All multi-byte integers are big-endian. After the handshake every frame is
 * protected with AES-256-GCM. The plaintext frame layout is:
 *
 *   type:1 | streamId:4 | payload:N
 *
 * The outer frame layout is:
 *
 *   ciphertextLength:4 | ciphertext + GCM tag:length
 *
 * Frame sequence numbers are implicit (they start at zero after each
 * handshake), and the GCM nonce is directionPrefix:4 | sequence:8.
 */
public final class Protocol {
    private Protocol() {
    }

    public static final byte[] MAGIC = new byte[]{'S', 'P', 'V', '3'};
    public static final int VERSION = 1;
    public static final int DEFAULT_PORT = 8388;

    /** Maximum size of one encrypted frame, including the GCM tag. */
    public static final int MAX_FRAME_CIPHERTEXT = 64 * 1024;
    /** Maximum number of application bytes carried by one frame. */
    public static final int MAX_DATA_CHUNK = 16 * 1024;
    public static final int MAX_HOST_LENGTH = 255;
    public static final int MAX_USERNAME_LENGTH = 64;
    public static final int MAX_PASSWORD_LENGTH = 256;
    public static final int MAX_STREAMS_PER_SESSION = 64;

    public static final int CONNECT_TIMEOUT_MS = 15_000;
    public static final int HANDSHAKE_TIMEOUT_MS = 20_000;
    public static final int OPEN_TIMEOUT_MS = 20_000;
    public static final int PING_INTERVAL_MS = 30_000;
    public static final int PBKDF2_ITERATIONS = 120_000;
    public static final int PBKDF2_MAX_ITERATIONS = 500_000;

    // Control frames (streamId 0).
    public static final byte LOGIN = 1;
    public static final byte AUTH_OK = 2;
    public static final byte AUTH_FAIL = 3;
    public static final byte PING = 10;
    public static final byte PONG = 11;

    // Stream frames.
    public static final byte OPEN = 20;
    public static final byte OPEN_OK = 21;
    public static final byte OPEN_ERR = 22;
    public static final byte DATA = 23;
    /** Sender will not send more bytes on this stream (half close). */
    public static final byte FIN = 24;
    /** Abort the stream immediately. */
    public static final byte RST = 25;

    public static String frameName(byte type) {
        switch (type) {
            case LOGIN:
                return "LOGIN";
            case AUTH_OK:
                return "AUTH_OK";
            case AUTH_FAIL:
                return "AUTH_FAIL";
            case PING:
                return "PING";
            case PONG:
                return "PONG";
            case OPEN:
                return "OPEN";
            case OPEN_OK:
                return "OPEN_OK";
            case OPEN_ERR:
                return "OPEN_ERR";
            case DATA:
                return "DATA";
            case FIN:
                return "FIN";
            case RST:
                return "RST";
            default:
                return "UNKNOWN(" + type + ")";
        }
    }
}
