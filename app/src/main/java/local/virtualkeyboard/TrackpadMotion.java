package local.virtualkeyboard;

/** Moves the reported centroid and preserves the distances between contacts. */
final class TrackpadMotion {
    private final int limitX;
    private final int limitY;
    private float offsetX;
    private float offsetY;
    private float lastX;
    private float lastY;
    private long lastTime;
    private int lastFingers;
    private float swipeStartX;
    private float swipeStartY;
    private boolean swipeStarted;

    TrackpadMotion(int limitX, int limitY) {
        this.limitX = limitX;
        this.limitY = limitY;
    }

    void reset() {
        offsetX = offsetY = 0f;
        lastFingers = 0;
    }

    void update(TrackpadTuning tuning, long time, int fingers, boolean topologyChanged,
                float x, float y, float minX, float minY, float maxX, float maxY,
                float unitsPerDpX, float unitsPerDpY) {
        if (topologyChanged || fingers != lastFingers) {
            swipeStartX = x;
            swipeStartY = y;
            swipeStarted = false;
        }
        if (lastFingers == 0 && fingers == 1) {
            // Mirror the initial contact for a reversed axis so a stroke has room to travel.
            if ((tuning.singleReverse() & TrackpadTuning.REVERSE_X) != 0) offsetX = limitX - 2 * x;
            if ((tuning.singleReverse() & TrackpadTuning.REVERSE_Y) != 0) offsetY = limitY - 2 * y;
        } else if (!topologyChanged && fingers == lastFingers) {
            float dx = x - lastX;
            float dy = y - lastY;
            if (fingers == 1) {
                // Full acceleration at two pad widths per second. A slower stroke keeps finer control.
                double padLengthsPerSecond = Math.hypot(dx / limitX, dy / limitY) * 1000
                        / Math.max(1, time - lastTime);
                float acceleration = 1 + tuning.acceleration() / 100f * (float) Math.min(1, padLengthsPerSecond / 2);
                float gainX = tuning.sensitivityX() / 100f * acceleration;
                float gainY = tuning.sensitivityY() / 100f * acceleration;
                offsetX += dx * (direction(tuning.singleReverse(), TrackpadTuning.REVERSE_X) * gainX - 1);
                offsetY += dy * (direction(tuning.singleReverse(), TrackpadTuning.REVERSE_Y) * gainY - 1);
            } else {
                moveMultiFinger(tuning, fingers, x, y, dx, dy, unitsPerDpX, unitsPerDpY);
            }
        }
        offsetX = Math.max(-minX, Math.min(limitX - maxX, offsetX));
        offsetY = Math.max(-minY, Math.min(limitY - maxY, offsetY));
        lastX = x;
        lastY = y;
        lastTime = time;
        lastFingers = fingers;
    }

    int x(float source) { return Math.round(source + offsetX); }
    int y(float source) { return Math.round(source + offsetY); }

    private void moveMultiFinger(TrackpadTuning tuning, int fingers, float x, float y,
                                 float dx, float dy, float unitsPerDpX, float unitsPerDpY) {
        int threshold = fingers == 2 ? tuning.twoFingerStartDp() : tuning.threeFingerStartDp();
        float moveX = dx;
        float moveY = dy;
        if (!swipeStarted && threshold > 0) {
            float totalX = x - swipeStartX;
            float totalY = y - swipeStartY;
            double distance = Math.hypot(totalX / unitsPerDpX, totalY / unitsPerDpY);
            swipeStarted = distance >= threshold;
            // Remove the initial travel instead of jumping by the entire threshold when it is crossed.
            float fraction = swipeStarted ? (float) (1 - threshold / distance) : 0;
            moveX = totalX * fraction;
            moveY = totalY * fraction;
        }
        // Translate the whole contact group so pinch and rotation geometry remains intact.
        float gain = tuning.multiGain() / 100f;
        int reverse = fingers == 2 ? tuning.scrollReverse() : 0;
        offsetX += moveX * direction(reverse, TrackpadTuning.REVERSE_X) * gain - dx;
        offsetY += moveY * direction(reverse, TrackpadTuning.REVERSE_Y) * gain - dy;
    }

    private static int direction(int flags, int axis) { return (flags & axis) == 0 ? 1 : -1; }
}
