package dev.saikiran.budsfirst;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the sound on the highest-priority connected device, without disconnecting anything.
 *
 * Android makes a device the "active" one (where media and calls play) the moment it connects,
 * and multipoint headphones can take it again later. Whenever a lower-priority device in the
 * list is active while a higher one is connected, the guard makes the higher one active again —
 * both stay connected, only the output moves. When the active device leaves (earbuds back in the
 * case), Android 13 leaves no device active, so the guard moves the sound to the next connected
 * device in the list straight away. Devices outside the list (a car, a watch) are left alone.
 *
 * Android 13 doesn't deliver broadcasts to a process that isn't an app, so the BudsFirst app
 * forwards them (see GuardLoop). tick()'s suggested delay — FAST_MS while a device from the list
 * is connected, SLOW_MS otherwise — is only used while the app can't be reached.
 */
final class Guard {
    static final int HEADSET = BluetoothProfile.HEADSET;
    static final int A2DP = BluetoothProfile.A2DP;
    static final int POLICY_FORBIDDEN = 0;
    static final int POLICY_ALLOWED = 100;
    private static final int ACTIVE_DEVICE_AUDIO = 0;
    private static final int ACTIVE_DEVICE_PHONE_CALL = 1;
    static final long FAST_MS = 250;
    static final long SLOW_MS = 1000;
    /** More switches than this within SWITCH_WINDOW_MS means something is fighting back. */
    private static final int MAX_SWITCHES = 6;
    private static final long SWITCH_WINDOW_MS = 10000;
    private static final long BACK_OFF_MS = 30000;
    private static final long PENDING_MS = 400;
    private static final long SETTLE_MS = 2000;

    interface Log {
        void line(String message);
    }

    private final BluetoothAdapter adapter;
    private final Map<Integer, BluetoothProfile> proxies = new HashMap<>();
    private final File legacyBlockedFile;
    private final Log log;
    private List<String> priority = new ArrayList<>();
    /** Last non-null active device per profile. */
    private final Map<Integer, String> lastActive = new HashMap<>();
    /** Recent switch times per profile, and when backing off ends. */
    private final Map<Integer, List<Long>> switches = new HashMap<>();
    private final Map<Integer, Long> backOffUntil = new HashMap<>();
    private final Map<Integer, String> lastTarget = new HashMap<>();
    private final Map<Integer, Long> lastSwitchAt = new HashMap<>();

    Guard(Context context, File legacyBlockedFile, Log log) throws ReflectiveOperationException {
        this.legacyBlockedFile = legacyBlockedFile;
        this.log = log;
        // Built from our Context (not getSystemService) so requests carry the shell identity.
        BluetoothManager manager = BluetoothManager.class.getDeclaredConstructor(Context.class).newInstance(context);
        adapter = manager.getAdapter();
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                proxies.put(profile, proxy);
                if (profile == A2DP) undoLegacyBlocks();
            }

            @Override
            public void onServiceDisconnected(int profile) {
                proxies.remove(profile);
            }
        };
        adapter.getProfileProxy(context, listener, HEADSET);
        adapter.getProfileProxy(context, listener, A2DP);
    }

    boolean isReady() {
        return proxies.containsKey(HEADSET) && proxies.containsKey(A2DP);
    }

    void setPriority(List<String> addresses) {
        List<String> list = new ArrayList<>();
        for (String a : addresses) list.add(a.trim().toUpperCase(Locale.ROOT));
        if (!list.equals(priority)) {
            priority = list;
            log.line("priority: " + describe(list));
        }
    }

    /** Applies the rule once; returns how long to wait before the next check. */
    long tick() {
        if (!isReady() || !adapter.isEnabled()) return SLOW_MS * 2;
        boolean watching = enforce(A2DP) | enforce(HEADSET);
        return watching ? FAST_MS : SLOW_MS;
    }

    /** Returns whether a device from the list is connected on this profile. */
    private boolean enforce(int profile) {
        BluetoothProfile proxy = proxies.get(profile);
        if (proxy == null) return false;
        Set<String> connected = new HashSet<>();
        for (BluetoothDevice d : proxy.getConnectedDevices()) connected.add(d.getAddress());
        String desired = null;
        for (String address : priority) {
            if (connected.contains(address)) {
                desired = address;
                break;
            }
        }
        String active = activeDevice(profile);
        String gone = null;
        if (active != null && !connected.contains(active)) {
            // Disconnected but still listed as active: Android hasn't caught up yet. Don't wait.
            gone = active;
            active = null;
        }
        if (desired != null && !desired.equals(active)) {
            if (active == null) {
                // Nothing plays on Bluetooth. If the playing device left — or Android cleared the
                // output right after our own switch — hand over now. If it's still connected,
                // someone chose the phone itself: leave that be.
                String last = gone != null ? gone : lastActive.get(profile);
                boolean left = last != null && !connected.contains(last);
                if (last == null || left || settling(profile)) {
                    if (switchTo(desired, profile, left ? name(last) + " left" : "nothing was playing")) active = desired;
                }
            } else if (priority.contains(active) && priority.indexOf(active) > priority.indexOf(desired)) {
                if (switchTo(desired, profile, name(active) + " had taken it")) active = desired;
            }
        }
        if (active != null) lastActive.put(profile, active);
        return desired != null;
    }

    /** Within SETTLE_MS of our last switch on this profile. */
    private boolean settling(int profile) {
        Long at = lastSwitchAt.get(profile);
        return at != null && SystemClock.elapsedRealtime() - at < SETTLE_MS;
    }

    private boolean switchTo(String address, int profile, String why) {
        long now = SystemClock.elapsedRealtime();
        // Already asked for this; Android is still applying it.
        Long at = lastSwitchAt.get(profile);
        if (address.equals(lastTarget.get(profile)) && at != null && now - at < PENDING_MS) return true;
        Long until = backOffUntil.get(profile);
        if (until != null && now < until) return false;
        List<Long> recent = switches.get(profile);
        if (recent == null) switches.put(profile, recent = new ArrayList<>());
        while (!recent.isEmpty() && now - recent.get(0) > SWITCH_WINDOW_MS) recent.remove(0);
        if (recent.size() >= MAX_SWITCHES) {
            backOffUntil.put(profile, now + BACK_OFF_MS);
            recent.clear();
            log.line("something keeps taking the " + (profile == A2DP ? "media" : "calls") + "; pausing 30 s");
            return false;
        }
        recent.add(now);
        lastTarget.put(profile, address);
        lastSwitchAt.put(profile, now);
        BluetoothDevice device = adapter.getRemoteDevice(address);
        int use = profile == A2DP ? ACTIVE_DEVICE_AUDIO : ACTIVE_DEVICE_PHONE_CALL;
        try {
            boolean ok = (boolean) BluetoothAdapter.class
                    .getMethod("setActiveDevice", BluetoothDevice.class, int.class)
                    .invoke(adapter, device, use);
            log.line((profile == A2DP ? "media" : "calls") + " → " + name(address) + " (" + why + ")"
                    + (ok ? "" : " — refused"));
            return ok;
        } catch (InvocationTargetException e) {
            log.line("setActiveDevice failed: " + e.getCause());
        } catch (ReflectiveOperationException e) {
            log.line("setActiveDevice unavailable: " + e);
        }
        return false;
    }

    /** Address of the device media (A2DP) or calls (HEADSET) currently play on, or null. */
    String activeDevice(int profile) {
        try {
            @SuppressWarnings("unchecked")
            List<BluetoothDevice> active = (List<BluetoothDevice>) BluetoothAdapter.class
                    .getMethod("getActiveDevices", int.class).invoke(adapter, profile);
            for (BluetoothDevice d : active) {
                if (d != null) return d.getAddress();
            }
        } catch (ReflectiveOperationException ignored) {
            // Not available: treat as unknown.
        }
        return null;
    }

    /** The highest-priority device connected for media, or null. */
    String holder() {
        BluetoothProfile a2dp = proxies.get(A2DP);
        if (a2dp == null) return null;
        Set<String> connected = new HashSet<>();
        for (BluetoothDevice d : a2dp.getConnectedDevices()) connected.add(d.getAddress());
        for (String address : priority) {
            if (connected.contains(address)) return address;
        }
        return null;
    }

    Set<String> connectedAddresses() {
        Set<String> out = new HashSet<>();
        for (BluetoothProfile p : proxies.values()) {
            for (BluetoothDevice d : p.getConnectedDevices()) out.add(d.getAddress());
        }
        return out;
    }

    List<BluetoothDevice> bondedDevices() {
        return new ArrayList<>(adapter.getBondedDevices());
    }

    Map<Integer, BluetoothProfile> proxies() {
        return proxies;
    }

    String name(String address) {
        String alias = adapter.getRemoteDevice(address).getAlias();
        return alias != null ? alias : address;
    }

    /**
     * Version 0.1 kept lower devices off by switching their "Phone calls" / "Media audio" off.
     * Turn back on whatever it switched off, once, then forget the record.
     */
    void undoLegacyBlocks() {
        List<String> lines;
        try {
            lines = Files.readAllLines(legacyBlockedFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        List<String> left = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.trim().split(" ");
            if (parts.length != 2) continue;
            BluetoothProfile p = proxies.get(Integer.parseInt(parts[1]));
            BluetoothDevice d = adapter.getRemoteDevice(parts[0]);
            if (p != null && setPolicy(p, d, POLICY_ALLOWED)) log.line("switched " + name(parts[0]) + " back on");
            else left.add(line.trim());
        }
        if (left.isEmpty()) {
            if (!legacyBlockedFile.delete()) log.line("could not delete " + legacyBlockedFile);
        } else {
            try {
                Files.write(legacyBlockedFile.toPath(), String.join("\n", left).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.line("could not save " + legacyBlockedFile + ": " + e);
            }
        }
    }

    static int policy(BluetoothProfile p, BluetoothDevice d) {
        try {
            return (int) p.getClass().getMethod("getConnectionPolicy", BluetoothDevice.class).invoke(p, d);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    private boolean setPolicy(BluetoothProfile p, BluetoothDevice d, int policy) {
        try {
            return (boolean) p.getClass()
                    .getMethod("setConnectionPolicy", BluetoothDevice.class, int.class)
                    .invoke(p, d, policy);
        } catch (InvocationTargetException e) {
            log.line("setConnectionPolicy failed: " + e.getCause());
        } catch (ReflectiveOperationException e) {
            log.line("setConnectionPolicy unavailable: " + e);
        }
        return false;
    }

    private String describe(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append(i == 0 ? "" : " > ").append(name(list.get(i)));
        }
        return list.isEmpty() ? "(empty)" : sb.toString();
    }
}
