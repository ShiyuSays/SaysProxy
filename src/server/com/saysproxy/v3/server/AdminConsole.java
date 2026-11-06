package com.saysproxy.v3.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Minimal server console. The only way to create accounts is this local
 * console; there is deliberately no client-side registration command.
 */
public final class AdminConsole {
    private final ProxyServer server;
    private final UserStore users;
    private final LogManager logs;
    private final CountDownLatch shutdown;

    public AdminConsole(ProxyServer server, UserStore users, LogManager logs, CountDownLatch shutdown) {
        this.server = server;
        this.users = users;
        this.logs = logs;
        this.shutdown = shutdown;
    }

    public void start() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "spv3-admin-console");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
        printHelp();
        while (server.isRunning()) {
            System.out.print("SaysProxyV3> ");
            System.out.flush();
            String line;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                System.err.println("读取控制台失败: " + e.getMessage());
                return;
            }
            if (line == null) {
                return;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith("/")) {
                System.out.println("命令必须以 / 开头，输入 /help 查看帮助");
                continue;
            }
            String[] parts = line.split("\\s+");
            String command = parts[0].toLowerCase(java.util.Locale.ROOT);
            try {
                if ("/help".equals(command)) {
                    printHelp();
                } else if ("/add".equals(command)) {
                    if (parts.length != 3) {
                        System.out.println("用法: /add 用户名 密码");
                        continue;
                    }
                    users.add(parts[1], parts[2].toCharArray());
                    logs.audit("ADMIN_ADD_USER", "console", null, "user=" + com.saysproxy.v3.common.IoUtil.logSafe(parts[1]));
                    System.out.println("已添加用户: " + parts[1]);
                } else if ("/del".equals(command)) {
                    if (parts.length != 2) {
                        System.out.println("用法: /del 用户名");
                        continue;
                    }
                    boolean removed = users.remove(parts[1]);
                    if (removed) {
                        server.kickUser(parts[1]);
                        logs.audit("ADMIN_DEL_USER", "console", null, "user=" + com.saysproxy.v3.common.IoUtil.logSafe(parts[1]));
                        System.out.println("已删除用户: " + parts[1]);
                    } else {
                        System.out.println("用户不存在: " + parts[1]);
                    }
                } else if ("/passwd".equals(command)) {
                    if (parts.length != 3) {
                        System.out.println("用法: /passwd 用户名 新密码");
                        continue;
                    }
                    users.changePassword(parts[1], parts[2].toCharArray());
                    server.kickUser(parts[1]);
                    logs.audit("ADMIN_CHANGE_PASSWORD", "console", null,
                            "user=" + com.saysproxy.v3.common.IoUtil.logSafe(parts[1]));
                    System.out.println("已更新密码并强制该用户重新登录: " + parts[1]);
                } else if ("/list".equals(command)) {
                    List<UserStore.UserInfo> list = users.list();
                    if (list.isEmpty()) {
                        System.out.println("(没有用户)");
                    } else {
                        System.out.println("用户名\t创建时间\t在线会话");
                        for (UserStore.UserInfo info : list) {
                            System.out.println(info.username + "\t" + info.createdText() + "\t"
                                    + server.getRegistry().getSessionsForUser(info.username));
                        }
                    }
                } else if ("/kick".equals(command)) {
                    if (parts.length != 2) {
                        System.out.println("用法: /kick 用户名");
                        continue;
                    }
                    boolean kicked = server.kickUser(parts[1]);
                    logs.audit("ADMIN_KICK_USER", "console", null,
                            "user=" + com.saysproxy.v3.common.IoUtil.logSafe(parts[1]) + " kicked=" + kicked);
                    System.out.println(kicked ? "已强制下线: " + parts[1] : "该用户当前没有在线会话");
                } else if ("/status".equals(command)) {
                    System.out.println("监听端口: " + server.getPort());
                    System.out.println("已认证会话: " + server.getActiveSessions());
                    System.out.println("用户数: " + users.size());
                } else if ("/stop".equals(command)) {
                    logs.audit("ADMIN_STOP", "console", null, null);
                    System.out.println("正在关闭服务器…");
                    server.stop();
                    shutdown.countDown();
                    return;
                } else {
                    System.out.println("未知命令: " + command + "，输入 /help 查看帮助");
                }
            } catch (IllegalArgumentException e) {
                System.out.println("参数错误: " + e.getMessage());
            } catch (IOException e) {
                System.out.println("操作失败: " + e.getMessage());
            }
        }
    }

    private static void printHelp() {
        System.out.println("可用命令:");
        System.out.println("  /add 用户名 密码      注册账户（密码至少 8 位，无空格）");
        System.out.println("  /del 用户名           删除账户");
        System.out.println("  /passwd 用户名 新密码 修改密码并踢下线");
        System.out.println("  /list                 列出账户");
        System.out.println("  /kick 用户名          强制用户下线");
        System.out.println("  /status               查看运行状态");
        System.out.println("  /stop                 关闭服务器");
        System.out.println("  /help                 显示本帮助");
    }
}
