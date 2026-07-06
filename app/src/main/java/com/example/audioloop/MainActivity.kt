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
import android.widget.AdapterView
import android.widget.ArrayAdapter
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

    // 程序用 setSelection 刷新下拉框时，临时挂起“用户选择”回调，避免自己触发自己
    private var suppressInputCallback = false
    private var suppressOutputCallback = false

    private var inputIds: List<String> = emptyList()
    private var outputIds: List<String> = emptyList()

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
            service?.forceRefresh()
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

        binding.boxInput.background = buildRoundedBackground(R.color.status_gray)
        binding.boxOutput.background = buildRoundedBackground(R.color.status_gray)

        binding.spinnerInput.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressInputCallback) return
                val selectedId = inputIds.getOrNull(position) ?: return
                service?.selectInput(if (selectedId == AudioBridgeService.AUTO_ID) null else selectedId)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerOutput.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressOutputCallback) return
                val selectedId = outputIds.getOrNull(position) ?: return
                service?.selectOutput(if (selectedId == AudioBridgeService.AUTO_ID) null else selectedId)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnToggle.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (svc.isRunning()) svc.stop() else svc.start()
        }

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

    private fun buildRoundedBackground(colorRes: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = cornerRadiusPx
        setColor(ContextCompat.getColor(this@MainActivity, colorRes))
    }

    private fun setBoxColor(box: View, active: Boolean) {
        val bg = box.background as? GradientDrawable ?: buildRoundedBackground(R.color.status_gray).also {
            box.background = it
        }
        bg.setColor(ContextCompat.getColor(this, if (active) R.color.status_green else R.color.status_gray))
    }

    override fun onStatusChanged(status: AudioBridgeService.Status) {
        runOnUiThread {
            populateSpinner(
                spinner = binding.spinnerInput,
                options = status.inputs,
                manualId = status.manualInputId,
                effectiveId = status.effectiveInputId,
                isInput = true
            )
            populateSpinner(
                spinner = binding.spinnerOutput,
                options = status.outputs,
                manualId = status.manualOutputId,
                effectiveId = status.effectiveOutputId,
                isInput = false
            )

            setBoxColor(binding.boxInput, status.running)
            setBoxColor(binding.boxOutput, status.running)

            binding.btnToggle.setText(if (status.running) R.string.btn_stop else R.string.btn_start)

            val inputLabel = status.inputs.find { it.id == status.effectiveInputId }?.label
            val outputLabel = status.outputs.find { it.id == status.effectiveOutputId }?.label
            binding.tvStatus.text = if (status.running && inputLabel != null && outputLabel != null) {
                getString(R.string.status_running_fmt, inputLabel, outputLabel)
            } else {
                getString(R.string.status_idle)
            }
        }
    }

    private fun populateSpinner(
        spinner: android.widget.Spinner,
        options: List<AudioBridgeService.DeviceOption>,
        manualId: String?,
        effectiveId: String?,
        isInput: Boolean
    ) {
        val effectiveLabel = options.find { it.id == effectiveId }?.label
        val autoLabel = getString(R.string.auto_label) + if (effectiveLabel != null) "（当前：$effectiveLabel）" else ""

        val ids = mutableListOf(AudioBridgeService.AUTO_ID)
        val labels = mutableListOf(autoLabel)
        options.forEach { opt ->
            ids += opt.id
            labels += opt.label
        }

        if (isInput) {
            suppressInputCallback = true
            inputIds = ids
        } else {
            suppressOutputCallback = true
            outputIds = ids
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val selectedIndex = if (manualId == null) 0 else ids.indexOf(manualId).let { if (it < 0) 0 else it }
        spinner.setSelection(selectedIndex, false)

        spinner.post {
            if (isInput) suppressInputCallback = false else suppressOutputCallback = false
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
