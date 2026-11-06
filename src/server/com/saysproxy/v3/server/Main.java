package com.saysproxy.v3.server;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

/**
 * SaysProxyV3 server entry point.
 *
 * The server is a console application. Accounts are created only from the
 * local console with /add; clients cannot register themselves.
 */
public final class Main {
    public static void main(String[] args) {
        Path home = Paths.get(System.getProperty("user.dir", "."));
        boolean console = true;
        Integer portOverride = null;
        try {
            for (int i = 0; i < args.length; i++) {
                if ("--home".equals(args[i])) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--home 需要一个目录参数");
                    }
                    home = Paths.get(args[++i]);
                } else if ("--port".equals(args[i])) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--port 需要一个端口参数");
                    }
                    portOverride = Integer.valueOf(args[++i]);
                } else if ("--no-console".equals(args[i])) {
                    console = false;
                } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                    printUsage();
                    return;
                } else {
                    throw new IllegalArgumentException("未知参数: " + args[i]);
                }
            }
        } catch (RuntimeException e) {
            System.err.println("参数错误: " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        ServerConfig config;
        UserStore users;
        LogManager logs;
        try {
            config = ServerConfig.load(home);
            if (portOverride != null) {
                if (portOverride < 1 || portOverride > 65535) {
                    throw new IllegalArgumentException("端口必须在 1-65535 之间");
                }
                config.port = portOverride;
            }
            users = new UserStore(config.usersFile);
            logs = new LogManager(config.logDir);
        } catch (Exception e) {
            System.err.println("加载配置失败: " + e.getMessage());
            System.exit(1);
            return;
        }

        ProxyServer server = new ProxyServer(config, users, logs);
        try {
            server.start();
        } catch (Exception e) {
            System.err.println("启动服务器失败: " + e.getMessage());
            try {
                logs.audit("SERVER_START_FAILED", "system", null, "error=" + e.getMessage());
            } catch (RuntimeException ignored) {
            }
            logs.close();
            System.exit(1);
            return;
        }

        printBanner(config, users, server);
        if (config.passwordCreated) {
            System.out.println("首次启动：已生成随机服务器连接密码，请打开上面的密码文件查看。");
            System.out.println("请不要把该密码公开；客户端和服务器必须使用同一个连接密码。");
        }

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    server.stop();
                } finally {
                    logs.close();
                }
            }
        }, "spv3-shutdown"));

        if (console) {
            new AdminConsole(server, users, logs, shutdown).start();
        }

        try {
            shutdown.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("服务器已关闭。");
    }

    private static void printBanner(ServerConfig config, UserStore users, ProxyServer server) {
        System.out.println("============================================================");
        System.out.println("  SaysProxyV3 Server");
        System.out.println("  监听地址 : " + config.bindAddress + ":" + server.getPort());
        System.out.println("  连接密码 : " + config.passwordFile);
        System.out.println("  用户文件 : " + config.usersFile + "  (" + users.size() + " 个用户)");
        System.out.println("  访问日志 : " + config.logDir);
        System.out.println("  并发限制 : 每用户 " + config.maxSessionsPerUser
                + " 会话 / " + config.maxIpsPerUser + " IP；总上限 " + config.maxSessionsTotal);
        System.out.println("  客户端注册功能已禁用，只能在本机控制台使用 /add 添加用户。");
        System.out.println("============================================================");
    }

    private static void printUsage() {
        System.out.println("SaysProxyV3 Server");
        System.out.println("用法: java -jar SaysProxyV3-Server.jar [--home 数据目录] [--port 端口] [--no-console]");
        System.out.println("  --home       配置和日志所在目录，默认为当前目录");
        System.out.println("  --port       临时覆盖监听端口（不会修改配置文件）");
        System.out.println("  --no-console 不启动管理控制台（用于自动化运行）");
    }
}
