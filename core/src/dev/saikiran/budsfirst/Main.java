package dev.saikiran.budsfirst;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Command-line entry point, run as the shell user:
 *   app_process -Djava.class.path=budsfirst.dex /system/bin dev.saikiran.budsfirst.Main list|run|release
 *
 *   list     paired devices, their audio connections, switches and where sound plays now
 *   run      keep enforcing the priority file (one address per line, highest first)
 *   release  turn back on switches the old (0.1) version turned off, then exit
 */
public final class Main {
    static final File DIR = new File("/data/local/tmp/budsfirst");

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "list";
        ShellContext context = ShellContext.create();
        Handler handler = new Handler(Looper.getMainLooper());
        Guard guard = new Guard(context, new File(DIR, "blocked"), new Guard.Log() {
            @Override
            public void line(String message) {
                log(message);
            }
        });
        long deadline = System.currentTimeMillis() + 10000;

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!guard.isReady()) {
                    if (System.currentTimeMillis() > deadline) {
                        log("Bluetooth audio services did not respond (is Bluetooth on?)");
                        System.exit(1);
                    }
                    handler.postDelayed(this, 100);
                    return;
                }
                switch (command) {
                    case "list":
                        list(guard);
                        System.exit(0);
                        break;
                    case "release":
                        guard.undoLegacyBlocks();
                        System.exit(0);
                        break;
                    case "run":
                        startGuard(guard, handler);
                        break;
                    default:
                        System.err.println("usage: budsfirst list|run|release");
                        System.exit(2);
                }
            }
        });
        Looper.loop();
    }

    private static void startGuard(Guard guard, Handler handler) {
        File priorityFile = new File(DIR, "priority");
        try {
            Files.write(new File(DIR, "pid").toPath(), String.valueOf(Process.myPid()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log("could not write pid: " + e);
        }
        log("guard running as uid " + Process.myUid());
        guard.setPriority(readPriority(priorityFile));
        new GuardLoop(guard, handler, new Guard.Log() {
            @Override
            public void line(String message) {
                log(message);
            }
        }).start();
        handler.post(new Runnable() {
            long loaded = priorityFile.lastModified();

            @Override
            public void run() {
                if (priorityFile.lastModified() != loaded) {
                    loaded = priorityFile.lastModified();
                    guard.setPriority(readPriority(priorityFile));
                }
                handler.postDelayed(this, 2000);
            }
        });
    }

    private static List<String> readPriority(File file) {
        List<String> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String address = line.replaceAll("#.*", "").trim().split("\\s+")[0];
                if (address.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) out.add(address);
            }
        } catch (IOException e) {
            log("no priority file at " + file);
        }
        return out;
    }

    private static void list(Guard guard) {
        Set<String> connected = guard.connectedAddresses();
        String media = guard.activeDevice(Guard.A2DP);
        String calls = guard.activeDevice(Guard.HEADSET);
        for (BluetoothDevice d : guard.bondedDevices()) {
            StringBuilder sb = new StringBuilder();
            sb.append(d.getAddress()).append("  ").append(guard.name(d.getAddress()));
            sb.append(connected.contains(d.getAddress()) ? "  [connected]" : "");
            sb.append(d.getAddress().equals(media) ? "  [media plays here]" : "");
            sb.append(d.getAddress().equals(calls) ? "  [calls play here]" : "");
            for (int profile : new int[] {Guard.HEADSET, Guard.A2DP}) {
                BluetoothProfile p = guard.proxies().get(profile);
                if (p == null) continue;
                int policy = Guard.policy(p, d);
                if (policy < 0 && p.getConnectionState(d) == BluetoothProfile.STATE_DISCONNECTED) continue;
                sb.append("  ").append(profile == Guard.HEADSET ? "calls" : "media")
                        .append('=').append(policy == Guard.POLICY_ALLOWED ? "on" : policy == Guard.POLICY_FORBIDDEN ? "OFF" : "?")
                        .append(p.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED ? "*" : "");
            }
            System.out.println(sb);
        }
    }

    static void log(String message) {
        System.out.println(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + message);
        System.out.flush();
    }
}
