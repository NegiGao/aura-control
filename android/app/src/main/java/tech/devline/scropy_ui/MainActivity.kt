package tech.devline.scropy_ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import tech.devline.scropy_ui.LocalizedText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import tech.devline.scropy_ui.adb.AdbAuthHelper
import tech.devline.scropy_ui.adb.AdbConnection
import tech.devline.scropy_ui.adb.AdbPairing
import tech.devline.scropy_ui.adb.savedDeviceSerial
import tech.devline.scropy_ui.adb.requireSavedSerial
import tech.devline.scropy_ui.ui.theme.ScropyTheme
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.coroutineScope

// --- Navigation model ---------------------------------------------------------

enum class AppAction { STREAM, SHELL }

data class SavedDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val model: String? = null,
    val androidVersion: String? = null,
    val screenshotPath: String? = null,
    val serial: String? = null,
)

sealed class Screen {
    object DeviceList : Screen()
    object ChooseTransport : Screen()
    object WifiConnect : Screen()
    object UsbConnect : Screen()
    data class ShellTerminal(val conn: AdbConnection, val label: String) : Screen()
}

// --- Saved-device persistence -------------------------------------------------

private const val PREFS_NAME = "scropy_prefs"
private const val KEY_SAVED = "saved_devices_v2"
private const val KEY_STREAM_QUALITY = "stream_quality"
private const val KEY_STREAM_FRAME_RATE = "stream_frame_rate"

private fun loadDevices(prefs: android.content.SharedPreferences): List<SavedDevice> {
    val raw = prefs.getString(KEY_SAVED, null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).run {
                SavedDevice(
                    getString("id"), getString("name"), getString("host"), getInt("port"),
                    optString("model").takeIf { it.isNotEmpty() },
                    optString("androidVersion").takeIf { it.isNotEmpty() },
                    optString("screenshotPath").takeIf { it.isNotEmpty() },
                    savedDeviceSerial(optString("serial").takeIf { it.isNotEmpty() }, getString("id")),
                )
            }
        }
    } catch (_: Exception) { emptyList() }
}

private fun persistDevices(prefs: android.content.SharedPreferences, list: List<SavedDevice>) {
    val arr = JSONArray()
    list.forEach { d ->
        arr.put(JSONObject().apply {
            put("id", d.id); put("name", d.name); put("host", d.host); put("port", d.port)
            d.model?.let { put("model", it) }
            d.androidVersion?.let { put("androidVersion", it) }
            d.screenshotPath?.let { put("screenshotPath", it) }
            d.serial?.let { put("serial", it) }
        })
    }
    prefs.edit().putString(KEY_SAVED, arr.toString()).apply()
}

private fun upsertDevice(prefs: android.content.SharedPreferences, device: SavedDevice) {
    val list = loadDevices(prefs).toMutableList()
    val idx = list.indexOfFirst {
        if (device.serial != null && it.serial != null) it.serial == device.serial
        else it.host == device.host && it.port == device.port
    }
    if (idx >= 0) list[idx] = device.copy(id = list[idx].id) else list.add(0, device)
    persistDevices(prefs, list)
}

private fun deleteDevice(prefs: android.content.SharedPreferences, id: String) =
    persistDevices(prefs, loadDevices(prefs).filter { it.id != id })

/** Replace a saved device by id (used for editing and for live-port updates). */
private fun updateDeviceById(prefs: android.content.SharedPreferences, updated: SavedDevice) =
    persistDevices(prefs, loadDevices(prefs).map { if (it.id == updated.id) updated else it })

// --- Network scan ------------------------------------------------------------

data class ScanResult(val host: String, val port: Int?, val label: String?)

private fun getLocalSubnetPrefix(context: Context): String? =
    getLocalIpInfo(context)?.substringBeforeLast('.')?.plus('.')

/**
 * Return the IPv4 address of the active Wi-Fi network.
 *
 * Phones commonly keep mobile data active while connected to Wi-Fi. Taking the
 * first NetworkInterface in that situation often returns rmnet_data0 (10.x),
 * which makes subnet discovery scan the cellular network instead of Wi-Fi.
 */
private fun getLocalIpInfo(context: Context): String? {
    val wifiAddress = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.allNetworks
            .firstOrNull { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            ?.let(cm::getLinkProperties)
            ?.linkAddresses
            ?.firstOrNull { it.address is java.net.Inet4Address && !it.address.isLoopbackAddress }
            ?.address
            ?.hostAddress
    }.getOrNull()
    if (!wifiAddress.isNullOrBlank()) return wifiAddress

    return try {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
            ?.sortedByDescending { it.name.startsWith("wlan") || it.name.startsWith("wifi") }
            ?.flatMap { it.interfaceAddresses.toList() }
            ?.firstOrNull { addr ->
                addr.address is java.net.Inet4Address && !addr.address.isLoopbackAddress
            }
            ?.address?.hostAddress
    } catch (_: Exception) { null }
}

suspend fun scanForDevices(context: Context): List<ScanResult> = withContext(Dispatchers.IO) {
    val prefix = getLocalSubnetPrefix(context) ?: return@withContext emptyList()
    val myIp = getLocalIpInfo(context)
    val found = Collections.synchronizedList(mutableListOf<ScanResult>())
    scanRange(1..254, workerCount = 32) { i ->
        val ip = "$prefix$i"
        if (ip != myIp) {
            val adbOpen = runCatching {
                Socket().use { s -> s.connect(InetSocketAddress(ip, 5555), 400) }
                true
            }.getOrElse { false }
            currentCoroutineContext().ensureActive()
            if (adbOpen) {
                found.add(ScanResult(ip, 5555, null))
            } else {
                val reachable = runCatching {
                    java.net.InetAddress.getByName(ip).isReachable(400)
                }.getOrElse { false }
                currentCoroutineContext().ensureActive()
                if (reachable) found.add(ScanResult(ip, null, null))
            }
        }
    }
    found.sortedWith(compareBy(
        { it.port == null },
        { it.host.substringAfterLast('.').toIntOrNull() ?: 0 },
    ))
}

// Scan all 5-digit ports on a specific host for open ADB connections
suspend fun scanAdbPorts(ip: String): List<Int> = withContext(Dispatchers.IO) {
    val open = Collections.synchronizedList(mutableListOf<Int>())
    scanRange(10000..65535, workerCount = 64) { port ->
        val ok = runCatching {
            Socket().use { s -> s.connect(InetSocketAddress(ip, port), 300) }
            true
        }.getOrElse { false }
        currentCoroutineContext().ensureActive()
        if (ok) open.add(port)
    }
    open.sorted()
}

// --- mDNS device discovery ---------------------------------------------------

data class DiscoveredDevice(val name: String, val host: String, val port: Int)

fun discoverAdbViaBonjour(
    context: android.content.Context,
    isActive: () -> Boolean = { true },
    onFound: (DiscoveredDevice) -> Unit,
): android.net.nsd.NsdManager.DiscoveryListener {
    val nsd = context.getSystemService(android.content.Context.NSD_SERVICE) as android.net.nsd.NsdManager
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    val pending = LinkedBlockingQueue<android.net.nsd.NsdServiceInfo>()
    val resolving = AtomicBoolean(false)
    val myIp = getLocalIpInfo(context)

    fun resolveNext() {
        if (!isActive()) { pending.clear(); resolving.set(false); return }
        val svc = pending.poll() ?: run { resolving.set(false); return }
        nsd.resolveService(svc, object : android.net.nsd.NsdManager.ResolveListener {
            override fun onResolveFailed(si: android.net.nsd.NsdServiceInfo, e: Int) = resolveNext()
            override fun onServiceResolved(si: android.net.nsd.NsdServiceInfo) {
                si.host?.hostAddress?.let { ip ->
                    if (ip != myIp) handler.post {
                        if (isActive()) onFound(DiscoveredDevice(si.serviceName, ip, si.port))
                    }
                }
                resolveNext()
            }
        })
    }

    return object : android.net.nsd.NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(t: String, e: Int) {}
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onServiceFound(svc: android.net.nsd.NsdServiceInfo) {
            if (!isActive()) return
            pending.add(svc)
            if (resolving.compareAndSet(false, true)) resolveNext()
        }
        override fun onServiceLost(svc: android.net.nsd.NsdServiceInfo) {}
    }.also { listener ->
        nsd.discoverServices("_adb-tls-connect._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener)
    }
}

/**
 * Resolve the *current* wireless-debugging port for [host] via mDNS.
 *
 * Android randomises the wireless-debugging connect port (it changes on lock/unlock,
 * toggling debugging, or reboot), but the device keeps advertising its live port over
 * mDNS. This finds the current port for a device whose IP we already know, so a saved
 * connection keeps working without re-pairing. Falls back to [fallbackPort] if mDNS
 * turns up nothing within [timeoutMs] (e.g. device asleep, or not on Wi-Fi).
 */
suspend fun resolveAdbPortViaMdns(
    context: android.content.Context,
    host: String,
    fallbackPort: Int,
    timeoutMs: Long = 3000,
): Int {
    // USB bootstrap enables ordinary ADB, not TLS wireless debugging.
    // Never replace its fixed port with an _adb-tls-connect advertisement.
    if (fallbackPort == 5555) return 5555
    val nsd = context.getSystemService(android.content.Context.NSD_SERVICE) as android.net.nsd.NsdManager
    val result = CompletableDeferred<Int>()
    val active = AtomicBoolean(true)
    val listener = discoverAdbViaBonjour(context, isActive = { active.get() }) { d ->
        if (d.host == host && !result.isCompleted) result.complete(d.port)
    }
    return try {
        withTimeoutOrNull(timeoutMs) { result.await() } ?: fallbackPort
    } finally {
        active.set(false)
        runCatching { nsd.stopServiceDiscovery(listener) }
    }
}

// --- Device metadata ---------------------------------------------------------

suspend fun fetchDeviceMetadata(
    conn: AdbConnection,
    context: android.content.Context,
    deviceId: String,
): Triple<String?, String?, String?> = withContext(Dispatchers.IO) {
    fun readProp(prop: String): String? = runCatching {
        conn.readCommand("shell:getprop $prop").takeIf { it.isNotEmpty() }
    }.getOrNull()
    val model = readProp("ro.product.model")
    val androidVersion = readProp("ro.build.version.release")
    val screenshotPath = runCatching {
        val bytes = conn.readService("exec:screencap -p")
        if (bytes.size < 8) return@runCatching null
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching null
        val file = java.io.File(context.filesDir, "screenshot_$deviceId.jpg")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, it) }
        file.absolutePath
    }.getOrNull()
    Triple(model, androidVersion, screenshotPath)
}

// --- USB helper ---------------------------------------------------------------

private fun findAdbDevices(usbManager: UsbManager): List<UsbDevice> =
    usbManager.deviceList.values.filter { device ->
        (0 until device.interfaceCount).any { i ->
            device.getInterface(i).run {
                interfaceClass == 0xFF && interfaceSubclass == 0x42 && interfaceProtocol == 0x01
            }
        }
    }

// --- MainActivity -------------------------------------------------------------

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_USB_PERMISSION = "tech.devline.scropy_ui.USB_PERMISSION"
    }

    private var usbPermissionCallback: ((Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                usbPermissionCallback?.invoke(granted)
                usbPermissionCallback = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // AURA briefly re-enumerates its USB gadget when the ADB transport is
        // established.  Android delivers that as a fresh USB attach intent.
        // Do not let a second MainActivity cover an active stream.
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && StreamActivity.isActive) {
            App.writeDiag("MainActivity.onCreate: ignored USB attach while streaming")
            finish()
            return
        }

        App.writeDiag("MainActivity.onCreate: started")
        enableEdgeToEdge()

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        setContent {
            ScropyTheme {
                AppNavHost(
                    onLaunchStream = { host, port, serial ->
                        startActivity(Intent(this, StreamActivity::class.java).apply {
                            putExtra(StreamActivity.EXTRA_HOST, host)
                            putExtra(StreamActivity.EXTRA_PORT, port)
                            putExtra(StreamActivity.EXTRA_SERIAL, serial)
                            putExtra(
                                StreamActivity.EXTRA_QUALITY,
                                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .getString(KEY_STREAM_QUALITY, StreamQuality.SMOOTH.name),
                            )
                            putExtra(
                                StreamActivity.EXTRA_FRAME_RATE,
                                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .getString(KEY_STREAM_FRAME_RATE, StreamFrameRate.FPS_60.name),
                            )
                        })
                    },
                    onLaunchUsbStream = { device ->
                        startActivity(Intent(this, StreamActivity::class.java).apply {
                            putExtra(StreamActivity.EXTRA_USB_DEVICE, device)
                            putExtra(
                                StreamActivity.EXTRA_QUALITY,
                                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .getString(KEY_STREAM_QUALITY, StreamQuality.SMOOTH.name),
                            )
                            putExtra(
                                StreamActivity.EXTRA_FRAME_RATE,
                                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .getString(KEY_STREAM_FRAME_RATE, StreamFrameRate.FPS_60.name),
                            )
                        })
                    },
                    onRequestUsbPermission = { device, cb ->
                        usbPermissionCallback = cb
                        val mgr = getSystemService(USB_SERVICE) as UsbManager
                        mgr.requestPermission(
                            device,
                            PendingIntent.getBroadcast(
                                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                            )
                        )
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(usbReceiver) }
    }
}

// --- Navigation host ----------------------------------------------------------

@Composable
fun AppNavHost(
    onLaunchStream: (host: String, port: Int, serial: String?) -> Unit,
    onLaunchUsbStream: (device: UsbDevice) -> Unit,
    onRequestUsbPermission: (device: UsbDevice, cb: (Boolean) -> Unit) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var stack by remember { mutableStateOf(listOf<Screen>(Screen.DeviceList)) }
    val current = stack.last()

    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun popToRoot() { stack = listOf(Screen.DeviceList) }

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val s = current) {
        Screen.DeviceList -> DeviceListScreen(
            prefs = prefs,
            onNewConnection = { push(Screen.ChooseTransport) },
            onLaunchStream = onLaunchStream,
            onOpenShell = { conn, label -> push(Screen.ShellTerminal(conn, label)) },
        )
        Screen.ChooseTransport -> ChooseTransportScreen(
            onBack = ::pop,
            onWifi = { push(Screen.WifiConnect) },
            onUsb = { push(Screen.UsbConnect) },
        )
        Screen.WifiConnect -> WifiConnectScreen(
            prefs = prefs,
            onBack = ::pop,
            onLaunchStream = { host, port, serial -> onLaunchStream(host, port, serial); popToRoot() },
            onOpenShell = { conn, label -> push(Screen.ShellTerminal(conn, label)) },
        )
        Screen.UsbConnect -> UsbConnectScreen(
            prefs = prefs,
            onLaunchWifiStream = { host, port, serial -> onLaunchStream(host, port, serial); popToRoot() },
            onBack = ::pop,
            onLaunchUsbStream = { device -> onLaunchUsbStream(device); popToRoot() },
            onOpenShell = { conn, label -> push(Screen.ShellTerminal(conn, label)) },
            onRequestUsbPermission = onRequestUsbPermission,
        )
        is Screen.ShellTerminal -> ShellTerminalScreen(
            conn = s.conn,
            label = s.label,
            onDisconnect = { runCatching { s.conn.close() }; pop() },
        )
    }
}

// --- Screen: Device List ------------------------------------------------------

@Composable
private fun QualitySelector(
    quality: StreamQuality,
    onQualityChange: (StreamQuality) -> Unit,
    frameRate: StreamFrameRate,
    onFrameRateChange: (StreamFrameRate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text("画質", style = MaterialTheme.typography.titleSmall)
        Text(
            "AURA（16:10）の目安：800p＝1280×800、1000p＝1600×1000、1200p＝1920×1200。端末により実際のサイズは異なります。滑らかは低ビットレート・60fps向けです。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StreamQuality.entries.forEach { option ->
                FilterChip(
                    selected = quality == option,
                    onClick = { onQualityChange(option) },
                    label = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(option.label)
                            Text(option.resolutionLabel, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("フレームレート", style = MaterialTheme.typography.titleSmall)
        Text(
            "60fpsは滑らか、30fpsは発熱と消費電力を抑えます。実際のフレームレートは端末と接続状態により変動します。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StreamFrameRate.entries.forEach { option ->
                FilterChip(
                    selected = frameRate == option,
                    onClick = { onFrameRateChange(option) },
                    label = { Text(option.label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    prefs: android.content.SharedPreferences,
    onNewConnection: () -> Unit,
    onLaunchStream: (host: String, port: Int, serial: String?) -> Unit,
    onOpenShell: (conn: AdbConnection, label: String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(loadDevices(prefs)) }
    var connectingId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var pendingConn by remember { mutableStateOf<AdbConnection?>(null) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var editingDevice by remember { mutableStateOf<SavedDevice?>(null) }
    var streamQuality by remember {
        mutableStateOf(StreamQuality.from(prefs.getString(KEY_STREAM_QUALITY, StreamQuality.SMOOTH.name)))
    }
    var streamFrameRate by remember {
        mutableStateOf(
            StreamFrameRate.from(
                prefs.getString(KEY_STREAM_FRAME_RATE, StreamFrameRate.FPS_60.name),
            ),
        )
    }

    fun selectQuality(value: StreamQuality) {
        streamQuality = value
        prefs.edit().putString(KEY_STREAM_QUALITY, value.name).apply()
    }

    fun selectFrameRate(value: StreamFrameRate) {
        streamFrameRate = value
        prefs.edit().putString(KEY_STREAM_FRAME_RATE, value.name).apply()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aura Control Public") },
                actions = { TextButton(onClick = onNewConnection) { Text("+ 接続") } },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
        LanguageSelector(Modifier.padding(horizontal = 16.dp))
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "接続済みのAURAはありません。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    QualitySelector(
                        quality = streamQuality,
                        onQualityChange = ::selectQuality,
                        frameRate = streamFrameRate,
                        onFrameRateChange = ::selectFrameRate,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onNewConnection) { Text("+ AURAを接続") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 60.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        QualitySelector(
                            quality = streamQuality,
                            onQualityChange = ::selectQuality,
                            frameRate = streamFrameRate,
                            onFrameRateChange = ::selectFrameRate,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
                items(devices, key = { it.id }) { device ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                        ) {
                            // Screenshot thumbnail — no padding, flush left edge
                            val screenshotBitmap by produceState(
                                initialValue = null as android.graphics.Bitmap?,
                                device.screenshotPath,
                            ) {
                                value = withContext(Dispatchers.IO) {
                                    device.screenshotPath?.let { path ->
                                        runCatching {
                                            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                                            android.graphics.BitmapFactory.decodeFile(path, opts)
                                        }.getOrNull()
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .width(80.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (screenshotBitmap != null) {
                                    Image(
                                        bitmap = screenshotBitmap!!.asImageBitmap(),
                                        contentDescription = uiText("前回の画面"),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                        filterQuality = FilterQuality.None,
                                    )
                                } else {
                                    Text("📱", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                            Column(modifier = Modifier.weight(1f).padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(device.model ?: device.name, style = MaterialTheme.typography.titleSmall)
                                        if (device.androidVersion != null) {
                                            Text(
                                                "Android ${device.androidVersion}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        Text(
                                            // Port is auto-resolved on connect and changes constantly,
                                            // so show just the IP (the stable, useful part).
                                            device.host,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(
                                        onClick = { editingDevice = device },
                                        enabled = connectingId == null,
                                    ) { Text("編集") }
                                    TextButton(
                                        onClick = { deleteDevice(prefs, device.id); devices = loadDevices(prefs) },
                                        enabled = connectingId == null,
                                    ) { Text("X") }
                                }
                                Spacer(Modifier.height(8.dp))
                                if (connectingId == device.id) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("接続中…", style = MaterialTheme.typography.bodySmall)
                                    }
                                } else {
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = {
                                                if (connectingId != null) return@OutlinedButton
                                                connectingId = device.id; errorMsg = null
                                                scope.launch {
                                                    // Wireless-debugging port drifts on lock/unlock — find the live one.
                                                    val livePort = if (device.id.startsWith("usb-wifi-")) 5555
                                                        else resolveAdbPortViaMdns(ctx, device.host, device.port)
                                                    if (livePort != device.port) {
                                                        updateDeviceById(prefs, device.copy(port = livePort))
                                                        devices = loadDevices(prefs)
                                                    }
                                                    runCatching {
                                                        withContext(Dispatchers.IO) {
                                                            AdbConnection.connectTcp(ctx as ComponentActivity, device.host, livePort).also { conn ->
                                                                try { conn.verifyDevice(requireSavedSerial(device.serial)) }
                                                                catch (e: Exception) { conn.close(); throw e }
                                                            }
                                                        }
                                                    }.fold(
                                                        onSuccess = { conn ->
                                                            val serial = conn.verifiedSerial!!
                                                            withContext(Dispatchers.IO) { conn.close() }
                                                            updateDeviceById(prefs, device.copy(port = livePort, serial = serial))
                                                            devices = loadDevices(prefs)
                                                            connectingId = null; onLaunchStream(device.host, livePort, serial)
                                                        },
                                                        onFailure = { connectingId = null; errorMsg = japaneseConnectionError(it) },
                                                    )
                                                }
                                            },
                                            modifier = Modifier.weight(1f).padding(end = 4.dp),
                                        ) { Text("投影") }
                                        OutlinedButton(
                                            onClick = {
                                                if (connectingId != null) return@OutlinedButton
                                                connectingId = device.id; errorMsg = null
                                                scope.launch {
                                                    val livePort = if (device.id.startsWith("usb-wifi-")) 5555
                                                        else resolveAdbPortViaMdns(ctx, device.host, device.port)
                                                    if (livePort != device.port) {
                                                        updateDeviceById(prefs, device.copy(port = livePort))
                                                        devices = loadDevices(prefs)
                                                    }
                                                    runCatching {
                                                        withContext(Dispatchers.IO) {
                                                            AdbConnection.connectTcp(ctx as ComponentActivity, device.host, livePort).also { conn ->
                                                                try { conn.verifyDevice(requireSavedSerial(device.serial)) }
                                                                catch (e: Exception) { conn.close(); throw e }
                                                            }
                                                        }
                                                    }.fold(
                                                        onSuccess = { conn ->
                                                            val serial = conn.verifiedSerial!!
                                                            updateDeviceById(prefs, device.copy(port = livePort, serial = serial))
                                                            devices = loadDevices(prefs)
                                                            // Refresh screenshot then open shell
                                                            scope.launch {
                                                                val (_, _, screenshot) = fetchDeviceMetadata(conn, ctx, device.id)
                                                                if (screenshot != null) {
                                                                    updateDeviceById(prefs, device.copy(port = livePort, screenshotPath = screenshot, serial = serial))
                                                                    devices = loadDevices(prefs)
                                                                }
                                                            }
                                                            connectingId = null; onOpenShell(conn, device.model ?: device.name)
                                                        },
                                                        onFailure = { connectingId = null; errorMsg = japaneseConnectionError(it) },
                                                    )
                                                }
                                            },
                                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                                        ) { Text("コマンド操作") }
                                    }
                                }
                            }
                        }
                    }
                }
                errorMsg?.let { msg ->
                    item {
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }

        TextButton(
            onClick = { showInfoDialog = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp),
        ) {
            Text(
                "このアプリについて",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        } // end outer Box
        } // language selector and device list

        editingDevice?.let { dev ->
            var nameField by remember(dev.id) { mutableStateOf(dev.name) }
            var hostField by remember(dev.id) { mutableStateOf(dev.host) }
            var portField by remember(dev.id) { mutableStateOf(dev.port.toString()) }
            AlertDialog(
                onDismissRequest = { editingDevice = null },
                title = { Text("接続情報を編集") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = nameField, onValueChange = { nameField = it },
                            label = { Text("名前") }, singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = hostField, onValueChange = { hostField = it },
                            label = { Text("IP アドレス") }, singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = portField,
                            onValueChange = { v -> portField = v.filter { it.isDigit() } },
                            label = { Text("ポート") }, singleLine = true,
                        )
                        Text(
                            "AURA の通常接続ポートは 5555 です。USB → Wi-Fi で自動設定できます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val p = portField.toIntOrNull() ?: dev.port
                        updateDeviceById(prefs, dev.copy(
                            name = nameField.trim().ifEmpty { dev.name },
                            host = hostField.trim().ifEmpty { dev.host },
                            port = p,
                        ))
                        devices = loadDevices(prefs)
                        editingDevice = null
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { editingDevice = null }) { Text("キャンセル") }
                },
            )
        }

        if (showInfoDialog) {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            val linkColor = MaterialTheme.colorScheme.primary
            val bodyColor = MaterialTheme.colorScheme.onSurface
            val annotated = buildAnnotatedString {
                withStyle(SpanStyle(color = bodyColor)) { append(uiText("開発者：")) }
                pushStringAnnotation(tag = "URL", annotation = "https://github.com/NegiGao")
                withStyle(SpanStyle(color = linkColor)) { append("NegiGao") }
                pop()
                withStyle(SpanStyle(color = bodyColor)) {
                    append(uiText("\n企画・AURA 向け機能開発\n\n" +
                        "AURA の投影・アプリ操作を行う Android 用ツールです。\n\n" +
                        "本アプリは開発・テスト用途を目的としています。" +
                        "接続先端末の所有者、または明確なアクセス許可を得ている方のみ使用してください。" +
                        "無許可のアクセスは法律に違反する可能性があります。" +
                        "不適切な使用について開発者は責任を負いません。"))
                }
                withStyle(SpanStyle(color = bodyColor)) { append(uiText("\n\nオープンソース基盤\nAndroid 投影クライアント：")) }
                pushStringAnnotation(tag = "URL", annotation = "https://github.com/feggaa/scrcpy-android")
                withStyle(SpanStyle(color = linkColor)) { append("Scropy Android / Rabi3（MIT）") }
                pop()
                withStyle(SpanStyle(color = bodyColor)) { append(uiText("\n投影サーバー：")) }
                pushStringAnnotation(tag = "URL", annotation = "https://github.com/Genymobile/scrcpy")
                withStyle(SpanStyle(color = linkColor)) { append("scrcpy / Genymobile（Apache 2.0）") }
                pop()
            }
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                title = { Text("このアプリについて") },
                text = {
                    ClickableText(
                        text = annotated,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium,
                        onClick = { offset ->
                            annotated.getStringAnnotations("URL", offset, offset)
                                .firstOrNull()?.let { link ->
                                    runCatching { uriHandler.openUri(link.item) }
                                        .onFailure { errorMsg = "リンクを開けません。ブラウザを確認してください。" }
                                }
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showInfoDialog = false }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { showInfoDialog = false; showLicenses = true }) { Text("ライセンス") }
                },
            )
        }
        if (showLicenses) {
            val licenses = remember {
                ctx.assets.open("open_source_licenses.txt").bufferedReader().use { it.readText() }
            }
            AlertDialog(
                onDismissRequest = { showLicenses = false },
                title = { Text("オープンソースライセンス") },
                text = { Text(licenses, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
                confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("閉じる") } },
            )
        }
    }
}

// --- Screen: Choose Transport -------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseTransportScreen(onBack: () -> Unit, onWifi: () -> Unit, onUsb: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新しい接続") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("接続方法を選択してください", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(32.dp))
            ElevatedCard(onClick = onWifi, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Wi-Fi で接続", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "IP アドレスで無線接続します。\n初回は USB → Wi-Fi で設定してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            ElevatedCard(onClick = onUsb, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("USB で接続", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "USB OTG ケーブルで接続します。\nAURA の USB デバッグを有効にしてください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// --- Screen: WiFi Connect -----------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiConnectScreen(
    prefs: android.content.SharedPreferences,
    onBack: () -> Unit,
    onLaunchStream: (host: String, port: Int, serial: String?) -> Unit,
    onOpenShell: (conn: AdbConnection, label: String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val scrollState = rememberScrollState()

    var host by remember { mutableStateOf(prefs.getString("last_host", "") ?: "") }
    var adbPortStr by remember { mutableStateOf(prefs.getString("last_adb_port", "") ?: "") }
    var action by remember { mutableStateOf(AppAction.STREAM) }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showPair by remember { mutableStateOf(true) }
    var pairPortStr by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var pendingConn by remember { mutableStateOf<AdbConnection?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var portScanHost by remember { mutableStateOf<String?>(null) }
    var portScanning by remember { mutableStateOf(false) }
    var portScanResults by remember { mutableStateOf<List<Int>>(emptyList()) }
    var portScanDone by remember { mutableStateOf(false) }
    val discoveredDevices = remember { mutableStateListOf<DiscoveredDevice>() }
    var discovering by remember { mutableStateOf(false) }
    var autoScanEnabled by remember { mutableStateOf(false) }
    var discoveryJob by remember { mutableStateOf<Job?>(null) }
    var portScanJob by remember { mutableStateOf<Job?>(null) }
    val discoverySession = remember { DiscoverySession() }
    val nsdManager = remember(ctx) { ctx.getSystemService(android.content.Context.NSD_SERVICE) as android.net.nsd.NsdManager }
    val nsdListenerRef = remember { mutableStateOf<android.net.nsd.NsdManager.DiscoveryListener?>(null) }

    fun stopActiveScans() {
        discoverySession.stop()
        discoveryJob?.cancel(); discoveryJob = null
        portScanJob?.cancel(); portScanJob = null
        nsdListenerRef.value?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        nsdListenerRef.value = null
        scanning = false
        discovering = false
        portScanning = false
    }

    fun scanText(japanese: String, chinese: String, english: String): String = when (UiLanguageState.current) {
        UiLanguage.JAPANESE -> japanese
        UiLanguage.CHINESE -> chinese
        UiLanguage.ENGLISH -> english
    }

    fun startNetworkDiscovery() {
        if (scanning || discovering || portScanning || connecting || pairing) return
        stopActiveScans()
        val token = discoverySession.begin()
        focus.clearFocus()
        scanning = true
        scanResults = emptyList()
        discoveredDevices.clear()
        discovering = true

        discoveryJob = scope.launch {
            try {
                coroutineScope {
                    val listener = runCatching {
                        discoverAdbViaBonjour(ctx, isActive = { discoverySession.accepts(token) && discovering }) { device ->
                            if (discoveredDevices.none { it.host == device.host && it.port == device.port }) {
                                discoveredDevices.add(device)
                            }
                        }
                    }.getOrNull()
                    nsdListenerRef.value = listener
                    if (listener != null) launch {
                        delay(3000)
                        if (discoverySession.accepts(token)) {
                            discovering = false
                            runCatching { nsdManager.stopServiceDiscovery(listener) }
                            nsdListenerRef.value = null
                        }
                    } else discovering = false

                    val results = scanForDevices(ctx)
                    if (discoverySession.accepts(token)) {
                        scanResults = results
                        scanning = false
                        val adbTargets = results.filter { it.port != null }
                        if (host.isBlank() && adbTargets.size == 1) {
                            host = adbTargets.first().host
                            adbPortStr = adbTargets.first().port.toString()
                            status = "AURA を自動検出：$host:$adbPortStr"
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (discoverySession.accepts(token)) {
                    status = scanText("検索に失敗しました。もう一度お試しください。", "扫描失败，请重试。", "Scan failed. Please try again.")
                }
            } finally {
                if (discoverySession.accepts(token)) {
                    discoverySession.stop()
                    nsdListenerRef.value?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
                    nsdListenerRef.value = null
                    scanning = false
                    discovering = false
                    discoveryJob = null
                }
            }
        }
    }

    LaunchedEffect(autoScanEnabled, connecting, pairing) {
        if (!autoScanEnabled || connecting || pairing) return@LaunchedEffect
        while (isActive) {
            startNetworkDiscovery()
            discoveryJob?.join()
            delay(5000)
        }
    }

    val activityLifecycle = (ctx as? ComponentActivity)?.lifecycle
    DisposableEffect(activityLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                autoScanEnabled = false
                stopActiveScans()
            }
        }
        activityLifecycle?.addObserver(observer)
        onDispose {
            activityLifecycle?.removeObserver(observer)
            stopActiveScans()
            runCatching { pendingConn?.close() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi 接続") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .verticalScroll(scrollState),
        ) {
            Spacer(Modifier.height(12.dp))
            Text("操作を選択してください", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row {
                FilterChip(
                    selected = action == AppAction.STREAM,
                    onClick = { action = AppAction.STREAM },
                    label = { Text("投影") },
                    modifier = Modifier.padding(end = 8.dp),
                )
                FilterChip(
                    selected = action == AppAction.SHELL,
                    onClick = { action = AppAction.SHELL },
                    label = { Text("コマンド操作") },
                )
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("端末の IP アドレス") },
                placeholder = { Text("例：192.168.1.65") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = adbPortStr, onValueChange = { adbPortStr = it.filter(Char::isDigit) },
                label = { Text("ADB ポート（AURA は通常 5555）") },
                placeholder = { Text("例：5555") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    focus.clearFocus()
                    val port = adbPortStr.toIntOrNull() ?: return@Button
                    if (host.isBlank()) return@Button
                    stopActiveScans()
                    runCatching { pendingConn?.close() }; pendingConn = null
                    connecting = true; status = "接続中…"; showPair = true
                    prefs.edit().putString("last_host", host.trim()).putString("last_adb_port", adbPortStr).apply()
                    scope.launch {
                        // The wireless-debugging port drifts, so a typed/pre-filled one goes
                        // stale — prefer the live port and correct the field if it moved.
                        val livePort = resolveAdbPortViaMdns(ctx, host.trim(), port)
                        if (livePort != port) adbPortStr = livePort.toString()
                        runCatching {
                            withContext(Dispatchers.IO) {
                                AdbConnection.connectTcp(ctx as ComponentActivity, host.trim(), livePort).also { conn ->
                                    try { conn.verifyDevice(null) } catch (e: Exception) { conn.close(); throw e }
                                }
                            }
                        }.fold(
                            onSuccess = { conn ->
                                autoScanEnabled = false
                                stopActiveScans()
                                pendingConn = conn
                                connecting = false; status = "端末情報を取得中…"
                                val deviceId = UUID.randomUUID().toString()
                                val serial = conn.verifiedSerial!!
                                val baseDevice = SavedDevice(deviceId, host.trim(), host.trim(), livePort, serial = serial)
                                upsertDevice(prefs, baseDevice)
                                val (model, ver, screenshot) = fetchDeviceMetadata(conn, ctx, deviceId)
                                upsertDevice(prefs, baseDevice.copy(
                                    name = model ?: host.trim(),
                                    model = model,
                                    androidVersion = ver,
                                    screenshotPath = screenshot,
                                ))
                                status = null
                                pendingConn = null
                                when (action) {
                                    AppAction.STREAM -> { conn.close(); onLaunchStream(host.trim(), livePort, serial) }
                                    AppAction.SHELL  -> onOpenShell(conn, model ?: host.trim())
                                }
                            },
                            onFailure = { e -> connecting = false; status = "X ${japaneseConnectionError(e)}"; showPair = true },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !connecting && !pairing && host.isNotBlank() && adbPortStr.isNotBlank(),
            ) {
                if (connecting) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (connecting) "接続中…" else "接続")
            }
            status?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(
                    msg,
                    color = if (msg.startsWith("X")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(20.dp))

            // ── Network scan ────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(scanText("自動検索", "自动扫描", "Auto scan"), style = MaterialTheme.typography.labelLarge)
                Switch(
                    checked = autoScanEnabled,
                    onCheckedChange = { enabled ->
                        autoScanEnabled = enabled
                        if (!enabled) stopActiveScans()
                    },
                    enabled = !connecting && !pairing,
                )
            }
            Text(
                scanText("接続後は自動停止します。接続の維持には影響しません。", "连接成功后自动停止，不影响当前连接。", "Stops after connection. Existing connections stay active."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (scanning || discovering || portScanning) {
                        autoScanEnabled = false
                        stopActiveScans()
                    } else startNetworkDiscovery()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !connecting && !pairing,
            ) {
                if (scanning || discovering) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (scanning || discovering || portScanning) scanText("検索を停止", "停止扫描", "Stop scan") else scanText("1回検索", "扫描一次", "Scan once"))
            }

            if ((scanning || discovering) && discoveredDevices.isEmpty() && scanResults.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "ネットワーク上の端末を検索中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Legacy ADB targets (including AURA on port 5555) ───────
            val legacyTargets = scanResults.filter { it.port != null }
            if (legacyTargets.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "AURA / ADB 端末",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                legacyTargets.forEach { device ->
                    ElevatedCard(
                        onClick = {
                            host = device.host
                            adbPortStr = device.port.toString()
                            status = "選択済み：${device.host}:${device.port}"
                            portScanHost = null
                            portScanResults = emptyList()
                            portScanDone = false
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(device.label ?: "ADB 端末", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${device.host}  ·  port ${device.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── Named devices (mDNS / Wireless Debugging) ────────────────
            if (discoveredDevices.isNotEmpty() || discovering) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Android 端末",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (discovering) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                discoveredDevices.forEach { device ->
                    ElevatedCard(
                        onClick = {
                            // Fill the fields from the tapped device, but keep the list
                            // visible (clearing it here made devices seem to "disappear").
                            host = device.host
                            adbPortStr = device.port.toString()
                            portScanHost = null; portScanResults = emptyList(); portScanDone = false
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(device.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${device.host}  \u00b7  port ${device.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── Port scan for selected host ──────────────────────────────
            if (portScanHost != null && portScanHost == host.trim() && adbPortStr.isBlank()) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        autoScanEnabled = false
                        stopActiveScans()
                        val token = discoverySession.begin()
                        portScanning = true
                        portScanResults = emptyList()
                        portScanDone = false
                        val targetHost = portScanHost!!
                        portScanJob = scope.launch {
                            try {
                                val results = scanAdbPorts(targetHost)
                                if (discoverySession.accepts(token) && host.trim() == targetHost) {
                                    portScanResults = results
                                    portScanDone = true
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                if (discoverySession.accepts(token)) {
                                    status = scanText("検索に失敗しました。もう一度お試しください。", "扫描失败，请重试。", "Scan failed. Please try again.")
                                }
                            } finally {
                                if (discoverySession.accepts(token)) {
                                    discoverySession.stop()
                                    portScanning = false
                                    portScanJob = null
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !portScanning && !connecting && !pairing,
                ) {
                    if (portScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (portScanning) "$portScanHost のポートを検索中…"
                        else "$portScanHost の ADB ポートを検索",
                    )
                }
                if (portScanning) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "5 桁のポート（10000～65535）を検索中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (portScanResults.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "利用可能な ADB ポート（タップして選択）：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    portScanResults.forEach { p ->
                        OutlinedButton(
                            onClick = {
                                adbPortStr = p.toString()
                                portScanHost = null
                                portScanResults = emptyList()
                                portScanDone = false
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text("${portScanHost}:$p", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (portScanDone && portScanResults.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$portScanHost に利用可能な 5 桁ポートがありません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!scanning && scanResults.isEmpty() && host.isBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    scanText("「1回検索」で同じ Wi-Fi の ADB 端末を検索できます。", "点击“扫描一次”，查找同一 Wi-Fi 下的 ADB 设备。", "Use Scan once to find ADB devices on the same Wi-Fi."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Pairing is meaningless until a target IP is chosen/entered.
            if (showPair && host.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("端末が未ペアリングです", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "端末の設定 → 開発者向けオプション → ワイヤレス デバッグ\n→ ペア設定コードによるペア設定",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pairPortStr, onValueChange = { pairPortStr = it.filter(Char::isDigit) },
                    label = { Text("ペアリング用ポート（端末の画面を確認）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pairCode, onValueChange = { pairCode = it.filter(Char::isDigit).take(6) },
                    label = { Text("6 桁のペアリングコード") },
                    placeholder = { Text("例：123456") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        focus.clearFocus()
                        val pairPort = pairPortStr.toIntOrNull() ?: return@Button
                        if (host.isBlank() || pairCode.length != 6) return@Button
                        stopActiveScans()
                        runCatching { pendingConn?.close() }; pendingConn = null
                        pairing = true; status = "ペアリング中…"
                        scope.launch {
                            val (priv, pub) = AdbAuthHelper.getOrCreateKeys(ctx as ComponentActivity)
                            runCatching { AdbPairing.pair(host.trim(), pairPort, pairCode, priv, pub) }.fold(
                                onSuccess = {
                                    // The connect port is NOT the pairing port, and it drifts —
                                    // find the live one rather than trusting whatever is typed.
                                    status = "ペアリング完了。端末を検索中…"
                                    kotlinx.coroutines.delay(2000)
                                    val adbPort = resolveAdbPortViaMdns(ctx, host.trim(), adbPortStr.toIntOrNull() ?: 0)
                                    if (adbPort <= 0) {
                                        pairing = false
                                        status = "ペアリング済みですが、接続ポートが見つかりません。" +
                                            "ワイヤレス デバッグを確認し、「ネットワークから検索」を押してください。"
                                    } else {
                                        adbPortStr = adbPort.toString()
                                        status = "ペアリング完了。接続中…"
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                AdbConnection.connectTcp(ctx, host.trim(), adbPort).also { conn ->
                                                    try { conn.verifyDevice(null) } catch (e: Exception) { conn.close(); throw e }
                                                }
                                            }
                                        }.fold(
                                            onSuccess = { conn ->
                                                autoScanEnabled = false
                                                stopActiveScans()
                                                pendingConn = conn
                                                pairing = false; status = "端末情報を取得中…"
                                                val deviceId = UUID.randomUUID().toString()
                                                val serial = conn.verifiedSerial!!
                                                val baseDevice = SavedDevice(deviceId, host.trim(), host.trim(), adbPort, serial = serial)
                                                upsertDevice(prefs, baseDevice)
                                                val (model, ver, screenshot) = fetchDeviceMetadata(conn, ctx, deviceId)
                                                upsertDevice(prefs, baseDevice.copy(
                                                    name = model ?: host.trim(),
                                                    model = model,
                                                    androidVersion = ver,
                                                    screenshotPath = screenshot,
                                                ))
                                                status = null; showPair = true
                                                pendingConn = null
                                                when (action) {
                                                    AppAction.STREAM -> { conn.close(); onLaunchStream(host.trim(), adbPort, serial) }
                                                    AppAction.SHELL  -> onOpenShell(conn, model ?: host.trim())
                                                }
                                            },
                                            onFailure = { e -> pairing = false; status = "ペアリング済みですが、接続に失敗しました：${japaneseConnectionError(e)}" },
                                        )
                                    }
                                },
                                onFailure = { e -> pairing = false; status = "ペアリングに失敗しました：${japaneseConnectionError(e)}" },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !pairing && !connecting && host.isNotBlank() &&
                        pairPortStr.isNotBlank() && pairCode.length == 6,
                ) {
                    if (pairing) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (pairing) "ペアリング中…" else "ペアリングして接続")
                }
            }
        }
    }
}

// --- Screen: USB Connect ------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbConnectScreen(
    prefs: android.content.SharedPreferences,
    onLaunchWifiStream: (String, Int, String?) -> Unit,
    onBack: () -> Unit,
    onLaunchUsbStream: (device: UsbDevice) -> Unit,
    onOpenShell: (conn: AdbConnection, label: String) -> Unit,
    onRequestUsbPermission: (device: UsbDevice, cb: (Boolean) -> Unit) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val usbManager = remember { ctx.getSystemService(Context.USB_SERVICE) as UsbManager }

    var action by remember { mutableStateOf(AppAction.STREAM) }
    var devices by remember { mutableStateOf(findAdbDevices(usbManager)) }
    var connectingDevice by remember { mutableStateOf<UsbDevice?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingConn by remember { mutableStateOf<AdbConnection?>(null) }
    var wirelessSuccess by remember { mutableStateOf<tech.devline.scropy_ui.adb.UsbWifiTarget?>(null) }

    fun prepareWifi(device: UsbDevice) {
        if (connectingDevice != null) return
        connectingDevice = device
        status = "Wi-Fi 接続を準備中… AURA で USB デバッグを許可してください"
        scope.launch {
            runCatching { tech.devline.scropy_ui.adb.setupUsbWifi(ctx, device, usbManager) }.fold(
                onSuccess = { target ->
                    val saved = SavedDevice("usb-wifi-${target.serial}", "AURA ${target.serial}", target.host, target.port, serial = target.serial)
                    upsertDevice(prefs, saved)
                    connectingDevice = null
                    status = "接続完了：${target.host}:${target.port}"
                    wirelessSuccess = target
                },
                onFailure = { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    connectingDevice = null
                    status = "Wi-Fi 接続失敗：${japaneseConnectionError(e)}\n「Wi-Fiで投影」で再試行するか、「USBで投影」を選択してください。"
                },
            )
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            devices = findAdbDevices(usbManager)
            kotlinx.coroutines.delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("USB 接続") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 24.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))
            Text("操作を選択してください", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row {
                FilterChip(
                    selected = action == AppAction.STREAM, onClick = { action = AppAction.STREAM }, enabled = connectingDevice == null,
                    label = { Text("投影") }, modifier = Modifier.padding(end = 8.dp),
                )
                FilterChip(
                    selected = action == AppAction.SHELL, onClick = { action = AppAction.SHELL }, enabled = connectingDevice == null,
                    label = { Text("コマンド操作") },
                )
            }
            Spacer(Modifier.height(20.dp))
            Text("USB のアクセスを許可した後、接続方法を選択してください。USB 投影は Wi-Fi 不要で、ケーブル接続を維持します。Wi-Fi 投影は両端末を同じ信頼できるネットワークに接続してください。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { devices = findAdbDevices(usbManager); status = null },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("USB 端末を検索") }
            Spacer(Modifier.height(16.dp))
            if (devices.isEmpty()) {
                Text(
                    "USB 端末が見つかりません。\n\n次を確認してください：\n・USB デバッグが有効\n・データ通信対応の OTG ケーブルで接続",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                devices.forEach { device ->
                    val hasPermission = usbManager.hasPermission(device)
                    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(device.productName ?: "不明な端末", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "VID: 0x${device.vendorId.toString(16).uppercase()}  PID: 0x${device.productId.toString(16).uppercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            when {
                                !hasPermission -> Button(
                                    onClick = {
                                        onRequestUsbPermission(device) { granted ->
                                            if (granted) { devices = findAdbDevices(usbManager); status = null }
                                            else status = "アクセスが許可されませんでした"
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("アクセスを許可") }
                                connectingDevice == device -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("接続中…", style = MaterialTheme.typography.bodySmall)
                                }
                                action == AppAction.STREAM -> UsbProjectionButtons(
                                    enabled = connectingDevice == null,
                                    onUsb = {
                                        if (connectingDevice == null) {
                                            // Do not open a probe connection: StreamActivity owns USB for its lifetime.
                                            status = null
                                            connectingDevice = device
                                            onLaunchUsbStream(device)
                                        }
                                    },
                                    onWifi = { prepareWifi(device) },
                                )
                                else -> Button(
                                    onClick = {
                                        if (connectingDevice != null) return@Button

                                        runCatching { pendingConn?.close() }; pendingConn = null
                                        connectingDevice = device; status = null
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    AdbConnection.connectUsb(ctx as ComponentActivity, device, usbManager)
                                                }
                                            }.fold(
                                                onSuccess = { conn ->
                                                    connectingDevice = null
                                                    when (action) {
                                                        AppAction.STREAM -> Unit // handled before connecting
                                                        AppAction.SHELL  -> onOpenShell(conn, device.productName ?: "USB 端末")
                                                    }
                                                },
                                                onFailure = { e -> connectingDevice = null; status = "X ${japaneseConnectionError(e)}" },
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = connectingDevice == null,
                                ) { Text("接続") }
                            }
                        }
                    }
                }
            }
            wirelessSuccess?.let { target ->
                AlertDialog(
                    onDismissRequest = { wirelessSuccess = null },
                    title = { Text("Wi-Fi 接続完了") },
                    text = { Text("AURA：${target.serial}\n接続先：${target.host}:${target.port}\n\n無線 ADB 接続と端末の確認が完了しました。USB ケーブルを外せます。\n投影は次のボタンから開始します。") },
                    confirmButton = {
                        TextButton(onClick = {
                            wirelessSuccess = null
                            onLaunchWifiStream(target.host, target.port, target.serial)
                        }) { Text("投影を開始") }
                    },
                    dismissButton = {
                        TextButton(onClick = { wirelessSuccess = null }) { Text("閉じる") }
                    },
                )
            }
            status?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(
                    msg,
                    color = if (msg.startsWith("X") || msg.startsWith("Wi-Fi 接続失敗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// --- Screen: Shell Terminal ---------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellTerminalScreen(
    conn: AdbConnection,
    label: String,
    onDisconnect: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val outputLines = remember { mutableStateListOf("--- Connected to $label ---") }
    val focus = LocalFocusManager.current

    LaunchedEffect(outputLines.size) {
        if (outputLines.isNotEmpty()) listState.animateScrollToItem(outputLines.size - 1)
    }

    fun runCmd() {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        command = ""; focus.clearFocus(); running = true
        outputLines.add("\$ $cmd")
        scope.launch { executeShell(conn, cmd, outputLines); running = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(label) },
                navigationIcon = { TextButton(onClick = onDisconnect) { Text("切断") } },
                actions = { TextButton(onClick = { outputLines.clear() }) { Text("消去") } },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1E1E1E)).padding(8.dp),
            ) {
                items(outputLines) { line ->
                    Text(
                        text = line,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = if (line.startsWith("\$ ")) Color(0xFF4EC9B0) else Color(0xFFD4D4D4),
                        ),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = command, onValueChange = { command = it },
                    label = { Text("コマンド") }, placeholder = { Text("例：ls /sdcard") },
                    singleLine = true, modifier = Modifier.weight(1f), enabled = !running,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { runCmd() }),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = ::runCmd, enabled = command.isNotBlank() && !running) { Text("実行") }
            }
        }
    }
}

// --- Shell execution ----------------------------------------------------------

private suspend fun executeShell(
    conn: AdbConnection,
    cmd: String,
    output: MutableList<String>,
) = withContext(Dispatchers.IO) {
    try {
        val stream = conn.open("shell:$cmd")
        val buf = ByteArray(4096)
        val sb = StringBuilder()
        while (true) {
            val n = stream.inputStream.read(buf)
            if (n < 0) break
            sb.append(String(buf, 0, n, Charsets.UTF_8))
            while (true) {
                val idx = sb.indexOf('\n')
                if (idx < 0) break
                val line = sb.substring(0, idx).trimEnd('\r')
                withContext(Dispatchers.Main) { output.add(line) }
                sb.delete(0, idx + 1)
            }
        }
        if (sb.isNotEmpty()) {
            val remaining = sb.toString().trimEnd('\r', '\n')
            if (remaining.isNotEmpty()) withContext(Dispatchers.Main) { output.add(remaining) }
        }
        stream.close()
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { output.add("[error] ${japaneseConnectionError(e)}") }
    }
}
