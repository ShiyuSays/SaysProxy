package com.saysproxy.v3.android;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Single-screen Android client using only platform widgets. */
public final class MainActivity extends Activity implements ProxyBus.Listener {
    private static final int LOCAL_PORT = 1080;
    private static final int REQUEST_NOTIFICATIONS = 41;
    private static final String PREFS_NAME = "saysproxy_client";
    private static final String PREF_SERVER = "server";
    private static final String PREF_PORT = "port";
    private static final String PREF_CONNECTION_PASSWORD = "connection_password";
    private static final String PREF_USERNAME = "username";

    private EditText serverField;
    private EditText portField;
    private EditText connectionPasswordField;
    private EditText usernameField;
    private EditText passwordField;
    private Button connectButton;
    private Button disconnectButton;
    private TextView statusView;
    private TextView proxyView;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadSavedSettings();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(18));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("SaysProxyV3");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("加密 TCP 代理 · Android 客户端");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.rgb(80, 80, 80));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.bottomMargin = dp(14);
        root.addView(subtitle, subtitleParams);

        serverField = addField(root, "服务器地址", "例如 1.2.3.4 或 proxy.example.com",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        portField = addField(root, "服务器端口", "8388", InputType.TYPE_CLASS_NUMBER);
        portField.setText("8388");
        connectionPasswordField = addField(root, "服务器连接密码", "服务器端生成的连接密码",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        usernameField = addField(root, "账户名称", "服务器 /add 添加的账户",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        passwordField = addField(root, "账户密码", "账户密码",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        TextView localInfo = new TextView(this);
        localInfo.setText("本地代理（固定）：127.0.0.1:" + LOCAL_PORT);
        localInfo.setTextSize(13);
        localInfo.setTextColor(Color.rgb(40, 80, 140));
        localInfo.setPadding(0, dp(2), 0, dp(10));
        root.addView(localInfo);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        connectButton = new Button(this);
        connectButton.setText("连接");
        disconnectButton = new Button(this);
        disconnectButton.setText("断开");
        disconnectButton.setEnabled(false);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        buttonParams.rightMargin = dp(6);
        buttonRow.addView(connectButton, buttonParams);
        buttonRow.addView(disconnectButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttonRow);

        statusView = new TextView(this);
        statusView.setText("未连接");
        statusView.setTextSize(14);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setPadding(0, dp(12), 0, dp(6));
        root.addView(statusView);

        proxyView = new TextView(this);
        proxyView.setText("连接成功后，本地代理为 127.0.0.1:" + LOCAL_PORT);
        proxyView.setTextSize(13);
        proxyView.setTextIsSelectable(true);
        proxyView.setPadding(0, 0, 0, dp(4));
        root.addView(proxyView);

        Button copyButton = new Button(this);
        copyButton.setText("复制代理地址");
        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("SaysProxyV3 proxy",
                            "127.0.0.1:" + LOCAL_PORT));
                }
                ProxyBus.log("已复制本地代理地址 127.0.0.1:" + LOCAL_PORT);
            }
        });
        root.addView(copyButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView usage = new TextView(this);
        usage.setText("使用方法\n"
                + "1. 连接成功后，本机 127.0.0.1:" + LOCAL_PORT + " 提供 SOCKS5 与 HTTP 代理。\n"
                + "2. 支持代理设置的 App 可直接填 127.0.0.1:" + LOCAL_PORT + "。\n"
                + "3. Android 系统可在 Wi-Fi 的高级设置里填写 HTTP 代理；不同厂商系统可能限制回环地址。\n"
                + "4. 本版本是本地代理模式，不接管全部 App 流量；未设置代理的 App 不会经过服务器。\n"
                + "5. 服务器地址、连接密码和账户名会自动保存在本机；账户密码不保存。");
        usage.setTextSize(12);
        usage.setTextColor(Color.rgb(70, 70, 70));
        usage.setPadding(0, dp(14), 0, dp(10));
        root.addView(usage);

        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志");
        logTitle.setTextSize(14);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setBackgroundColor(Color.rgb(245, 245, 245));
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView.setMinLines(8);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logParams.topMargin = dp(4);
        root.addView(logView, logParams);

        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connect();
            }
        });
        disconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disconnect();
            }
        });
        return scroll;
    }

    private EditText addField(LinearLayout parent, String label, String hint, int inputType) {
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(13);
        labelView.setPadding(0, dp(8), 0, dp(2));
        parent.addView(labelView);

        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setTextSize(15);
        parent.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private void connect() {
        String server = serverField.getText().toString().trim();
        String portText = portField.getText().toString().trim();
        String connectionPassword = connectionPasswordField.getText().toString();
        String username = usernameField.getText().toString().trim();
        String password = passwordField.getText().toString();
        if (server.isEmpty()) {
            showError("请输入服务器地址");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            showError("服务器端口必须是数字");
            return;
        }
        if (port < 1 || port > 65535) {
            showError("服务器端口必须在 1-65535 之间");
            return;
        }
        if (connectionPassword.isEmpty()) {
            showError("请输入服务器连接密码");
            return;
        }
        if (username.isEmpty()) {
            showError("请输入账户名称");
            return;
        }
        if (password.isEmpty()) {
            showError("请输入账户密码");
            return;
        }
        saveSettings(server, port, connectionPassword, username);
        hideKeyboard();
        requestNotificationPermissionIfNeeded();
        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_CONNECT);
        intent.putExtra(ProxyService.EXTRA_SERVER, server);
        intent.putExtra(ProxyService.EXTRA_PORT, port);
        intent.putExtra(ProxyService.EXTRA_CONNECTION_PASSWORD, connectionPassword);
        intent.putExtra(ProxyService.EXTRA_USERNAME, username);
        intent.putExtra(ProxyService.EXTRA_PASSWORD, password);
        intent.putExtra(ProxyService.EXTRA_LOCAL_PORT, LOCAL_PORT);
        try {
            startForegroundService(intent);
        } catch (RuntimeException e) {
            applyState(ProxyBus.State.ERROR, "无法启动代理服务: " + e.getMessage(), -1);
            return;
        }
        applyState(ProxyBus.State.CONNECTING, "正在连接 " + server + ":" + port, -1);
    }

    private void disconnect() {
        stopService(new Intent(this, ProxyService.class));
        statusView.setText("正在断开…");
        statusView.setTextColor(Color.rgb(230, 126, 0));
        connectButton.setEnabled(false);
        disconnectButton.setEnabled(false);
        setInputsEnabled(false);
    }

    private void showError(String message) {
        statusView.setText("错误: " + message);
        statusView.setTextColor(Color.rgb(183, 28, 28));
    }

    private void loadSavedSettings() {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String server = preferences.getString(PREF_SERVER, "");
        if (server != null && !server.isEmpty()) {
            serverField.setText(server);
        }
        int savedPort = preferences.getInt(PREF_PORT, 0);
        if (savedPort >= 1 && savedPort <= 65535) {
            portField.setText(Integer.toString(savedPort));
        }
        String connectionPassword = preferences.getString(PREF_CONNECTION_PASSWORD, "");
        if (connectionPassword != null && !connectionPassword.isEmpty()) {
            connectionPasswordField.setText(connectionPassword);
        }
        String username = preferences.getString(PREF_USERNAME, "");
        if (username != null && !username.isEmpty()) {
            usernameField.setText(username);
        }
    }

    private void saveSettings(String server, int port, String connectionPassword, String username) {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        preferences.edit()
                .putString(PREF_SERVER, server)
                .putInt(PREF_PORT, port)
                .putString(PREF_CONNECTION_PASSWORD, connectionPassword)
                .putString(PREF_USERNAME, username)
                .apply();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    private void hideKeyboard() {
        View view = getCurrentFocus();
        if (view != null) {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        }
    }

    private void applyState(ProxyBus.State state, String detail, int localPort) {
        switch (state) {
            case CONNECTED:
                statusView.setText("已连接");
                statusView.setTextColor(Color.rgb(27, 94, 32));
                proxyView.setText("SOCKS5 / HTTP：127.0.0.1:" + localPort);
                setInputsEnabled(false);
                connectButton.setEnabled(false);
                disconnectButton.setEnabled(true);
                break;
            case CONNECTING:
                statusView.setText(detail);
                statusView.setTextColor(Color.rgb(230, 126, 0));
                setInputsEnabled(false);
                connectButton.setEnabled(false);
                disconnectButton.setEnabled(false);
                break;
            case ERROR:
                statusView.setText(detail);
                statusView.setTextColor(Color.rgb(183, 28, 28));
                setInputsEnabled(true);
                connectButton.setEnabled(true);
                disconnectButton.setEnabled(false);
                proxyView.setText("本地代理：未启动");
                break;
            case DISCONNECTED:
            default:
                statusView.setText("未连接");
                statusView.setTextColor(Color.rgb(60, 60, 60));
                setInputsEnabled(true);
                connectButton.setEnabled(true);
                disconnectButton.setEnabled(false);
                proxyView.setText("连接成功后，本地代理为 127.0.0.1:" + LOCAL_PORT);
                break;
        }
    }

    private void setInputsEnabled(boolean enabled) {
        serverField.setEnabled(enabled);
        portField.setEnabled(enabled);
        connectionPasswordField.setEnabled(enabled);
        usernameField.setEnabled(enabled);
        passwordField.setEnabled(enabled);
    }

    private void renderLogs(List<String> logs) {
        StringBuilder out = new StringBuilder();
        for (String line : logs) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        logView.setText(out.toString());
    }

    @Override
    public void onState(final ProxyBus.State state, final String detail, final int localPort) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyState(state, detail, localPort);
            }
        });
    }

    @Override
    public void onLog(final String line) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String current = logView.getText().toString();
                if (current.length() > 0) {
                    logView.append("\n" + line);
                } else {
                    logView.setText(line);
                }
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        ProxyBus.Snapshot snapshot = ProxyBus.register(this);
        applyState(snapshot.state, snapshot.detail, snapshot.localPort);
        renderLogs(snapshot.logs);
    }

    @Override
    protected void onStop() {
        ProxyBus.unregister(this);
        super.onStop();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
