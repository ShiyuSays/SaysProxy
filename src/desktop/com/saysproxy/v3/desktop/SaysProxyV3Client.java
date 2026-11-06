package com.saysproxy.v3.desktop;

import com.saysproxy.v3.common.LocalProxyServer;
import com.saysproxy.v3.common.LogSink;
import com.saysproxy.v3.common.TunnelClient;
import com.saysproxy.v3.common.TunnelListener;

import javax.swing.BorderFactory;
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
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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

    private volatile TunnelClient tunnel;
    private volatile LocalProxyServer proxyServer;
    private volatile boolean connecting;
    private volatile boolean manualDisconnect;
    private volatile boolean closing;

    public SaysProxyV3Client() {
        super("SaysProxyV3 客户端");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        buildUi();
        bindActions();
        resetToDisconnected();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closing = true;
                disconnect();
                dispose();
            }
        });
        setMinimumSize(new Dimension(720, 620));
        pack();
        setLocationRelativeTo(null);
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
        JLabel hint = new JLabel("服务器连接密码由服务器首次启动时生成，见 config/connection-password.txt。");
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

        connecting = true;
        manualDisconnect = false;
        setInputsEnabled(false);
        connectButton.setEnabled(false);
        disconnectButton.setEnabled(false);
        statusLabel.setText("正在连接…");
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
                            log("连接失败: " + e.getMessage());
                            if (!closing) {
                                JOptionPane.showMessageDialog(SaysProxyV3Client.this,
                                        e.getMessage(), "连接失败", JOptionPane.ERROR_MESSAGE);
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
        log("服务器连接已断开: " + reason);
        JOptionPane.showMessageDialog(this, "与服务器的连接已断开：\n" + reason,
                "连接断开", JOptionPane.WARNING_MESSAGE);
    }

    private void resetToDisconnected() {
        if (connecting) {
            statusLabel.setText("正在连接…");
            setInputsEnabled(false);
            connectButton.setEnabled(false);
            disconnectButton.setEnabled(false);
            copyButton.setEnabled(false);
            proxyAddressField.setText("");
            proxyAddressField.setEnabled(false);
            return;
        }
        statusLabel.setText("未连接");
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
