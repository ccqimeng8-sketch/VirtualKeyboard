package local.virtualkeyboard;

import java.util.ArrayDeque;

/** Interpolates confirmed contact positions and evens out brief changes in speed. */
final class PointerSamples {
    private static final int MAX_SAMPLES = 32;
    private static final int SMOOTHING_RADIUS_MS = 8;

    record Sample(long time, float x, float y) {}

    private final ArrayDeque<Sample> samples = new ArrayDeque<>(MAX_SAMPLES);

    void add(long time, float x, float y) {
        Sample last = samples.peekLast();
        if (last != null) {
            if (time < last.time()) return;
            if (time == last.time()) samples.removeLast();
        }
        if (samples.size() == MAX_SAMPLES) samples.removeFirst();
        samples.addLast(new Sample(time, x, y));
    }

    Sample smoothAt(long time) {
        Sample center = at(time);
        if (time <= samples.getFirst().time() || time >= latest().time()) return center;

        Sample before = at(time - SMOOTHING_RADIUS_MS);
        Sample after = at(time + SMOOTHING_RADIUS_MS);
        return new Sample(time, (before.x() + center.x() + after.x()) / 3,
                (before.y() + center.y() + after.y()) / 3);
    }

    private Sample at(long time) {
        Sample before = samples.getFirst();
        if (time <= before.time()) return before;
        for (Sample after : samples) {
            if (time == after.time()) return after;
            if (time < after.time()) {
                float portion = (float) (time - before.time()) / (after.time() - before.time());
                return new Sample(time, before.x() + (after.x() - before.x()) * portion,
                        before.y() + (after.y() - before.y()) * portion);
            }
            before = after;
        }
        return before;
    }

    Sample latest() { return samples.getLast(); }

    void clear() { samples.clear(); }
}
