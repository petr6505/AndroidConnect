package io.github.androidconnect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LaptopChromebook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val app get() = App.instance

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())

        setContent {
            AppTheme {
                val state by app.link.state.collectAsStateWithLifecycle()
                // Go fullscreen only when a connection the user started comes up,
                // not when the app is reopened while a connection is already running.
                var previous by remember { mutableStateOf(state) }
                LaunchedEffect(state) {
                    val connected = state is LinkState.Streaming && previous !is LinkState.Streaming
                    previous = state
                    if (connected && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) openDisplay()
                }
                MainScreen(state, onOpenDisplay = ::openDisplay)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.discovery.acquire()
    }

    override fun onStop() {
        app.discovery.release()
        super.onStop()
    }

    private fun openDisplay() = startActivity(Intent(this, DisplayActivity::class.java))

    private fun requiredPermissions() = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(state: LinkState, onOpenDisplay: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Android Connect") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state) {
                    is LinkState.Idle -> LaptopList()
                    is LinkState.Connecting -> ProgressCard("Connecting to ${state.name}…", null)
                    is LinkState.Reconnecting -> ProgressCard("Reconnecting to ${state.name}…", state.reason)
                    is LinkState.Pairing -> PairingCard(state)
                    is LinkState.Streaming -> ConnectedCard(state, onOpenDisplay)
                    is LinkState.Failed -> FailedCard(state)
                }
            }
        }
    }
    if (showSettings) SettingsDialog { showSettings = false }
}

@Composable
private fun LaptopList() {
    val app = App.instance
    val paired by app.store.laptops.collectAsStateWithLifecycle()
    val found by app.discovery.found.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf(false) }
    val pairedIds = paired.map { it.serverId }.toSet()
    val unpaired = found.values.filter { it.serverId !in pairedIds }.sortedBy { it.serviceName }

    val last = paired.firstOrNull { it.serverId == app.store.lastServerId } ?: paired.singleOrNull()
    if (last != null) {
        val online = found.values.firstOrNull { it.serverId == last.serverId }
        CenteredCard {
            Text(last.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(if (online != null) "Available on this Wi-Fi" else "Last seen at ${last.host}",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { connect(last, online) }) { Text("Connect display") }
        }
    }

    if (paired.isNotEmpty()) {
        SectionTitle("Your laptops")
        Card {
            paired.sortedBy { it.name }.forEachIndexed { i, laptop ->
                if (i > 0) HorizontalDivider()
                val online = found.values.firstOrNull { it.serverId == laptop.serverId }
                PairedRow(laptop, online)
            }
        }
    }

    SectionTitle(if (paired.isEmpty()) "Laptops nearby" else "Pair a new laptop")
    Card {
        if (unpaired.isEmpty()) {
            ListItem(
                leadingContent = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
                headlineContent = { Text("Searching on this Wi-Fi…") },
                supportingContent = {
                    Text("Make sure Android Connect is running on your Ubuntu laptop and both devices " +
                        "are on the same network.")
                },
                colors = ListItemDefaults.colors(containerColor = CardDefaults.cardColors().containerColor),
            )
        }
        unpaired.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable { app.link.connect(Target(f.host, f.port, f.serviceName, f.serverId)) },
                leadingContent = { Icon(Icons.Outlined.LaptopChromebook, null) },
                headlineContent = { Text(f.serviceName) },
                supportingContent = { Text("Tap to pair · ${f.host}") },
                colors = ListItemDefaults.colors(containerColor = CardDefaults.cardColors().containerColor),
            )
        }
    }
    TextButton(onClick = { manual = true }) { Text("Connect by IP address") }
    if (manual) ManualDialog { manual = false }
}

private fun connect(laptop: Laptop, online: FoundLaptop?) = App.instance.link.connect(
    Target(online?.host ?: laptop.host, online?.port ?: laptop.port, laptop.name, laptop.serverId))

@Composable
private fun PairedRow(laptop: Laptop, online: FoundLaptop?) {
    var menu by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = { Icon(Icons.Filled.Laptop, null) },
        headlineContent = { Text(laptop.name, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(if (online != null) "Available" else "Last seen at ${laptop.host}") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { connect(laptop, online) }) { Text("Connect") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Forget") }, onClick = { menu = false; confirmForget = true })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = CardDefaults.cardColors().containerColor),
    )
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget ${laptop.name}?") },
            text = { Text("You'll need to pair again to use it as a screen.") },
            confirmButton = {
                TextButton(onClick = { App.instance.store.remove(laptop.serverId); confirmForget = false }) {
                    Text("Forget")
                }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
private fun CenteredCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 48.dp)) {
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

@Composable
private fun ProgressCard(title: String, detail: String?) = CenteredCard {
    CircularProgressIndicator()
    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    if (detail != null) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    OutlinedButton(onClick = { App.instance.link.disconnect() }) { Text("Cancel") }
}

@Composable
private fun PairingCard(state: LinkState.Pairing) = CenteredCard {
    Text("Confirm on your laptop", style = MaterialTheme.typography.titleLarge)
    Text("${state.code.take(3)} ${state.code.drop(3)}", fontSize = 48.sp, fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
    Text("“${state.name}” is asking whether to trust this tablet. Accept only if it shows the same code.",
        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = { App.instance.link.disconnect() }) { Text("Cancel") }
}

@Composable
private fun ConnectedCard(state: LinkState.Streaming, onOpenDisplay: () -> Unit) = CenteredCard {
    Text("Connected to ${state.name}", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text("${state.width} × ${state.height}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { App.instance.link.disconnect() }) { Text("Disconnect") }
        Button(onClick = onOpenDisplay) { Text("Show screen") }
    }
}

@Composable
private fun FailedCard(state: LinkState.Failed) = CenteredCard {
    val app = App.instance
    Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
    Text("Couldn't connect to ${state.name}", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(state.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.serverId != null && app.store.laptop(state.serverId) != null) {
            OutlinedButton(onClick = { app.store.remove(state.serverId); app.link.dismissFailure() }) {
                Text("Forget laptop")
            }
        }
        Button(onClick = { app.link.dismissFailure() }) { Text("OK") }
    }
}

@Composable
private fun ManualDialog(onDismiss: () -> Unit) {
    var address by remember { mutableStateOf("") }
    fun submit() {
        val text = address.trim()
        if (text.isEmpty()) return
        val host = text.substringBeforeLast(':').takeIf { ':' in text && !text.endsWith(']') } ?: text
        val port = text.substringAfterLast(':', "").toIntOrNull() ?: Protocol.DEFAULT_PORT
        App.instance.link.connect(Target(host.trim('[', ']'), port, host, null))
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect by IP address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The address is shown in the Android Connect window on your laptop.")
                OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true,
                    placeholder = { Text("192.168.1.20") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
        },
        confirmButton = { TextButton(onClick = ::submit) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    val store = App.instance.store
    val settings by store.settings.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Choice("Frame rate", listOf(30 to "30 fps", 60 to "60 fps"), settings.fps) {
                    store.updateSettings(settings.copy(fps = it))
                }
                Choice("Quality", listOf("low" to "Low", "balanced" to "Balanced", "high" to "High"),
                    settings.quality) { store.updateSettings(settings.copy(quality = it)) }
                Choice("Resolution", listOf("native" to "Native", "fhd" to "1080p"), settings.resolution) {
                    store.updateSettings(settings.copy(resolution = it))
                }
                Text("Frame rate, quality and resolution apply the next time you connect.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Choice("One-finger touch acts as", listOf("touch" to "Touchscreen", "mouse" to "Mouse"),
                    settings.touchMode) { store.updateSettings(settings.copy(touchMode = it)) }
                Toggle("Show performance overlay (or tap with four fingers on the screen)", settings.showStats) {
                    store.updateSettings(settings.copy(showStats = it))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, text) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(text) }
            }
        }
    }
}
