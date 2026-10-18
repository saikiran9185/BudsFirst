package dev.saikiran.budsfirst

import android.app.Application

class BudsFirstApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BluetoothEvents.register(this)
        GuardClient.init(this)
    }
}
