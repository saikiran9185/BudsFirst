package dev.saikiran.budsfirst

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The guard's door into this app. The guard runs as the shell user, so it can't bind services
 * or receive broadcasts; through this provider it gets Bluetooth profile binders ("bind") and
 * hands over the binder that BluetoothEvents pokes on every Bluetooth change ("attach"). Only
 * answers the shell (or root) user. See AppRelay on the guard side.
 */
class RelayProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("Only the BudsFirst guard may call this")
        if (method == "attach") {
            extras?.getBinder("guard")?.let(BluetoothEvents::attach)
            return Bundle()
        }
        if (method != "bind" || arg == null) return null
        val component = ComponentName.unflattenFromString(arg) ?: return null
        if (!component.packageName.contains("bluetooth")) return null
        val binder = bind(context!!.applicationContext, component) ?: return null
        return Bundle().apply { putBinder("service", binder) }
    }

    private fun bind(context: Context, component: ComponentName): IBinder? {
        bound[component]?.takeIf { it.isBinderAlive }?.let { return it }
        val ready = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                bound[component] = service
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                bound.remove(component)
            }
        }
        // Flags 0, like the framework: never start a profile service while Bluetooth is off.
        if (!context.bindService(Intent().setComponent(component), connection, 0)) return null
        ready.await(3, TimeUnit.SECONDS)
        return bound[component]
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

    private companion object {
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
        val bound = ConcurrentHashMap<ComponentName, IBinder>()
    }
}
