package tech.devline.scropy_ui

import android.app.Presentation
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import tech.devline.scropy_ui.LocalizedText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.devline.scropy_ui.adb.AdbConnection
import tech.devline.scropy_ui.adb.DeviceIdentityException
import tech.devline.scropy_ui.scrcpy.AudioPlayer
import tech.devline.scropy_ui.scrcpy.ControlSender
import tech.devline.scropy_ui.scrcpy.ScrcpySession
import tech.devline.scropy_ui.scrcpy.VideoDecoder
import tech.devline.scropy_ui.ui.theme.ScropyTheme

enum class StreamQuality(
    val label: String,
    val maxSize: Int,
    val videoBitRate: Int,
) {
    // Keep the decoder-compatible 1280x800 frame size, but lower the bitrate.
    // This trades fine detail for stable 60 fps playback on Beam Pro.
    SMOOTH("滑らか", 1280, 2_500_000),
    LOW("低", 1280, 4_000_000),
    MEDIUM("中", 1600, 6_000_000),
    HIGH("高", 1920, 8_000_000);

    // AURA's usual 16:10 display; maxSize limits the long edge, not the height.
    val resolutionLabel: String
        get() = when (maxSize) {
            1600 -> "1000p"
            1920 -> "1200p"
            else -> "800p"
        }

    companion object {
        fun from(value: String?): StreamQuality =
            entries.firstOrNull { it.name == value } ?: SMOOTH
    }
}

enum class StreamFrameRate(val label: String, val fps: Int) {
    FPS_30("30fps", 30),
    FPS_60("60fps", 60);

    companion object {
        fun from(value: String?): StreamFrameRate =
            entries.firstOrNull { it.name == value } ?: FPS_60
    }
}

class StreamActivity : ComponentActivity() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_SERIAL = "serial"
        const val EXTRA_USB_DEVICE = "usb_device"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_FRAME_RATE = "frame_rate"

        @Volatile
        var isActive: Boolean = false
            private set

        private const val OUTPUT_WIDTH = 1920
        private const val OUTPUT_HEIGHT = 1200
    }

    // ─── Session state ────────────────────────────────────────────────────────
    @Volatile private var adbConn: AdbConnection? = null
    private var session:     ScrcpySession? = null
    private var videoDecoder: VideoDecoder? = null
    private var audioPlayer:  AudioPlayer?  = null
    private var controlSender: ControlSender? = null
    private var decodeJob: Job? = null
    private var audioJob:  Job? = null
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var healthJob: Job? = null
    private var statisticsJob: Job? = null
    private val commandRunner = OrderedCommandRunner()
    private val latestCommand = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var shuttingDown = false
    private lateinit var displayManager: DisplayManager
    private var externalPresentation: AuraOutputPresentation? = null
    // Remembered connection params so we can offer Retry on failure
    private var pendingHost: String? = null
    private var pendingPort: Int = 5555
    private var pendingSerial: String? = null
    private var pendingUsbDevice: UsbDevice? = null
    private var streamQuality: StreamQuality = StreamQuality.SMOOTH
    private var streamFrameRate: StreamFrameRate = StreamFrameRate.FPS_60

    @Volatile private var surface: Surface? = null

    // ─── UI state ─────────────────────────────────────────────────────────────
    private var statusText  = mutableStateOf("接続中…")
    private var errorText   = mutableStateOf<String?>(null)
    private var isConnected = mutableStateOf(false)
    // Whether the remote device's physical screen is currently powered on.
    private var screenOn    = mutableStateOf(true)
    private var controlOnly = mutableStateOf(false)
    private var remoteAspect = mutableFloatStateOf(16f / 10f)
    private var commandStatus = mutableStateOf("")
    private var audioWarning = mutableStateOf<String?>(null)
    private var videoRates = mutableStateOf(WAITING_VIDEO_RATES)
    private var videoResolution = mutableStateOf("— × —")
    private var appCountdown = mutableStateOf<AppCountdown?>(null)
    private var externalDisplayAttached = mutableStateOf(false)
    private var externalDisplayStatus = mutableStateOf("Beam Pro 本体表示")
    private val approvedExternalDisplays = mutableSetOf<Int>()
    private val declinedExternalDisplays = mutableSetOf<Int>()
    private var externalDisplayPrompt: android.app.AlertDialog? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = showExternalPresentation()
        override fun onDisplayChanged(displayId: Int) = showExternalPresentation()
        override fun onDisplayRemoved(displayId: Int) {
            approvedExternalDisplays.remove(displayId)
            declinedExternalDisplays.remove(displayId)
            externalDisplayPrompt?.dismiss()
            externalDisplayPrompt = null
            if (externalPresentation?.display?.displayId == displayId) {
                externalPresentation?.dismiss()
                externalPresentation = null
                externalDisplayAttached.value = false
                externalDisplayStatus.value = "Beam Pro 本体表示"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isActive = true

        // Go fully immersive — hide status bar and navigation bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Determine connection mode: USB or TCP
        val usbDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_USB_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_USB_DEVICE)
        }

        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, 5555)
        streamQuality = StreamQuality.from(intent.getStringExtra(EXTRA_QUALITY))
        streamFrameRate = StreamFrameRate.from(intent.getStringExtra(EXTRA_FRAME_RATE))

        if (usbDevice == null && host == null) { finish(); return }

        pendingHost = host
        pendingPort = port
        pendingSerial = intent.getStringExtra(EXTRA_SERIAL)
        pendingUsbDevice = usbDevice

        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)
        showExternalPresentation()

        setContent {
            ScropyTheme {
                val connectionStatus = streamConnectionStatus(pendingUsbDevice != null, isConnected.value, errorText.value != null)
                BeamProControllerScreen(
                    statusText  = statusText.value,
                    errorText   = errorText.value,
                    isConnected = isConnected.value,
                    screenOn    = screenOn.value,
                    controlOnly = controlOnly.value,
                    onPanelMode = { if (!controlOnly.value) toggleControlPanel() },
                    remoteAspect = remoteAspect.floatValue,
                    externalDisplayAttached = externalDisplayAttached.value,
                    externalDisplayStatus = externalDisplayStatus.value,
                    streamQuality = streamQuality,
                    streamFrameRate = streamFrameRate,
                    commandStatus = commandStatus.value,
                    audioWarning = audioWarning.value,
                    connectionStatus = connectionStatus,
                    videoRates = "${videoResolution.value}\n${videoRates.value}",
                    appCountdown = appCountdown.value,
                    onClearAppCountdown = { appCountdown.value = null },
                    onToggleScreen = {
                        val newOn = !screenOn.value
                        screenOn.value = newOn
                        controlSender?.sendDisplayPower(newOn)
                    },
                    onSurfaceReady = ::handleOutputSurfaceReady,
                    onSurfaceDestroyed = ::handleOutputSurfaceDestroyed,
                    onTouchEvent = { ev, w, h -> controlSender?.sendTouchEvent(ev, w, h) },
                    onScrollEvent = { ev, w, h -> controlSender?.sendScrollEvent(ev, w, h) },
                    onDisconnect        = {
                        shuttingDown = true
                        disconnectAll()
                        finish()
                    },
                    onRetry             = ::retryConnection,
                    onCommand           = ::runAuraCommand,
                )
            }
        }
    }

    /**
     * Put the video on a secondary display while this Activity remains on Beam
     * Pro as the controller.  A Presentation gives the two displays independent
     * content instead of Android's normal screen mirroring.
     */
    private fun showExternalPresentation() {
        if (controlOnly.value) return
        val candidates = (
            displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList() +
                displayManager.displays.toList()
            )
            .distinctBy { it.displayId }
            .filter { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }
        val target = candidates.firstOrNull()

        if (target == null) {
            externalDisplayPrompt?.dismiss()
            externalDisplayPrompt = null
            approvedExternalDisplays.clear()
            declinedExternalDisplays.clear()
            // On Beam Pro itself, use the long side for the 16:10 AURA image.
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            externalPresentation?.dismiss()
            externalPresentation = null
            externalDisplayAttached.value = false
            externalDisplayStatus.value = "Beam Pro 本体表示"
            return
        }
        if (target.displayId !in approvedExternalDisplays) {
            if (!externalDisplayAttached.value) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            if (target.displayId in declinedExternalDisplays || externalDisplayPrompt != null) return
            externalDisplayPrompt = android.app.AlertDialog.Builder(this)
                .setTitle(uiText("外部ディスプレイに投影しますか？"))
                .setMessage(uiText("接続先：${target.name}\nキャンセルすると、Beam Pro 本体での投影を続けます。"))
                .setPositiveButton(uiText("外部に投影")) { _, _ ->
                    approvedExternalDisplays.add(target.displayId)
                    externalDisplayPrompt = null
                    showExternalPresentation()
                }
                .setNegativeButton(uiText("キャンセル")) { _, _ ->
                    declinedExternalDisplays.add(target.displayId)
                    externalDisplayPrompt = null
                }
                .setOnCancelListener {
                    declinedExternalDisplays.add(target.displayId)
                    externalDisplayPrompt = null
                }
                .create().also { it.show() }
            return
        }
        // With a second display attached, keep the handheld controller upright.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        if (externalPresentation?.display?.displayId == target.displayId &&
            externalPresentation?.isShowing == true
        ) return

        externalPresentation?.dismiss()
        externalDisplayAttached.value = true
        externalPresentation = AuraOutputPresentation(
            context = this,
            targetDisplay = target,
            outputWidth = OUTPUT_WIDTH,
            outputHeight = OUTPUT_HEIGHT,
            onSurfaceReady = ::handleOutputSurfaceReady,
            onSurfaceDestroyed = ::handleOutputSurfaceDestroyed,
            onTouchEvent = { ev, w, h -> controlSender?.sendTouchEvent(ev, w, h) },
            onScrollEvent = { ev, w, h -> controlSender?.sendScrollEvent(ev, w, h) },
        ).also { it.show() }
        externalPresentation?.updateAspect(remoteAspect.floatValue)

        val exactMode = target.supportedModes.any {
            it.physicalWidth == OUTPUT_WIDTH && it.physicalHeight == OUTPUT_HEIGHT
        }
        externalDisplayStatus.value = if (exactMode) {
            "外接出力 ${OUTPUT_WIDTH}×${OUTPUT_HEIGHT}（横画面）"
        } else {
            "外接出力 16:10（${OUTPUT_WIDTH}×${OUTPUT_HEIGHT} 基準）"
        }
    }

    private fun handleOutputSurfaceReady(newSurface: Surface) {
        if (controlOnly.value) return
        surface = newSurface
        if (isConnected.value) {
            restartVideoOnSurface(newSurface)
            return
        }
        if (pendingUsbDevice != null || pendingHost != null) startConnection()
    }

    private fun handleOutputSurfaceDestroyed(oldSurface: Surface) {
        if (surface !== oldSurface) return
        surface = null
        videoDecoder?.releaseCodec()
    }

    // ─── Connection lifecycle ─────────────────────────────────────────────────

    private var recoveryFailures = 0
    private var connectedAtMs = 0L

    private fun startConnection(automatic: Boolean = false) {
        if (shuttingDown || isFinishing || isConnected.value || connectJob?.isActive == true) return
        if (!controlOnly.value && surface == null) return
        val attempts = if (automatic) 3 - recoveryFailures else 1
        if (attempts <= 0) {
            errorText.value = "接続が切れました。再接続してください。"
            keepScreenAwake(false)
            clearSessionResources()
            return
        }
        isConnected.value = false
        errorText.value = null
        connectJob = lifecycleScope.launch {
            val ownJob = currentCoroutineContext()[Job]
            try {
                retryConnectionOperation(
                    attempts = attempts,
                    canRetry = { it !is DeviceIdentityException },
                    onAttempt = {
                        if (automatic) {
                            recoveryFailures++
                            statusText.value = "再接続中…（$recoveryFailures/3）"
                            delay(1000L * recoveryFailures)
                        } else {
                            statusText.value = "接続中…"
                        }
                        withContext(Dispatchers.IO) { clearSessionResources() }
                    },
                ) { establishConnection() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("StreamActivity", "Connection failed", e)
                withContext(Dispatchers.IO) { clearSessionResources() }
                isConnected.value = false
                statusText.value = "接続失敗"
                errorText.value = japaneseConnectionError(e)
                keepScreenAwake(false)
            } finally {
                if (connectJob === ownJob) connectJob = null
            }
        }
    }

    private suspend fun establishConnection() {
        var fresh: AdbConnection? = null
        try {
            val usb = pendingUsbDevice
            val connection = if (usb != null) {
                AdbConnection.connectUsb(this, usb, getSystemService(USB_SERVICE) as UsbManager)
            } else {
                AdbConnection.connectTcp(this, pendingHost ?: error("接続先がありません。"), pendingPort)
            }
            fresh = connection
            currentCoroutineContext().ensureActive()
            pendingSerial = withContext(Dispatchers.IO) { connection.verifyDevice(pendingSerial) }
            adbConn = connection

            if (controlOnly.value) {
                val ready = withContext(Dispatchers.IO) {
                    connection.readCommand("shell:echo __AURA_CONTROL_READY__", 5000) == "__AURA_CONTROL_READY__"
                }
                check(ready) { "操作用接続を確認できません。" }
                statusText.value = "操作パネル（投影停止・AURA 接続済み）"
            } else {
                statusText.value = "投影サーバーを起動中…"
                val sess = ScrcpySession.start(
                    context = this, adb = connection, enableAudio = true,
                    maxSize = streamQuality.maxSize, maxFps = streamFrameRate.fps,
                    videoBitRate = streamQuality.videoBitRate,
                )
                session = sess
                currentCoroutineContext().ensureActive()
                val info = sess.deviceInfo
                controlSender = ControlSender(sess.controlStream).also {
                    it.deviceWidth = info.width
                    it.deviceHeight = info.height
                }
                updateVideoSize(connection, info.width, info.height)
                statusText.value = "${info.name} を投影中"
            }

            isConnected.value = true
            errorText.value = null
            connectedAtMs = SystemClock.elapsedRealtime()
            keepScreenAwake(true)
            connection.setDisconnectListener {
                lifecycleScope.launch { onConnectionLost(connection) }
            }
            if (!controlOnly.value) startMedia(connection, session!!)
            startControlHealthCheck(connection)
            fresh = null
        } finally {
            fresh?.let { connection ->
                withContext(NonCancellable + Dispatchers.IO) {
                    connection.setDisconnectListener(null)
                    connection.close()
                }
            }
        }
    }

    private fun startMedia(connection: AdbConnection, sess: ScrcpySession) {
        val info = sess.deviceInfo
        val decoder = VideoDecoder(
            stream = sess.videoStream, codecId = info.videoCodec,
            width = info.width, height = info.height,
            onResize = { w, h ->
                lifecycleScope.launch { updateVideoSize(connection, w, h) }
            },
        )
        videoDecoder = decoder
        statisticsJob?.cancel()
        videoRates.value = WAITING_VIDEO_RATES
        val sampler = VideoRateSampler(SystemClock.elapsedRealtime())
        statisticsJob = lifecycleScope.launch {
            while (isConnected.value && videoDecoder === decoder) {
                delay(1000)
                if (!isConnected.value || videoDecoder !== decoder) break
                sampler.sample(SystemClock.elapsedRealtime(), decoder.framesReceived, decoder.videoBytesReceived)
                    ?.let { videoRates.value = it.label }
            }
        }
        decodeJob = lifecycleScope.launch(Dispatchers.IO) {
            runMediaWorker(onFailure = { e ->
                android.util.Log.e("StreamActivity", "Video initialization/decode failed", e)
            }) {
                val output = surface ?: throw java.io.IOException("映像出力先がありません。")
                decoder.start(output)
            }
            withContext(Dispatchers.Main) {
                if (videoDecoder === decoder) onConnectionLost(connection)
            }
        }
        if (sess.audioStream != null && info.audioCodec !in 0..1) {
            val player = AudioPlayer(this, sess.audioStream, info.audioCodec)
            audioPlayer = player
            audioJob = lifecycleScope.launch(Dispatchers.IO) {
                runMediaWorker(onFailure = { e ->
                    android.util.Log.e("StreamActivity", "Audio initialization/decode failed", e)
                }) { player.start() }
                withContext(Dispatchers.Main) {
                    if (audioPlayer === player && isConnected.value) {
                        audioWarning.value = "音声転送が停止しました。映像と AURA の音声は継続します。"
                    }
                }
            }
        } else {
            audioWarning.value = "音声転送を開始できません。映像は継続します。"
        }
    }

    private fun updateVideoSize(connection: AdbConnection, width: Int, height: Int) {
        if (adbConn !== connection || width <= 0 || height <= 0) return
        controlSender?.let { it.deviceWidth = width; it.deviceHeight = height }
        remoteAspect.floatValue = width.toFloat() / height
        videoResolution.value = "$width × $height"
        externalPresentation?.updateAspect(remoteAspect.floatValue)
    }

    private fun restartVideoOnSurface(output: Surface) {
        val connection = adbConn ?: return
        try {
            videoDecoder?.restartCodec(output)
            controlSender?.sendResetVideo()
        } catch (e: Exception) {
            android.util.Log.e("StreamActivity", "Video surface restart failed", e)
            onConnectionLost(connection)
        }
    }

    private fun startControlHealthCheck(connection: AdbConnection) {
        healthJob?.cancel()
        healthJob = lifecycleScope.launch {
            while (isConnected.value && adbConn === connection) {
                delay(5000)
                if (!controlOnly.value && appCountdown.value == null) continue
                try {
                    commandRunner.run {
                        if (adbConn !== connection || !isConnected.value) return@run
                        val current = appCountdown.value
                        val snapshot = withContext(Dispatchers.IO) {
                            connection.readCommand(if (current == null) "shell:echo __AURA_ALIVE__"
                                else "shell:am stack list; echo __AURA_ALIVE__", 3000)
                        }
                        if (snapshot.lineSequence().lastOrNull() != "__AURA_ALIVE__") {
                            throw java.io.IOException("ADB health check failed")
                        }
                        if (adbConn === connection && appCountdown.value === current) {
                            appCountdown.value = appCountdownAfterTaskSnapshot(current, snapshot)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (controlOnly.value || !connection.isAlive()) {
                        onConnectionLost(connection)
                        return@launch
                    }
                }
            }
        }
    }

    private fun toggleControlPanel() {
        if (controlOnly.value) return
        controlOnly.value = true
        isConnected.value = false
        errorText.value = null
        statusText.value = "操作用接続を準備中…"
        externalDisplayPrompt?.dismiss()
        externalDisplayPrompt = null
        externalPresentation?.dismiss()
        externalPresentation = null
        externalDisplayAttached.value = false
        surface = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        retryConnection()
    }

    private fun disconnectAll() {
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        clearSessionResources()
        isConnected.value = false
        keepScreenAwake(false)
    }

    private fun clearSessionResources() {
        val connection = adbConn
        adbConn = null
        connection?.setDisconnectListener(null)
        latestCommand.incrementAndGet()
        healthJob?.cancel()
        healthJob = null
        statisticsJob?.cancel()
        statisticsJob = null
        videoRates.value = WAITING_VIDEO_RATES
        decodeJob?.cancel()
        audioJob?.cancel()
        controlSender?.close()
        videoDecoder?.stop()
        audioPlayer?.stop()
        session?.close()
        connection?.close()
        session = null
        videoDecoder = null
        audioPlayer = null
        controlSender = null
        audioWarning.value = null
    }

    private fun onConnectionLost(connection: AdbConnection) {
        if (shuttingDown || isFinishing || adbConn !== connection || !isConnected.value) return
        if (SystemClock.elapsedRealtime() - connectedAtMs >= 30_000) recoveryFailures = 0
        isConnected.value = false
        commandStatus.value = ""
        latestCommand.incrementAndGet()
        errorText.value = null
        statusText.value = "接続が切れました。再接続中…"
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            connectJob?.join()
            if (!shuttingDown && !isFinishing) startConnection(automatic = true)
        }
    }

    private fun keepScreenAwake(keepAwake: Boolean) {
        if (keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun runAuraCommand(label: String, command: String) {
        if (!isConnected.value) { commandStatus.value = "失敗"; return }
        val connection = adbConn ?: run { commandStatus.value = "失敗"; return }
        val request = latestCommand.incrementAndGet()
        commandStatus.value = ""
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                commandRunner.run {
                    if (adbConn !== connection || !isConnected.value) return@run
                    val output = withContext(Dispatchers.IO) {
                        connection.readCommand("shell:($command); result=\$?; echo __AURA_RESULT__\$result",
                            timeoutMs = 8_000L)
                    }
                    var succeeded = output.lineSequence().lastOrNull() == "__AURA_RESULT__0" &&
                        !Regex("(?im)(error|exception|permission denial|failed|not found)").containsMatchIn(output)
                    val app = experienceAppForCommand(command)
                    if (succeeded && app != null) {
                        succeeded = withContext(Dispatchers.IO) {
                            for (attempt in 0..2) {
                                if (hasAppTask(connection.readCommand("shell:am stack list", 3000), app)) {
                                    return@withContext true
                                }
                                if (attempt < 2) delay(150)
                            }
                            false
                        }
                    }
                    android.util.Log.i("AuraCommand", "$label: $output")
                    if (adbConn === connection && isConnected.value) {
                        appCountdown.value = nextAppCountdown(appCountdown.value, command, succeeded,
                            SystemClock.elapsedRealtime())
                    }
                    if (request == latestCommand.get() && adbConn === connection) {
                        commandStatus.value = if (succeeded) "OK" else "失敗"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AuraCommand", "$label failed", e)
                if (request == latestCommand.get() && adbConn === connection) commandStatus.value = "失敗"
                if (!connection.isAlive()) onConnectionLost(connection)
            }
        }
    }

    /** Re-attempt the connection after a failure, reusing the original params. */
    private fun retryConnection() {
        reconnectJob?.cancel()
        val previous = connectJob
        previous?.cancel()
        adbConn?.setDisconnectListener(null)
        adbConn?.close()
        recoveryFailures = 0
        errorText.value = null
        statusText.value = "再接続中…"
        isConnected.value = false
        reconnectJob = lifecycleScope.launch {
            previous?.join()
            if (!shuttingDown && !isFinishing) {
                withContext(Dispatchers.IO) { clearSessionResources() }
                startConnection()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Release the video codec before the surface is destroyed by the system
        videoDecoder?.releaseCodec()
        audioPlayer?.pauseAudio()
    }

    override fun onResume() {
        super.onResume()
        showExternalPresentation()
        surface?.let { s ->
            if (isConnected.value) {
                restartVideoOnSurface(s)
            }
        }
        audioPlayer?.resumeAudio()
    }

    override fun onDestroy() {
        externalDisplayPrompt?.dismiss()
        externalDisplayPrompt = null
        isActive = false
        shuttingDown = true
        connectJob?.cancel()
        if (::displayManager.isInitialized) {
            runCatching { displayManager.unregisterDisplayListener(displayListener) }
        }
        externalPresentation?.dismiss()
        externalPresentation = null
        disconnectAll()
        super.onDestroy()
    }
}

// ─── Composable UI ─────────────────────────────────────────────────────────────

@Composable
internal fun BeamProControllerScreen(
    statusText: String,
    errorText: String?,
    isConnected: Boolean,
    screenOn: Boolean,
    controlOnly: Boolean,
    onPanelMode: () -> Unit,
    remoteAspect: Float,
    externalDisplayAttached: Boolean,
    externalDisplayStatus: String,
    streamQuality: StreamQuality,
    streamFrameRate: StreamFrameRate,
    commandStatus: String,
    audioWarning: String?,
    connectionStatus: String,
    videoRates: String,
    appCountdown: AppCountdown?,
    onClearAppCountdown: () -> Unit,
    onToggleScreen: () -> Unit,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    onTouchEvent: (MotionEvent, Int, Int) -> Unit,
    onScrollEvent: (MotionEvent, Int, Int) -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onCommand: (String, String) -> Unit,
) {
    // A monotonic deadline survives menu dismissal, output-mode changes and rotation.
    var timerDeadline by rememberSaveable { mutableStateOf<Long?>(null) }
    var timerNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(timerDeadline, appCountdown?.deadline) {
        timerNow = SystemClock.elapsedRealtime()
        val deadline = maxOf(timerDeadline ?: 0L, appCountdown?.deadline ?: 0L)
        while (timerNow < deadline) {
            delay(minOf(1_000L, deadline - timerNow))
            timerNow = SystemClock.elapsedRealtime()
        }
    }
    val timer = experienceTimerDisplay(timerDeadline, timerNow)
    val onTimerStart = {
        timerNow = SystemClock.elapsedRealtime()
        timerDeadline = startExperienceTimer(timerDeadline, timerNow)
    }
    val onTimerReset = { timerDeadline = null; onClearAppCountdown() }
    if (!externalDisplayAttached && !controlOnly) {
        LocalBeamProStreamScreen(
            statusText = statusText,
            errorText = errorText,
            isConnected = isConnected,
            screenOn = screenOn,
            remoteAspect = remoteAspect,
            streamQuality = streamQuality,
            streamFrameRate = streamFrameRate,
            commandStatus = commandStatus,
            audioWarning = audioWarning,
            connectionStatus = connectionStatus,
            videoRates = videoRates,
            onToggleScreen = onToggleScreen,
            onSurfaceReady = onSurfaceReady,
            onSurfaceDestroyed = onSurfaceDestroyed,
            onTouchEvent = onTouchEvent,
            onScrollEvent = onScrollEvent,
            onDisconnect = onDisconnect,
            onPanelMode = onPanelMode,
            onRetry = onRetry,
            onCommand = onCommand,
            timer = timer,
            onTimerStart = onTimerStart,
            onTimerReset = onTimerReset,
            appCountdown = appCountdown,
            timerNow = timerNow,
        )
        return
    }

    var externalControlsVisible by remember { mutableStateOf(true) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            StreamToolbar(connectionStatus, if (controlOnly) "" else videoRates,
                externalControlsVisible, timer, appCountdown, timerNow,
                onTimerStart = onTimerStart,
                onCloseAllApps = { AuraCommands.closeAllApps.let { onCommand(it.label, it.command) } },
                onToggleControls = { externalControlsVisible = !externalControlsVisible })
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(if (controlOnly) "操作パネル" else externalDisplayStatus, style = MaterialTheme.typography.titleSmall)
                    Text(connectionStatus, style = MaterialTheme.typography.labelMedium)
                    if (!controlOnly) Text(videoRates, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (controlOnly) "投影・音声転送は停止中。AURA の操作は続けられます。" else "画質 ${streamQuality.label}（${streamQuality.resolutionLabel}目安）/ ${streamFrameRate.fps}fps ・ ${audioWarning ?: "AURA + Beam Pro 音声"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        errorText ?: statusText,
                        color = if (errorText == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (errorText != null) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRetry) { Text("再接続") }
                    }
                }
            }

            if (isConnected && externalControlsVisible) {
                if (commandStatus.isNotBlank()) {
                    Text(commandStatus, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall)
                }
                SectionTitle("アプリ")
                AppPanelButtons(onCommand)
                SectionTitle("操作")
                AuraCommands.controls.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { action ->
                            ControlButton(action.label, Modifier.weight(1f)) { onCommand(action.label, action.command) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                SectionTitle("AURA 音量")
                Row(Modifier.fillMaxWidth()) {
                    listOf(0, 4, 8, 12, 15).forEach { value ->
                        ControlButton(value.toString(), Modifier.weight(1f)) {
                            onCommand("音量 $value", AuraCommands.mediaVolume(value))
                        }
                    }
                }
                AuraCommands.modes.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { action ->
                            ControlButton(action.label, Modifier.weight(1f)) { onCommand(action.label, action.command) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    ControlButton(if (controlOnly) "操作パネル（投影停止中）" else "投影を停止・操作のみ", Modifier.weight(1f), onPanelMode)
                    ControlButton("投影・操作を終了", Modifier.weight(1f), onDisconnect)
                }
            } else if (!isConnected && errorText == null) {
                Spacer(Modifier.height(18.dp))
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                TextButton(onClick = onDisconnect) { Text("接続をキャンセル") }
            } else if (!isConnected) {
                Row(Modifier.fillMaxWidth()) {
                    ControlButton("再接続", Modifier.weight(1f), onRetry)
                    ControlButton("投影・操作を終了", Modifier.weight(1f), onDisconnect)
                }
            }
            if (externalControlsVisible) {
                Spacer(Modifier.height(12.dp))
                ExperienceTimerResetButton(onTimerReset)
            }
        }
    }
}

/** Beam Pro-only fallback: video stays full-size on the handset when no second display exists. */
@Composable
internal fun LocalBeamProStreamScreen(
    statusText: String,
    errorText: String?,
    isConnected: Boolean,
    screenOn: Boolean,
    remoteAspect: Float,
    streamQuality: StreamQuality,
    streamFrameRate: StreamFrameRate,
    commandStatus: String,
    audioWarning: String?,
    connectionStatus: String,
    videoRates: String,
    onToggleScreen: () -> Unit,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    onTouchEvent: (MotionEvent, Int, Int) -> Unit,
    onScrollEvent: (MotionEvent, Int, Int) -> Unit,
    onDisconnect: () -> Unit,
    onPanelMode: () -> Unit,
    onRetry: () -> Unit,
    onCommand: (String, String) -> Unit,
    timer: ExperienceTimerDisplay,
    onTimerStart: () -> Unit,
    onTimerReset: () -> Unit,
    appCountdown: AppCountdown?,
    timerNow: Long,
) {
    var controlsVisible by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        val railWidth = if (controlsVisible) minOf(maxWidth, minOf(300.dp, maxWidth * 0.42f).coerceAtLeast(224.dp))
            else minOf(148.dp, maxWidth * 0.35f)
        val levelColumns = if (railWidth >= 296.dp) 5 else 3
        Row(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().testTag("stream-video-area")) {
                val (videoWidthPx, videoHeightPx) = fitVideoSize(
                    constraints.maxWidth.coerceAtLeast(1), constraints.maxHeight.coerceAtLeast(1), remoteAspect)
                val density = LocalDensity.current
                val videoWidth = with(density) { videoWidthPx.toDp() }
                val videoHeight = with(density) { videoHeightPx.toDp() }
                AndroidView(
                    modifier = Modifier.width(videoWidth).height(videoHeight)
                        .align(Alignment.CenterStart).testTag("stream-video"),
                    factory = { context ->
                        SurfaceView(context).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                private var currentSurface: Surface? = null
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    currentSurface = holder.surface
                                    onSurfaceReady(holder.surface)
                                }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    currentSurface?.let(onSurfaceDestroyed)
                                    currentSurface = null
                                }
                            })
                            setOnTouchListener { view, event ->
                                when (event.action) {
                                    MotionEvent.ACTION_SCROLL -> onScrollEvent(event, view.width, view.height)
                                    else -> onTouchEvent(event, view.width, view.height)
                                }
                                true
                            }
                        }
                    },
                )

                if (!isConnected) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(
                            Modifier.fillMaxSize().padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (errorText == null) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text(statusText)
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick = onDisconnect) { Text("接続をキャンセル") }
                            } else {
                                Text("接続に失敗しました", color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(8.dp))
                                Text(errorText, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(onClick = onRetry) { Text("再接続") }
                                    OutlinedButton(onClick = onDisconnect) { Text("戻る") }
                                }
                            }
                        }
                    }
                }
            }
            if (isConnected) {
                Column(Modifier.width(railWidth).fillMaxHeight().testTag("stream-control-rail")) {
                    StreamToolbar(connectionStatus,
                        videoRates + (audioWarning?.let { "\n$it" } ?: ""),
                        controlsVisible, timer, appCountdown, timerNow,
                        onTimerStart = onTimerStart,
                        onCloseAllApps = { AuraCommands.closeAllApps.let { onCommand(it.label, it.command) } },
                        onToggleControls = { controlsVisible = !controlsVisible })
                    if (controlsVisible) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().weight(1f)
                                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                                .testTag("stream-controls"),
                            shape = RoundedCornerShape(14.dp),
                            color = Color.Black.copy(alpha = 0.82f),
                            contentColor = Color.White,
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                                    if (commandStatus.isNotBlank()) Text(commandStatus, style = MaterialTheme.typography.labelSmall)
                                    SectionTitle("アプリ")
                                    AppPanelButtons(onCommand)
                                    SectionTitle("操作")
                                    AuraCommands.controls.chunked(2).forEach { row ->
                                        Row(Modifier.fillMaxWidth()) {
                                            row.forEach { action ->
                                                ControlButton(action.label, Modifier.weight(1f)) { onCommand(action.label, action.command) }
                                            }
                                            if (row.size == 1) Spacer(Modifier.weight(1f))
                                        }
                                    }
                                    SectionTitle("AURA 音量")
                                    listOf(0, 4, 8, 12, 15).chunked(levelColumns).forEach { row ->
                                        Row(Modifier.fillMaxWidth()) {
                                            row.forEach { value ->
                                                ControlButton(value.toString(), Modifier.weight(1f)) {
                                                    onCommand("音量 $value", AuraCommands.mediaVolume(value))
                                                }
                                            }
                                        }
                                    }
                                    AuraCommands.modes.chunked(2).forEach { row ->
                                        Row(Modifier.fillMaxWidth()) {
                                            row.forEach { action ->
                                                ControlButton(action.label, Modifier.weight(1f)) { onCommand(action.label, action.command) }
                                            }
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth()) {
                                        ControlButton("投影を停止・操作のみ", Modifier.weight(1f), onPanelMode)
                                        ControlButton("投影・操作を終了", Modifier.weight(1f), onDisconnect)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    ExperienceTimerResetButton(onTimerReset)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppPanelButtons(onCommand: (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AuraCommands.apps.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { action -> AppControlButton(action, Modifier.weight(1f), onCommand) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AppControlButton(action: AuraAction, modifier: Modifier, onCommand: (String, String) -> Unit) {
    if (action.label == "Gemini") {
        Row(modifier) {
            ControlButton("Gemini 起動", Modifier.weight(1f)) { onCommand("Gemini", action.command) }
            ControlButton("Gemini 終了", Modifier.weight(1f)) {
                onCommand("Gemini", AuraCommands.geminiClose)
            }
        }
    } else {
        ControlButton(action.label, modifier) { onCommand(action.label, action.command) }
    }
}

@Composable
private fun SectionTitle(label: String) {
    Text(label, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp), style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun ControlButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.padding(2.dp), contentPadding = PaddingValues(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Full-screen 16:10 video output hosted only on the external display. */
private class AuraOutputPresentation(
    context: Context,
    targetDisplay: Display,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val onSurfaceReady: (Surface) -> Unit,
    private val onSurfaceDestroyed: (Surface) -> Unit,
    private val onTouchEvent: (MotionEvent, Int, Int) -> Unit,
    private val onScrollEvent: (MotionEvent, Int, Int) -> Unit,
) : Presentation(context, targetDisplay) {
    private var videoView: AspectSurfaceView? = null

    fun updateAspect(aspect: Float) {
        videoView?.let { video ->
            video.targetAspect = aspect
            val (width, height) = fitVideoSize(outputWidth, outputHeight, aspect)
            video.holder.setFixedSize(width, height)
            video.requestLayout()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawableResource(android.R.color.black)

        display.supportedModes
            .firstOrNull { it.physicalWidth == outputWidth && it.physicalHeight == outputHeight }
            ?.let { mode ->
                window?.attributes = window?.attributes?.apply {
                    preferredDisplayModeId = mode.modeId
                }
            }

        val root = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val video = AspectSurfaceView(context, outputWidth.toFloat() / outputHeight).apply {
            holder.setFixedSize(outputWidth, outputHeight)
            holder.addCallback(object : SurfaceHolder.Callback {
                private var currentSurface: Surface? = null

                override fun surfaceCreated(holder: SurfaceHolder) {
                    currentSurface = holder.surface
                    onSurfaceReady(holder.surface)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    currentSurface?.let(onSurfaceDestroyed)
                    currentSurface = null
                }
            })
            setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_SCROLL -> onScrollEvent(event, view.width, view.height)
                    else -> onTouchEvent(event, view.width, view.height)
                }
                true
            }
        }
        videoView = video
        root.addView(
            video,
            FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        setContentView(root)
    }
}

/** Fit the actual video inside the output canvas; never stretch or crop it. */
private class AspectSurfaceView(context: Context, var targetAspect: Float) : SurfaceView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        val (width, height) = fitVideoSize(maxWidth.coerceAtLeast(1), maxHeight.coerceAtLeast(1), targetAspect)
        setMeasuredDimension(width, height)
    }
}
