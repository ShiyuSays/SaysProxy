package com.saysproxy.v3.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One logical byte stream multiplexed over the encrypted control connection.
 * The stream behaves like a socket stream. close() aborts an unfinished
 * stream; after both sides have sent FIN it simply releases the stream.
 */
public final class TunnelStream {
    private static final Object EOF = new Object();
    private static final Object RESET = new Object();

    private final int id;
    private final String host;
    private final int port;
    private final TunnelClient owner;
    private final CountDownLatch opened = new CountDownLatch(1);
    private final LinkedBlockingQueue<Object> incoming = new LinkedBlockingQueue<Object>(256);
    private final InputStream input = new StreamInput();
    private final OutputStream output = new StreamOutput();

    private volatile String openError;
    private volatile String resetReason;
    private volatile boolean closed;
    private volatile boolean localFin;
    private volatile boolean remoteFin;
    private volatile boolean inputFinished;
    private volatile byte[] current;
    private int currentOffset;

    TunnelStream(int id, String host, int port, TunnelClient owner) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.owner = owner;
    }

    public int getId() {
        return id;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public InputStream getInputStream() {
        return input;
    }

    public OutputStream getOutputStream() {
        return output;
    }

    boolean awaitOpen(long timeoutMs) throws IOException {
        try {
            if (!opened.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("连接目标超时: " + host + ":" + port);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("连接被中断", e);
        }
        if (openError != null) {
            throw new IOException(openError);
        }
        return true;
    }

    void onOpenOk() {
        opened.countDown();
    }

    void onOpenError(String message) {
        openError = message == null ? "服务器拒绝连接目标" : message;
        closed = true;
        opened.countDown();
        incoming.clear();
        incoming.offer(RESET);
    }

    void onData(byte[] data) {
        if (closed) {
            return;
        }
        if (!incoming.offer(data)) {
            resetReason = "接收缓冲区已满，连接已重置";
            closed = true;
            incoming.clear();
            incoming.offer(RESET);
            opened.countDown();
            owner.abortStream(this, true);
        }
    }

    void onFin() {
        remoteFin = true;
        inputFinished = true;
        if (!closed && !incoming.offer(EOF)) {
            resetReason = "接收缓冲区已满，连接已重置";
            closed = true;
            incoming.clear();
            incoming.offer(RESET);
            opened.countDown();
            owner.abortStream(this, true);
        }
        if (localFin && remoteFin) {
            closed = true;
            owner.removeStream(id);
        }
    }

    void onReset(String reason) {
        if (closed) {
            opened.countDown();
            return;
        }
        closed = true;
        resetReason = reason;
        openError = openError == null ? reason : openError;
        inputFinished = true;
        opened.countDown();
        incoming.clear();
        incoming.offer(RESET);
        owner.removeStream(id);
    }

    public boolean isClosed() {
        return closed;
    }

    /** Sends FIN. No more application bytes may be written afterwards. */
    public void shutdownOutput() {
        if (localFin || closed) {
            return;
        }
        localFin = true;
        try {
            owner.sendStreamFrame(Protocol.FIN, id, null, 0, 0);
        } catch (IOException e) {
            onReset("发送 FIN 失败: " + e.getMessage());
            return;
        }
        if (remoteFin) {
            closed = true;
            owner.removeStream(id);
        }
    }

    /**
     * Releases the stream. If either direction is still open, a RST is sent so
     * the peer can release its target socket immediately.
     */
    public void close() {
        if (closed) {
            owner.removeStream(id);
            return;
        }
        boolean graceful = localFin && remoteFin;
        closed = true;
        owner.removeStream(id);
        incoming.clear();
        incoming.offer(RESET);
        if (!graceful) {
            try {
                owner.sendStreamFrame(Protocol.RST, id, null, 0, 0);
            } catch (IOException ignored) {
            }
        }
    }

    void abortFromOwner(String reason) {
        onReset(reason);
    }

    private final class StreamInput extends InputStream {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (current != null && currentOffset < current.length) {
                int copy = Math.min(length, current.length - currentOffset);
                System.arraycopy(current, currentOffset, buffer, offset, copy);
                currentOffset += copy;
                if (currentOffset == current.length) {
                    current = null;
                }
                return copy;
            }
            while (true) {
                if (inputFinished && incoming.isEmpty()) {
                    if (resetReason != null) {
                        throw new IOException(resetReason);
                    }
                    return -1;
                }
                if (closed && incoming.isEmpty()) {
                    if (resetReason != null) {
                        throw new IOException(resetReason);
                    }
                    return -1;
                }
                Object item;
                try {
                    item = incoming.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("读取被中断", e);
                }
                if (item == EOF) {
                    return -1;
                }
                if (item == RESET) {
                    if (resetReason != null) {
                        throw new IOException(resetReason);
                    }
                    return -1;
                }
                current = (byte[]) item;
                currentOffset = 0;
                int copy = Math.min(length, current.length);
                System.arraycopy(current, 0, buffer, offset, copy);
                currentOffset = copy;
                if (currentOffset == current.length) {
                    current = null;
                }
                return copy;
            }
        }

        @Override
        public void close() {
            TunnelStream.this.close();
        }
    }

    private final class StreamOutput extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return;
            }
            if (closed || localFin) {
                throw new IOException("流已关闭");
            }
            int remaining = length;
            int position = offset;
            while (remaining > 0) {
                int chunk = Math.min(remaining, Protocol.MAX_DATA_CHUNK);
                owner.sendStreamFrame(Protocol.DATA, id, buffer, position, chunk);
                position += chunk;
                remaining -= chunk;
            }
        }

        @Override
        public void flush() {
            // Frames are flushed by the secure channel on every write.
        }

        @Override
        public void close() {
            shutdownOutput();
        }
    }

    @Override
    public String toString() {
        return host + ":" + port + "#" + id;
    }

    void discard() {
        closed = true;
        incoming.clear();
        incoming.offer(RESET);
        if (current != null) {
            Arrays.fill(current, (byte) 0);
            current = null;
        }
    }
}
