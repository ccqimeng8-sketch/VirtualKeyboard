package local.virtualkeyboard;

import java.io.ByteArrayOutputStream;

final class HidReports {
    static final int KEYBOARD = 1;
    static final int CONSUMER = 2;
    static final int TRACKPAD = 3;
    static final int SLOT_COUNT = 5;
    static final int COMPACT_SLOT_COUNT = 3;
    static final int REPORT_RATE = 60;
    static final int GLOBE = 0x029D;

    // Apple Accessory Design Guidelines, §14.2 and §14.3. The keyboard and
    // Digitizer / Touch Pad top-level collections share one HID service.
    static byte[] descriptor(int physicalX, int physicalY, int logicalX, int logicalY, boolean compact) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bytes(out, 0x05, 0x01, 0x09, 0x06, 0xA1, 1, 0x85, KEYBOARD);
        bytes(out, 0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0, 0x25, 1,
                0x75, 1, 0x95, 8, 0x81, 2); // Modifier bits
        bytes(out, 0x75, 8, 0x95, 1, 0x81, 1); // Boot keyboard reserved byte
        bytes(out, 0x19, 0, 0x29, 0xFF, 0x25, 0xFF, 0x95, 6, 0x81, 0);
        if (!compact) {
            bytes(out, 0x05, 8, 0x19, 1, 0x29, 5, 0x25, 1, 0x75, 1, 0x95, 5, 0x91, 2,
                    0x95, 3, 0x91, 1); // LEDs
        }
        bytes(out, 0xC0);
        if (!compact) {
            bytes(out, 0x05, 0x0C, 0x09, 1, 0xA1, 1, 0x85, CONSUMER, 0x15, 0,
                    0x26, 0x9D, 0x02, 0x19, 0, 0x2A, 0x9D, 0x02,
                    0x75, 16, 0x95, 1, 0x81, 0, 0xC0);
        }

        bytes(out, 0x05, 0x0D, 0x09, 5, 0xA1, 1, 0x85, TRACKPAD,
                0x26, 0xFF, 0xFF, 0x09, 0x56, 0x75, 16, 0x95, 1, 0x81, 2);
        // Feature report packs Surface Switch in bit zero and rate in bits 1–15.
        bytes(out, 0x25, 1, 0x75, 1, 0x09, 0x57, 0xB1, 2);
        if (compact) bytes(out, 0x75, 7, 0xB1, 1);
        else bytes(out, 0x26, 0xFF, 0x7F, 0x75, 15, 0x09, 0xA1, 0xB1, 2);
        bytes(out, 0x05, 9, 0x25, 1, 0x19, 1, 0x29, 2, 0x95, 2, 0x75, 1, 0x81, 2,
                0x95, 6, 0x81, 1);
        bytes(out, 0x05, 0x0D, 0x95, 2);
        int count = slotCount(compact);
        for (int slot = 0; slot < count; slot++) {
            bytes(out, 0xA4, 0x09, 0x22, 0xA1, 0,
                    0x09, 0x42, 0x09, 0x47, 0x81, 2, // Tip, confidence
                    0x09, 0x38, 0x25, count - 1, 0x75, 6, 0x95, 1, 0x81, 2);
            bytes(out, 0x05, 1, 0x75, 12, 0x55, 0x0E, 0x65, 0x11);
            axis(out, 0x30, physicalX, logicalX);
            axis(out, 0x31, physicalY, logicalY);
            bytes(out, 0xC0, 0xB4);
        }
        bytes(out, 0xC0);
        return out.toByteArray();
    }

    private static void axis(ByteArrayOutputStream out, int usage, int physical, int logical) {
        bytes(out, 0x46, physical, physical >> 8, 0x26, logical, logical >> 8,
                0x09, usage, 0x81, 2);
    }

    private static void bytes(ByteArrayOutputStream out, int... values) {
        for (int value : values) out.write(value);
    }

    static byte[] keyboard(int modifiers, int usage) {
        return new byte[] {(byte) modifiers, 0, (byte) usage, 0, 0, 0, 0, 0};
    }

    static byte[] consumer(int usage) {
        return new byte[] {(byte) usage, (byte) (usage >> 8)};
    }

    static byte[] feature(boolean surfaceEnabled, boolean compact) {
        if (compact) return new byte[] {(byte) (surfaceEnabled ? 1 : 0)};
        return consumer(REPORT_RATE * 2 + (surfaceEnabled ? 1 : 0));
    }

    static int slotCount(boolean compact) { return compact ? COMPACT_SLOT_COUNT : SLOT_COUNT; }

    static byte[] pad(long eventMillis, int buttons, int slots) {
        // Scan time is a wrapping 16-bit clock in units of 100 microseconds.
        int scan = (int) (eventMillis * 10);
        byte[] report = new byte[3 + 4 * slots];
        report[0] = (byte) scan;
        report[1] = (byte) (scan >> 8);
        report[2] = (byte) buttons;
        return report;
    }

    static void contact(byte[] report, int slot, boolean down, boolean confident, int x, int y) {
        int offset = 3 + 4 * slot;
        report[offset] = (byte) ((slot << 2) | (confident ? 2 : 0) | (down ? 1 : 0));
        int xy = x | (y << 12);
        report[offset + 1] = (byte) xy;
        report[offset + 2] = (byte) (xy >> 8);
        report[offset + 3] = (byte) (xy >> 16);
    }

    private HidReports() {}
}
