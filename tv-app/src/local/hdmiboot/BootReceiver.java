package local.hdmiboot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean test = "local.hdmiboot.TEST".equals(action);
        boolean recent = LaunchService.isRecentBoot();
        LaunchService.log(context, "received " + action + ", recentBoot=" + recent);
        if (test || recent) {
            context.startService(new Intent(context, LaunchService.class));
        }
    }
}
