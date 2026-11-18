package local.virtualkeyboard;

/** USB HID usages for the US physical keyboard layout. */
final class HidKeyMap {
    private static final String BASE = "1234567890-=[]\\;'`,./";
    private static final String SHIFTED = "!@#$%^&*()_+{}|:\"~<>?";
    private static final int[] USAGES = {0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27,
            0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38};

    record Stroke(int usage, int modifiers) { }

    static Stroke forCharacter(char character) {
        if (character >= 'a' && character <= 'z') return new Stroke(4 + character - 'a', 0);
        int index = BASE.indexOf(character);
        if (index >= 0) return new Stroke(USAGES[index], 0);
        index = SHIFTED.indexOf(character);
        if (index >= 0) return new Stroke(USAGES[index], 2);
        throw new IllegalArgumentException("Unsupported HID character: " + character);
    }

    private HidKeyMap() { }
}
