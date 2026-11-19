package local.virtualkeyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class TrackpadView extends View {
    private static final double POINTER_INTERVAL_MS = 1000.0 / 120;
    // Leave room for a centered smoothing window and the arrival of the next touch sample.
    private static final int POINTER_LOOKBACK_MS = 17;

    private final Consumer<byte[]> send;
    private final BiConsumer<byte[], byte[]> sendClick;
    private final int logicalX;
    private final int logicalY;
    private final TouchFeedback touchFeedback;
    private final int[] ids;
    private final int[] x;
    private final int[] y;
    private final float[] sourceX;
    private final float[] sourceY;
    private final TrackpadMotion motion;
    private final TapGesture tapGesture;
    private final PointerSamples pointerSamples = new PointerSamples();
    private final Handler pointerHandler = Handler.createAsync(Looper.getMainLooper());
    private final Runnable pointerFrame = this::sendPointerFrame;
    private TrackpadTuning tuning;
    private boolean blocked;
    private int pointerSlot = -1;
    private int pointerButtons;
    private double pointerFrameTime;
    private boolean pointerFramePending;
    private int sentPointerX;
    private int sentPointerY;

    TrackpadView(Context context, int logicalX, int logicalY, int slots, Consumer<byte[]> send,
            BiConsumer<byte[], byte[]> sendClick) {
        super(context);
        this.logicalX = logicalX;
        this.logicalY = logicalY;
        touchFeedback = new TouchFeedback(slots, getResources().getDisplayMetrics().density);
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

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (touchFeedback.draw(canvas, SystemClock.uptimeMillis())) postInvalidateOnAnimation();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            reset();
            return true;
        }
        if (touchFeedback.record(event)) postInvalidateOnAnimation();
        int fingers = event.getPointerCount();
        long time = event.getEventTime();
        boolean move = action == MotionEvent.ACTION_MOVE;
        if (!move || fingers != 1) finishPointerMovement(true);
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
            if (!blocked) resetInput();
            blocked = true;
            return true;
        }
        if (blocked) {
            // A permitted three-finger gesture may begin after a disabled two-finger stage.
            if (action != MotionEvent.ACTION_POINTER_DOWN || fingers < 3) return true;
            blocked = false;
        }
        boolean wasDragging = tapGesture.dragging();
        for (int index = 0; index < fingers; index++) {
            int id = event.getPointerId(index);
            for (int sample = 0; sample < event.getHistorySize(); sample++) {
                tapGesture.update(id, event.getHistoricalX(index, sample), event.getHistoricalY(index, sample), move);
            }
            tapGesture.update(id, event.getX(index), event.getY(index), move);
        }
        if (!wasDragging && tapGesture.dragging()) {
            // Catch the item at the previous pointer position before applying the current movement.
            finishPointerMovement(true);
            publish(time, TapGesture.PRIMARY, false);
        }
        int up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                ? event.getActionIndex() : -1;
        int click = action == MotionEvent.ACTION_UP ? tapGesture.finish(time, tuning.tapToClick()) : 0;
        int buttons = tapGesture.dragging() ? TapGesture.PRIMARY : 0;
        boolean pacePointer = move && fingers == 1;
        byte[] report = pacePointer ? null : HidReports.pad(time + (click != 0 ? 1 : 0), buttons, ids.length);
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
                float reportX = motion.x(sourceX[slot]);
                float reportY = motion.y(sourceY[slot]);
                if (pacePointer) queuePointerMovement(time, slot, buttons, reportX, reportY);
                x[slot] = Math.round(reportX);
                y[slot] = Math.round(reportY);
            } else ids[slot] = -1;
            if (report != null) HidReports.contact(report, slot, down, true, x[slot], y[slot]);
        }
        if (pacePointer) return true;
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

    private void queuePointerMovement(long time, int slot, int buttons, float reportX, float reportY) {
        if (pointerSlot < 0) {
            pointerSlot = slot;
            pointerButtons = buttons;
            sentPointerX = x[slot];
            sentPointerY = y[slot];
        }
        pointerSamples.add(time, reportX, reportY);
        if (!pointerFramePending && (sentPointerX != Math.round(reportX) || sentPointerY != Math.round(reportY))) {
            schedulePointerFrame(SystemClock.uptimeMillis() + POINTER_INTERVAL_MS);
        }
    }

    private void schedulePointerFrame(double time) {
        pointerFrameTime = time;
        pointerFramePending = true;
        pointerHandler.postAtTime(pointerFrame, (long) Math.ceil(time));
    }

    private void sendPointerFrame() {
        pointerFramePending = false;
        long now = SystemClock.uptimeMillis();
        // Skip missed frame times after a stall. A stale burst adds transport latency.
        double frame = now - pointerFrameTime >= POINTER_INTERVAL_MS ? now : pointerFrameTime;
        long sampleTime = Math.round(frame - POINTER_LOOKBACK_MS);
        sendPointerPosition(pointerSamples.smoothAt(sampleTime));
        long newestSampleTime = pointerSamples.latest().time();
        if (sampleTime < newestSampleTime || now - newestSampleTime < 2 * POINTER_INTERVAL_MS) {
            schedulePointerFrame(Math.max(frame + POINTER_INTERVAL_MS, now + POINTER_INTERVAL_MS - 1));
        }
    }

    private void sendPointerPosition(PointerSamples.Sample sample) {
        int x = Math.round(sample.x());
        int y = Math.round(sample.y());
        if (x == sentPointerX && y == sentPointerY) return;
        byte[] report = HidReports.pad(sample.time(), pointerButtons, ids.length);
        HidReports.contact(report, pointerSlot, true, true, x, y);
        sentPointerX = x;
        sentPointerY = y;
        send.accept(report);
    }

    private void finishPointerMovement(boolean flush) {
        if (pointerSlot < 0) return;
        if (pointerFramePending) {
            pointerHandler.removeCallbacks(pointerFrame);
            pointerFramePending = false;
        }
        if (flush) sendPointerPosition(pointerSamples.latest());
        pointerSamples.clear();
        pointerSlot = -1;
    }

    void reset() {
        resetInput();
        touchFeedback.clear();
        invalidate();
    }

    private void resetInput() {
        finishPointerMovement(false);
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
