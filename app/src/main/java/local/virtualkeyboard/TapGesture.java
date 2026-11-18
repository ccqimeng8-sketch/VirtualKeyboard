package local.virtualkeyboard;

final class TapGesture {
    static final int PRIMARY = 1;
    static final int SECONDARY = 2;
    private static final int MAX_TAP_MILLIS = 230;
    private static final int MAX_CHORD_DELAY_MILLIS = 105;

    private final float slopSquared;
    private final float doubleTapSlopSquared;
    private final int doubleTapTimeout;
    private boolean pending;
    private boolean dragArmed;
    private boolean dragging;
    private int firstId;
    private int secondId;
    private long startTime;
    private float firstX;
    private float firstY;
    private float secondX;
    private float secondY;
    private long lastPrimaryTap = -1;
    private float lastPrimaryX;
    private float lastPrimaryY;

    TapGesture(float slop, int doubleTapTimeout) {
        slopSquared = slop * slop;
        doubleTapSlopSquared = 9 * slopSquared;
        this.doubleTapTimeout = doubleTapTimeout;
    }

    void start(long time, int id, float x, float y, boolean primaryEnabled) {
        dragArmed = primaryEnabled && lastPrimaryTap >= 0 && time - lastPrimaryTap <= doubleTapTimeout
                && distanceSquared(x, y, lastPrimaryX, lastPrimaryY) <= doubleTapSlopSquared;
        lastPrimaryTap = -1;
        pending = true;
        dragging = false;
        startTime = time;
        firstId = id;
        firstX = x;
        firstY = y;
        secondId = -1;
    }

    void addFinger(long time, int id, float x, float y, boolean secondaryEnabled) {
        pending = pending && secondaryEnabled && !dragging && secondId == -1
                && time - startTime <= MAX_CHORD_DELAY_MILLIS;
        secondId = id;
        secondX = x;
        secondY = y;
        dragging = dragArmed = false;
    }

    void update(int id, float x, float y, boolean canStartDrag) {
        if (!pending) return;
        if (id != firstId && id != secondId) return;
        boolean first = id == firstId;
        if (distanceSquared(x, y, first ? firstX : secondX, first ? firstY : secondY) > slopSquared) {
            dragging = canStartDrag && dragArmed && first && secondId == -1;
            pending = false;
        }
    }

    boolean dragging() { return dragging; }

    int finish(long time, boolean primaryEnabled) {
        int click = 0;
        if (pending && time - startTime <= MAX_TAP_MILLIS) {
            if (secondId >= 0) click = SECONDARY;
            else if (primaryEnabled) {
                click = PRIMARY;
                lastPrimaryTap = time;
                lastPrimaryX = firstX;
                lastPrimaryY = firstY;
            }
        }
        pending = dragArmed = dragging = false;
        return click;
    }

    void cancel() {
        pending = dragArmed = dragging = false;
        lastPrimaryTap = -1;
    }

    private static float distanceSquared(float x, float y, float otherX, float otherY) {
        float dx = x - otherX;
        float dy = y - otherY;
        return dx * dx + dy * dy;
    }
}
