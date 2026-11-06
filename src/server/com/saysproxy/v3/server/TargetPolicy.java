package com.saysproxy.v3.server;

import java.io.IOException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves target names on the server and refuses loopback, private, link-local,
 * multicast and documentation ranges by default. Resolution happens once;
 * the returned address is the exact address the server will connect to, which
 * avoids a second DNS lookup and DNS-rebinding race.
 */
public final class TargetPolicy {
    private TargetPolicy() {
    }

    public static InetSocketAddress resolve(String inputHost, int port, boolean allowPrivate)
            throws IOException {
        if (port < 1 || port > 65535) {
            throw new IOException("目标端口无效");
        }
        String host = normalizeHost(inputHost);
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IOException("无法解析目标地址: " + host);
        }
        if (addresses.length == 0) {
            throw new IOException("无法解析目标地址: " + host);
        }
        List<InetAddress> ordered = new ArrayList<InetAddress>(addresses.length);
        // Prefer IPv4: the server may be on a host without IPv6 connectivity.
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address && !blocked(address, allowPrivate)) {
                ordered.add(address);
            }
        }
        for (InetAddress address : addresses) {
            if (!(address instanceof Inet4Address) && !blocked(address, allowPrivate)) {
                ordered.add(address);
            }
        }
        if (ordered.isEmpty()) {
            throw new IOException("目标地址位于禁止访问的网络范围: " + host);
        }
        InetAddress selected = ordered.get(0);
        return new InetSocketAddress(selected, port);
    }

    public static boolean blocked(InetAddress address, boolean allowPrivate) {
        if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (allowPrivate) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            int c = bytes[2] & 0xff;
            // 0.0.0.0/8, 100.64.0.0/10 (CGNAT), 192.0.0.0/24, 198.18.0.0/15,
            // TEST-NET-1/2/3 and 240.0.0.0/4.
            if (a == 0) {
                return true;
            }
            if (a == 100 && b >= 64 && b <= 127) {
                return true;
            }
            if (a == 192 && b == 0 && c == 0) {
                return true;
            }
            if (a == 198 && (b == 18 || b == 19)) {
                return true;
            }
            if (a == 192 && b == 0 && c == 2) {
                return true;
            }
            if (a == 198 && b == 51 && c == 100) {
                return true;
            }
            if (a == 203 && b == 0 && c == 113) {
                return true;
            }
            if (a >= 240) {
                return true;
            }
            return false;
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            // fc00::/7 unique local.
            if ((first & 0xfe) == 0xfc) {
                return true;
            }
            // 2001:db8::/32 documentation.
            if (first == 0x20 && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8) {
                return true;
            }
            // IPv4-mapped addresses are checked via their embedded IPv4 form.
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
                try {
                    byte[] v4 = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
                    return blocked(InetAddress.getByAddress(v4), allowPrivate);
                } catch (UnknownHostException ignored) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static String normalizeHost(String input) throws IOException {
        if (input == null) {
            throw new IOException("目标地址为空");
        }
        String host = input.trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty() || host.length() > 255) {
            throw new IOException("目标地址无效");
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '/' || c == '\\' || c == '@'
                    || c == '#' || c == '?' || c == '%') {
                throw new IOException("目标地址包含非法字符");
            }
        }
        if (host.indexOf(':') < 0) {
            try {
                host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(java.util.Locale.ROOT);
            } catch (IllegalArgumentException e) {
                throw new IOException("目标域名无效");
            }
        }
        if (host.isEmpty() || host.equals(".")) {
            throw new IOException("目标地址无效");
        }
        return host;
    }
}
