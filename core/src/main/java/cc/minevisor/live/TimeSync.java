package cc.minevisor.live;

public final class TimeSync {

    private static final int REFRESH_TICKS = 200;
    private static final long DRIFT_TICKS = 20;

    private final EventHub events;
    private long ticks;
    private long lastSent = -1;
    private long lastTime;
    private boolean lastTicking;

    public TimeSync(EventHub events) {
        this.events = events;
    }

    public void tick(long time, boolean ticking) {
        ticks++;
        long expected = lastTicking ? lastTime + (ticks - lastSent) : lastTime;
        boolean jumped = Math.abs(time - expected) > DRIFT_TICKS;
        if (lastSent >= 0 && !jumped && ticking == lastTicking && ticks - lastSent < REFRESH_TICKS) return;
        lastSent = ticks;
        lastTime = time;
        lastTicking = ticking;
        events.publishState("time", "{\"time\":" + time + ",\"ticking\":" + ticking + "}");
    }
}
