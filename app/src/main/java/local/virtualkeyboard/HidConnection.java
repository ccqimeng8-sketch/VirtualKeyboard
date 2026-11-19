package local.virtualkeyboard;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.Arrays;
import java.util.function.BiConsumer;

@SuppressLint("MissingPermission") // The Activity grants Nearby Devices before opening the profile.
final class HidConnection implements AutoCloseable {
    private static final String TAG = "VirtualKeyboardHID";
    private static final int LEFT_CONTROL = 1;

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Executor main;
    private final boolean compact;
    private final BiConsumer<String, Boolean> status;
    private BluetoothHidDevice profile;
    private BluetoothDevice host;
    private boolean requestingProfile;
    private boolean registering;
    private boolean registered;
    private boolean closed;
    private boolean boot;
    private boolean surfaceEnabled = true;
    private byte[] pad;
    private byte[] keys = new byte[8];
    private byte[] consumer = new byte[2];
    private byte[] leds = new byte[1];
    private byte[] descriptor;

    HidConnection(Context context, BluetoothAdapter adapter, boolean compact, BiConsumer<String, Boolean> status) {
        this.context = context;
        this.adapter = adapter;
        this.status = status;
        this.compact = compact;
        pad = HidReports.pad(0, 0, HidReports.slotCount(compact));
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        main = handler::post;
    }

    void open(byte[] descriptor) {
        this.descriptor = descriptor;
        if (closed || registered || registering || requestingProfile) return;
        if (profile != null) {
            register();
            return;
        }
        update("正在启动蓝牙键盘与触控板");
        requestingProfile = adapter.getProfileProxy(context, service, BluetoothProfile.HID_DEVICE);
        if (!requestingProfile) update("此手机未提供蓝牙 HID 设备模式");
    }

    boolean isRegistered() { return registered; }

    void reconnect(BluetoothDevice device) {
        if (registered && host == null && device != null && profile.connect(device)) {
            update("正在连接 " + deviceName(device));
        }
    }

    private final BluetoothProfile.ServiceListener service = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int id, BluetoothProfile proxy) {
            if (closed) {
                adapter.closeProfileProxy(id, proxy);
                return;
            }
            requestingProfile = false;
            profile = (BluetoothHidDevice) proxy;
            register();
        }

        @Override public void onServiceDisconnected(int id) {
            registered = registering = requestingProfile = false;
            host = null;
            profile = null;
            if (!closed) update("蓝牙服务已断开");
        }
    };

    private void register() {
        String appName = context.getString(R.string.app_name);
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                appName, "Keyboard and multi-touch trackpad", appName,
                BluetoothHidDevice.SUBCLASS1_KEYBOARD, descriptor);
        registering = profile.registerApp(sdp, null, null, main, callback);
        if (!registering) update("蓝牙 HID 注册失败，请检查手机是否支持");
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean success) {
            if (closed) return;
            registering = false;
            registered = success;
            if (!success) host = null;
            update(success ? "等待 iPad 配对" : "蓝牙 HID 未注册，点连接重试");
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (closed) return;
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                boot = false;
                surfaceEnabled = true;
                pad = HidReports.pad(0, 0, HidReports.slotCount(compact));
                keys = new byte[8];
                consumer = new byte[2];
                leds = new byte[1];
                update("已连接 " + deviceName(device));
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (device.equals(host)) host = null;
                update(host == null ? "已断开，等待 iPad 连接" : "已连接 " + deviceName(host));
            }
        }

        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int size) {
            if (profile == null || closed) return;
            byte[] data = null;
            int report = Byte.toUnsignedInt(id);
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                if (report == HidReports.KEYBOARD || (boot && report == 0)) data = keys;
                if (!boot && report == HidReports.TRACKPAD) data = pad;
                if (!compact && !boot && report == HidReports.CONSUMER) data = consumer;
            } else if (type == BluetoothHidDevice.REPORT_TYPE_FEATURE && report == HidReports.TRACKPAD) {
                data = HidReports.feature(surfaceEnabled, compact);
            } else if ((!compact || boot) && type == BluetoothHidDevice.REPORT_TYPE_OUTPUT &&
                    (report == HidReports.KEYBOARD || (boot && report == 0))) {
                data = leds;
            }
            if (data == null) profile.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
            else profile.replyReport(device, type, id, data);
        }

        @Override public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            if (profile == null || closed) return;
            byte result = BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID;
            if (type == BluetoothHidDevice.REPORT_TYPE_FEATURE && id == HidReports.TRACKPAD &&
                    data.length == (compact ? 1 : 2)) {
                surfaceEnabled = (data[0] & 1) != 0;
                result = BluetoothHidDevice.ERROR_RSP_SUCCESS;
            } else if ((!compact || boot) && type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && data.length == 1 &&
                    (id == HidReports.KEYBOARD || (boot && id == 0))) {
                leds = data.clone();
                result = BluetoothHidDevice.ERROR_RSP_SUCCESS;
            }
            profile.reportError(device, result);
        }

        @Override public void onInterruptData(BluetoothDevice device, byte id, byte[] data) {
            if ((id == HidReports.KEYBOARD || (boot && id == 0)) && data.length == 1) {
                leds = data.clone();
            }
        }

        @Override public void onSetProtocol(BluetoothDevice device, byte protocol) {
            boot = protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE;
        }
    };

    boolean trackpad(byte[] data) {
        int previousButtons = pad[2];
        pad = data;
        if (!surfaceEnabled) {
            pad = Arrays.copyOf(data, data.length);
            Arrays.fill(pad, 3, pad.length, (byte) 0);
        }
        boolean queued = send(HidReports.TRACKPAD, pad);
        if (queued && previousButtons != pad[2]) {
            Log.d(TAG, "Trackpad button state queued: " + pad[2]);
        }
        return queued;
    }

    void trackpadClick(byte[] press, byte[] release) {
        if (press[2] != TapGesture.SECONDARY) {
            trackpad(press);
            trackpad(release);
            return;
        }

        // iPadOS accepts Control-click as a secondary click on pointing devices.
        if (profile == null || host == null || boot) {
            trackpad(release);
            return;
        }
        boolean addControl = (keys[0] & LEFT_CONTROL) == 0;
        if (addControl) {
            byte[] controlKeys = keys.clone();
            controlKeys[0] |= LEFT_CONTROL;
            if (!send(HidReports.KEYBOARD, controlKeys)) {
                trackpad(release);
                return;
            }
        }

        press[2] = TapGesture.PRIMARY;
        boolean pressQueued = trackpad(press);
        boolean releaseQueued = trackpad(release);
        boolean keysRestored = !addControl || send(HidReports.KEYBOARD, keys);
        if (pressQueued && releaseQueued && keysRestored) {
            Log.d(TAG, "Secondary click queued as Control-click");
        }
    }

    void keyboard(int modifiers, int usage) {
        keys = HidReports.keyboard(modifiers, usage);
        send(HidReports.KEYBOARD, keys);
    }

    void consumer(int usage) {
        if (compact) return;
        consumer = HidReports.consumer(usage);
        send(HidReports.CONSUMER, consumer);
    }

    private boolean send(int id, byte[] data) {
        return profile != null && host != null && (!boot || id == HidReports.KEYBOARD)
                && profile.sendReport(host, boot ? 0 : id, data);
    }

    private String deviceName(BluetoothDevice device) {
        String name = device.getName();
        return name == null ? device.getAddress() : name;
    }

    private void update(String message) {
        Log.i(TAG, message);
        status.accept(message, host != null);
    }

    @Override public void close() {
        closed = true;
        if (profile != null) {
            profile.unregisterApp();
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, profile);
            profile = null;
        }
        host = null;
    }
}
