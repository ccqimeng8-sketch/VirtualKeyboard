package local.virtualkeyboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

@SuppressLint("MissingPermission") // Permission is checked before reaching Bluetooth actions.
public final class ControlActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE = 2;
    private static final int REQUEST_DISCOVERABLE = 3;
    private static final int INK = Color.rgb(241, 245, 250);
    private static final int MUTED = Color.rgb(174, 187, 204);
    private static final int BACKGROUND = Color.rgb(16, 21, 30);
    private static final int SURFACE = Color.rgb(25, 33, 46);
    private static final int KEY = Color.rgb(44, 56, 74);
    private static final int BLUE = Color.rgb(38, 86, 171);
    private static final String TWO_FINGER_KEY = "two_finger_gestures";
    private static final String THREE_FINGER_KEY = "three_finger_gestures";
    private static final String TAP_TO_CLICK_KEY = "tap_to_click";
    private static final String SINGLE_REVERSE_KEY = "single_finger_reverse";
    private static final String SCROLL_REVERSE_KEY = "two_finger_scroll_reverse";
    private static final String SENSITIVITY_KEY = "pointer_sensitivity";
    private static final String SENSITIVITY_X_KEY = "pointer_sensitivity_x";
    private static final String SENSITIVITY_Y_KEY = "pointer_sensitivity_y";
    private static final String SENSITIVITY_LINKED_KEY = "pointer_sensitivity_linked";
    private static final String ACCELERATION_KEY = "pointer_acceleration";
    private static final String MULTI_GAIN_KEY = "multi_finger_gain";
    private static final String TWO_FINGER_START_KEY = "two_finger_swipe_start_dp";
    private static final String THREE_FINGER_START_KEY = "three_finger_swipe_start_dp";
    private static final String SIMPLE_KEYBOARD_KEY = "simple_keyboard";
    private static final String[] DIRECTION_OPTIONS = {"关闭", "上下", "左右", "上下和左右"};

    private BluetoothAdapter adapter;
    // Older Android Bluetooth Classic stacks encode the SDP sequence length in one byte.
    private final boolean compact = Build.VERSION.SDK_INT < 36 || !BuildConfig.FULL_HID;
    private HidConnection hid;
    private byte[] descriptor;
    private TrackpadView pad;
    private FrameLayout keyboard;
    private FrameLayout root;
    private LinearLayout settingsPage;
    private TextView status;
    private ImageButton modeCorner;
    private final List<Button> modifierKeys = new ArrayList<>();
    private int modifiers;
    private boolean typing;
    private boolean simpleKeyboard;
    private boolean symbolLayer;
    private boolean connected;
    private SharedPreferences preferences;
    private boolean settingsVisible;
    // Assigned only on Android 13+ while this screen owns the Back action.
    private Object settingsBackCallback;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences = getPreferences(MODE_PRIVATE);
        simpleKeyboard = preferences.getBoolean(SIMPLE_KEYBOARD_KEY, false);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter != null) hid = new HidConnection(this, adapter, compact, this::updateStatus);

        root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        // Keep the existing report geometry so paired iPads can reuse their cached descriptor.
        android.util.DisplayMetrics screen = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(screen);
        int width = Math.max(screen.widthPixels, screen.heightPixels) - dp(24);
        int height = Math.min(screen.widthPixels, screen.heightPixels) - dp(104);
        int physicalX = physicalSize(width, screen.xdpi, 1320);
        int physicalY = physicalSize(height, screen.ydpi, 540);
        int logicalX = Math.min(4095, physicalX * 5 / 2);
        int logicalY = Math.min(4095, physicalY * 5 / 2);
        descriptor = HidReports.descriptor(physicalX, physicalY, logicalX, logicalY, compact);
        pad = new TrackpadView(this, logicalX, logicalY, HidReports.slotCount(compact), this::sendPad,
                this::sendPadClick);
        pad.setBackgroundColor(SURFACE);
        syncPadTuning();
        root.addView(pad, new FrameLayout.LayoutParams(-1, -1));
        keyboard = new FrameLayout(this);
        rebuildKeyboard();
        keyboard.setVisibility(View.GONE);
        FrameLayout.LayoutParams keyboardArea = new FrameLayout.LayoutParams(-1, -1);
        keyboardArea.topMargin = dp(60);
        keyboardArea.setMarginStart(dp(8));
        keyboardArea.setMarginEnd(dp(8));
        keyboardArea.bottomMargin = dp(8);
        root.addView(keyboard, keyboardArea);
        addCornerControls();
        FrameLayout content = new FrameLayout(this);
        content.addView(root, new FrameLayout.LayoutParams(-1, -1));
        settingsPage = makeSettings();
        updateStatus(adapter == null ? "此手机未提供蓝牙适配器" : "尚未连接 iPad", false);
        settingsPage.setVisibility(View.GONE);
        content.addView(settingsPage, new FrameLayout.LayoutParams(-1, -1));
        setContentView(content);
        immersive();
    }

    private void addCornerControls() {
        modeCorner = cornerButton("切换到键盘", R.drawable.ic_keyboard, this::toggleMode);
        FrameLayout.LayoutParams left = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.START);
        left.topMargin = dp(7);
        left.setMarginStart(dp(9));
        root.addView(modeCorner, left);

        ImageButton settings = cornerButton("设置", R.drawable.ic_settings, () -> showSettings(true));
        FrameLayout.LayoutParams right = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
        right.topMargin = dp(7);
        right.setMarginEnd(dp(9));
        root.addView(settings, right);
    }

    private ImageButton cornerButton(String description, int icon, Runnable click) {
        ImageButton button = new ImageButton(this);
        button.setContentDescription(description);
        button.setImageResource(icon);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setPadding(0, 0, 0, 0);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(KEY);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x35FFFFFF), circle, null));
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private static int physicalSize(int pixels, float dpi, int fallback) {
        int candidate = Math.round(pixels * 254f / dpi);
        return candidate >= 200 && candidate <= 2200 ? candidate : fallback;
    }

    @Override protected void onResume() {
        super.onResume();
        if (permitted() && adapter != null && adapter.isEnabled()) hid.open(descriptor);
    }

    @Override protected void onPause() {
        clearInputs();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (hid != null && permitted()) hid.close();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private boolean permitted() {
        return Build.VERSION.SDK_INT < 31 ||
                (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED);
    }

    private void chooseConnection() {
        if (adapter == null) return;
        if (!permitted()) {
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE}, REQUEST_PERMISSIONS);
            return;
        }
        if (!adapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE);
            return;
        }
        if (!hid.isRegistered()) {
            hid.open(descriptor);
            return;
        }
        List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
        String[] options = new String[devices.size() + 1];
        options[0] = "配对新 iPad";
        for (int index = 0; index < devices.size(); index++) {
            BluetoothDevice device = devices.get(index);
            String name = device.getName();
            options[index + 1] = "连接 " + (name == null ? device.getAddress() : name);
        }
        new AlertDialog.Builder(this).setTitle("蓝牙连接")
                .setItems(options, (dialog, index) -> {
                    if (index == 0) {
                        Intent discover = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                        discover.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
                        startActivityForResult(discover, REQUEST_DISCOVERABLE);
                    } else {
                        hid.reconnect(devices.get(index - 1));
                    }
                }).setNegativeButton("取消", null).show();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQUEST_PERMISSIONS && permitted()) chooseConnection();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_ENABLE && result == RESULT_OK) chooseConnection();
        if (request == REQUEST_DISCOVERABLE && result > 0 && !connected) {
            String name = adapter.getName();
            updateStatus("在 iPad 蓝牙设置中选择 " + (name == null ? "这部手机" : name), false);
        }
    }

    private void updateStatus(String message, boolean nowConnected) {
        if (connected && !nowConnected) clearInputs();
        connected = nowConnected;
        status.setText(message);
        status.setTextColor(nowConnected ? 0xFF92E4B5 : MUTED);
    }

    private void sendPad(byte[] report) {
        if (hid != null) hid.trackpad(report);
    }

    private void sendPadClick(byte[] press, byte[] release) {
        if (hid != null) hid.trackpadClick(press, release);
    }

    private void clearInputs() {
        if (pad != null) pad.reset();
        modifiers = 0;
        updateModifiers();
        if (hid != null) {
            hid.keyboard(0, 0);
            hid.consumer(0);
        }
    }

    private void toggleMode() {
        pad.reset();
        typing = !typing;
        pad.setVisibility(typing ? View.GONE : View.VISIBLE);
        keyboard.setVisibility(typing ? View.VISIBLE : View.GONE);
        modeCorner.setContentDescription(typing ? "切换到触控板" : "切换到键盘");
        modeCorner.setImageResource(typing ? R.drawable.ic_trackpad : R.drawable.ic_keyboard);
        root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private LinearLayout makeSettings() {
        LinearLayout page = new LinearLayout(this);
        page.setPadding(dp(12), dp(10), dp(12), dp(10));
        page.setBackgroundColor(BACKGROUND);

        LinearLayout navigation = settingsStack();
        navigation.setBackground(background(SURFACE));
        addSettingsRow(navigation, settingsButton("‹  返回", () -> showSettings(false)));
        LinearLayout.LayoutParams sidebar = new LinearLayout.LayoutParams(dp(148), -1);
        sidebar.setMarginEnd(dp(10));
        page.addView(scrollSettings(navigation), sidebar);

        FrameLayout content = new FrameLayout(this);
        page.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        String[] labels = {"灵敏度设置", "手势设置", "键盘设置", "配对与连接"};
        LinearLayout[] sections = {makeSensitivitySettings(), makeGestureSettings(), makeKeyboardSettings(), makeConnectionSettings()};
        Button[] links = new Button[labels.length];
        ScrollView[] panels = new ScrollView[labels.length];
        for (int index = 0; index < labels.length; index++) {
            int selected = index;
            panels[index] = scrollSettings(sections[index]);
            content.addView(panels[index], new FrameLayout.LayoutParams(-1, -1));
            links[index] = settingsButton(labels[index], () -> selectPanel(links, panels, selected));
            addSettingsRow(navigation, links[index]);
        }
        selectPanel(links, panels, 0);
        return page;
    }

    private void selectPanel(Button[] links, ScrollView[] panels, int selected) {
        for (int index = 0; index < panels.length; index++) {
            boolean active = index == selected;
            links[index].setSelected(active);
            links[index].setBackground(background(active ? BLUE : KEY));
            panels[index].setVisibility(active ? View.VISIBLE : View.GONE);
        }
    }

    private LinearLayout makeSensitivitySettings() {
        LinearLayout section = settingsStack();
        LinearLayout cursor = settingsCard(section, "单指光标");
        addPointerSensitivity(cursor);
        addSlider(cursor, "光标加速度（额外）", ACCELERATION_KEY, 0, 100, 0, "%");

        LinearLayout multi = settingsCard(section, "多指滑动");
        addSlider(multi, "双指起滑距离", TWO_FINGER_START_KEY, 0, 36, 0, " dp");
        addSlider(multi, "三指起滑距离", THREE_FINGER_START_KEY, 0, 36, 0, " dp");
        addSlider(multi, "多指滑动增益", MULTI_GAIN_KEY, 100, 200, 100, "%");
        multi.addView(settingsText("起滑距离控制手机何时开始传送滑动，0 dp 时立即传送。iPadOS 自己判断何时触发系统手势，调高增益会放大传给 iPad 的位移。", 14, MUTED));

        return section;
    }

    private void addPointerSensitivity(LinearLayout cursor) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        cursor.addView(row, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout sliders = new LinearLayout(this);
        sliders.setOrientation(LinearLayout.VERTICAL);
        row.addView(sliders, new LinearLayout.LayoutParams(0, -2, 1));
        SettingsSlider x = addSlider(sliders, "X 轴灵敏度（左右）", 50, 200, pointerSensitivity(SENSITIVITY_X_KEY), "%");
        SettingsSlider y = addSlider(sliders, "Y 轴灵敏度（上下）", 50, 200, pointerSensitivity(SENSITIVITY_Y_KEY), "%");

        ImageButton magnet = new ImageButton(this);
        magnet.setImageResource(R.drawable.ic_magnet);
        magnet.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        magnet.setPadding(0, 0, 0, 0);
        styleSensitivityLink(magnet, preferences.getBoolean(SENSITIVITY_LINKED_KEY, false));
        magnet.setOnClickListener(view -> {
            boolean linked = !magnet.isSelected();
            preferences.edit().putBoolean(SENSITIVITY_LINKED_KEY, linked).apply();
            styleSensitivityLink(magnet, linked);
        });
        LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(dp(48), dp(48));
        icon.setMarginStart(dp(8));
        row.addView(magnet, icon);

        x.onUserChange(value -> savePointerSensitivity(SENSITIVITY_X_KEY, value, magnet, SENSITIVITY_Y_KEY, y));
        y.onUserChange(value -> savePointerSensitivity(SENSITIVITY_Y_KEY, value, magnet, SENSITIVITY_X_KEY, x));
    }

    private void styleSensitivityLink(ImageButton magnet, boolean linked) {
        magnet.setSelected(linked);
        magnet.setImageTintList(ColorStateList.valueOf(linked ? INK : MUTED));
        magnet.setBackground(new RippleDrawable(ColorStateList.valueOf(0x35FFFFFF),
                background(linked ? BLUE : KEY), null));
        String label = "X、Y 轴灵敏度联动";
        String state = linked ? "已开启" : "已关闭";
        if (Build.VERSION.SDK_INT >= 30) {
            magnet.setContentDescription(label);
            magnet.setStateDescription(state);
        } else {
            magnet.setContentDescription(label + "，" + state);
        }
    }

    private void savePointerSensitivity(String key, int value, ImageButton magnet, String otherKey,
            SettingsSlider otherSlider) {
        SharedPreferences.Editor updated = preferences.edit().putInt(key, value);
        if (magnet.isSelected()) {
            updated.putInt(otherKey, value);
            otherSlider.bar().setProgress(value);
        }
        updated.apply();
        syncPadTuning();
    }

    private int pointerSensitivity(String axisKey) {
        return preferences.getInt(axisKey, preferences.getInt(SENSITIVITY_KEY, 100));
    }

    private LinearLayout makeGestureSettings() {
        LinearLayout section = settingsStack();
        LinearLayout controls = settingsCard(section, "触控手势");
        controls.addView(directionSetting("单指移动反向", SINGLE_REVERSE_KEY));
        controls.addView(directionSetting("双指滚动反向", SCROLL_REVERSE_KEY));
        controls.addView(toggleSetting("单指轻点左键", TAP_TO_CLICK_KEY));
        controls.addView(settingsText("轻点后再按住滑动可拖移。双指轻点为右键。", 14, MUTED));
        controls.addView(toggleSetting("双指原生手势", TWO_FINGER_KEY));
        controls.addView(toggleSetting("三指及以上原生手势", THREE_FINGER_KEY));
        LinearLayout guide = makeGestureHelp();
        guide.setBackground(background(SURFACE));
        section.addView(guide);
        return section;
    }

    private LinearLayout makeKeyboardSettings() {
        LinearLayout section = settingsStack();
        LinearLayout layout = settingsCard(section, "键盘布局");
        Button full = settingsButton("完整键盘", () -> {});
        Button simple = settingsButton("精简键盘", () -> {});
        full.setOnClickListener(view -> chooseKeyboard(false, full, simple));
        simple.setOnClickListener(view -> chooseKeyboard(true, full, simple));
        styleKeyboardChoice(full, simple);
        addSettingsRow(layout, full);
        layout.addView(settingsText("字母、数字、导航键和修饰键同屏。", 14, MUTED));
        addSettingsRow(layout, simple);
        layout.addView(settingsText("数字行常驻，字母按错位键列排列。轻点「符号」切换字符，轻点 ABC 返回字母。", 14, MUTED));
        return section;
    }

    private void chooseKeyboard(boolean simple, Button fullChoice, Button simpleChoice) {
        if (simpleKeyboard == simple) return;
        simpleKeyboard = simple;
        symbolLayer = false;
        preferences.edit().putBoolean(SIMPLE_KEYBOARD_KEY, simple).apply();
        rebuildKeyboard();
        styleKeyboardChoice(fullChoice, simpleChoice);
    }

    private void styleKeyboardChoice(Button full, Button simple) {
        full.setSelected(!simpleKeyboard);
        simple.setSelected(simpleKeyboard);
        full.setBackground(background(simpleKeyboard ? KEY : BLUE));
        simple.setBackground(background(simpleKeyboard ? BLUE : KEY));
    }

    private LinearLayout makeConnectionSettings() {
        LinearLayout section = settingsStack();
        LinearLayout connection = settingsCard(section, "蓝牙连接");
        status = new TextView(this);
        status.setTextSize(16);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setPadding(0, dp(5), 0, dp(12));
        connection.addView(status);
        addSettingsRow(connection, settingsButton("管理蓝牙连接", this::chooseConnection));
        return section;
    }

    private LinearLayout settingsCard(LinearLayout parent, String title) {
        LinearLayout card = settingsStack();
        card.setBackground(background(SURFACE));
        card.addView(settingsText(title, 20, INK));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        parent.addView(card, params);
        return card;
    }

    private Button settingsButton(String title, Runnable click) {
        Button action = button(title, click);
        action.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        action.setPadding(dp(10), 0, dp(8), 0);
        return action;
    }

    private void addSettingsRow(LinearLayout parent, View child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.bottomMargin = dp(6);
        parent.addView(child, params);
    }

    private LinearLayout settingsStack() {
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setPadding(dp(12), dp(8), dp(12), dp(12));
        return stack;
    }

    private ScrollView scrollSettings(LinearLayout stack) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setScrollbarFadingEnabled(false);
        scroll.addView(stack, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private LinearLayout makeGestureHelp() {
        String appName = getString(R.string.app_name);
        GestureGuide guide = GestureGuide.forDevice(Build.MANUFACTURER, Build.BRAND, appName);
        LinearLayout stack = settingsStack();
        stack.addView(settingsText("三指防误触", 20, INK));
        stack.addView(settingsText(guide.family() + " · Android " + Build.VERSION.RELEASE, 14, MUTED));
        GestureTestView test = new GestureTestView(this);
        test.setTextColor(INK);
        test.setTextSize(15);
        test.setPadding(dp(10), 0, dp(10), 0);
        test.setBackground(background(KEY));
        stack.addView(test, new LinearLayout.LayoutParams(-1, dp(118)));
        stack.addView(settingsText("如果截屏或 AI 抢走触摸，请查找只对 " + appName + " 生效的系统选项。"
                + "也可以轻点左上角切换键盘。", 15, INK));
        stack.addView(settingsText(guide.steps(), 15, INK));

        LinearLayout actions = new LinearLayout(this);
        actions.addView(button("打开系统设置", () -> startActivity(new Intent(Settings.ACTION_SETTINGS))),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        if (guide.helpUrl() != null) {
            LinearLayout.LayoutParams link = new LinearLayout.LayoutParams(0, dp(48), 1);
            link.setMarginStart(dp(8));
            actions.addView(button("品牌帮助", () -> openGuide(guide.helpUrl())), link);
        }
        stack.addView(actions);
        return stack;
    }

    private Switch toggleSetting(String label, String key) {
        Switch option = new Switch(this);
        option.setText(label);
        option.setTextColor(INK);
        option.setTextSize(16);
        option.setMinHeight(dp(48));
        option.setChecked(preferences.getBoolean(key, true));
        option.setThumbTintList(ColorStateList.valueOf(INK));
        option.setTrackTintList(new ColorStateList(new int[][] {
                new int[] {android.R.attr.state_checked}, new int[] {}}, new int[] {BLUE, KEY}));
        option.setOnCheckedChangeListener((switchView, enabled) -> {
            preferences.edit().putBoolean(key, enabled).apply();
            syncPadTuning();
        });
        return option;
    }

    private Button directionSetting(String label, String key) {
        Button option = button(label + " · " + DIRECTION_OPTIONS[preferences.getInt(key, 0)], () -> {});
        option.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(label)
                .setSingleChoiceItems(DIRECTION_OPTIONS, preferences.getInt(key, 0), (dialog, index) -> {
                    preferences.edit().putInt(key, index).apply();
                    option.setText(label + " · " + DIRECTION_OPTIONS[index]);
                    syncPadTuning();
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show());
        option.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        option.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, dp(48));
        row.bottomMargin = dp(6);
        option.setLayoutParams(row);
        return option;
    }

    private void addSlider(LinearLayout parent, String label, String key, int min, int max, int initial, String unit) {
        addSlider(parent, label, min, max, preferences.getInt(key, initial), unit).onUserChange(value -> {
            preferences.edit().putInt(key, value).apply();
            syncPadTuning();
        });
    }

    private SettingsSlider addSlider(LinearLayout parent, String label, int min, int max, int value, String unit) {
        TextView title = settingsText(label + "  " + value + unit, 15, INK);
        parent.addView(title);
        SeekBar slider = new SeekBar(this);
        slider.setContentDescription(label);
        slider.setMin(min);
        slider.setMax(max);
        slider.setProgress(value);
        slider.setProgressTintList(ColorStateList.valueOf(0xFF699BFF));
        slider.setThumbTintList(ColorStateList.valueOf(INK));
        parent.addView(slider, new LinearLayout.LayoutParams(-1, dp(40)));
        return new SettingsSlider(slider, title, label, unit);
    }

    private record SettingsSlider(SeekBar bar, TextView title, String label, String unit) {
        void onUserChange(IntConsumer action) {
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    title.setText(label + "  " + value + unit);
                    if (fromUser) action.accept(value);
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
        }
    }

    private void syncPadTuning() {
        pad.setTuning(new TrackpadTuning(pointerSensitivity(SENSITIVITY_X_KEY), pointerSensitivity(SENSITIVITY_Y_KEY),
                preferences.getInt(ACCELERATION_KEY, 0), preferences.getInt(MULTI_GAIN_KEY, 100),
                preferences.getInt(SINGLE_REVERSE_KEY, 0),
                preferences.getInt(SCROLL_REVERSE_KEY, 0), preferences.getInt(TWO_FINGER_START_KEY, 0),
                preferences.getInt(THREE_FINGER_START_KEY, 0), preferences.getBoolean(TWO_FINGER_KEY, true),
                preferences.getBoolean(THREE_FINGER_KEY, true), preferences.getBoolean(TAP_TO_CLICK_KEY, true)));
    }

    private TextView settingsText(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(8));
        return view;
    }

    private void openGuide(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException noBrowser) {
            android.widget.Toast.makeText(this, "没有可打开厂商帮助的浏览器", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showSettings(boolean show) {
        settingsVisible = show;
        if (show) clearInputs();
        root.setVisibility(show ? View.GONE : View.VISIBLE);
        settingsPage.setVisibility(show ? View.VISIBLE : View.GONE);
        if (Build.VERSION.SDK_INT >= 33) updateSettingsBackHandler(show);
    }

    @android.annotation.TargetApi(33)
    private void updateSettingsBackHandler(boolean show) {
        if (show && settingsBackCallback == null) {
            android.window.OnBackInvokedCallback callback = () -> showSettings(false);
            settingsBackCallback = callback;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        } else if (!show && settingsBackCallback instanceof android.window.OnBackInvokedCallback callback) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
            settingsBackCallback = null;
        }
    }

    // Android 13+ uses the platform dispatcher registered by showSettings.
    @Override @SuppressLint("GestureBackNavigation") @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (settingsVisible) showSettings(false);
        else super.onBackPressed();
    }

    private void rebuildKeyboard() {
        modifierKeys.clear();
        keyboard.removeAllViews();
        LinearLayout layout = simpleKeyboard ? makeSimpleKeyboard() : makeKeyboard();
        keyboard.addView(layout, new FrameLayout.LayoutParams(-1, -1));
        updateModifiers();
    }

    private LinearLayout keyboardLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(background(SURFACE));
        layout.setPadding(dp(5), dp(3), dp(5), dp(3));
        return layout;
    }

    private LinearLayout makeKeyboard() {
        LinearLayout layout = keyboardLayout();
        LinearLayout row = row(layout);
        key(row, "Esc", 0x29, 1);
        symbols(row, "1234567890-=");
        key(row, "退格", 0x2A, 2);
        row = row(layout);
        key(row, "Tab", 0x2B, 1.5f);
        symbols(row, "qwertyuiop[]");
        key(row, "\\", 0x31, 1.5f);
        row = row(layout);
        key(row, "Caps", 0x39, 1.75f);
        symbols(row, "asdfghjkl;'");
        key(row, "回车", 0x28, 2.25f);
        row = row(layout);
        modifier(row, "Shift", 2, 2.25f);
        symbols(row, "zxcvbnm,./");
        key(row, "↑", 0x52, 1);
        key(row, "Del", 0x4C, 1.75f);
        row = row(layout);
        if (!compact) addKey(row, button("地球", () -> {
            if (hid != null && connected) {
                hid.consumer(HidReports.GLOBE);
                hid.consumer(0);
            }
        }), 1.1f);
        modifier(row, "Ctrl", 1, 1);
        modifier(row, "Option", 4, 1.45f);
        modifier(row, "⌘", 8, 1);
        key(row, "空格", 0x2C, 5.2f);
        key(row, "`", 0x35, 1);
        key(row, "←", 0x50, 1);
        key(row, "↓", 0x51, 1);
        key(row, "→", 0x4F, 1);
        return layout;
    }

    private LinearLayout makeSimpleKeyboard() {
        LinearLayout layout = keyboardLayout();
        LinearLayout row = row(layout);
        symbols(row, "1234567890");
        key(row, "退格", 0x2A, 2.5f);

        if (symbolLayer) {
            row = row(layout);
            symbols(row, "-=[]\\;'`,./");
            key(row, "回车", 0x28, 1.5f);
            row = row(layout);
            spacer(row, 0.75f);
            symbols(row, "_+{}|:\"~<>?");
            spacer(row, 0.75f);
            row = row(layout);
            modifier(row, "Shift", 2, 1.5f);
            symbols(row, "!@#$%^&*()");
            spacer(row, 1);
        } else {
            row = row(layout);
            key(row, "Tab", 0x2B, 1.6f);
            symbols(row, "qwertyuiop");
            spacer(row, 0.9f);
            row = row(layout);
            key(row, "Caps", 0x39, 1.85f);
            symbols(row, "asdfghjkl");
            key(row, "回车", 0x28, 1.65f);
            row = row(layout);
            modifier(row, "Shift", 2, 2.3f);
            symbols(row, "zxcvbnm,.");
            modifier(row, "Shift", 2, 1.2f);
        }

        row = row(layout);
        modifier(row, "⌘", 8, 1.45f);
        modifier(row, "Ctrl", 1, 1.35f);
        modifier(row, "Option", 4, 1.55f);
        key(row, "空格", 0x2C, 6.55f);
        Button layer = button(symbolLayer ? "ABC" : "符号", () -> {
            symbolLayer = !symbolLayer;
            rebuildKeyboard();
        });
        layer.setContentDescription(symbolLayer ? "ABC · 切换到字母" : "切换到符号");
        addKey(row, layer, 1.6f);
        return layout;
    }

    private void spacer(LinearLayout row, float width) {
        row.addView(new android.widget.Space(this), new LinearLayout.LayoutParams(0, -1, width));
    }

    private void symbols(LinearLayout row, String symbols) {
        for (char symbol : symbols.toCharArray()) {
            HidKeyMap.Stroke stroke = HidKeyMap.forCharacter(symbol);
            key(row, String.valueOf(Character.toUpperCase(symbol)), stroke.usage(), stroke.modifiers(), 1);
        }
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        parent.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        return row;
    }

    private void key(LinearLayout row, String label, int usage, float width) {
        key(row, label, usage, 0, width);
    }

    private void key(LinearLayout row, String label, int usage, int extraModifiers, float width) {
        addKey(row, button(label, () -> {
            if (hid != null && connected) {
                hid.keyboard(modifiers | extraModifiers, usage);
                hid.keyboard(modifiers, 0);
            }
        }), width);
    }

    private void modifier(LinearLayout row, String label, int bit, float width) {
        Button key = button(label, () -> {
            modifiers ^= bit;
            updateModifiers();
            if (hid != null && connected) hid.keyboard(modifiers, 0);
        });
        key.setTag(bit);
        modifierKeys.add(key);
        addKey(row, key, width);
    }

    private void updateModifiers() {
        for (Button key : modifierKeys) {
            boolean selected = (modifiers & (int) key.getTag()) != 0;
            key.setSelected(selected);
            key.setBackground(background(selected ? BLUE : KEY));
        }
    }

    private void addKey(LinearLayout row, Button key, float width) {
        // Put gaps inside each slot so the number of keys in a row does not change the unit width.
        FrameLayout slot = new FrameLayout(this);
        FrameLayout.LayoutParams inset = new FrameLayout.LayoutParams(-1, -1);
        inset.setMargins(dp(2), dp(2), dp(2), dp(2));
        slot.addView(key, inset);
        row.addView(slot, new LinearLayout.LayoutParams(0, -1, width));
    }

    private Button button(String text, Runnable click) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(INK);
        button.setTextSize(15);
        button.setSingleLine(true);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setMinimumHeight(0);
        button.setMinHeight(0);
        button.setBackground(background(KEY));
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private GradientDrawable background(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(8));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
