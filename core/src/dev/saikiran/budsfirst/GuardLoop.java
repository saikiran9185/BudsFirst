package dev.saikiran.budsfirst;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

/**
 * Runs the guard on the main looper. The BudsFirst app forwards Bluetooth broadcasts through
 * the nudge binder, so changes are handled the moment they happen; a slow check runs as a
 * safety net, or a fast one while the app can't be reached.
 */
final class GuardLoop {
    /** Android settles a connection change over a few hundred ms, so look again a few times. */
    private static final long[] BURST_MS = {0, 60, 200, 500, 1200};
    private static final long SAFETY_MS = 3000;
    private static final long RELINK_MS = 15000;

    private final Guard guard;
    private final Handler main;
    private final Guard.Log log;
    private AppRelay.Link link;
    private long relinkAt;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            guard.tick();
        }
    };

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            long delay = guard.tick();
            if (ensureLinked()) delay = Math.max(delay, SAFETY_MS);
            main.postDelayed(this, delay);
        }
    };

    private final Binder nudge = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return false;
            main.post(new Runnable() {
                @Override
                public void run() {
                    burst();
                }
            });
            return true;
        }
    };

    GuardLoop(Guard guard, Handler main, Guard.Log log) {
        this.guard = guard;
        this.main = main;
        this.log = log;
    }

    void start() {
        main.post(loop);
    }

    boolean isLinked() {
        return link != null && link.isAlive();
    }

    void stop() {
        main.removeCallbacks(loop);
        main.removeCallbacks(check);
        if (link != null) link.close();
    }

    private void burst() {
        main.removeCallbacks(check);
        for (long delay : BURST_MS) main.postDelayed(check, delay);
    }

    private boolean ensureLinked() {
        if (isLinked()) return true;
        if (SystemClock.elapsedRealtime() < relinkAt) return false;
        boolean wasLinked = link != null;
        link = AppRelay.attach(nudge);
        if (link == null) {
            relinkAt = SystemClock.elapsedRealtime() + RELINK_MS;
            if (wasLinked) log.line("lost the app's Bluetooth events; checking 4× a second instead");
            return false;
        }
        try {
            link.provider.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    main.removeCallbacks(loop);
                    main.postDelayed(loop, 1000);
                }
            }, 0);
        } catch (Exception ignored) {
            // Already dead: the next loop relinks.
        }
        log.line("listening to Bluetooth events");
        return true;
    }
}
