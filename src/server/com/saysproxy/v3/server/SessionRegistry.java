package com.saysproxy.v3.server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Enforces simultaneous-session, per-user IP and per-IP limits. All checks and
 * updates happen under one lock so two racing logins cannot bypass a limit.
 */
public final class SessionRegistry {
    private final int maxSessionsPerUser;
    private final int maxIpsPerUser;
    private final int maxSessionsPerIp;
    private final int maxSessionsTotal;

    private final Map<String, Integer> sessionsByUser = new HashMap<String, Integer>();
    private final Map<String, Integer> sessionsByIp = new HashMap<String, Integer>();
    private final Map<String, Integer> sessionsByUserIp = new HashMap<String, Integer>();
    private final Map<String, Set<String>> ipsByUser = new HashMap<String, Set<String>>();
    private int total;

    public SessionRegistry(int maxSessionsPerUser, int maxIpsPerUser, int maxSessionsPerIp,
                           int maxSessionsTotal) {
        this.maxSessionsPerUser = maxSessionsPerUser;
        this.maxIpsPerUser = maxIpsPerUser;
        this.maxSessionsPerIp = maxSessionsPerIp;
        this.maxSessionsTotal = maxSessionsTotal;
    }

    /**
     * @return null on success, otherwise a human-readable rejection reason.
     */
    public synchronized String register(String username, String clientIp) {
        if (total >= maxSessionsTotal) {
            return "服务器连接数已达上限，请稍后再试";
        }
        int userCount = count(sessionsByUser, username);
        if (userCount >= maxSessionsPerUser) {
            return "该账户的并发连接数已达上限";
        }
        int ipCount = count(sessionsByIp, clientIp);
        if (ipCount >= maxSessionsPerIp) {
            return "该 IP 的并发连接数已达上限";
        }
        Set<String> ips = ipsByUser.get(username);
        if (ips == null) {
            ips = new HashSet<String>();
            ipsByUser.put(username, ips);
        }
        if (!ips.contains(clientIp) && ips.size() >= maxIpsPerUser) {
            return "该账户使用的 IP 数量已达上限";
        }
        sessionsByUser.put(username, userCount + 1);
        sessionsByIp.put(clientIp, ipCount + 1);
        String pair = pair(username, clientIp);
        sessionsByUserIp.put(pair, count(sessionsByUserIp, pair) + 1);
        ips.add(clientIp);
        total++;
        return null;
    }

    public synchronized void unregister(String username, String clientIp) {
        decrement(sessionsByUser, username);
        decrement(sessionsByIp, clientIp);
        String pair = pair(username, clientIp);
        decrement(sessionsByUserIp, pair);
        if (count(sessionsByUserIp, pair) <= 0) {
            Set<String> ips = ipsByUser.get(username);
            if (ips != null) {
                ips.remove(clientIp);
                if (ips.isEmpty()) {
                    ipsByUser.remove(username);
                }
            }
        }
        if (total > 0) {
            total--;
        }
    }

    private static void decrement(Map<String, Integer> map, String key) {
        int value = count(map, key) - 1;
        if (value <= 0) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
    }

    private static int count(Map<String, Integer> map, String key) {
        Integer value = map.get(key);
        return value == null ? 0 : value;
    }

    private static String pair(String username, String clientIp) {
        return username + '\u0000' + clientIp;
    }

    public synchronized int getTotal() {
        return total;
    }

    public synchronized int getSessionsForUser(String username) {
        return count(sessionsByUser, username);
    }

    public int getMaxSessionsTotal() {
        return maxSessionsTotal;
    }
}
