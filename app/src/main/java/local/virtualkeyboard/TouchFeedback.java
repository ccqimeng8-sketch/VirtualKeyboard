package local.virtualkeyboard;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/** Renders physical contact positions independently of the Bluetooth reports. */
final class TouchFeedback {
    private static final int MAX_POINTS = 64;
    private static final int TRAIL_FADE_MS = 540;
    private static final int CONTACT_FADE_MS = 160;

    private final Trail[] trails;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float spacingSquared;
    private final float trailLength;
    private final float trailWidth;
    private final float minMarkerRadius;
    private final float maxMarkerRadius;
    private final float markerPadding;
    private final float markerOutline;

    TouchFeedback(int contacts, float density) {
        trails = new Trail[contacts];
        for (int i = 0; i < contacts; i++) trails[i] = new Trail();
        spacingSquared = 4 * density * density;
        trailLength = 168 * density;
        trailWidth = 4.2f * density;
        minMarkerRadius = 28 * density;
        maxMarkerRadius = 42 * density;
        markerPadding = 7 * density;
        markerOutline = density;
        paint.setColor(0xFFB1C8EA);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    boolean record(MotionEvent event) {
        int action = event.getActionMasked();
        int changedIndex = event.getActionIndex();
        boolean starting = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN;
        boolean ending = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP;
        boolean needsDraw = false;
        long time = event.getEventTime();
        int historySize = event.getHistorySize();
        for (int i = 0; i < event.getPointerCount(); i++) {
            int id = event.getPointerId(i);
            float x = event.getX(i);
            float y = event.getY(i);
            float radius = Math.max(minMarkerRadius, Math.min(maxMarkerRadius,
                    event.getTouchMajor(i) / 2 + markerPadding));
            Trail trail = find(id);
            if (starting && i == changedIndex) {
                Trail available = trail == null ? availableTrail() : trail;
                if (available != null) {
                    available.start(id, x, y, time);
                    available.radius = radius;
                    needsDraw = true;
                }
            } else if (trail != null) {
                if (Math.abs(radius - trail.radius) >= markerOutline / 2) {
                    trail.radius = radius;
                    needsDraw = true;
                }
                needsDraw |= trail.x != x || trail.y != y || historySize > 0;
                for (int sample = 0; sample < historySize; sample++) {
                    trail.add(event.getHistoricalX(i, sample), event.getHistoricalY(i, sample),
                            event.getHistoricalEventTime(sample), spacingSquared);
                }
                if (trail.x != x || trail.y != y) {
                    trail.add(x, y, time, spacingSquared);
                }
                if (ending && i == changedIndex) {
                    trail.pointerId = -1;
                    trail.releasedAt = time;
                    needsDraw = true;
                }
            }
        }
        return needsDraw;
    }

    private Trail find(int pointerId) {
        for (Trail trail : trails) if (trail.pointerId == pointerId) return trail;
        return null;
    }

    private Trail availableTrail() {
        Trail oldest = null;
        for (Trail trail : trails) {
            if (trail.size == 0) return trail;
            if (trail.pointerId < 0 && (oldest == null || trail.releasedAt < oldest.releasedAt)) oldest = trail;
        }
        return oldest;
    }

    boolean draw(Canvas canvas, long now) {
        boolean animate = false;
        for (Trail trail : trails) {
            if (trail.size == 0) continue;
            boolean visibleTrail = drawTrail(canvas, trail, now);
            boolean down = trail.pointerId >= 0;
            float marker = down ? 1 : fade(now - trail.releasedAt, CONTACT_FADE_MS);
            if (marker > 0) drawMarker(canvas, trail, marker);
            animate |= visibleTrail || (!down && marker > 0);
            if (!down && !visibleTrail && marker == 0) trail.clear();
        }
        return animate;
    }

    private boolean drawTrail(Canvas canvas, Trail trail, long now) {
        int first = trail.index(0);
        float cutoff = trail.distances[trail.index(trail.size - 1)] - trailLength;
        paint.setStrokeWidth(trailWidth);
        boolean visible = false;
        for (int i = 1; i < trail.size; i++) {
            int next = trail.index(i);
            float distance = trail.distances[next];
            float strength = Math.min(1, (distance - cutoff) / trailLength) * fade(now - trail.times[next], TRAIL_FADE_MS);
            int alpha = Math.round(52 * strength);
            if (distance > cutoff && alpha > 0) {
                float startX = trail.pointsX[first];
                float startY = trail.pointsY[first];
                if (trail.distances[first] < cutoff) {
                    float fraction = (cutoff - trail.distances[first]) / (distance - trail.distances[first]);
                    startX += fraction * (trail.pointsX[next] - startX);
                    startY += fraction * (trail.pointsY[next] - startY);
                }
                paint.setAlpha(alpha);
                canvas.drawLine(startX, startY, trail.pointsX[next], trail.pointsY[next], paint);
                visible = true;
            }
            first = next;
        }
        return visible;
    }

    private void drawMarker(Canvas canvas, Trail trail, float opacity) {
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(Math.round(42 * opacity));
        canvas.drawCircle(trail.x, trail.y, trail.radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(markerOutline);
        paint.setAlpha(Math.round(79 * opacity));
        canvas.drawCircle(trail.x, trail.y, trail.radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private static float fade(long age, int duration) {
        return Math.max(0, 1 - (float) Math.max(0, age) / duration);
    }

    void clear() {
        for (Trail trail : trails) trail.clear();
    }

    private static final class Trail {
        private final float[] pointsX = new float[MAX_POINTS];
        private final float[] pointsY = new float[MAX_POINTS];
        private final float[] distances = new float[MAX_POINTS];
        private final long[] times = new long[MAX_POINTS];
        private int pointerId = -1;
        private int next;
        private int size;
        private long releasedAt;
        private float x;
        private float y;
        private float radius;

        private int index(int offset) { return (next + MAX_POINTS - size + offset) % MAX_POINTS; }

        private void start(int id, float x, float y, long time) {
            clear();
            pointerId = id;
            this.x = x;
            this.y = y;
            append(x, y, time, 0);
        }

        private void add(float x, float y, long time, float spacingSquared) {
            this.x = x;
            this.y = y;
            int last = index(size - 1);
            float dx = x - pointsX[last];
            float dy = y - pointsY[last];
            float stepSquared = dx * dx + dy * dy;
            if (stepSquared >= spacingSquared) append(x, y, time, distances[last] + (float) Math.sqrt(stepSquared));
        }

        private void append(float x, float y, long time, float distance) {
            pointsX[next] = x;
            pointsY[next] = y;
            times[next] = time;
            distances[next] = distance;
            next = (next + 1) % MAX_POINTS;
            size = Math.min(size + 1, MAX_POINTS);
        }

        private void clear() {
            pointerId = -1;
            next = size = 0;
            releasedAt = 0;
        }
    }
}
