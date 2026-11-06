package com.saysproxy.v3.common;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated, encrypted frame channel and the SaysProxyV3 handshake.
 *
 * The handshake mixes an ephemeral ECDH secret with a PBKDF2 hash of the
 * server connection password. The server sends an HMAC proof; the client
 * verifies it before sending the account name/password inside the encrypted
 * channel.
 */
public final class SecureChannel {
    private static final String SESSION_INFO = "SaysProxyV3 session v1";
    private static final String PROOF_INFO = "SaysProxyV3 server proof v1";
    private static final int KEY_LENGTH = 32;
    private static final int SALT_LENGTH = 16;

    private final DataInputStream input;
    private final DataOutputStream output;
    private final byte[] outKey;
    private final byte[] inKey;
    private final byte[] outNoncePrefix;
    private final byte[] inNoncePrefix;
    private final Object sendLock = new Object();
    private long sendSequence;
    private long receiveSequence;
    private volatile boolean closed;

    private SecureChannel(DataInputStream input, DataOutputStream output, byte[] outKey,
                          byte[] inKey, byte[] outNoncePrefix, byte[] inNoncePrefix) {
        this.input = input;
        this.output = output;
        this.outKey = outKey;
        this.inKey = inKey;
        this.outNoncePrefix = outNoncePrefix;
        this.inNoncePrefix = inNoncePrefix;
    }

    public static SecureChannel clientHandshake(Socket socket, String password)
            throws IOException {
        if (password == null || password.isEmpty()) {
            throw new IOException("服务器连接密码为空");
        }
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 32 * 1024));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 32 * 1024));

        KeyPair keyPair = Crypto.generateEcKeyPair();
        byte[] clientPublic = Crypto.encodeEcPublicKey(keyPair.getPublic());
        byte[] clientNonce = Crypto.randomBytes(32);

        out.write(Protocol.MAGIC);
        out.writeByte(Protocol.VERSION);
        out.writeShort(clientPublic.length);
        out.write(clientPublic);
        out.write(clientNonce);
        out.flush();

        byte[] magic = readFixed(in, 4);
        if (!Arrays.equals(magic, Protocol.MAGIC)) {
            throw new IOException("服务器响应无效");
        }
        int version = in.readUnsignedByte();
        if (version != Protocol.VERSION) {
            throw new IOException("协议版本不匹配: " + version);
        }
        int status = in.readUnsignedByte();
        if (status != 0) {
            String message = readErrorMessage(in);
            throw new IOException(message);
        }
        int serverPublicLength = in.readUnsignedShort();
        if (serverPublicLength != 65) {
            throw new IOException("服务器公钥长度无效");
        }
        byte[] serverPublic = readFixed(in, serverPublicLength);
        byte[] serverNonce = readFixed(in, 32);
        byte[] salt = readFixed(in, SALT_LENGTH);
        int iterations = in.readInt();
        if (iterations < 10_000 || iterations > Protocol.PBKDF2_MAX_ITERATIONS) {
            throw new IOException("服务器 KDF 参数无效");
        }
        byte[] receivedProof = readFixed(in, 32);

        PublicKey serverKey;
        try {
            serverKey = Crypto.decodeEcPublicKey(serverPublic);
        } catch (RuntimeException e) {
            throw new IOException("服务器公钥无效", e);
        }
        byte[] sharedSecret;
        try {
            sharedSecret = Crypto.ecdh(keyPair.getPrivate(), serverKey);
        } catch (RuntimeException e) {
            throw new IOException("密钥协商失败", e);
        }

        byte[] transcript = transcript(clientPublic, clientNonce, serverPublic, serverNonce, salt, iterations);
        byte[] psk = Crypto.pbkdf2(password.toCharArray(), salt, iterations, 32);
        byte[] prk = Crypto.hmacSha256(psk, sharedSecret);
        byte[] okm = Crypto.hkdfExpand(prk, concat(SESSION_INFO.getBytes(StandardCharsets.UTF_8), transcript), 72);
        byte[] proofKey = Crypto.hkdfExpand(prk, concat(PROOF_INFO.getBytes(StandardCharsets.UTF_8), transcript), 32);
        byte[] expectedProof = Crypto.hmacSha256(proofKey, transcript);

        boolean proofValid = Crypto.constantTimeEquals(expectedProof, receivedProof);
        Crypto.wipe(psk);
        Crypto.wipe(sharedSecret);
        Crypto.wipe(prk);
        Crypto.wipe(proofKey);
        Crypto.wipe(expectedProof);
        if (!proofValid) {
            Crypto.wipe(okm);
            throw new IOException("服务器连接密码错误或服务器身份校验失败");
        }

        byte[] c2sKey = Arrays.copyOfRange(okm, 0, KEY_LENGTH);
        byte[] s2cKey = Arrays.copyOfRange(okm, KEY_LENGTH, KEY_LENGTH * 2);
        byte[] c2sPrefix = Arrays.copyOfRange(okm, KEY_LENGTH * 2, KEY_LENGTH * 2 + 4);
        byte[] s2cPrefix = Arrays.copyOfRange(okm, KEY_LENGTH * 2 + 4, KEY_LENGTH * 2 + 8);
        Crypto.wipe(okm);
        return new SecureChannel(in, out, c2sKey, s2cKey, c2sPrefix, s2cPrefix);
    }

    public static SecureChannel serverHandshake(Socket socket, String password)
            throws IOException {
        if (password == null || password.isEmpty()) {
            throw new IOException("服务器连接密码未配置");
        }
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 32 * 1024));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 32 * 1024));

        byte[] magic = readFixed(in, 4);
        if (!Arrays.equals(magic, Protocol.MAGIC)) {
            throw new IOException("不是 SaysProxyV3 客户端");
        }
        int version = in.readUnsignedByte();
        if (version != Protocol.VERSION) {
            throw new IOException("协议版本不支持: " + version);
        }
        int clientPublicLength = in.readUnsignedShort();
        if (clientPublicLength != 65) {
            sendError(out, "客户端公钥长度无效");
            throw new IOException("客户端公钥长度无效");
        }
        byte[] clientPublic = readFixed(in, clientPublicLength);
        byte[] clientNonce = readFixed(in, 32);

        KeyPair keyPair = Crypto.generateEcKeyPair();
        byte[] serverPublic = Crypto.encodeEcPublicKey(keyPair.getPublic());
        byte[] serverNonce = Crypto.randomBytes(32);
        byte[] salt = Crypto.randomBytes(SALT_LENGTH);
        int iterations = Protocol.PBKDF2_ITERATIONS;
        byte[] transcript = transcript(clientPublic, clientNonce, serverPublic, serverNonce, salt, iterations);

        PublicKey clientKey;
        try {
            clientKey = Crypto.decodeEcPublicKey(clientPublic);
        } catch (RuntimeException e) {
            sendError(out, "客户端公钥无效");
            throw new IOException("客户端公钥无效", e);
        }
        byte[] sharedSecret = Crypto.ecdh(keyPair.getPrivate(), clientKey);
        byte[] psk = Crypto.pbkdf2(password.toCharArray(), salt, iterations, 32);
        byte[] prk = Crypto.hmacSha256(psk, sharedSecret);
        byte[] okm = Crypto.hkdfExpand(prk, concat(SESSION_INFO.getBytes(StandardCharsets.UTF_8), transcript), 72);
        byte[] proofKey = Crypto.hkdfExpand(prk, concat(PROOF_INFO.getBytes(StandardCharsets.UTF_8), transcript), 32);
        byte[] proof = Crypto.hmacSha256(proofKey, transcript);

        Crypto.wipe(psk);
        Crypto.wipe(sharedSecret);
        Crypto.wipe(prk);
        Crypto.wipe(proofKey);

        out.write(Protocol.MAGIC);
        out.writeByte(Protocol.VERSION);
        out.writeByte(0);
        out.writeShort(serverPublic.length);
        out.write(serverPublic);
        out.write(serverNonce);
        out.write(salt);
        out.writeInt(iterations);
        out.write(proof);
        out.flush();

        byte[] c2sKey = Arrays.copyOfRange(okm, 0, KEY_LENGTH);
        byte[] s2cKey = Arrays.copyOfRange(okm, KEY_LENGTH, KEY_LENGTH * 2);
        byte[] c2sPrefix = Arrays.copyOfRange(okm, KEY_LENGTH * 2, KEY_LENGTH * 2 + 4);
        byte[] s2cPrefix = Arrays.copyOfRange(okm, KEY_LENGTH * 2 + 4, KEY_LENGTH * 2 + 8);
        Crypto.wipe(okm);
        Crypto.wipe(proof);
        return new SecureChannel(in, out, s2cKey, c2sKey, s2cPrefix, c2sPrefix);
    }

    private static byte[] transcript(byte[] clientPublic, byte[] clientNonce, byte[] serverPublic,
                                     byte[] serverNonce, byte[] salt, int iterations) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        DataOutputStream out = new DataOutputStream(buffer);
        out.write(Protocol.MAGIC);
        out.writeByte(Protocol.VERSION);
        out.writeShort(clientPublic.length);
        out.write(clientPublic);
        out.writeShort(clientNonce.length);
        out.write(clientNonce);
        out.writeShort(serverPublic.length);
        out.write(serverPublic);
        out.writeShort(serverNonce.length);
        out.write(serverNonce);
        out.writeShort(salt.length);
        out.write(salt);
        out.writeInt(iterations);
        out.flush();
        return buffer.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static void sendError(DataOutputStream out, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        out.write(Protocol.MAGIC);
        out.writeByte(Protocol.VERSION);
        out.writeByte(1);
        out.writeShort(bytes.length);
        out.write(bytes);
        out.flush();
    }

    private static String readErrorMessage(DataInputStream in) {
        try {
            int length = in.readUnsignedShort();
            if (length > 1024) {
                return "服务器拒绝连接";
            }
            byte[] data = readFixed(in, length);
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "服务器拒绝连接";
        }
    }

    private static byte[] readFixed(DataInputStream in, int length) throws IOException {
        if (length < 0 || length > 4096) {
            throw new IOException("invalid field length");
        }
        byte[] bytes = new byte[length];
        try {
            in.readFully(bytes);
        } catch (EOFException e) {
            throw new IOException("连接在握手完成前断开", e);
        }
        return bytes;
    }

    public void sendFrame(byte type, int streamId, byte[] payload, int offset, int length)
            throws IOException {
        if (length < 0 || offset < 0 || offset + length > (payload == null ? 0 : payload.length)) {
            throw new IllegalArgumentException("invalid frame payload");
        }
        if (length > Protocol.MAX_FRAME_CIPHERTEXT - 16 - 5) {
            throw new IOException("frame too large");
        }
        int plainLength = 1 + 4 + length;
        byte[] plain = new byte[plainLength];
        plain[0] = type;
        plain[1] = (byte) (streamId >>> 24);
        plain[2] = (byte) (streamId >>> 16);
        plain[3] = (byte) (streamId >>> 8);
        plain[4] = (byte) streamId;
        if (length > 0) {
            System.arraycopy(payload, offset, plain, 5, length);
        }
        byte[] aad = new byte[4];
        int cipherLength = plainLength + 16;
        aad[0] = (byte) (cipherLength >>> 24);
        aad[1] = (byte) (cipherLength >>> 16);
        aad[2] = (byte) (cipherLength >>> 8);
        aad[3] = (byte) cipherLength;
        byte[] nonce;
        byte[] ciphertext;
        synchronized (sendLock) {
            if (closed) {
                throw new IOException("secure channel closed");
            }
            nonce = Crypto.nonce(outNoncePrefix, sendSequence++);
            ciphertext = Crypto.aesGcmEncrypt(outKey, nonce, aad, plain, 0, plainLength);
            output.writeInt(ciphertext.length);
            output.write(ciphertext);
            output.flush();
        }
        Arrays.fill(plain, (byte) 0);
    }

    public Frame readFrame() throws IOException {
        int length;
        try {
            length = input.readInt();
        } catch (EOFException e) {
            throw new IOException("连接已关闭", e);
        }
        if (length < 16 + 5 || length > Protocol.MAX_FRAME_CIPHERTEXT) {
            throw new IOException("非法帧长度: " + length);
        }
        byte[] ciphertext = IoUtil.readFully(input, length);
        byte[] aad = new byte[4];
        aad[0] = (byte) (length >>> 24);
        aad[1] = (byte) (length >>> 16);
        aad[2] = (byte) (length >>> 8);
        aad[3] = (byte) length;
        byte[] nonce = Crypto.nonce(inNoncePrefix, receiveSequence++);
        byte[] plain;
        try {
            plain = Crypto.aesGcmDecrypt(inKey, nonce, aad, ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IOException("加密帧校验失败（连接密码错误、数据被篡改或已断线）", e);
        }
        if (plain.length < 5) {
            throw new IOException("非法帧内容");
        }
        byte type = plain[0];
        int streamId = ((plain[1] & 0xff) << 24) | ((plain[2] & 0xff) << 16)
                | ((plain[3] & 0xff) << 8) | (plain[4] & 0xff);
        byte[] payload = Arrays.copyOfRange(plain, 5, plain.length);
        Arrays.fill(plain, (byte) 0);
        return new Frame(type, streamId, payload);
    }

    public void close() {
        closed = true;
        try {
            input.close();
        } catch (IOException ignored) {
        }
        try {
            output.close();
        } catch (IOException ignored) {
        }
    }

    public static final class Frame {
        public final byte type;
        public final int streamId;
        public final byte[] payload;

        public Frame(byte type, int streamId, byte[] payload) {
            this.type = type;
            this.streamId = streamId;
            this.payload = payload;
        }
    }
}
