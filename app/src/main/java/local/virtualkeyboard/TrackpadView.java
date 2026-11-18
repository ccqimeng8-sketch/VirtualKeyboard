package local.virtualkeyboard;

import android.content.Context;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class TrackpadView extends View {
    private final Consumer<byte[]> send;
    private final BiConsumer<byte[], byte[]> sendClick;
    private final int logicalX;
    private final int logicalY;
    private final int[] ids;
    private final int[] x;
    private final int[] y;
    private final float[] sourceX;
    private final float[] sourceY;
    private final TrackpadMotion motion;
    private final TapGesture tapGesture;
    private TrackpadTuning tuning;
    private boolean blocked;

    TrackpadView(Context context, int logicalX, int logicalY, int slots, Consumer<byte[]> send,
            BiConsumer<byte[], byte[]> sendClick) {
        super(context);
        this.logicalX = logicalX;
        this.logicalY = logicalY;
        this.send = send;
        this.sendClick = sendClick;
        ids = new int[slots];
        x = new int[slots];
        y = new int[slots];
        sourceX = new float[slots];
        sourceY = new float[slots];
        motion = new TrackpadMotion(logicalX, logicalY);
        tapGesture = new TapGesture(ViewConfiguration.get(context).getScaledTouchSlop(),
                ViewConfiguration.getDoubleTapTimeout());
        Arrays.fill(ids, -1);
        setContentDescription("多点触控板");
    }

    void setTuning(TrackpadTuning tuning) { this.tuning = tuning; }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            reset();
            return true;
        }
        int fingers = event.getPointerCount();
        long time = event.getEventTime();
        if (action == MotionEvent.ACTION_DOWN) {
            // Send to the external display without waiting for the phone's next display frame.
            if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) requestUnbufferedDispatch(event);
            blocked = false;
            motion.reset();
            tapGesture.start(time, event.getPointerId(0), event.getX(), event.getY(), tuning.tapToClick());
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            tapGesture.addFinger(time, event.getPointerId(index), event.getX(index), event.getY(index),
                    fingers == 2 && tuning.twoFinger());
        }
        if ((fingers == 2 && !tuning.twoFinger()) || (fingers >= 3 && !tuning.threeFinger())) {
            if (!blocked) reset();
            blocked = true;
            return true;
        }
        if (blocked) {
            // A permitted three-finger gesture may begin after a disabled two-finger stage.
            if (action != MotionEvent.ACTION_POINTER_DOWN || fingers < 3) return true;
            blocked = false;
        }
        boolean wasDragging = tapGesture.dragging();
        boolean move = action == MotionEvent.ACTION_MOVE;
        for (int index = 0; index < fingers; index++) {
            int id = event.getPointerId(index);
            for (int sample = 0; sample < event.getHistorySize(); sample++) {
                tapGesture.update(id, event.getHistoricalX(index, sample), event.getHistoricalY(index, sample), move);
            }
            tapGesture.update(id, event.getX(index), event.getY(index), move);
        }
        if (!wasDragging && tapGesture.dragging()) {
            // Catch the item at the previous pointer position before applying the current movement.
            publish(time, TapGesture.PRIMARY, false);
        }
        int up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                ? event.getActionIndex() : -1;
        int click = action == MotionEvent.ACTION_UP ? tapGesture.finish(time, tuning.tapToClick()) : 0;
        int buttons = tapGesture.dragging() ? TapGesture.PRIMARY : 0;
        byte[] report = HidReports.pad(time + (click != 0 ? 1 : 0), buttons, ids.length);
        int active = 0;
        int lifted = -1;
        float sumX = 0, sumY = 0, minX = logicalX, minY = logicalY, maxX = 0, maxY = 0;
        // Keep a stable slot until its final report. Freed slots may be reused in the next frame.
        for (int index = 0; index < fingers; index++) {
            int id = event.getPointerId(index);
            int slot = slot(id, index != up);
            if (slot < 0) continue;
            if (index == up) {
                lifted = slot;
                continue;
            }
            sourceX[slot] = coordinate(event.getX(index), getWidth(), logicalX);
            // Android view coordinates increase downward, iPadOS's trackpad axis upward.
            sourceY[slot] = logicalY - coordinate(event.getY(index), getHeight(), logicalY);
            sumX += sourceX[slot];
            sumY += sourceY[slot];
            minX = Math.min(minX, sourceX[slot]);
            minY = Math.min(minY, sourceY[slot]);
            maxX = Math.max(maxX, sourceX[slot]);
            maxY = Math.max(maxY, sourceY[slot]);
            active++;
        }
        if (active > 0) {
            float density = getResources().getDisplayMetrics().density;
            motion.update(tuning, time, active, !move,
                    sumX / active, sumY / active, minX, minY, maxX, maxY,
                    density * logicalX / Math.max(1, getWidth() - 1),
                    density * logicalY / Math.max(1, getHeight() - 1));
        }
        for (int slot = 0; slot < ids.length; slot++) {
            if (ids[slot] < 0) continue;
            boolean down = slot != lifted;
            if (down) {
                x[slot] = motion.x(sourceX[slot]);
                y[slot] = motion.y(sourceY[slot]);
            } else ids[slot] = -1;
            HidReports.contact(report, slot, down, true, x[slot], y[slot]);
        }
        if (click != 0 && lifted >= 0) {
            // Press while the contact is present. The next frame releases both.
            byte[] press = HidReports.pad(time, click, ids.length);
            HidReports.contact(press, lifted, true, true, x[lifted], y[lifted]);
            sendClick.accept(press, report);
        } else {
            send.accept(report);
        }
        return true;
    }

    void reset() {
        publish(SystemClock.uptimeMillis(), 0, true);
        Arrays.fill(ids, -1);
        motion.reset();
        tapGesture.cancel();
    }

    private void publish(long time, int buttons, boolean release) {
        byte[] report = HidReports.pad(time, buttons, ids.length);
        for (int slot = 0; slot < ids.length; slot++) {
            if (ids[slot] >= 0) HidReports.contact(report, slot, !release, !release, x[slot], y[slot]);
        }
        send.accept(report);
    }

    private int slot(int id, boolean allocate) {
        for (int slot = 0; slot < ids.length; slot++) if (ids[slot] == id) return slot;
        if (allocate) {
            for (int slot = 0; slot < ids.length; slot++) {
                if (ids[slot] == -1) {
                    ids[slot] = id;
                    return slot;
                }
            }
        }
        return -1;
    }

    private static float coordinate(float value, int pixels, int limit) {
        return Math.max(0, Math.min(1, value / Math.max(1, pixels - 1))) * limit;
    }
}
