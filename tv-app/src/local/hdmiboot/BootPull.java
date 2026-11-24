package local.hdmiboot;

// Shared state for the "bring the HDMI picture back after boot" logic. Once the picture has stayed
// in front for a few seconds the job is done, and nothing pulls the user back any more.
final class BootPull {
    static final long STABLE_FRONT_MS = 6000;
    static volatile boolean done;
    static volatile long playerFrontSince;

    private BootPull() {
    }
}
