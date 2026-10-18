package dev.saikiran.budsfirst;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The guard itself. Shizuku runs this in its own process as the shell user, which is what makes
 * choosing the active Bluetooth audio device possible. It keeps running after the app closes.
 * Binder calls arrive on binder threads; all Bluetooth work happens on the main looper.
 */
public final class GuardService extends IGuard.Stub {
    private static final String TAG = "BudsFirst";
    private static final File PRIORITY_FILE = new File(Main.DIR, "priority");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private Guard guard;
    private GuardLoop loop;
    private volatile String status = "{}";

    public GuardService() {
        main.post(this::start);
    }

    public GuardService(Context context) {
        this();
    }

    private void start() {
        try {
            Main.DIR.mkdirs();
            guard = new Guard(ShellContext.create(), new File(Main.DIR, "blocked"), this::log);
            guard.setPriority(readPriority());
            log("guard started (uid " + Process.myUid() + ")");
        } catch (Exception e) {
            log("could not start: " + e);
            status = "{\"error\":" + JSONObject.quote(e.toString()) + "}";
            return;
        }
        loop = new GuardLoop(guard, main, this::log);
        loop.start();
        main.post(new Runnable() {
            @Override
            public void run() {
                status = buildStatus();
                main.postDelayed(this, 1000);
            }
        });
    }

    @Override
    public void setPriority(List<String> addresses) {
        List<String> copy = new ArrayList<>(addresses);
        main.post(() -> {
            if (guard != null) guard.setPriority(copy);
            try {
                Files.write(PRIORITY_FILE.toPath(), String.join("\n", copy).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                log("could not save priority: " + e);
            }
        });
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public void releaseAll() {
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            if (guard != null) guard.undoLegacyBlocks();
            done.countDown();
        });
        try {
            done.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void destroy() {
        releaseAll();
        if (loop != null) loop.stop();
        System.exit(0);
    }

    private List<String> readPriority() {
        List<String> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(PRIORITY_FILE.toPath(), StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) out.add(line.trim());
            }
        } catch (IOException ignored) {
            // The app sends the list as soon as it connects.
        }
        return out;
    }

    private String buildStatus() {
        try {
            JSONObject o = new JSONObject();
            o.put("ready", guard.isReady());
            o.put("uid", Process.myUid());
            o.put("linked", loop != null && loop.isLinked());
            o.put("holder", guard.holder() == null ? JSONObject.NULL : guard.holder());
            o.put("connected", new JSONArray(guard.connectedAddresses()));
            String media = guard.activeDevice(Guard.A2DP);
            String calls = guard.activeDevice(Guard.HEADSET);
            o.put("media", media == null ? JSONObject.NULL : media);
            o.put("calls", calls == null ? JSONObject.NULL : calls);
            synchronized (log) {
                o.put("log", new JSONArray(log));
            }
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private void log(String message) {
        Log.i(TAG, message);
        String line = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date()) + "  " + message;
        synchronized (log) {
            log.addFirst(line);
            while (log.size() > 30) log.removeLast();
        }
    }
}
