package com.example.audioloop

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.audioloop.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), AudioBridgeService.StatusListener {

    private lateinit var binding: ActivityMainBinding

    private var service: AudioBridgeService? = null
    private var bound = false

    private val cornerRadiusPx by lazy { 28f * resources.displayMetrics.density }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startAndBindService()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binderObj: IBinder?) {
            val localBinder = binderObj as AudioBridgeService.LocalBinder
            service = localBinder.getService()
            service?.statusListener = this@MainActivity
            bound = true
            service?.getStatus()?.let { (wired, bt, running) ->
                onStatusChanged(wired, bt, running)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.boxWired.background = buildRoundedBackground(R.color.status_gray)
        binding.boxBluetooth.background = buildRoundedBackground(R.color.status_gray)

        binding.btnRefresh.setOnClickListener {
            if (bound) service?.forceRefresh() else ensurePermissionAndStart()
        }

        binding.btnDebug.setOnClickListener {
            showDeviceDebugDialog()
        }

        ensurePermissionAndStart()
    }

    override fun onStart() {
        super.onStart()
        if (!bound && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            bindService(Intent(this, AudioBridgeService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            service?.statusListener = null
            unbindService(connection)
            bound = false
        }
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startAndBindService()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, AudioBridgeService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun buildRoundedBackground(colorRes: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(ContextCompat.getColor(this@MainActivity, colorRes))
        }
    }

    private fun setBoxState(box: View, stateText: android.widget.TextView, connected: Boolean) {
        val bg = box.background as? GradientDrawable ?: buildRoundedBackground(R.color.status_gray).also {
            box.background = it
        }
        bg.setColor(
            ContextCompat.getColor(
                this,
                if (connected) R.color.status_green else R.color.status_gray
            )
        )
        stateText.setText(if (connected) R.string.state_connected else R.string.state_disconnected)
    }

    override fun onStatusChanged(wired: Boolean, bluetooth: Boolean, running: Boolean) {
        runOnUiThread {
            setBoxState(binding.boxWired, binding.tvWiredState, wired)
            setBoxState(binding.boxBluetooth, binding.tvBtState, bluetooth)

            val noMic = service?.isWiredNoMic() == true
            binding.tvStatus.text = when {
                running -> getString(R.string.status_running)
                noMic -> getString(R.string.status_no_mic)
                wired && !bluetooth -> getString(R.string.status_wired_only)
                else -> getString(R.string.status_idle)
            }
        }
    }

    private fun showDeviceDebugDialog() {
        val content = service?.dumpAudioDevices() ?: "服务未连接"
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_devices)
            .setMessage(content)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }
}
