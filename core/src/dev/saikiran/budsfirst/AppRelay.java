package dev.saikiran.budsfirst;

import android.content.AttributionSource;
import android.content.ComponentName;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Talks to the BudsFirst app's RelayProvider from the shell process. Uses
 * getContentProviderExternal — the route the `content` shell command takes — because a process
 * that isn't an app can't use ContentResolver. Calling it starts the app if needed.
 */
final class AppRelay {
    static final String AUTHORITY = "dev.saikiran.budsfirst.relay";
    private static final String TAG = "BudsFirst";
    private static final int USER = 0;

    /** An open handle on the app's provider. While it's held, Android keeps the app running. */
    static final class Link {
        final IBinder provider;
        private final IBinder token;

        private Link(IBinder provider, IBinder token) {
            this.provider = provider;
            this.token = token;
        }

        boolean isAlive() {
            return provider.isBinderAlive();
        }

        void close() {
            remove(token);
        }
    }

    private AppRelay() {
    }

    /** Asks the app to bind a Bluetooth profile service and return its binder. */
    static IBinder fetch(ComponentName service) {
        IBinder token = new Binder();
        try {
            Object provider = acquire(token);
            if (provider == null) return null;
            Bundle result = call(provider, "bind", service.flattenToString(), null);
            return result == null ? null : result.getBinder("service");
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "relay failed for " + service.getShortClassName(), e);
            return null;
        } finally {
            remove(token);
        }
    }

    /**
     * Gives the app a binder to poke whenever Bluetooth changes, and keeps the provider handle
     * open: Android ranks a process with an external provider handle as foreground, so the app
     * stays alive to forward events (it can receive broadcasts; a shell process can't).
     */
    static Link attach(IBinder nudge) {
        IBinder token = new Binder();
        try {
            Object provider = acquire(token);
            if (provider == null) return null;
            Bundle extras = new Bundle();
            extras.putBinder("guard", nudge);
            call(provider, "attach", null, extras);
            return new Link(((IInterface) provider).asBinder(), token);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "could not link to the app", e);
            remove(token);
            return null;
        }
    }

    private static Object acquire(IBinder token) throws ReflectiveOperationException {
        Object holder = activityManager().getClass()
                .getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                .invoke(activityManager(), AUTHORITY, USER, token, "budsfirst");
        if (holder == null) {
            Log.w(TAG, "BudsFirst app not installed");
            return null;
        }
        return holder.getClass().getField("provider").get(holder);
    }

    private static void remove(IBinder token) {
        try {
            activityManager().getClass()
                    .getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                    .invoke(activityManager(), AUTHORITY, token, USER);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Already gone.
        }
    }

    private static Object activityManager() throws ReflectiveOperationException {
        return Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
    }

    /** IContentProvider.call: (AttributionSource, …) since Android 12, (callingPackage, featureId, …) before. */
    private static Bundle call(Object provider, String method, String arg, Bundle extras)
            throws ReflectiveOperationException {
        for (Method m : provider.getClass().getMethods()) {
            if (!m.getName().equals("call")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 5 && p[0] == AttributionSource.class) {
                AttributionSource source = new AttributionSource.Builder(Process.myUid())
                        .setPackageName(ShellContext.PACKAGE).build();
                return (Bundle) m.invoke(provider, source, AUTHORITY, method, arg, extras);
            }
            if (p.length == 6 && p[0] == String.class) {
                return (Bundle) m.invoke(provider, ShellContext.PACKAGE, null, AUTHORITY, method, arg, extras);
            }
        }
        throw new NoSuchMethodException("IContentProvider.call");
    }
}
