package com.saysproxy.v3.desktop;

import com.saysproxy.v3.common.LocalProxyServer;
import com.saysproxy.v3.common.LogSink;
import com.saysproxy.v3.common.TunnelClient;
import com.saysproxy.v3.common.TunnelListener;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileSystemView;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * SaysProxyV3 desktop client. Deliberately uses only the JDK's Swing widgets.
 *
 * The client exposes a loopback-only mixed SOCKS5/HTTP proxy. Browser or other
 * software should be pointed at 127.0.0.1:localPort after connecting.
 */
public final class SaysProxyV3Client extends JFrame {
    private static final long serialVersionUID = 1L;

    private final JTextField serverField = new JTextField("127.0.0.1", 18);
    private final JTextField portField = new JTextField("8388", 6);
    private final JPasswordField connectionPasswordField = new JPasswordField(20);
    private final JTextField usernameField = new JTextField(16);
    private final JPasswordField accountPasswordField = new JPasswordField(20);
    private final JTextField localPortField = new JTextField("1080", 6);
    private final JButton connectButton = new JButton("连接");
    private final JButton disconnectButton = new JButton("断开");
    private final JLabel statusLabel = new JLabel("未连接");
    private final JTextField proxyAddressField = new JTextField(24);
    private final JButton copyButton = new JButton("复制代理地址");
    private final JTextArea logArea = new JTextArea(12, 70);
    private final JTextArea instructions = new JTextArea();
    private final ClientSettings clientSettings = ClientSettings.forCurrentUser();

    private volatile TunnelClient tunnel;
    private volatile LocalProxyServer proxyServer;
    private volatile boolean connecting;
    private volatile boolean manualDisconnect;
    private volatile boolean closing;
    private volatile TrayIcon trayIcon;
    private volatile boolean trayHintShown;

    public SaysProxyV3Client() {
        super("SaysProxyV3 客户端");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        buildUi();
        bindActions();
        loadSettingsSafely();
        resetToDisconnected();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (trayIcon != null) {
                    hideToTray();
                } else {
                    exitApplication();
                }
            }
        });
        setupTray();
        setMinimumSize(new Dimension(720, 620));
        pack();
        setLocationRelativeTo(null);
    }

    private void setupTray() {
        Image trayImage = loadJavaDefaultIcon();
        if (!SystemTray.isSupported()) {
            log("当前系统不支持系统托盘，关闭窗口将退出程序");
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem showItem = new MenuItem("显示主界面");
            showItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    showWindow();
                }
            });
            MenuItem exitItem = new MenuItem("退出 SaysProxyV3");
            exitItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    exitApplication();
                }
            });
            menu.add(showItem);
            menu.addSeparator();
            menu.add(exitItem);

            TrayIcon icon = new TrayIcon(trayImage, "SaysProxyV3 - 未连接", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    showWindow();
                }
            });
            SystemTray.getSystemTray().add(icon);
            trayIcon = icon;
            log("系统托盘已启用：关闭窗口后程序继续在后台运行，右键托盘图标可退出");
        } catch (AWTException e) {
            trayIcon = null;
            log("创建系统托盘图标失败，关闭窗口将退出程序: " + e.getMessage());
        } catch (RuntimeException e) {
            trayIcon = null;
            log("创建系统托盘图标失败，关闭窗口将退出程序: " + e.getMessage());
        }
    }

    /**
     * Uses the operating system's icon for this Java archive. On Windows this
     * is the standard Java application icon. No custom icon asset is bundled;
     * the main window keeps the platform/AWT default icon.
     */
    private static Image loadJavaDefaultIcon() {
        try {
            File location = new File(SaysProxyV3Client.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (location.isFile()) {
                Icon icon = FileSystemView.getFileSystemView().getSystemIcon(location);
                if (icon != null) {
                    return scaleIcon(icon, 32);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Icon fallback = UIManager.getIcon("OptionPane.informationIcon");
            if (fallback != null) {
                return scaleIcon(fallback, 32);
            }
        } catch (RuntimeException ignored) {
        }
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    private static Image scaleIcon(Icon icon, int size) {
        int width = Math.max(1, icon.getIconWidth());
        int height = Math.max(1, icon.getIconHeight());
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sourceGraphics = source.createGraphics();
        try {
            icon.paintIcon(new JLabel(), sourceGraphics, 0, 0);
        } finally {
            sourceGraphics.dispose();
        }
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, size, size, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private void hideToTray() {
        setVisible(false);
        TrayIcon icon = trayIcon;
        if (icon != null && !trayHintShown) {
            trayHintShown = true;
            icon.displayMessage("SaysProxyV3",
                    "程序已最小化到系统托盘，代理继续运行。右键托盘图标可退出。",
                    TrayIcon.MessageType.INFO);
        }
    }

    private void showWindow() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (closing) {
                    return;
                }
                setVisible(true);
                setExtendedState(getExtendedState() & ~JFrame.ICONIFIED);
                toFront();
                requestFocus();
            }
        });
    }

    private void exitApplication() {
        if (closing) {
            return;
        }
        closing = true;
        disconnect();
        TrayIcon icon = trayIcon;
        trayIcon = null;
        if (icon != null) {
            try {
                SystemTray.getSystemTray().remove(icon);
            } catch (RuntimeException ignored) {
            }
        }
        dispose();
        System.exit(0);
    }

    private void updateTrayTooltip(String state) {
        if (!SwingUtilities.isEventDispatchThread()) {
            final String text = state;
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    updateTrayTooltip(text);
                }
            });
            return;
        }
        TrayIcon icon = trayIcon;
        if (icon != null) {
            icon.setToolTip("SaysProxyV3 - " + (state == null ? "未连接" : state));
        }
    }

    private void buildUi() {
        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(content);

        JLabel title = new JLabel("SaysProxyV3 加密代理客户端");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.add(title, BorderLayout.WEST);
        statusLabel.setBorder(new EmptyBorder(0, 12, 0, 0));
        titlePanel.add(statusLabel, BorderLayout.EAST);
        content.add(titlePanel, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("连接设置"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        addField(form, gbc, 0, "服务器地址", serverField);
        addField(form, gbc, 1, "服务器端口", portField);
        addField(form, gbc, 2, "服务器连接密码", connectionPasswordField);
        addField(form, gbc, 3, "账户名称", usernameField);
        addField(form, gbc, 4, "账户密码", accountPasswordField);
        addField(form, gbc, 5, "本地代理端口", localPortField);

        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 2;
        JLabel hint = new JLabel("连接信息自动保存在本机（服务器地址、连接密码、账户名）；账户密码不保存。");
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        form.add(hint, gbc);

        connectButton.setPreferredSize(new Dimension(100, 30));
        disconnectButton.setPreferredSize(new Dimension(100, 30));
        JPanel buttons = new JPanel();
        buttons.add(connectButton);
        buttons.add(disconnectButton);
        gbc.gridy = 7;
        gbc.anchor = GridBagConstraints.WEST;
        form.add(buttons, gbc);

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.add(form, BorderLayout.NORTH);
        JPanel proxyPanel = new JPanel(new BorderLayout(8, 0));
        proxyPanel.setBorder(BorderFactory.createTitledBorder("本机代理地址"));
        proxyAddressField.setEditable(false);
        proxyAddressField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        proxyPanel.add(proxyAddressField, BorderLayout.CENTER);
        proxyPanel.add(copyButton, BorderLayout.EAST);
        center.add(proxyPanel, BorderLayout.CENTER);
        content.add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        instructions.setEditable(false);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setRows(3);
        instructions.setBorder(BorderFactory.createTitledBorder("使用说明"));
        bottom.add(instructions, BorderLayout.NORTH);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createTitledBorder("运行日志"));
        bottom.add(scroll, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        // Keep the form/instructions visible when the window is resized.
        bottom.setPreferredSize(new Dimension(700, 250));
    }

    private static void addField(JPanel panel, GridBagConstraints gbc, int row, String label,
                                 java.awt.Component field) {
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        field.setMinimumSize(new Dimension(220, 24));
        panel.add(field, gbc);
    }

    private void bindActions() {
        connectButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                connect();
            }
        });
        disconnectButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                disconnect();
            }
        });
        copyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String text = proxyAddressField.getText();
                if (text != null && !text.isEmpty()) {
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new StringSelection(text), null);
                    log("已复制代理地址: " + text);
                }
            }
        });
        getRootPane().setDefaultButton(connectButton);
    }

    private void connect() {
        if (connecting || tunnel != null) {
            return;
        }
        final String serverHost;
        final int serverPort;
        final String connectionPassword;
        final String username;
        final String password;
        final int localPort;
        try {
            serverHost = requireText(serverField.getText(), "服务器地址");
            serverPort = parsePort(requireText(portField.getText(), "服务器端口"), "服务器端口");
            connectionPassword = new String(connectionPasswordField.getPassword());
            if (connectionPassword.isEmpty()) {
                throw new IllegalArgumentException("请输入服务器连接密码");
            }
            username = requireText(usernameField.getText(), "账户名称");
            password = new String(accountPasswordField.getPassword());
            if (password.isEmpty()) {
                throw new IllegalArgumentException("请输入账户密码");
            }
            localPort = parsePort(requireText(localPortField.getText(), "本地代理端口"), "本地代理端口");
            if (localPort < 1024) {
                throw new IllegalArgumentException("本地代理端口请使用 1024-65535，避免权限问题");
            }
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "输入有误", JOptionPane.WARNING_MESSAGE);
            return;
        }

        saveSettingsSafely(serverHost, serverPort, connectionPassword, username, localPort);
        connecting = true;
        manualDisconnect = false;
        setInputsEnabled(false);
        connectButton.setEnabled(false);
        disconnectButton.setEnabled(false);
        statusLabel.setText("正在连接…");
        updateTrayTooltip("正在连接");
        proxyAddressField.setText("");
        log("正在连接 " + serverHost + ":" + serverPort + " …");

        final TunnelListener listener = new TunnelListener() {
            @Override
            public void onLog(final String message) {
                log(message);
            }

            @Override
            public void onDisconnected(final String reason) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        handleRemoteDisconnect(reason);
                    }
                });
            }
        };

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                TunnelClient newTunnel = null;
                LocalProxyServer newProxy = null;
                try {
                    newTunnel = TunnelClient.connect(serverHost, serverPort, connectionPassword,
                            username, password, listener);
                    final TunnelClient connectedTunnel = newTunnel;
                    LogSink proxyLog = new LogSink() {
                        @Override
                        public void log(String message) {
                            SaysProxyV3Client.this.log(message);
                        }
                    };
                    newProxy = new LocalProxyServer(connectedTunnel, localPort, proxyLog);
                    newProxy.start();
                    if (closing || manualDisconnect || !newTunnel.isRunning()) {
                        newProxy.close();
                        newTunnel.disconnect();
                        return;
                    }
                    tunnel = newTunnel;
                    proxyServer = newProxy;
                    final int actualLocalPort = newProxy.getPort();
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            connecting = false;
                            statusLabel.setText("已连接");
                            updateTrayTooltip("已连接 127.0.0.1:" + actualLocalPort);
                            proxyAddressField.setText("SOCKS5 / HTTP  127.0.0.1:" + actualLocalPort);
                            proxyAddressField.setEnabled(true);
                            disconnectButton.setEnabled(true);
                            copyButton.setEnabled(true);
                            instructions.setText(
                                    "在浏览器或其他软件中设置代理：\n"
                                            + "类型：SOCKS5 或 HTTP（支持 HTTPS CONNECT）\n"
                                            + "地址：127.0.0.1    端口：" + actualLocalPort + "\n"
                                            + "客户端只监听本机回环地址，不对外网开放。");
                            log("连接成功，代理已启动: 127.0.0.1:" + actualLocalPort);
                            log("提示：如果软件不支持代理，请使用支持 SOCKS5/HTTP 代理的程序。");
                        }
                    });
                } catch (final Exception e) {
                    final LocalProxyServer cleanupProxy = newProxy;
                    final TunnelClient cleanupTunnel = newTunnel;
                    if (cleanupProxy != null) {
                        cleanupProxy.close();
                    }
                    if (cleanupTunnel != null) {
                        cleanupTunnel.disconnect();
                    }
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            connecting = false;
                            tunnel = null;
                            proxyServer = null;
                            resetToDisconnected();
                            statusLabel.setText("连接失败");
                            updateTrayTooltip("连接失败");
                            log("连接失败: " + e.getMessage());
                            if (!closing) {
                                if (!isVisible() && trayIcon != null) {
                                    trayIcon.displayMessage("连接失败", e.getMessage(),
                                            TrayIcon.MessageType.ERROR);
                                } else {
                                    JOptionPane.showMessageDialog(SaysProxyV3Client.this,
                                            e.getMessage(), "连接失败", JOptionPane.ERROR_MESSAGE);
                                }
                            }
                        }
                    });
                }
            }
        }, "spv3-desktop-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private void disconnect() {
        manualDisconnect = true;
        if (connecting) {
            connecting = false;
        }
        LocalProxyServer proxy = proxyServer;
        proxyServer = null;
        if (proxy != null) {
            proxy.close();
        }
        TunnelClient current = tunnel;
        tunnel = null;
        if (current != null) {
            current.disconnect();
            log("已断开连接");
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (!connecting) {
                    resetToDisconnected();
                }
            }
        });
    }

    private void handleRemoteDisconnect(String reason) {
        if (manualDisconnect || closing) {
            return;
        }
        LocalProxyServer proxy = proxyServer;
        proxyServer = null;
        if (proxy != null) {
            proxy.close();
        }
        tunnel = null;
        connecting = false;
        resetToDisconnected();
        statusLabel.setText("连接已断开");
        updateTrayTooltip("连接已断开");
        log("服务器连接已断开: " + reason);
        if (!isVisible() && trayIcon != null) {
            trayIcon.displayMessage("连接已断开", reason, TrayIcon.MessageType.WARNING);
        } else {
            JOptionPane.showMessageDialog(this, "与服务器的连接已断开：\n" + reason,
                    "连接断开", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void resetToDisconnected() {
        if (connecting) {
            statusLabel.setText("正在连接…");
            updateTrayTooltip("正在连接");
            setInputsEnabled(false);
            connectButton.setEnabled(false);
            disconnectButton.setEnabled(false);
            copyButton.setEnabled(false);
            proxyAddressField.setText("");
            proxyAddressField.setEnabled(false);
            return;
        }
        statusLabel.setText("未连接");
        updateTrayTooltip("未连接");
        setInputsEnabled(tunnel == null);
        connectButton.setEnabled(tunnel == null);
        disconnectButton.setEnabled(tunnel != null);
        copyButton.setEnabled(tunnel != null);
        proxyAddressField.setEnabled(tunnel != null);
        if (tunnel == null) {
            proxyAddressField.setText("");
        }
    }

    private void setInputsEnabled(boolean enabled) {
        serverField.setEnabled(enabled);
        portField.setEnabled(enabled);
        connectionPasswordField.setEnabled(enabled);
        usernameField.setEnabled(enabled);
        accountPasswordField.setEnabled(enabled);
        localPortField.setEnabled(enabled);
    }

    private void loadSettingsSafely() {
        try {
            ClientSettings.Data data = clientSettings.load();
            if (data.serverHost.isEmpty() && data.connectionPassword.isEmpty()
                    && data.username.isEmpty()) {
                return;
            }
            if (!data.serverHost.isEmpty()) {
                serverField.setText(data.serverHost);
            }
            portField.setText(Integer.toString(data.serverPort));
            connectionPasswordField.setText(data.connectionPassword);
            if (!data.username.isEmpty()) {
                usernameField.setText(data.username);
            }
            localPortField.setText(Integer.toString(data.localPort));
            log("已载入上次连接信息（账户密码需重新输入）");
        } catch (IOException e) {
            log("读取本机连接配置失败: " + e.getMessage());
        }
    }

    private void saveSettingsSafely(String serverHost, int serverPort, String connectionPassword,
                                    String username, int localPort) {
        ClientSettings.Data data = new ClientSettings.Data();
        data.serverHost = serverHost;
        data.serverPort = serverPort;
        data.connectionPassword = connectionPassword;
        data.username = username;
        data.localPort = localPort;
        try {
            clientSettings.save(data);
            log("已保存连接信息（不含账户密码）");
        } catch (IOException e) {
            log("保存连接信息失败: " + e.getMessage());
        }
    }

    private void log(final String message) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    log(message);
                }
            });
            return;
        }
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        logArea.append("[" + time + "] " + message + "\n");
        String text = logArea.getText();
        if (text.length() > 80_000) {
            logArea.setText(text.substring(text.length() - 60_000));
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入" + field);
        }
        return value.trim();
    }

    private static int parsePort(String value, String field) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + "必须是 1-65535 的数字");
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new SaysProxyV3Client().setVisible(true);
            }
        });
    }
}
