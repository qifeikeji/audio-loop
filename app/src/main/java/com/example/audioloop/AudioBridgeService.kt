package com.example.audioloop

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
 * 前台服务：
 * 1. 通过 AudioManager 监听音频设备的增删，判断“3.5mm 耳机口输入”和“蓝牙耳机输出”是否就绪。
 * 2. 当检测到 3.5mm 输入时，启动 AudioRecord -> AudioTrack 的采集/播放线程，
 *    将采集到的音频写给 AudioTrack（USAGE_MEDIA），系统会自动把 STREAM_MUSIC 路由到
 *    已连接的蓝牙耳机（A2DP），从而实现“有线输入 -> 蓝牙输出”。
 */
class AudioBridgeService : Service() {

    companion object {
        private const val CHANNEL_ID = "audio_loop_channel"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 44100
    }

    interface StatusListener {
        fun onStatusChanged(wired: Boolean, bluetooth: Boolean, running: Boolean)
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioBridgeService = this@AudioBridgeService
    }

    private val binder = LocalBinder()
    var statusListener: StatusListener? = null

    private lateinit var audioManager: AudioManager
    private var loopbackThread: Thread? = null
    @Volatile private var running = false

    @Volatile private var wiredConnected = false
    @Volatile private var btConnected = false
    @Volatile private var wiredNoMicDetected = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        refreshDeviceStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        refreshDeviceStatus()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        stopLoopback()
    }

    fun getStatus(): Triple<Boolean, Boolean, Boolean> = Triple(wiredConnected, btConnected, running)

    fun isWiredNoMic(): Boolean = wiredNoMicDetected

    fun forceRefresh() = refreshDeviceStatus()

    /**
     * 返回当前系统识别到的所有输入/输出音频设备的原始信息，用于排查“插入 AUX 线没反应”这类问题。
     */
    fun dumpAudioDevices(): String {
        val sb = StringBuilder()
        sb.append("== 输入设备 (INPUTS) ==\n")
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (inputs.isEmpty()) {
            sb.append("(无)\n")
        } else {
            inputs.forEach { sb.append(describeDevice(it)).append('\n') }
        }
        sb.append("\n== 输出设备 (OUTPUTS) ==\n")
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (outputs.isEmpty()) {
            sb.append("(无)\n")
        } else {
            outputs.forEach { sb.append(describeDevice(it)).append('\n') }
        }
        return sb.toString()
    }

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

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = refreshDeviceStatus()
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = refreshDeviceStatus()
    }

    private fun refreshDeviceStatus() {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val newWired = inputs.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_LINE_ANALOG
        }

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val newBt = outputs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        // 插了线但系统没有识别出麦克风电路（常见于普通三段 AUX 线）：耳机孔有输出设备，但输入列表里没有对应设备
        val newWiredNoMic = !newWired && outputs.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }

        wiredConnected = newWired
        btConnected = newBt
        wiredNoMicDetected = newWiredNoMic

        if (wiredConnected && !running) {
            startLoopback()
        } else if (!wiredConnected && running) {
            stopLoopback()
        }

        notifyStatus()
        updateNotification()
    }

    private fun notifyStatus() {
        statusListener?.onStatusChanged(wiredConnected, btConnected, running)
    }

    private fun startLoopback() {
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
    }

    private fun stopLoopback() {
        running = false
        loopbackThread?.interrupt()
        loopbackThread = null
    }

    private fun runLoopback() {
        val channelIn = AudioFormat.CHANNEL_IN_STEREO
        val channelOut = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        var minBufIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelIn, encoding)
        if (minBufIn <= 0) {
            minBufIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding)
        }
        if (minBufIn <= 0) {
            running = false
            notifyStatus()
            return
        }
        val bufferSize = minBufIn.coerceAtLeast(4096) * 2

        val audioRecord = try {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelIn,
                encoding,
                bufferSize
            )
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            running = false
            notifyStatus()
            return
        }

        val minBufOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelOut, encoding)
        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(channelOut)
                .build(),
            minBufOut.coerceAtLeast(bufferSize),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

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
            // 采集/播放过程中出现异常时直接结束循环，交由外部状态刷新重新判定
        } finally {
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
            runCatching { audioTrack.stop() }
            runCatching { audioTrack.release() }
        }
    }

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
        val text = when {
            running -> getString(R.string.status_running)
            wiredConnected -> getString(R.string.status_wired_only)
            else -> getString(R.string.status_idle)
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
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }
}
