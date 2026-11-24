package local.hdmiboot;

import android.content.Context;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

// Turns the TV off when the Xiaomi box is switched off.
//
// The TV ignores the CEC standby message the box sends, but it does notice that the HDMI signal
// is gone (ITVPlayer.isSignalStable() flips to false about a second after the box sleeps). So a
// loss that lasts longer than GRACE_MS, on a source that had a stable signal, puts the TV into
// standby, the same as its power key. It only sees the box while HDMI is the current source
// (on the TV desktop the source is E_INPUT_SOURCE_OTHER).
//
// A loss that starts right after the user changed the foreground app is ignored, so switching
// between the picture and the TV desktop can never switch the TV off.
final class SignalWatcher extends Thread {
    private static final String HDMI_PLAYER_PKG = "com.changhong.tvap.atv.activity";
    // Windows that can appear on top of the picture without the user leaving it.
    private static final Set<String> OVERLAYS = new HashSet<String>(Arrays.asList(
            "android", "com.android.systemui", "local.hdmiboot",
            "com.changhong.dmt.system.misc", "com.changhong.source"));
    private static final long POLL_MS = 1000;
    private static final long GRACE_MS = 3000;
    private static final long UI_CHANGE_WINDOW_MS = 3000;
    private static final long REPEAT_MS = 60 * 1000L;

    private static boolean started;
    private static volatile boolean playerInFront;
    private static volatile long lastForegroundChangeAt;
    private static volatile String lastForegroundPkg = "";

    private final Context context;

    static synchronized void ensureStarted(Context c) {
        if (started) {
            return;
        }
        started = true;
        new SignalWatcher(c.getApplicationContext()).start();
    }

    // Called for every window change the accessibility service sees.
    static void onWindowChanged(String pkg) {
        if (OVERLAYS.contains(pkg)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        lastForegroundChangeAt = now;
        lastForegroundPkg = pkg;
        boolean front = HDMI_PLAYER_PKG.equals(pkg);
        if (front && !playerInFront) {
            BootPull.playerFrontSince = now;
        } else if (!front) {
            BootPull.playerFrontSince = 0;
        }
        playerInFront = front;
    }

    private SignalWatcher(Context c) {
        super("SignalWatcher");
        setDaemon(true);
        context = c;
    }

    @Override
    public void run() {
        boolean armed = false;
        String armedSource = null;
        long lossStartedAt = 0;
        boolean ignoreLoss = false;
        long lastStandbyAt = 0;
        Boolean prevStable = null;
        String prevSource = null;
        LaunchService.log(context, "signal watcher started");
        while (true) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            // The boot-time pull is finished once the picture has stayed in front long enough.
            if (!BootPull.done && playerInFront && BootPull.playerFrontSince != 0
                    && now - BootPull.playerFrontSince >= BootPull.STABLE_FRONT_MS) {
                BootPull.done = true;
                LaunchService.log(context, "boot pull done: picture stayed in front");
            }
            Boolean stable = Standby.signalStable(context);
            String source = Standby.source(context);
            if (stable == null || source == null) {
                continue;
            }
            if (!stable.equals(prevStable) || !source.equals(prevSource)) {
                LaunchService.log(context, "state: stable=" + stable + " source=" + source
                        + " fg=" + lastForegroundPkg);
                prevStable = stable;
                prevSource = source;
            }
            if (stable) {
                if (!armed || !source.equals(armedSource)) {
                    LaunchService.log(context, "armed: " + source + " signal stable");
                }
                armed = true;
                armedSource = source;
                lossStartedAt = 0;
                ignoreLoss = false;
                continue;
            }
            // No signal. Only a loss on the source that was showing a picture counts.
            if (!armed || !source.equals(armedSource)) {
                armed = false;
                armedSource = null;
                lossStartedAt = 0;
                continue;
            }
            if (lossStartedAt == 0) {
                lossStartedAt = now;
                long sinceUi = now - lastForegroundChangeAt;
                ignoreLoss = lastForegroundChangeAt != 0 && sinceUi < UI_CHANGE_WINDOW_MS;
                LaunchService.log(context, "signal lost on " + source
                        + (ignoreLoss ? ", ignored: foreground changed " + sinceUi + " ms ago"
                        : ", waiting " + GRACE_MS + " ms"));
            }
            if (!ignoreLoss && now - lossStartedAt >= GRACE_MS
                    && (lastStandbyAt == 0 || now - lastStandbyAt > REPEAT_MS)) {
                lastStandbyAt = now;
                armed = false;
                armedSource = null;
                lossStartedAt = 0;
                Standby.now(context, "HDMI signal lost");
            }
        }
    }
}
