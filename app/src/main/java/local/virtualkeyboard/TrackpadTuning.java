package local.virtualkeyboard;

record TrackpadTuning(int sensitivityX, int sensitivityY, int acceleration, int multiGain, int singleReverse,
                     int scrollReverse, int twoFingerStartDp, int threeFingerStartDp, boolean twoFinger,
                     boolean threeFinger, boolean tapToClick) {
    static final int REVERSE_Y = 1;
    static final int REVERSE_X = 2;
}
