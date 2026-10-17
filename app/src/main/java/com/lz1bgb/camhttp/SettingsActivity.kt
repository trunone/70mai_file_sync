package com.lz1bgb.camhttp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.lz1bgb.camhttp.databinding.ActivitySettingsBinding

/**
 * @brief Main UI activity managing transfer controls and application settings.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var httpClient: HttpClient

    private var fileTransferService: FileTransferService? = null
    private var isBound = false

    private var manualServiceState: Boolean? = null

    private val isServiceRunning: Boolean
        get() {
            manualServiceState?.let { return it }
            return FileTransferService.isServiceRunningInForeground
        }

    private val handler = Handler(Looper.getMainLooper())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FileTransferService.LocalBinder
            fileTransferService = binder.getService()
            isBound = true
            updateStartStopButtonUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fileTransferService = null
            isBound = false
            updateStartStopButtonUI()
        }
    }

    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Failed to take persistable Uri permission: ${e.message}")
            }
            binding.backupPathTxt.setText(uri.toString())
            getSharedPreferences("FtpSettings", MODE_PRIVATE).edit {
                putString("PATH_LOCAL", uri.toString())
            }
            Toast.makeText(this, "Folder selected", Toast.LENGTH_SHORT).show()
        }
    }

    private val updateTask = object : Runnable {
        override fun run() {
            refreshServiceStatus()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(updateTask)
    }

    override fun onStart() {
        super.onStart()
        handler.post(updateTask)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun refreshServiceStatus() {
        if (fileTransferService == null || !isServiceRunning) {
            binding.statusTextView.text = getString(R.string.status_waiting_start)
        }
        if (manualServiceState == null) {
            updateStartStopButtonUI()
        }
        if (isBound && fileTransferService != null) {
            val status = fileTransferService!!.getServiceStatus()

            binding.statusTextView.text = status.generalStatus
            binding.lastDownloadResultTextView.text = getString(R.string.label_last_download, status.lastDownloadResult)
            binding.totalFilesDownloadedTextView.text = getString(R.string.label_total_downloaded, status.totalFilesDownloaded)
            binding.lastDownloadDurationTextView.text = getString(R.string.label_last_duration, status.lastDownloadDuration)
            binding.pendingCameraFilesTextView.text = getString(R.string.label_pending_camera, status.pendingCameraFiles)

            if (status.currentFileName.isNotEmpty()) {
                binding.fileNameInProcess.text = getString(R.string.label_current_file, status.currentFileName)
                binding.fileTransferSpeed.text = getString(R.string.label_current_speed, status.currentSpeed)
                binding.fileProgressPercentage.text = getString(R.string.label_current_progress, status.currentProgress)
                binding.fileProgressPercentage.visibility = View.VISIBLE
            } else {
                binding.fileNameInProcess.text = getString(R.string.status_idle)
                binding.fileTransferSpeed.text = getString(R.string.speed_idle)
                binding.fileProgressPercentage.visibility = View.GONE
            }

            binding.statusTextView.visibility = View.VISIBLE
            binding.lastDownloadResultTextView.visibility = View.VISIBLE
            binding.fileNameInProcess.visibility = View.VISIBLE
            binding.fileTransferSpeed.visibility = View.VISIBLE
            binding.totalFilesDownloadedTextView.visibility = View.VISIBLE
            binding.lastDownloadDurationTextView.visibility = View.VISIBLE
            binding.pendingCameraFilesTextView.visibility = View.VISIBLE
        } else {
            updateStartStopButtonUI()
        }
    }

    private fun updateStartStopButtonUI() {
        if (isServiceRunning) {
            binding.startStopButton.text = getString(R.string.btn_stop)
            binding.startStopButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.toggle_stop))
            binding.startStopButton.setIconResource(R.drawable.ic_stop)
        } else {
            binding.startStopButton.text = getString(R.string.btn_start)
            binding.startStopButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.toggle_start))
            binding.startStopButton.setIconResource(R.drawable.ic_play)
            binding.statusTextView.text = getString(R.string.status_waiting_start)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        httpClient = HttpClient(this)
        setupSettingsControls()

        if (isServiceRunning) {
            Intent(this, FileTransferService::class.java).also { intent ->
                bindService(intent, serviceConnection, BIND_AUTO_CREATE)
            }
        }

        val statsPrefs = getSharedPreferences("FtpStats", MODE_PRIVATE)

        // Display initial counters
        val initialTotalDownloads = statsPrefs.getInt("TOTAL_DOWNLOADS", 0)
        binding.totalFilesDownloadedTextView.text = getString(R.string.label_total_downloaded, initialTotalDownloads)
        binding.totalFilesDownloadedTextView.visibility = View.VISIBLE

        binding.startStopButton.setOnClickListener {
            val serviceIntent = Intent(this, FileTransferService::class.java)
            if (!isServiceRunning) {
                manualServiceState = true
                startForegroundService(serviceIntent)
                bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                Toast.makeText(this, "Service Started.", Toast.LENGTH_SHORT).show()
            } else {
                manualServiceState = false
                if (isBound && fileTransferService != null) {
                    fileTransferService!!.stopTransfer()
                    try {
                        unbindService(serviceConnection)
                    } catch (e: Exception) {
                        android.util.Log.e("Activity", "Unbind error: ${e.message}")
                    }
                    isBound = false
                    fileTransferService = null
                }
                stopService(serviceIntent)
                updateStartStopButtonUI()
                Toast.makeText(this, "Service Stopped.", Toast.LENGTH_SHORT).show()
            }
            updateStartStopButtonUI()
        }

        binding.clrCount.setOnClickListener {
            val sp = getSharedPreferences("FtpStats", MODE_PRIVATE)
            sp.edit {
                putInt("TOTAL_DOWNLOADS", 0)
                putString("FTP_STATUS", getString(R.string.status_idle))
                putString("LAST_DOWNLOAD_DURATION", "0 sec.")
            }
            binding.totalFilesDownloadedTextView.text = getString(R.string.label_total_downloaded, 0)
            binding.lastDownloadDurationTextView.text = getString(R.string.label_last_duration, "0 sec.")
            Toast.makeText(this, "Stats Reset.", Toast.LENGTH_SHORT).show()
        }

        binding.clrToken.setOnClickListener {
            val cp = getSharedPreferences("CamSettings", MODE_PRIVATE)
            cp.edit { remove("SESSION_TOKEN") }
            Toast.makeText(this, "Token Reset. New pairing required.", Toast.LENGTH_SHORT).show()
        }

        binding.connectWifiButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    private fun setupSettingsControls() {
        val prefs = getSharedPreferences("FtpSettings", MODE_PRIVATE)

        binding.camIPTxt.setText(prefs.getString("IP_CAM", "192.168.0.1"))
        val savedLocalPath = prefs.getString("PATH_LOCAL", null) ?: prefs.getString("PATH_SERVER", "")
        binding.backupPathTxt.setText(savedLocalPath)
        binding.autoDeleteCheckBox.isChecked = prefs.getBoolean("AUTO_DELETE_REMOTE", true)
        binding.cycleCheckCheckBox.isChecked = prefs.getBoolean("CYCLE_CHECK_ENABLED", true)

        binding.camIPTxt.doAfterTextChanged { editable ->
            prefs.edit { putString("IP_CAM", editable?.toString() ?: "") }
        }

        binding.backupPathTxt.doAfterTextChanged { editable ->
            prefs.edit { putString("PATH_LOCAL", editable?.toString() ?: "") }
        }

        binding.autoDeleteCheckBox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("AUTO_DELETE_REMOTE", isChecked) }
        }

        binding.cycleCheckCheckBox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("CYCLE_CHECK_ENABLED", isChecked) }
        }

        binding.browseFolderButton.setOnClickListener {
            openDocumentTreeLauncher.launch(null)
        }
    }
}
