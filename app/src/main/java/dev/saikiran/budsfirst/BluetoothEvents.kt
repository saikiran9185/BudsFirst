package dev.saikiran.budsfirst

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.Parcel
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Forwards Bluetooth changes to the guard the moment they happen. The guard runs as the shell
 * user and Android 13 won't deliver broadcasts to it, so this app receives them and pokes the
 * guards' binders (handed over in RelayProvider "attach"). While that link exists the guard also
 * keeps this process alive.
 */
object BluetoothEvents : BroadcastReceiver() {
    private val ACTIONS = listOf(
        "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED",
        "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.headset.profile.action.ACTIVE_DEVICE_CHANGED",
        BluetoothDevice.ACTION_ACL_CONNECTED,
        BluetoothDevice.ACTION_ACL_DISCONNECTED,
        BluetoothAdapter.ACTION_STATE_CHANGED,
    )

    private val guards = CopyOnWriteArraySet<IBinder>()

    fun attach(guard: IBinder) {
        guards.add(guard)
    }

    fun register(context: Context) {
        val filter = IntentFilter().apply { ACTIONS.forEach(::addAction) }
        // Exported: these come from the Bluetooth app, not the system uid. They're protected
        // broadcasts, so other apps can't fake them.
        ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onReceive(context: Context, intent: Intent) {
        for (guard in guards) {
            val data = Parcel.obtain()
            try {
                guard.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY)
            } catch (e: Exception) {
                guards.remove(guard)
            } finally {
                data.recycle()
            }
        }
    }
}
