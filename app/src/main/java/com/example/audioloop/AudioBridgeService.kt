package com.example.audioloop

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

/**
 * 前台服务：负责
 * 1. 枚举当前可用的音频输入 / 输出设备（含 3.5mm、蓝牙、USB、内置麦克风/扬声器）。
 * 2. 让用户手动选择“输入设备”，输出设备默认自动排除掉与输入相同的物理蓝牙设备后选取。
 * 3. 真正做音频采集 -> 播放的转发线程，并用 setPreferredDevice 强制绑定到用户选择的
 *    输入 / 输出设备，避免被系统默认路由策略（比如“有线优先于蓝牙”）带偏。
 * 4. 当输入选择的是“蓝牙麦克风”时，走 SCO(HFP) 通话声道（8k/16k 单声道，音质有限，这是
 *    蓝牙协议本身的限制），输出可以是另一个走 A2DP 的高音质蓝牙设备。
 */
class AudioBridgeService : Service() {

    companion object {
        private const val CHANNEL_ID = "audio_loop_channel"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_SAMPLE_RATE = 44100
        private const val SCO_SAMPLE_RATE = 16000
        private const val SCO_CONNECT_TIMEOUT_MS = 4000L
        const val AUTO_ID = "auto"
    }

    data class DeviceOption(
        val id: String,
        val label: String,
        val type: Int,
        val address: String? = null,
        val audioDeviceInfo: AudioDeviceInfo? = null,
        val bluetoothDevice: BluetoothDevice? = null
    )

    data class Status(
        val inputs: List<DeviceOption>,
        val outputs: List<DeviceOption>,
        val manualInputId: String?,
        val effectiveInputId: String?,
        val manualOutputId: String?,
        val effectiveOutputId: String?,
        val running: Boolean
    )

    interface StatusListener {
        fun onStatusChanged(status: Status)
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioBridgeService = this@AudioBridgeService
    }

    private val binder = LocalBinder()
    var statusListener: StatusListener? = null

    private lateinit var audioManager: AudioManager
    private var bluetoothHeadsetProxy: BluetoothHeadset? = null

    private var loopbackThread: Thread? = null
    @Volatile private var running = false

    @Volatile private var manualInputId: String? = null
    @Volatile private var manualOutputId: String? = null

    @Volatile private var currentInputs: List<DeviceOption> = emptyList()
    @Volatile private var currentOutputs: List<DeviceOption> = emptyList()

    private var scoReceiverRegistered = false
    @Volatile private var scoConnected = false
    private val scoLock = Object()

    private val relevantInputTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_BUILTIN_MIC
    )

    private val relevantOutputTypes = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_LINE_ANALOG
    )

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        registerScoReceiver()
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        btManager?.adapter?.getProfileProxy(this, headsetProfileListener, BluetoothProfile.HEADSET)
        refreshOptions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        refreshOptions()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        unregisterScoReceiver()
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothHeadsetProxy?.let { btManager?.adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        stopLoopback()
    }

    // ---------- 对外接口 ----------

    fun isRunning() = running

    fun selectInput(id: String?) {
        manualInputId = if (id == AUTO_ID) null else id
        notifyStatus()
        if (running) restartLoopback()
    }

    fun selectOutput(id: String?) {
        manualOutputId = if (id == AUTO_ID) null else id
        notifyStatus()
        if (running) restartLoopback()
    }

    fun forceRefresh() = refreshOptions()

    fun start() {
        if (running) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        running = true
        loopbackThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            runLoopback()
        }, "audio-loopback").also { it.start() }
        notifyStatus()
        updateNotification()
    }

    fun stop() {
        stopLoopback()
        notifyStatus()
        updateNotification()
    }

    fun dumpAudioDevices(): String {
        val sb = StringBuilder()
        sb.append("== 输入设备 (INPUTS) ==\n")
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (inputs.isEmpty()) sb.append("(无)\n") else inputs.forEach { sb.append(describeDevice(it)).append('\n') }

        sb.append("\n蓝牙通话(HFP)已连接设备：\n")
        val btDevices = bluetoothHeadsetProxy?.connectedDevices.orEmpty()
        if (btDevices.isEmpty()) sb.append("(无)\n") else btDevices.forEach {
            sb.append("· ${safeDeviceName(it)} (${it.address})\n")
        }

        sb.append("\n== 输出设备 (OUTPUTS) ==\n")
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (outputs.isEmpty()) sb.append("(无)\n") else outputs.forEach { sb.append(describeDevice(it)).append('\n') }
        return sb.toString()
    }

    // ---------- 设备枚举与选择 ----------

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = refreshOptions()
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = refreshOptions()
    }

    private val headsetProfileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            bluetoothHeadsetProxy = proxy as? BluetoothHeadset
            refreshOptions()
        }

        override fun onServiceDisconnected(profile: Int) {
            bluetoothHeadsetProxy = null
            refreshOptions()
        }
    }

    @Synchronized
    private fun refreshOptions() {
        val inputs = mutableListOf<DeviceOption>()
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type in relevantInputTypes }
            .forEach { info ->
                inputs += DeviceOption(
                    id = "dev:${info.type}:${info.id}",
                    label = labelFor(info),
                    type = info.type,
                    address = info.address,
                    audioDeviceInfo = info
                )
            }

        // 蓝牙 HFP（通话）设备在没有真正建立 SCO 连接前，不会出现在 GET_DEVICES_INPUTS 里，
        // 所以单独通过 BluetoothHeadset Profile 查询“已连接、支持通话音频”的设备列表。
        bluetoothHeadsetProxy?.connectedDevices?.forEach { btDevice ->
            inputs += DeviceOption(
                id = "bt-sco:${btDevice.address}",
                label = "蓝牙麦克风 (${safeDeviceName(btDevice)})",
                type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                address = btDevice.address,
                bluetoothDevice = btDevice
            )
        }

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in relevantOutputTypes }
            .map { info ->
                DeviceOption(
                    id = "dev:${info.type}:${info.id}",
                    label = labelFor(info),
                    type = info.type,
                    address = info.address,
                    audioDeviceInfo = info
                )
            }

        currentInputs = inputs
        currentOutputs = outputs

        notifyStatus()
        updateNotification()
    }

    private fun resolveInput(): DeviceOption? {
        manualInputId?.let { id -> currentInputs.find { it.id == id } }?.let { return it }
        return currentInputs.firstOrNull {
            it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC && it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } ?: currentInputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: currentInputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    private fun resolveOutput(input: DeviceOption?): DeviceOption? {
        manualOutputId?.let { id -> currentOutputs.find { it.id == id } }?.let { return it }
        val excludeAddress = input?.address
        val a2dp = currentOutputs.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && (excludeAddress == null || it.address != excludeAddress)
        }
        if (a2dp.isNotEmpty()) return a2dp.first()
        currentOutputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }?.let { return it }
        return currentOutputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: currentOutputs.firstOrNull()
    }

    private fun notifyStatus() {
        val input = resolveInput()
        val output = resolveOutput(input)
        statusListener?.onStatusChanged(
            Status(
                inputs = currentInputs,
                outputs = currentOutputs,
                manualInputId = manualInputId,
                effectiveInputId = input?.id,
                manualOutputId = manualOutputId,
                effectiveOutputId = output?.id,
                running = running
            )
        )
    }

    private fun safeDeviceName(device: BluetoothDevice): String =
        try { device.name ?: device.address } catch (e: SecurityException) { device.address }

    private fun describeDevice(info: AudioDeviceInfo): String {
        val name = runCatching { info.productName?.toString() }.getOrNull() ?: "未知"
        return "· ${typeName(info.type)} (type=${info.type})  名称: $name"
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机(带麦克风)"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机(无麦克风)"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙(A2DP 音乐)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙(SCO 通话)"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 设备"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "模拟 Line-in"
        AudioDeviceInfo.TYPE_TELEPHONY -> "通话设备"
        else -> "未知类型"
    }

    private fun labelFor(info: AudioDeviceInfo): String {
        val typeName = typeName(info.type)
        if (info.type == AudioDeviceInfo.TYPE_BUILTIN_MIC || info.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            return typeName
        }
        val product = runCatching { info.productName?.toString() }.getOrNull()
        return if (!product.isNullOrBlank()) "$typeName ($product)" else typeName
    }

    // ---------- 音频采集/播放线程 ----------

    private fun restartLoopback() {
        stopLoopback()
        start()
    }

    private fun stopLoopback() {
        running = false
        loopbackThread?.interrupt()
        loopbackThread = null
    }

    private fun runLoopback() {
        val input = resolveInput()
        val output = resolveOutput(input)

        if (input == null || output == null) {
            running = false
            notifyStatus()
            return
        }

        val useSco = input.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        var scoStarted = false

        if (useSco) {
            scoConnected = false
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            scoStarted = true
            synchronized(scoLock) {
                if (!scoConnected) {
                    runCatching { scoLock.wait(SCO_CONNECT_TIMEOUT_MS) }
                }
            }
            if (!scoConnected) {
                cleanupSco(true)
                running = false
                notifyStatus()
                return
            }
        }

        val sampleRate = if (useSco) SCO_SAMPLE_RATE else DEFAULT_SAMPLE_RATE
        val channelIn = if (useSco) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val channelOut = if (useSco) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val source = if (useSco) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC

        var minBufIn = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
        if (minBufIn <= 0) minBufIn = 4096
        val bufferSize = minBufIn.coerceAtLeast(4096) * 2

        val audioRecord = try {
            @Suppress("MissingPermission")
            AudioRecord(source, sampleRate, channelIn, encoding, bufferSize)
        } catch (e: Exception) {
            null
        }

        if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            cleanupSco(scoStarted)
            running = false
            notifyStatus()
            return
        }

        input.audioDeviceInfo?.let { runCatching { audioRecord.preferredDevice = it } }

        val minBufOut = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelOut)
                .build(),
            minBufOut.coerceAtLeast(bufferSize),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        // 关键修复：显式绑定输出设备，避免系统“有线优先于蓝牙”的默认路由策略
        // 把声音送回了错误的设备（比如又送回 3.5mm 耳机本身的喇叭）。
        output.audioDeviceInfo?.let { runCatching { audioTrack.preferredDevice = it } }

        val buffer = ByteArray(bufferSize)
        try {
            audioRecord.startRecording()
            audioTrack.play()
            while (running && !Thread.currentThread().isInterrupted) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    audioTrack.write(buffer, 0, read)
                }
            }
        } catch (e: Exception) {
            // 采集/播放过程中出现异常（比如设备中途断开）时结束循环
        } finally {
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
            runCatching { audioTrack.stop() }
            runCatching { audioTrack.release() }
            cleanupSco(scoStarted)
            running = false
            notifyStatus()
            updateNotification()
        }
    }

    private fun cleanupSco(started: Boolean) {
        if (!started) return
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
            scoConnected = state == AudioManager.SCO_AUDIO_STATE_CONNECTED
            synchronized(scoLock) { scoLock.notifyAll() }
        }
    }

    private fun registerScoReceiver() {
        if (!scoReceiverRegistered) {
            registerReceiver(scoStateReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            scoReceiverRegistered = true
        }
    }

    private fun unregisterScoReceiver() {
        if (scoReceiverRegistered) {
            runCatching { unregisterReceiver(scoStateReceiver) }
            scoReceiverRegistered = false
        }
    }

    // ---------- 通知 ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_title),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val input = resolveInput()
        val output = resolveOutput(input)
        val text = if (running) {
            "正在转发：${input?.label ?: "?"} → ${output?.label ?: "?"}"
        } else {
            "已就绪，未开始转发"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }
}
