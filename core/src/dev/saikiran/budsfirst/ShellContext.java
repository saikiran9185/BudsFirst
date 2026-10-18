package dev.saikiran.budsfirst;

import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * A Context for code running as the adb shell user (through adb or Shizuku), not as an app.
 * Bluetooth checks that the calling uid owns the package in the AttributionSource, so every
 * request is made in the name of com.android.shell — the package that holds BLUETOOTH_PRIVILEGED.
 */
final class ShellContext extends ContextWrapper {
    static final String PACKAGE = "com.android.shell";

    private final Handler main = new Handler(Looper.getMainLooper());

    private ShellContext(Context base) {
        super(base);
    }

    static ShellContext create() throws ReflectiveOperationException {
        ShellContext context = new ShellContext(systemContext());
        connectBluetoothModule();
        return context;
    }

    /**
     * Since Android 13 the Bluetooth framework finds its system service through a hook that app
     * startup installs; a bare shell process never runs that, so BluetoothAdapter would be null.
     */
    private static void connectBluetoothModule() throws ReflectiveOperationException {
        Class<?> initializer;
        Class<?> serviceManager;
        try {
            initializer = Class.forName("android.bluetooth.BluetoothFrameworkInitializer");
            serviceManager = Class.forName("android.os.BluetoothServiceManager");
        } catch (ClassNotFoundException e) {
            return; // Older Android: BluetoothAdapter looks the service up itself.
        }
        if (initializer.getMethod("getBluetoothServiceManager").invoke(null) == null) {
            Object manager = serviceManager.getDeclaredConstructor().newInstance();
            initializer.getMethod("setBluetoothServiceManager", serviceManager).invoke(null, manager);
        }
    }

    @Override
    public String getPackageName() {
        return PACKAGE;
    }

    @Override
    public String getOpPackageName() {
        return PACKAGE;
    }

    @Override
    public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(Process.myUid()).setPackageName(PACKAGE).build();
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    /**
     * Bluetooth profile proxies bind their service through the Context, but Android only lets
     * app processes bind services. The BudsFirst app binds it instead and hands over the binder
     * (see AppRelay), so the framework's own proxy code runs unchanged — including rebinding
     * after Bluetooth is switched off and on. Not an @Override: the method is hidden in the SDK.
     */
    public boolean bindServiceAsUser(Intent service, final ServiceConnection connection, int flags, UserHandle user) {
        final ComponentName component = service.getComponent();
        final IBinder binder = component == null ? null : AppRelay.fetch(component);
        if (binder == null) return false;
        try {
            binder.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            connection.onServiceDisconnected(component);
                        }
                    });
                }
            }, 0);
        } catch (RemoteException e) {
            return false;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                connection.onServiceConnected(component, binder);
            }
        });
        return true;
    }

    @Override
    public void unbindService(ServiceConnection connection) {
        // Nothing is bound from this process; the app holds the real binding.
    }

    /** The framework's system Context, creating a bare ActivityThread first if this process has none. */
    private static Context systemContext() throws ReflectiveOperationException {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
        if (thread == null) {
            Constructor<?> constructor = activityThread.getDeclaredConstructor();
            constructor.setAccessible(true);
            thread = constructor.newInstance();
            Field current = activityThread.getDeclaredField("sCurrentActivityThread");
            current.setAccessible(true);
            current.set(null, thread);
            Field system = activityThread.getDeclaredField("mSystemThread");
            system.setAccessible(true);
            system.setBoolean(thread, true);
        }
        return (Context) activityThread.getMethod("getSystemContext").invoke(thread);
    }
}
