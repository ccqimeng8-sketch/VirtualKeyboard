package local.virtualkeyboard;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.TextView;

final class GestureTestView extends TextView {
    private int fingers;

    GestureTestView(Context context) {
        super(context);
        setGravity(Gravity.CENTER);
        setText("在这里双指或三指滑动");
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                fingers = 1;
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            case MotionEvent.ACTION_POINTER_DOWN -> fingers = Math.max(fingers, event.getPointerCount());
            case MotionEvent.ACTION_CANCEL -> setText("触摸中断，可能被系统手势接管。再试一次。");
            case MotionEvent.ACTION_UP -> setText(fingers < 2 ? "试试双指或三指一起滑"
                    : "收到完整的 " + fingers + " 指触摸");
            default -> { }
        }
        return true;
    }
}
