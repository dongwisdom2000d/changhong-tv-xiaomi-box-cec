package local.hdmiboot;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// After boot, bring the Changhong source player (the HDMI picture) in front of the hotel launcher.
// The launcher starts a few seconds after BOOT_COMPLETED, so the start is repeated a few times;
// starting an activity that is already in front does nothing.
public class LaunchService extends Service {
    private static final String PKG = "com.changhong.tvap.atv.activity";
    private static final String CLS = "com.changhong.tvap.atv.activity.AtvMainActivity";
    private static final long[] DELAYS_MS = {1500, 2500, 3000, 4000, 5000, 7000, 10000, 15000};
    private static boolean running;

    // BOOT_COMPLETED style triggers only count shortly after the TV has booted.
    static boolean isRecentBoot() {
        return android.os.SystemClock.elapsedRealtime() < 4 * 60 * 1000L;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "probe".equals(intent.getStringExtra("cmd"))) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Standby.probe(LaunchService.this);
                }
            }).start();
            return START_NOT_STICKY;
        }
        synchronized (LaunchService.class) {
            if (running) {
                return START_NOT_STICKY;
            }
            running = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < DELAYS_MS.length; i++) {
                        Thread.sleep(DELAYS_MS[i]);
                        if (BootPull.done) {
                            log(LaunchService.this, "retries stopped: boot pull done");
                            break;
                        }
                        launch(i + 1);
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    synchronized (LaunchService.class) {
                        running = false;
                    }
                    stopSelf();
                }
            }
        }).start();
        return START_NOT_STICKY;
    }

    private void launch(int attempt) {
        startAtv(this, "attempt " + attempt);
    }

    static void startAtv(Context c, String why) {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(PKG, CLS));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            log(c, "start AtvMainActivity " + why);
        } catch (Exception e) {
            log(c, "start failed " + why + ": " + e);
        }
    }

    static void log(Context c, String msg) {
        try {
            File f = new File(c.getFilesDir(), "log.txt");
            FileWriter w = new FileWriter(f, true);
            w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " up=" + (android.os.SystemClock.elapsedRealtime() / 1000) + "s " + msg + "\n");
            w.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
