package dev.saikiran.budsfirst

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

enum class Setup { NotInstalled, NotRunning, NeedsPermission, Ready }

enum class Kind { Earbuds, Headphones, Speaker, Other }

data class PairedDevice(val address: String, val name: String, val audio: Boolean, val kind: Kind)

data class GuardStatus(
    val ready: Boolean = false,
    /** True while the app forwards Bluetooth events, so the guard reacts instantly. */
    val linked: Boolean = false,
    val holder: String? = null,
    val connected: Set<String> = emptySet(),
    /** Where media and calls play right now. */
    val media: String? = null,
    val calls: String? = null,
    val log: List<String> = emptyList(),
    val error: String? = null,
)

data class UiState(
    val setup: Setup = Setup.NotRunning,
    val enabled: Boolean = true,
    val bluetoothAllowed: Boolean = false,
    val paired: List<PairedDevice> = emptyList(),
    val priority: List<String> = emptyList(),
    /** Null while the guard service isn't connected. */
    val status: GuardStatus? = null,
)

/** Owns the Shizuku connection, the saved priority list and the guard's live status. */
object GuardClient {
    private lateinit var app: Application
    private val prefs by lazy { app.getSharedPreferences("budsfirst", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    @Volatile
    private var guard: IGuard? = null
    private var binding = false
    private var polling: Job? = null

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(app.packageName, GuardService::class.java.name))
            .daemon(true)
            .processNameSuffix("guard")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            if (binder == null || !binder.pingBinder()) return
            guard = IGuard.Stub.asInterface(binder)
            pushPriority()
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            guard = null
            binding = false
            refresh()
        }
    }

    fun init(application: Application) {
        app = application
        _state.update {
            it.copy(
                enabled = prefs.getBoolean("enabled", true),
                priority = prefs.getString("priority", "")!!.split(',').filter(String::isNotBlank),
            )
        }
        Shizuku.addBinderReceivedListenerSticky { refresh(); bindIfWanted() }
        Shizuku.addBinderDeadListener { guard = null; binding = false; refresh() }
        Shizuku.addRequestPermissionResultListener { _, result ->
            refresh()
            if (result == PackageManager.PERMISSION_GRANTED) bindIfWanted()
        }
    }

    fun startPolling() {
        polling?.cancel()
        polling = scope.launch {
            while (isActive) {
                refresh()
                delay(1000)
            }
        }
    }

    fun stopPolling() {
        polling?.cancel()
        polling = null
    }

    fun requestShizukuPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(1)
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        _state.update { it.copy(enabled = on) }
        TileService.requestListeningState(app, ComponentName(app, GuardTile::class.java))
        if (on) {
            bindIfWanted()
        } else {
            val g = guard
            guard = null
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { g?.releaseAll() } }
                runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
                refresh()
            }
        }
    }

    fun add(address: String) = editPriority { if (address !in it) it.add(address) }

    fun remove(address: String) = editPriority { it.remove(address) }

    fun move(address: String, by: Int) = editPriority {
        val from = it.indexOf(address)
        val to = (from + by).coerceIn(0, it.lastIndex)
        if (from >= 0 && to != from) it.add(to, it.removeAt(from))
    }

    private fun editPriority(change: (MutableList<String>) -> Unit) {
        val list = _state.value.priority.toMutableList().also(change)
        prefs.edit().putString("priority", list.joinToString(",")).apply()
        _state.update { it.copy(priority = list) }
        pushPriority()
    }

    private fun pushPriority() {
        val g = guard ?: return
        val list = _state.value.priority
        scope.launch(Dispatchers.IO) { runCatching { g.setPriority(list) } }
    }

    private fun bindIfWanted() {
        if (!_state.value.enabled || guard != null || binding || setup() != Setup.Ready) return
        binding = runCatching { Shizuku.bindUserService(serviceArgs, connection) }.isSuccess
    }

    fun refresh() {
        scope.launch {
            val g = guard
            val json = withContext(Dispatchers.IO) { runCatching { g?.status() }.getOrNull() }
            val bluetoothAllowed = app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            _state.update {
                it.copy(
                    setup = setup(),
                    bluetoothAllowed = bluetoothAllowed,
                    paired = if (bluetoothAllowed) pairedDevices() else emptyList(),
                    status = json?.let(::parseStatus),
                )
            }
            bindIfWanted()
        }
    }

    private fun setup(): Setup = when {
        Shizuku.pingBinder() ->
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) Setup.Ready
            else Setup.NeedsPermission
        isShizukuInstalled() -> Setup.NotRunning
        else -> Setup.NotInstalled
    }

    private fun isShizukuInstalled() =
        runCatching { app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess

    @SuppressLint("MissingPermission")
    private fun pairedDevices(): List<PairedDevice> {
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.map { d ->
                val name = d.alias ?: d.name ?: d.address
                PairedDevice(
                    address = d.address,
                    name = name,
                    audio = d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO,
                    kind = kindOf(name, d.bluetoothClass?.deviceClass),
                )
            }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    private fun kindOf(name: String, deviceClass: Int?): Kind {
        val n = name.lowercase()
        return when {
            listOf("bud", "pods", "earbud", "tws", "earfree", "airdopes").any { it in n } -> Kind.Earbuds
            listOf("speaker", "boom", "flip", "charge", "soundbar").any { it in n } -> Kind.Speaker
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER -> Kind.Speaker
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> Kind.Headphones
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> Kind.Earbuds
            deviceClass != null && deviceClass shr 8 == BluetoothClass.Device.Major.AUDIO_VIDEO shr 8 -> Kind.Headphones
            else -> Kind.Other
        }
    }

    private fun parseStatus(json: String): GuardStatus {
        val o = JSONObject(json)
        fun JSONArray?.strings() = if (this == null) emptyList() else List(length()) { getString(it) }
        return GuardStatus(
            ready = o.optBoolean("ready"),
            linked = o.optBoolean("linked"),
            holder = if (o.isNull("holder")) null else o.optString("holder"),
            connected = o.optJSONArray("connected").strings().toSet(),
            media = if (o.isNull("media")) null else o.optString("media").ifEmpty { null },
            calls = if (o.isNull("calls")) null else o.optString("calls").ifEmpty { null },
            log = o.optJSONArray("log").strings(),
            error = o.optString("error").ifEmpty { null },
        )
    }
}
