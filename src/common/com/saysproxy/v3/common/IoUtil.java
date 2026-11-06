package com.saysproxy.v3.common;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Small binary/string helpers used by the wire protocol. */
public final class IoUtil {
    private IoUtil() {
    }

    public static byte[] readFully(DataInputStream in, int length) throws IOException {
        if (length < 0 || length > Protocol.MAX_FRAME_CIPHERTEXT) {
            throw new IOException("invalid payload length: " + length);
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    public static void writeString(DataOutputStream out, String value, int maxBytes) throws IOException {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        if (data.length > maxBytes) {
            throw new IOException("string too long");
        }
        out.writeShort(data.length);
        out.write(data);
    }

    public static String readString(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readUnsignedShort();
        if (length > maxBytes) {
            throw new IOException("string too long");
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    public static byte[] loginPayload(String username, String password) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        writeString(out, username, Protocol.MAX_USERNAME_LENGTH * 4);
        writeString(out, password, Protocol.MAX_PASSWORD_LENGTH * 4);
        out.flush();
        return buffer.toByteArray();
    }

    public static String[] parseLoginPayload(byte[] payload) throws IOException {
        if (payload == null || payload.length > 4096) {
            throw new IOException("invalid login payload");
        }
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(payload));
        String username = readString(in, Protocol.MAX_USERNAME_LENGTH * 4);
        String password = readString(in, Protocol.MAX_PASSWORD_LENGTH * 4);
        return new String[]{username, password};
    }

    public static byte[] openPayload(String host, int port) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        writeString(out, host, Protocol.MAX_HOST_LENGTH * 4);
        out.writeShort(port);
        out.flush();
        return buffer.toByteArray();
    }

    public static String[] parseOpenPayload(byte[] payload) throws IOException {
        if (payload == null || payload.length > 2048) {
            throw new IOException("invalid open payload");
        }
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(payload));
        String host = readString(in, Protocol.MAX_HOST_LENGTH * 4);
        int port = in.readUnsignedShort();
        return new String[]{host, Integer.toString(port)};
    }

    public static byte[] authPayload(boolean ok, String message) {
        byte[] text = (message == null ? "" : message).getBytes(StandardCharsets.UTF_8);
        int length = Math.min(text.length, 1024);
        byte[] out = new byte[1 + 2 + length];
        out[0] = (byte) (ok ? 1 : 0);
        out[1] = (byte) (length >>> 8);
        out[2] = (byte) length;
        System.arraycopy(text, 0, out, 3, length);
        return out;
    }

    public static String parseAuthPayload(byte[] payload) throws IOException {
        if (payload == null || payload.length < 3) {
            throw new IOException("invalid auth response");
        }
        int length = ((payload[1] & 0xff) << 8) | (payload[2] & 0xff);
        if (length > payload.length - 3) {
            throw new IOException("invalid auth response");
        }
        if (payload[0] != 1) {
            throw new IOException(new String(payload, 3, length, StandardCharsets.UTF_8));
        }
        return new String(payload, 3, length, StandardCharsets.UTF_8);
    }

    public static int readUnsignedByteOrEof(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new EOFException();
        }
        return value;
    }

    public static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static void closeQuietly(java.net.Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static void closeQuietly(OutputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Sanitizes a value before putting it into a tab-separated log line. */
    public static String logSafe(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length() && out.length() < 512; i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\t' || c == '\r' || c == '\n') {
                out.append('?');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
