package local.hdmiboot;

import android.app.Activity;
import android.os.Bundle;

// Opened once so the package leaves the "stopped" state and can receive BOOT_COMPLETED.
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LaunchService.log(this, "MainActivity opened");
        finish();
    }
}
