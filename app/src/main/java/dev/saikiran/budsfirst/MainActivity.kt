package dev.saikiran.budsfirst

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BudsFirstTheme { BudsFirstScreen() } }
    }

    override fun onStart() {
        super.onStart()
        GuardClient.startPolling()
    }

    override fun onStop() {
        GuardClient.stopPolling()
        super.onStop()
    }
}

private val BrandStart = Color(0xFF6C63FF)
private val BrandEnd = Color(0xFF3A2FD6)

@Composable
private fun BudsFirstTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

@DrawableRes
private fun iconFor(kind: Kind?) = when (kind) {
    Kind.Earbuds -> R.drawable.ic_earbuds
    Kind.Headphones -> R.drawable.ic_headphones
    Kind.Speaker -> R.drawable.ic_speaker
    else -> R.drawable.ic_bluetooth
}

@Composable
private fun BudsFirstScreen() {
    val state by GuardClient.state.collectAsState()
    val context = LocalContext.current
    val devices = state.paired.associateBy { it.address }
    fun name(address: String?) = address?.let { devices[it]?.name ?: it } ?: ""
    fun kind(address: String?) = address?.let { devices[it]?.kind }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        GuardClient.refresh()
    }
    LaunchedEffect(Unit) { askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT) }

    Scaffold { insets ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = insets.calculateTopPadding() + 16.dp,
                bottom = insets.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header() }

            item {
                when (state.setup) {
                    Setup.NotInstalled -> StepCard(
                        "Install Shizuku",
                        "BudsFirst uses Shizuku to choose where Bluetooth audio plays — something normal apps aren't allowed to do. It's free and doesn't need root.",
                        "Get Shizuku",
                    ) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }
                    Setup.NotRunning -> StepCard(
                        "Start Shizuku",
                        "Open Shizuku and tap Start under “Start via Wireless debugging”. After every phone restart, start it once again.",
                        "Open Shizuku",
                    ) { context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let(context::startActivity) }
                    Setup.NeedsPermission -> StepCard(
                        "Allow BudsFirst",
                        "Shizuku is running. Let BudsFirst use it.",
                        "Allow",
                    ) { GuardClient.requestShizukuPermission() }
                    Setup.Ready -> NowPlayingCard(state, ::name, ::kind)
                }
            }

            item { PriorityCard(state, ::name, ::kind) }

            item {
                SectionCard {
                    Text("Add a device", style = MaterialTheme.typography.titleMedium)
                    if (!state.bluetoothAllowed) {
                        Text(
                            "Allow “Nearby devices” so BudsFirst can see your paired devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(onClick = { askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                            Text("Allow")
                        }
                    } else {
                        val others = state.paired.filter { it.address !in state.priority }
                        val shown = others.filter { it.audio }.ifEmpty { others }
                        if (shown.isEmpty()) {
                            Text(
                                "Every paired audio device is in your list.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        shown.forEach { device ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DeviceIcon(iconFor(device.kind), MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    device.name,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(onClick = { GuardClient.add(device.address) }) { Text("Add") }
                            }
                        }
                    }
                }
            }

            val log = state.status?.log.orEmpty()
            if (state.enabled && log.isNotEmpty()) item { ActivityCard(log) }

            item {
                Text(
                    "Every device stays connected. Media and calls always play on the highest device in your list " +
                        "that's connected; if a lower one grabs the sound, BudsFirst moves it straight back. " +
                        "Put your earbuds away and the sound moves to the next device at once, with no reconnecting. " +
                        "Devices that aren't in the list are left alone. To use a lower device, move it up, or turn " +
                        "the guard off — there's a Quick Settings tile for that.",
                    Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .background(Brush.linearGradient(listOf(BrandStart, BrandEnd)), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_tile), contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("BudsFirst", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Sound stays on the device you're using.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NowPlayingCard(state: UiState, name: (String?) -> String, kind: (String?) -> Kind?) {
    val status = state.status
    val media = status?.media
    val calls = status?.calls
    val (icon, title, subtitle) = when {
        !state.enabled -> Triple(R.drawable.ic_bluetooth, "Guard is off", "Android picks where sound plays.")
        status == null -> Triple(R.drawable.ic_bluetooth, "Starting…", "")
        status.error != null -> Triple(R.drawable.ic_bluetooth, "Couldn't start", status.error)
        !status.ready -> Triple(R.drawable.ic_bluetooth, "Waiting for Bluetooth", "Turn Bluetooth on.")
        media != null -> Triple(
            iconFor(kind(media)),
            name(media),
            when (calls) {
                media -> "Media and calls"
                null -> "Media"
                else -> "Media · calls on ${name(calls)}"
            },
        )
        calls != null -> Triple(iconFor(kind(calls)), name(calls), "Calls")
        else -> Triple(R.drawable.ic_phone, "This phone", "Nothing from your list is connected.")
    }
    val standby = state.priority.filter {
        status != null && it in status.connected && it != media && it != calls
    }
    val on = state.enabled
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (on) "NOW PLAYING ON" else "PRIORITY GUARD",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 1.2.sp,
                )
                Switch(checked = on, onCheckedChange = GuardClient::setEnabled)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(60.dp)
                        .background(
                            if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(icon),
                        contentDescription = null,
                        tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (on && standby.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "On standby: " + standby.joinToString { name(it) },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (on && status?.ready == true) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Text(
                        if (status.linked) "⚡ Instant switching" else "Checking 4× a second",
                        Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PriorityCard(state: UiState, name: (String?) -> String, kind: (String?) -> Kind?) {
    val status = state.status
    SectionCard {
        Text("Priority", style = MaterialTheme.typography.titleMedium)
        Text(
            "Top wins. The rest stay connected on standby.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.priority.isEmpty()) {
            Text(
                "Add your earbuds first, then your headphones.",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.priority.forEachIndexed { index, address ->
            val connected = status != null && address in status.connected
            val playing = status != null && (status.media == address || status.calls == address)
            val (label, dot) = when {
                playing -> "Playing" to MaterialTheme.colorScheme.primary
                connected -> "Standby" to MaterialTheme.colorScheme.tertiary
                else -> "Not connected" to MaterialTheme.colorScheme.outline
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(26.dp)
                        .background(
                            if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (playing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                DeviceIcon(iconFor(kind(address)), MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name(address), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(dot, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { GuardClient.move(address, -1) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = { GuardClient.move(address, 1) }, enabled = index < state.priority.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                }
                IconButton(onClick = { GuardClient.remove(address) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(log: List<String>) {
    var open by remember { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Activity", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
        }
        val lines = if (open) log.take(20) else log.take(1)
        lines.forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceIcon(@DrawableRes icon: Int, tint: Color) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
}

@Composable
private fun StepCard(title: String, body: String, button: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}
