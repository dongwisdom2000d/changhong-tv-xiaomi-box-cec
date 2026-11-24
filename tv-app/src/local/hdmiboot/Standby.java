package local.hdmiboot;

import android.content.Context;

import java.lang.reflect.Method;

// Puts the TV into standby through the Changhong framework client (com.changhong.tvos, on the boot
// class path). The client knows the right binder numbering for the running TVService, so nothing here
// talks to the service with hand-written transaction codes.
final class Standby {
    private static final String TV_MANAGER = "com.changhong.tvos.common.TVManager";

    private static Object systemManager(Context c) throws Exception {
        Class<?> tvm = Class.forName(TV_MANAGER);
        Object manager = tvm.getMethod("getInstance", Context.class).invoke(null, c.getApplicationContext());
        if (manager == null) {
            throw new IllegalStateException("TVManager.getInstance returned null");
        }
        Object system = tvm.getMethod("getSystemManager").invoke(manager);
        if (system == null) {
            throw new IllegalStateException("getSystemManager returned null");
        }
        return system;
    }

    // Read-only check that the framework client reaches the running service. The product name is a
    // string the service has to return, so a non-null value proves the binder round trip works.
    static void probe(Context c) {
        try {
            Class<?> tvm = Class.forName(TV_MANAGER);
            Object manager = tvm.getMethod("getInstance", Context.class).invoke(null, c.getApplicationContext());
            Object misc = tvm.getMethod("getMiscManager").invoke(manager);
            Object name = misc.getClass().getMethod("getCurProductName").invoke(misc);
            Object build = misc.getClass().getMethod("getBuildTime").invoke(misc);
            Object system = systemManager(c);
            Object sleep = system.getClass().getMethod("getUserSleepTimer").invoke(system);
            LaunchService.log(c, "probe: productName=" + name + " buildTime=" + build + " userSleepTimer=" + sleep
                    + " signalStable=" + signalStable(c) + " source=" + source(c));
        } catch (Throwable t) {
            LaunchService.log(c, "probe failed: " + t);
        }
    }

    // Whether the current source has a stable signal, or null if it cannot be read.
    static Boolean signalStable(Context c) {
        try {
            Class<?> tvm = Class.forName(TV_MANAGER);
            Object manager = tvm.getMethod("getInstance", Context.class).invoke(null, c.getApplicationContext());
            Object player = tvm.getMethod("getTVPlayer").invoke(manager);
            return (Boolean) player.getClass().getMethod("isSignalStable").invoke(player);
        } catch (Throwable t) {
            return null;
        }
    }

    // Name of the current input source (for example E_INPUT_SOURCE_HDMI), or null.
    static String source(Context c) {
        try {
            Class<?> tvm = Class.forName(TV_MANAGER);
            Object manager = tvm.getMethod("getInstance", Context.class).invoke(null, c.getApplicationContext());
            Object sources = tvm.getMethod("getSourceManager").invoke(manager);
            Object src = sources.getClass().getMethod("getCurInputSourceAndScence").invoke(sources);
            return src == null ? null : src.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean now(Context c, String why) {
        try {
            Object system = systemManager(c);
            system.getClass().getMethod("systemStandby", boolean.class).invoke(system, false);
            LaunchService.log(c, "standby (" + why + "): systemStandby(false) called");
            return true;
        } catch (Throwable t) {
            LaunchService.log(c, "standby (" + why + ") failed: " + t);
            return false;
        }
    }

    private Standby() {
    }
}
