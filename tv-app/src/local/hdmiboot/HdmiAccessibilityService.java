package local.hdmiboot;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

// The system binds enabled accessibility services on every boot, independent of broadcasts,
// so this is a second trigger next to BootReceiver.
public class HdmiAccessibilityService extends AccessibilityService {
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        boolean recent = LaunchService.isRecentBoot();
        LaunchService.log(this, "accessibility connected, recentBoot=" + recent);
        SignalWatcher.ensureStarted(this);
        if (recent) {
            startService(new Intent(this, LaunchService.class));
        }
    }

    private static final String LAUNCHER_PKG = "com.changhong.hotelv3";
    private static final long PULL_WINDOW_MS = 90 * 1000L;
    private static final int MAX_PULLS = 6;
    private int pulls;

    // Right after boot, every time the hotel launcher window appears, put the HDMI picture back in front.
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null) {
            return;
        }
        SignalWatcher.onWindowChanged(pkg.toString());
        if (!LAUNCHER_PKG.contentEquals(pkg)) {
            return;
        }
        if (BootPull.done || android.os.SystemClock.elapsedRealtime() > PULL_WINDOW_MS
                || pulls >= MAX_PULLS) {
            return;
        }
        pulls++;
        LaunchService.startAtv(this, "launcher shown, pull " + pulls);
    }

    @Override
    public void onInterrupt() {
    }
}
