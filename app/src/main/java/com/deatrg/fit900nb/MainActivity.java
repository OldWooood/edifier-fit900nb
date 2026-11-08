package com.deatrg.fit900nb;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQ_BT = 41;

    private static final int BG = 0xFF0B0E14;
    private static final int CARD = 0xFF151A23;
    private static final int CARD_2 = 0xFF10151D;
    private static final int ROW = 0xFF1C2330;
    private static final int TEXT = 0xFFE8EDF5;
    private static final int MUTED = 0xFF8593A8;
    private static final int FAINT = 0xFF5C6A7E;
    private static final int ACCENT = 0xFF5B8CFF;
    private static final int ACCENT_DIM = 0x335B8CFF;
    private static final int GREEN = 0xFF43D39E;
    private static final int AMBER = 0xFFF0B35B;
    private static final int RED = 0xFFFF6B6B;
    private static final int TRACK = 0xFF252D3B;

    private static final int DEFAULT_ANC_INDEX = 0x10;
    private static final long ANC_SWITCH_COOLDOWN_MS = 2200L;
    private static final long SCENE_SWITCH_COOLDOWN_MS = 1500L;
    private static final long SHUTDOWN_CONFIRM_TIMEOUT_MS = 3000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logs = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final List<ModeRow> modeRows = new ArrayList<>();
    private final List<SceneRow> sceneRows = new ArrayList<>();

    private BluetoothAdapter adapter;
    private SppClient spp;
    private BluetoothDevice selected;
    private boolean connected;
    private int queryGeneration;
    private int clientGeneration;
    private long lastAncSwitchAt;
    private long lastSceneSwitchAt;
    private int ancSwitchToken;
    private int sceneSwitchToken;
    private int pendingAncValue = -1;
    private int pendingSceneMode = -1;
    private boolean shutdownConfirmPending;
    private int shutdownConfirmToken;

    private int batteryPercent = -1;
    private boolean charging;
    private int ancIndex = DEFAULT_ANC_INDEX;
    private int ancValue = -1;
    private int sceneMode = -1;
    private int sceneMenu = -1;
    private String version = "";
    private String btName = "";

    private View statusDot;
    private TextView statusText;
    private TextView deviceText;
    private TextView connectButton;
    private TextView settingsButton;
    private TextView shutdownButton;
    private TextView batteryPill;
    private ProgressBar batteryBar;
    private TextView ancCurrent;
    private ProgressBar ancSpinner;
    private TextView ancIndexText;
    private LinearLayout modeList;
    private TextView sceneCurrent;
    private ProgressBar sceneSpinner;
    private LinearLayout sceneList;
    private TextView infoText;
    private LinearLayout debugCard;
    private TextView logView;
    private ScrollView logScroll;
    private EditText consoleInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);

        adapter = BluetoothAdapter.getDefaultAdapter();
        buildUi();
        ensureBtPermission();
        refreshTargetDevice();
        appendLog("SYS", "Fit900NB 控制台已启动");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasBtPermission()) refreshTargetDevice();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTask: launcher taps reuse this instance instead of creating a
        // second MainActivity with its own (disconnected) SppClient.
        if (!connected && hasBtPermission()) refreshTargetDevice();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (spp != null) spp.close();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        // Android 15+ draws edge-to-edge by default; keep content clear of the bars.
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(),
                    0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(40));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        header.addView(titleBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        titleBox.addView(text("Fit900NB", 30, TEXT, Typeface.BOLD));
        TextView subtitle = text("EDIFIER · 轻量蓝牙控制台", 12, MUTED, Typeface.NORMAL);
        subtitle.setPadding(0, dp(3), 0, 0);
        titleBox.addView(subtitle);

        TextView badge = text("SPP", 10, ACCENT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setLetterSpacing(0.12f);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(bg(ACCENT_DIM, 20));
        badge.setOnLongClickListener(v -> {
            toggleDebugCard();
            return true;
        });
        header.addView(badge);

        // Connection card
        LinearLayout connectionCard = card();
        root.addView(connectionCard, cardParams(16));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        connectionCard.addView(statusRow);

        statusDot = new View(this);
        statusDot.setBackground(oval(FAINT));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.setMargins(0, 0, dp(12), 0);
        statusRow.addView(statusDot, dotLp);

        LinearLayout statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusRow.addView(statusBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusText = text("未连接", 15, TEXT, Typeface.BOLD);
        statusBox.addView(statusText);
        deviceText = text("正在查找已配对的耳机…", 11, MUTED, Typeface.NORMAL);
        deviceText.setPadding(0, dp(3), 0, 0);
        statusBox.addView(deviceText);

        batteryPill = text("—", 12, MUTED, Typeface.BOLD);
        batteryPill.setGravity(Gravity.CENTER);
        batteryPill.setPadding(dp(10), dp(6), dp(10), dp(6));
        batteryPill.setBackground(bg(ROW, 12));
        batteryPill.setVisibility(View.GONE);
        batteryPill.setOnClickListener(v -> queryBattery());
        LinearLayout.LayoutParams batteryPillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        batteryPillLp.setMargins(dp(10), 0, 0, 0);
        statusRow.addView(batteryPill, batteryPillLp);

        batteryBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        batteryBar.setMax(100);
        batteryBar.setProgress(0);
        batteryBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        batteryBar.setProgressBackgroundTintList(ColorStateList.valueOf(TRACK));
        batteryBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams batteryBarLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        batteryBarLp.topMargin = dp(12);
        connectionCard.addView(batteryBar, batteryBarLp);

        connectButton = action("连接耳机", true);
        connectButton.setOnClickListener(v -> onConnectClicked());
        connectionCard.addView(connectButton, btnParams(14));

        settingsButton = action("打开系统蓝牙设置", false);
        settingsButton.setOnClickListener(v -> openBluetoothSettings());
        connectionCard.addView(settingsButton, btnParams(8));

        shutdownButton = dangerAction("关闭耳机电源");
        shutdownButton.setVisibility(View.GONE);
        shutdownButton.setOnClickListener(v -> onShutdownClicked());
        connectionCard.addView(shutdownButton, btnParams(8));

        // Noise card
        LinearLayout noiseCard = card();
        root.addView(noiseCard, cardParams(14));

        LinearLayout noiseHeader = new LinearLayout(this);
        noiseHeader.setOrientation(LinearLayout.HORIZONTAL);
        noiseHeader.setGravity(Gravity.CENTER_VERTICAL);
        noiseCard.addView(noiseHeader);
        noiseHeader.addView(text("降噪模式", 16, TEXT, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ancCurrent = text("未获取", 11, MUTED, Typeface.NORMAL);
        noiseHeader.addView(ancCurrent);

        ancSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        ancSpinner.setIndeterminate(true);
        ancSpinner.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        ancSpinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        spinnerLp.setMargins(dp(8), 0, 0, 0);
        noiseHeader.addView(ancSpinner, spinnerLp);

        HorizontalScrollView modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        modeList = new LinearLayout(this);
        modeList.setOrientation(LinearLayout.HORIZONTAL);
        modeScroll.addView(modeList);
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeLp.topMargin = dp(14);
        noiseCard.addView(modeScroll, modeLp);

        LinearLayout ancFooter = new LinearLayout(this);
        ancFooter.setOrientation(LinearLayout.HORIZONTAL);
        ancFooter.setGravity(Gravity.CENTER_VERTICAL);
        ancFooter.setPadding(0, dp(12), 0, 0);
        noiseCard.addView(ancFooter);

        ancIndexText = text("索引 0x" + hex2(ancIndex), 10, FAINT, Typeface.NORMAL);
        ancIndexText.setPadding(dp(8), dp(5), dp(8), dp(5));
        ancIndexText.setBackground(bg(ROW, 20));
        ancIndexText.setOnClickListener(v -> showAncIndexDialog());
        ancFooter.addView(ancIndexText);
        TextView ancHint = text("点击索引可手动覆盖", 10, FAINT, Typeface.NORMAL);
        ancHint.setPadding(dp(8), 0, 0, 0);
        ancFooter.addView(ancHint);

        // Scene modes: music / game / spatial
        LinearLayout sceneCard = card();
        root.addView(sceneCard, cardParams(14));

        LinearLayout sceneHeader = new LinearLayout(this);
        sceneHeader.setOrientation(LinearLayout.HORIZONTAL);
        sceneHeader.setGravity(Gravity.CENTER_VERTICAL);
        sceneCard.addView(sceneHeader);
        sceneHeader.addView(text("模式", 16, TEXT, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sceneCurrent = text("未获取", 11, MUTED, Typeface.NORMAL);
        sceneHeader.addView(sceneCurrent);

        sceneSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        sceneSpinner.setIndeterminate(true);
        sceneSpinner.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        sceneSpinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams sceneSpinnerLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        sceneSpinnerLp.setMargins(dp(8), 0, 0, 0);
        sceneHeader.addView(sceneSpinner, sceneSpinnerLp);

        sceneList = new LinearLayout(this);
        sceneList.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sceneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sceneLp.topMargin = dp(14);
        sceneCard.addView(sceneList, sceneLp);

        TextView sceneHint = text("音乐 / 游戏 / 空间音效", 10, FAINT, Typeface.NORMAL);
        sceneHint.setPadding(0, dp(10), 0, 0);
        sceneCard.addView(sceneHint);

        // Info card
        LinearLayout infoCard = card();
        root.addView(infoCard, cardParams(14));
        infoCard.addView(text("设备信息", 16, TEXT, Typeface.BOLD));

        infoText = mono("", 11, MUTED);
        infoText.setLineSpacing(dp(3), 1.15f);
        infoText.setTextIsSelectable(true);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoLp.topMargin = dp(12);
        infoCard.addView(infoText, infoLp);
        updateInfo();

        // Debug card (hidden; long-press the SPP badge to reveal)
        debugCard = card();
        root.addView(debugCard, cardParams(14));
        debugCard.setVisibility(View.GONE);
        debugCard.addView(text("高级 / 调试", 16, TEXT, Typeface.BOLD));

        TextView debugHint = text(
                "输入十六进制命令。例：CC 或 C1 10 03 或完整帧 AA EC ...",
                10, FAINT, Typeface.NORMAL);
        debugHint.setPadding(0, dp(6), 0, dp(10));
        debugCard.addView(debugHint);

        consoleInput = new EditText(this);
        consoleInput.setHint("CC");
        consoleInput.setHintTextColor(FAINT);
        consoleInput.setTextColor(TEXT);
        consoleInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        consoleInput.setTypeface(Typeface.MONOSPACE);
        consoleInput.setSingleLine(true);
        consoleInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        consoleInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        consoleInput.setBackground(bg(CARD_2, 12));
        debugCard.addView(consoleInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout consoleButtons = new LinearLayout(this);
        consoleButtons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams consoleBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        consoleBtnLp.topMargin = dp(10);
        debugCard.addView(consoleButtons, consoleBtnLp);

        TextView sendButton = action("发送", true);
        sendButton.setOnClickListener(v -> sendConsole());
        consoleButtons.addView(sendButton, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView queryButton = action("查询全部", false);
        queryButton.setOnClickListener(v -> queryAll());
        LinearLayout.LayoutParams queryLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        queryLp.setMargins(dp(8), 0, 0, 0);
        consoleButtons.addView(queryButton, queryLp);

        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(10), 0, 0);
        chipsScroll.addView(chips);
        debugCard.addView(chipsScroll);

        addChip(chips, "D8 能力", Protocol.CMD_FUNCTION);
        addChip(chips, "08 模式", Protocol.CMD_SCENE_QUERY);
        addChip(chips, "CC 降噪", Protocol.CMD_ANC_QUERY);
        addChip(chips, "D0 电量", Protocol.CMD_BATTERY);
        addChip(chips, "F2 状态", Protocol.CMD_DEVICE_STATE);
        addChip(chips, "C6 版本", Protocol.CMD_VERSION);

        logView = mono("", 10, 0xFF9FB0C7);
        logView.setLineSpacing(dp(2), 1.1f);
        logView.setTextIsSelectable(false);
        logScroll = new ScrollView(this);
        logScroll.setBackground(bg(0xFF0F131A, 12));
        logScroll.setPadding(dp(10), dp(10), dp(10), dp(10));
        logScroll.setFocusable(false);
        logScroll.setFocusableInTouchMode(false);
        logScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        logScroll.addView(logView);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        logLp.topMargin = dp(12);
        debugCard.addView(logScroll, logLp);

        TextView copy = action("复制日志", false);
        copy.setOnClickListener(v -> copyLog());
        debugCard.addView(copy, btnParams(10));

        TextView footer = text("协议：Edifier SPP v2 · 明文 · CRC8(sum) · 非官方 App",
                9, FAINT, Typeface.NORMAL);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin = dp(22);
        root.addView(footer, footerLp);

        rebuildModes();
        updateAncUi();
        rebuildScenes();
        updateSceneUi();

        // The EditText would otherwise grab focus and scroll the page to the
        // debug section as soon as the activity opens.
        consoleInput.clearFocus();
        scroll.requestFocus();
        scroll.post(() -> scroll.scrollTo(0, 0));
        scroll.postDelayed(() -> {
            consoleInput.clearFocus();
            scroll.scrollTo(0, 0);
        }, 350);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(bg(CARD, 20));
        return card;
    }

    private LinearLayout.LayoutParams cardParams(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private LinearLayout.LayoutParams btnParams(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private TextView text(String value, float sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView mono(String value, float sp, int color) {
        TextView view = text(value, sp, color, Typeface.NORMAL);
        view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    private TextView action(String label, boolean primary) {
        TextView view = text(label, 13, primary ? Color.WHITE : TEXT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setBackground(bg(primary ? ACCENT : ROW, 14));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private TextView dangerAction(String label) {
        TextView view = text(label, 13, RED, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setBackground(bg(0xFF2A171B, 14));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private GradientDrawable bg(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String hex2(int value) {
        return String.format(Locale.US, "%02X", value & 0xFF);
    }

    // ------------------------------------------------------------ devices

    private boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureBtPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBtPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                refreshTargetDevice();
            } else {
                toast("需要蓝牙权限才能连接耳机");
                statusText.setText("缺少蓝牙权限");
                deviceText.setText("请在系统设置中允许“附近的设备”权限");
            }
        }
    }

    private void refreshTargetDevice() {
        if (connected) return;

        if (adapter == null) {
            selected = null;
            statusText.setText("蓝牙不可用");
            deviceText.setText("这台设备不支持蓝牙");
            setConnectAvailable(false);
            return;
        }
        if (!hasBtPermission()) {
            selected = null;
            statusText.setText("需要蓝牙权限");
            deviceText.setText("请允许“附近的设备”权限");
            setConnectAvailable(false);
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                selected = null;
                statusText.setText("蓝牙未开启");
                deviceText.setText("请先打开系统蓝牙");
                statusDot.setBackground(oval(AMBER));
                setConnectAvailable(false);
                return;
            }
            BluetoothDevice found = null;
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (isTarget(device)) {
                    found = device;
                    break;
                }
            }
            selected = found;
            if (found != null) {
                statusText.setText("未连接");
                statusDot.setBackground(oval(FAINT));
                deviceText.setText(safeName(found) + " · " + safeAddress(found));
                setConnectAvailable(true);
            } else {
                statusText.setText("未找到耳机");
                statusDot.setBackground(oval(AMBER));
                deviceText.setText("请先在系统蓝牙中配对 EDIFIER Fit900NB");
                setConnectAvailable(false);
            }
        } catch (SecurityException e) {
            selected = null;
            statusText.setText("需要蓝牙权限");
            deviceText.setText("无法读取已配对设备");
            setConnectAvailable(false);
        }
    }

    private void setConnectAvailable(boolean available) {
        if (connectButton == null) return;
        connectButton.setClickable(available);
        connectButton.setAlpha(available ? 1f : 0.55f);
    }

    private boolean isTarget(BluetoothDevice device) {
        String name = safeName(device).toUpperCase(Locale.ROOT);
        return name.contains("EDIFIER") || name.contains("FIT900");
    }

    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.isEmpty() ? "未知设备" : name;
        } catch (SecurityException e) {
            return "未知设备";
        }
    }

    private String safeAddress(BluetoothDevice device) {
        try {
            return device.getAddress();
        } catch (SecurityException e) {
            return "??:??:??:??:??:??";
        }
    }

    private void openBluetoothSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception e) {
            toast("无法打开系统蓝牙设置");
        }
    }

    // ---------------------------------------------------------- connection

    private void onConnectClicked() {
        if (connected) {
            if (spp != null) spp.close();
            handleConnectionState("disconnected", "已断开连接");
            return;
        }
        if (!hasBtPermission()) {
            ensureBtPermission();
            return;
        }
        if (selected == null) {
            toast("请先在系统蓝牙中配对 EDIFIER Fit900NB");
            refreshTargetDevice();
            return;
        }
        connect(selected);
    }

    private void connect(BluetoothDevice device) {
        if (!hasBtPermission()) {
            ensureBtPermission();
            return;
        }
        if (spp != null) {
            spp.close();
            spp = null;
        }
        selected = device;
        connected = false;
        queryGeneration++;
        batteryPercent = -1;
        charging = false;
        ancValue = -1;
        sceneMode = -1;
        sceneMenu = -1;
        lastAncSwitchAt = 0;
        lastSceneSwitchAt = 0;
        ancSwitchToken++;
        sceneSwitchToken++;
        pendingAncValue = -1;
        pendingSceneMode = -1;
        showAncSpinner(false);
        showSceneSpinner(false);
        setModeButtonsEnabled(true);
        setSceneButtonsEnabled(true);
        version = "";
        btName = "";
        updateBatteryUi();
        setBatteryVisible(false);
        updateAncUi();
        updateSceneUi();
        updateInfo();

        statusText.setText("正在连接");
        statusDot.setBackground(oval(AMBER));
        deviceText.setText(safeName(device) + " · " + safeAddress(device));
        connectButton.setText("连接中…");
        connectButton.setAlpha(0.65f);
        connectButton.setClickable(false);
        resetShutdownButton();
        if (shutdownButton != null) shutdownButton.setVisibility(View.GONE);

        final int generation = ++clientGeneration;
        spp = new SppClient(adapter, device, new SppClient.Listener() {
            @Override
            public void onState(String state, String message) {
                ui.post(() -> {
                    if (generation == clientGeneration) handleConnectionState(state, message);
                });
            }

            @Override
            public void onFrame(Protocol.Frame frame) {
                ui.post(() -> {
                    if (generation == clientGeneration) handleFrame(frame);
                });
            }

            @Override
            public void onLog(String message) {
                ui.post(() -> {
                    if (generation == clientGeneration) appendLog("SYS", message);
                });
            }
        });
        spp.connect();
    }

    private void handleConnectionState(String state, String message) {
        if ("connected".equals(state)) {
            connected = true;
            statusText.setText("已连接");
            statusDot.setBackground(oval(GREEN));
            connectButton.setText("断开连接");
            connectButton.setAlpha(1f);
            connectButton.setClickable(true);
            if (selected != null) {
                deviceText.setText(safeName(selected) + " · " + safeAddress(selected));
            }
            setBatteryVisible(true);
            setModeButtonsEnabled(true);
            setSceneButtonsEnabled(true);
            resetShutdownButton();
            if (shutdownButton != null) shutdownButton.setVisibility(View.VISIBLE);
            appendLog("SYS", message);
            queryAll();
        } else if ("connecting".equals(state)) {
            statusText.setText("正在连接");
            statusDot.setBackground(oval(AMBER));
            appendLog("SYS", message);
        } else {
            connected = false;
            statusText.setText("未连接");
            statusDot.setBackground(oval(FAINT));
            connectButton.setText("连接耳机");
            connectButton.setAlpha(1f);
            connectButton.setClickable(true);
            appendLog("SYS", message);
            setBatteryVisible(false);
            ancSwitchToken++;
            pendingAncValue = -1;
            showAncSpinner(false);
            setModeButtonsEnabled(true);
            sceneSwitchToken++;
            pendingSceneMode = -1;
            showSceneSpinner(false);
            setSceneButtonsEnabled(true);
            resetShutdownButton();
            if (shutdownButton != null) shutdownButton.setVisibility(View.GONE);
            refreshTargetDevice();
        }
    }

    private void toggleDebugCard() {
        if (debugCard == null) return;
        boolean show = debugCard.getVisibility() != View.VISIBLE;
        debugCard.setVisibility(show ? View.VISIBLE : View.GONE);
        toast(show ? "调试面板已显示" : "调试面板已隐藏");
    }

    private void queryAll() {
        if (!connected) {
            toast("请先连接耳机");
            return;
        }
        final int generation = ++queryGeneration;
        final int[] commands = {
                Protocol.CMD_FUNCTION,
                Protocol.CMD_ANC_QUERY,
                Protocol.CMD_BATTERY,
                Protocol.CMD_DEVICE_STATE,
                Protocol.CMD_VERSION,
                Protocol.CMD_NAME,
                Protocol.CMD_SCENE_QUERY
        };
        for (int i = 0; i < commands.length; i++) {
            final int cmd = commands[i];
            ui.postDelayed(() -> {
                if (connected && generation == queryGeneration) {
                    sendCommand(cmd, new byte[0]);
                }
            }, 180L + i * 330L);
        }
    }

    private void queryBattery() {
        if (!connected) {
            toast("请先连接耳机");
            return;
        }
        sendCommand(Protocol.CMD_BATTERY, new byte[0]);
        ui.postDelayed(() -> sendCommand(Protocol.CMD_DEVICE_STATE, new byte[0]), 260);
    }

    private void onShutdownClicked() {
        if (!connected) {
            toast("请先连接耳机");
            return;
        }
        if (!shutdownConfirmPending) {
            shutdownConfirmPending = true;
            final int token = ++shutdownConfirmToken;
            shutdownButton.setText("再次点击确认关机");
            shutdownButton.setTextColor(Color.WHITE);
            shutdownButton.setBackground(bg(0xFF5A1F2A, 14));
            ui.postDelayed(() -> {
                if (token == shutdownConfirmToken && shutdownConfirmPending) {
                    resetShutdownButton();
                }
            }, SHUTDOWN_CONFIRM_TIMEOUT_MS);
            return;
        }

        shutdownConfirmPending = false;
        shutdownConfirmToken++;
        shutdownButton.setEnabled(false);
        shutdownButton.setAlpha(0.6f);
        shutdownButton.setText("正在关机…");
        sendCommand(Protocol.CMD_SHUTDOWN, new byte[0]);
        appendLog("SYS", "已发送关机命令");
        toast("关机命令已发送");

        ui.postDelayed(() -> {
            if (connected) resetShutdownButton();
        }, 4000);
    }

    private void resetShutdownButton() {
        shutdownConfirmPending = false;
        if (shutdownButton == null) return;
        shutdownButton.setText("关闭耳机电源");
        shutdownButton.setTextColor(RED);
        shutdownButton.setBackground(bg(0xFF2A171B, 14));
        shutdownButton.setEnabled(true);
        shutdownButton.setAlpha(1f);
    }

    private void queryAnc() {
        if (connected) sendCommand(Protocol.CMD_ANC_QUERY, new byte[0]);
    }

    private void sendCommand(int cmd, byte[] payload) {
        if (!connected || spp == null) {
            toast("请先连接耳机");
            return;
        }
        byte[] frame = Protocol.frame(cmd, payload);
        appendLog("TX", Protocol.hex(frame));
        spp.send(frame);
    }

    // --------------------------------------------------------------- frames

    private void handleFrame(Protocol.Frame frame) {
        appendLog("RX", Protocol.hex(frame.raw()));
        switch (frame.cmd) {
            case Protocol.CMD_BATTERY:
                parseBatteryD0(frame.payload);
                break;
            case Protocol.CMD_DEVICE_STATE:
                parseDeviceState(frame.payload);
                break;
            case Protocol.CMD_ANC_QUERY:
            case Protocol.CMD_ANC_SET:
                parseAnc(frame.payload);
                break;
            case Protocol.CMD_FUNCTION:
                // Raw D8 payload is already visible in the debug log.
                break;
            case Protocol.CMD_VERSION:
                version = parseVersion(frame.payload);
                updateInfo();
                break;
            case Protocol.CMD_NAME:
                btName = Protocol.ascii(frame.payload);
                updateInfo();
                break;
            case Protocol.CMD_SCENE_QUERY:
            case Protocol.CMD_SCENE_SET:
                parseScene(frame.payload);
                break;
            case Protocol.CMD_EQ_QUERY:
                appendLog("EQ", Protocol.hex(frame.payload));
                break;
            default:
                break;
        }
    }

    private void parseBatteryD0(byte[] payload) {
        if (payload.length == 0) return;
        int value = payload[0] & 0xFF;
        int percent;
        boolean isCharging = charging;
        if (value <= 100) {
            percent = value;
        } else {
            percent = value & 0x7F;
            isCharging = (value & 0x80) != 0;
        }
        if (percent <= 100) {
            setBattery(percent, isCharging);
        }
    }

    private void parseDeviceState(byte[] payload) {
        if (payload.length < 4) return;

        int type = payload[0] & 0xFF;
        boolean isCharging = payload.length > 4 && (payload[4] & 0x0F) == 1;

        if (type == 4) {
            int percent = payload[3] & 0xFF;
            if (percent <= 100) {
                setBattery(percent, isCharging);
            }
            return;
        }

        int left = payload[1] & 0xFF;
        int right = payload[2] & 0xFF;
        int box = payload[3] & 0xFF;
        if (left <= 100 && right <= 100 && (left > 0 || right > 0)) {
            setBattery(Math.max(left, right), isCharging);
        } else if (box <= 100 && box > 0) {
            setBattery(box, isCharging);
        }
    }

    private void setBattery(int percent, boolean isCharging) {
        batteryPercent = Math.max(0, Math.min(100, percent));
        charging = isCharging;
        if (batteryPill != null) {
            batteryPill.setText(batteryPercent + "%" + (isCharging ? " ⚡" : ""));
            batteryPill.setTextColor(ACCENT);
            batteryPill.setBackground(bg(ACCENT_DIM, 12));
        }
        if (batteryBar != null) batteryBar.setProgress(batteryPercent);
    }

    private void updateBatteryUi() {
        if (batteryPill != null) {
            batteryPill.setText(batteryPercent < 0 ? "—" : batteryPercent + "%");
            batteryPill.setTextColor(batteryPercent < 0 ? MUTED : ACCENT);
            batteryPill.setBackground(bg(batteryPercent < 0 ? ROW : ACCENT_DIM, 12));
        }
        if (batteryBar != null) {
            batteryBar.setProgress(batteryPercent < 0 ? 0 : batteryPercent);
        }
    }

    private void setBatteryVisible(boolean visible) {
        if (batteryPill != null) batteryPill.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (batteryBar != null) batteryBar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void parseAnc(byte[] payload) {
        if (payload.length == 0) return;
        int index = ancIndex;
        int value = -1;

        if (payload.length >= 2) {
            int a = payload[0] & 0xFF;
            int b = payload[1] & 0xFF;
            if (a >= 1 && a <= 0x7F && b <= 0x0F) {
                index = a;
                value = b;
            } else {
                value = a;
            }
        } else {
            value = payload[0] & 0xFF;
        }

        if (index <= 0) index = DEFAULT_ANC_INDEX;
        if (index != ancIndex) {
            ancIndex = index;
            rebuildModes();
        }
        ancValue = value;
        if (pendingAncValue >= 0 && value == pendingAncValue) {
            pendingAncValue = -1;
        }
        updateAncUi();
    }

    private void parseScene(byte[] payload) {
        if (payload.length == 0) return;
        sceneMode = payload[0] & 0xFF;
        sceneMenu = payload.length > 1 ? payload[1] & 0xFF : -1;
        if (pendingSceneMode >= 0 && sceneMode == pendingSceneMode) {
            pendingSceneMode = -1;
        }
        updateSceneUi();
    }

    private String parseVersion(byte[] payload) {
        if (payload.length == 0) return "";
        boolean ascii = true;
        for (byte b : payload) {
            int c = b & 0xFF;
            if (c < 0x20 || c > 0x7E) {
                ascii = false;
                break;
            }
        }
        if (ascii) return Protocol.ascii(payload);
        StringBuilder sb = new StringBuilder("V");
        for (int i = 0; i < payload.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(payload[i] & 0xFF);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- ANC UI

    private void rebuildModes() {
        if (modeList == null) return;
        modeList.removeAllViews();
        modeRows.clear();

        String[] names = modeNames(ancIndex);
        for (int i = 0; i < names.length; i++) {
            createModeButton(i + 1, names[i]);
        }
    }

    private void createModeButton(int value, String name) {
        TextView button = text(name, 12, TEXT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(9), dp(10), dp(9), dp(10));
        button.setBackground(bg(ROW, 12));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> setAnc(value));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        modeList.addView(button, lp);
        modeRows.add(new ModeRow(value, button));
    }

    private void updateAncUi() {
        if (ancCurrent == null) return;
        ancCurrent.setText(ancValue >= 0 ? "当前：" + modeName(ancIndex, ancValue) : "未获取");
        ancIndexText.setText("索引 0x" + hex2(ancIndex));

        for (ModeRow row : modeRows) {
            boolean applied = row.value == ancValue;
            row.button.setBackground(bg(applied ? ACCENT : ROW, 12));
            row.button.setTextColor(applied ? Color.WHITE : TEXT);
        }
        updateInfo();
    }

    private void setAnc(int value) {
        if (!connected) {
            toast("请先连接耳机");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastAncSwitchAt < ANC_SWITCH_COOLDOWN_MS) return;
        lastAncSwitchAt = now;

        final int token = ++ancSwitchToken;
        final int index = ancIndex;
        pendingAncValue = value;
        sendCommand(Protocol.CMD_ANC_SET, new byte[]{(byte) index, (byte) value});
        ancValue = value;
        updateAncUi();
        setModeButtonsEnabled(false);
        showAncSpinner(true);

        // Some firmware needs a second write after switching from OFF.
        ui.postDelayed(() -> {
            if (token == ancSwitchToken && pendingAncValue == value) {
                sendCommand(Protocol.CMD_ANC_SET, new byte[]{(byte) index, (byte) value});
            }
        }, 1000);

        ui.postDelayed(() -> {
            if (token == ancSwitchToken) queryAnc();
        }, 1400);

        ui.postDelayed(() -> {
            if (token != ancSwitchToken) return;
            pendingAncValue = -1;
            showAncSpinner(false);
            setModeButtonsEnabled(true);
            updateAncUi();
            queryAnc();
        }, ANC_SWITCH_COOLDOWN_MS);
    }

    private void setModeButtonsEnabled(boolean enabled) {
        for (ModeRow row : modeRows) {
            row.button.setEnabled(enabled);
            row.button.setAlpha(enabled ? 1f : 0.55f);
        }
    }

    private void showAncSpinner(boolean show) {
        if (ancSpinner != null) {
            ancSpinner.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (show && ancCurrent != null) {
            ancCurrent.setText("切换中…");
        }
    }

    // ------------------------------------------------------------ scene UI

    private void rebuildScenes() {
        if (sceneList == null) return;
        sceneList.removeAllViews();
        sceneRows.clear();
        String[] names = {"音乐", "游戏", "空间音效"};
        for (int i = 0; i < names.length; i++) {
            createSceneButton(i, names[i]);
        }
    }

    private void createSceneButton(int mode, String name) {
        TextView button = text(name, 12, TEXT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(9), dp(10), dp(9), dp(10));
        button.setBackground(bg(ROW, 12));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> setScene(mode));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, dp(6), 0);
        sceneList.addView(button, lp);
        sceneRows.add(new SceneRow(mode, button));
    }

    private void updateSceneUi() {
        if (sceneCurrent == null) return;
        sceneCurrent.setText(sceneMode >= 0 ? "当前：" + sceneName(sceneMode) : "未获取");
        for (SceneRow row : sceneRows) {
            boolean applied = row.mode == sceneMode;
            row.button.setBackground(bg(applied ? ACCENT : ROW, 12));
            row.button.setTextColor(applied ? Color.WHITE : TEXT);
        }
        updateInfo();
    }

    private void setScene(int mode) {
        if (!connected) {
            toast("请先连接耳机");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastSceneSwitchAt < SCENE_SWITCH_COOLDOWN_MS) return;
        lastSceneSwitchAt = now;

        final int token = ++sceneSwitchToken;
        pendingSceneMode = mode;
        sendCommand(Protocol.CMD_SCENE_SET, new byte[]{(byte) mode});
        sceneMode = mode;
        updateSceneUi();
        setSceneButtonsEnabled(false);
        showSceneSpinner(true);

        ui.postDelayed(() -> {
            if (token == sceneSwitchToken && pendingSceneMode == mode) {
                sendCommand(Protocol.CMD_SCENE_SET, new byte[]{(byte) mode});
            }
        }, 700);

        ui.postDelayed(() -> {
            if (token == sceneSwitchToken) sendCommand(Protocol.CMD_SCENE_QUERY, new byte[0]);
        }, 900);

        ui.postDelayed(() -> {
            if (token != sceneSwitchToken) return;
            pendingSceneMode = -1;
            showSceneSpinner(false);
            setSceneButtonsEnabled(true);
            updateSceneUi();
            sendCommand(Protocol.CMD_SCENE_QUERY, new byte[0]);
        }, SCENE_SWITCH_COOLDOWN_MS);
    }

    private void setSceneButtonsEnabled(boolean enabled) {
        for (SceneRow row : sceneRows) {
            row.button.setEnabled(enabled);
            row.button.setAlpha(enabled ? 1f : 0.55f);
        }
    }

    private void showSceneSpinner(boolean show) {
        if (sceneSpinner != null) {
            sceneSpinner.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (show && sceneCurrent != null) {
            sceneCurrent.setText("切换中…");
        }
    }

    private static String sceneName(int mode) {
        switch (mode) {
            case 0:
                return "音乐";
            case 1:
                return "游戏";
            case 2:
                return "空间音效";
            default:
                return "模式 " + mode;
        }
    }

    private void showAncIndexDialog() {
        EditText input = new EditText(this);
        input.setText(hex2(ancIndex));
        input.setTextColor(TEXT);
        input.setHintTextColor(FAINT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setTypeface(Typeface.MONOSPACE);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AlertDialog.Builder(this)
                .setTitle("ANC 索引")
                .setMessage("十六进制，例如 10、1B、2E。连接后设备返回的索引会自动覆盖这里。")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        int value = Integer.parseInt(input.getText().toString().trim(), 16);
                        if (value < 1 || value > 0x7F) throw new NumberFormatException();
                        ancIndex = value;
                        rebuildModes();
                        updateAncUi();
                        toast("索引已设为 0x" + hex2(ancIndex));
                    } catch (Exception e) {
                        toast("索引格式不正确");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String[] modeNames(int index) {
        switch (index) {
            case 0x10:
                return new String[]{"深度降噪", "舒适降噪", "防风噪", "环境声", "降噪关"};
            case 0x1B:
                return new String[]{"深度降噪", "舒适降噪", "宽频降噪", "防风噪", "环境声", "降噪关"};
            case 0x1F:
                return new String[]{"深度降噪", "舒适降噪", "轻度降噪", "环境声", "防风噪", "降噪关"};
            case 0x2E:
                return new String[]{"自适应降噪", "深度降噪", "舒适降噪", "防风噪", "环境声", "降噪关"};
            case 0x2F:
                return new String[]{"深度降噪", "舒适降噪", "防风噪", "环境声", "降噪关"};
            default:
                return new String[]{"模式 1", "模式 2", "模式 3", "模式 4", "模式 5", "模式 6"};
        }
    }

    private static String modeName(int index, int value) {
        String[] names = modeNames(index);
        if (value >= 1 && value <= names.length) return names[value - 1];
        return "模式 " + value;
    }

    private void updateInfo() {
        if (infoText == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("名称   ").append(btName.isEmpty() ? "—" : btName).append('\n');
        sb.append("固件   ").append(version.isEmpty() ? "—" : version).append('\n');
        sb.append("协议   SPP v2 · 明文\n");
        sb.append("模式   ").append(sceneMode >= 0 ? sceneName(sceneMode) : "—").append('\n');
        sb.append("ANC    0x").append(hex2(ancIndex))
                .append(ancValue >= 0 ? " · " + modeName(ancIndex, ancValue) : " · 未获取");
        infoText.setText(sb.toString());
    }

    // ------------------------------------------------------------- console

    private void addChip(LinearLayout parent, String label, int cmd) {
        TextView chip = text(label, 10, MUTED, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(11), dp(7), dp(11), dp(7));
        chip.setBackground(bg(ROW, 18));
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setOnClickListener(v -> sendCommand(cmd, new byte[0]));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(7), 0);
        parent.addView(chip, lp);
    }

    private void sendConsole() {
        String raw = consoleInput.getText().toString().trim();
        if (raw.isEmpty()) return;

        try {
            String normalized = raw.replace(',', ' ').replace(':', ' ');
            String[] parts = normalized.split("\\s+");
            if (parts.length == 0) return;

            if (parts[0].equalsIgnoreCase("AA")) {
                byte[] frame = new byte[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    frame[i] = (byte) Integer.parseInt(parts[i], 16);
                }
                if (!connected || spp == null) {
                    toast("请先连接耳机");
                    return;
                }
                appendLog("TX", Protocol.hex(frame));
                spp.send(frame);
                return;
            }

            int cmd = Integer.parseInt(parts[0], 16);
            byte[] payload = new byte[Math.max(0, parts.length - 1)];
            for (int i = 1; i < parts.length; i++) {
                payload[i - 1] = (byte) Integer.parseInt(parts[i], 16);
            }
            sendCommand(cmd, payload);
        } catch (NumberFormatException e) {
            toast("请输入十六进制，例如 CC 或 C1 10 03");
        }
    }

    private void appendLog(String tag, String message) {
        Log.d("Fit900NB", tag + "  " + message);
        if (logView == null) return;
        String line = timeFormat.format(new Date()) + "  " + tag + "  " + message + "\n";
        logs.append(line);
        if (logs.length() > 14000) {
            logs.delete(0, logs.length() - 14000);
        }
        logView.setText(logs.toString());
        logScroll.post(() -> logScroll.scrollTo(0, logView.getBottom()));
    }

    private void copyLog() {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        manager.setPrimaryClip(ClipData.newPlainText("Fit900NB log", logs.toString()));
        toast("日志已复制");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static final class ModeRow {
        final int value;
        final TextView button;

        ModeRow(int value, TextView button) {
            this.value = value;
            this.button = button;
        }
    }

    private static final class SceneRow {
        final int mode;
        final TextView button;

        SceneRow(int mode, TextView button) {
            this.mode = mode;
            this.button = button;
        }
    }
}
