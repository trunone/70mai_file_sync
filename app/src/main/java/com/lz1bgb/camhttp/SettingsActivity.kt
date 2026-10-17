package com.lz1bgb.camhttp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.lz1bgb.camhttp.databinding.ActivitySettingsBinding
import com.lz1bgb.camhttp.databinding.DialogSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @brief Main UI activity managing transfer controls and remote file viewer.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var httpClient: HttpClient
    private lateinit var remoteFilesAdapter: RemoteFilesAdapter
    
    private var fileTransferService: FileTransferService? = null
    private var isBound = false

    private var manualServiceState: Boolean? = null
    private var activeDialogBinding: DialogSettingsBinding? = null

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
            activeDialogBinding?.dialogBackupPathTxt?.setText(uri.toString())
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

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            R.id.action_refresh -> {
                fetchRemoteFiles()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        activeDialogBinding = dialogBinding

        val prefs = getSharedPreferences("FtpSettings", MODE_PRIVATE)
        dialogBinding.dialogCamIPTxt.setText(prefs.getString("IP_CAM", "192.168.0.1"))

        val savedLocalPath = prefs.getString("PATH_LOCAL", null) ?: prefs.getString("PATH_SERVER", "")
        dialogBinding.dialogBackupPathTxt.setText(savedLocalPath)

        val autoDeleteEnabled = prefs.getBoolean("AUTO_DELETE_REMOTE", true)
        dialogBinding.dialogAutoDeleteCheckBox.isChecked = autoDeleteEnabled

        val cycleCheckEnabled = prefs.getBoolean("CYCLE_CHECK_ENABLED", true)
        dialogBinding.dialogCycleCheckCheckBox.isChecked = cycleCheckEnabled

        dialogBinding.dialogBrowseFolderButton.setOnClickListener {
            openDocumentTreeLauncher.launch(null)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_settings)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                prefs.edit {
                    putString("IP_CAM", dialogBinding.dialogCamIPTxt.text.toString())
                    putString("PATH_LOCAL", dialogBinding.dialogBackupPathTxt.text.toString())
                    putBoolean("AUTO_DELETE_REMOTE", dialogBinding.dialogAutoDeleteCheckBox.isChecked)
                    putBoolean("CYCLE_CHECK_ENABLED", dialogBinding.dialogCycleCheckCheckBox.isChecked)
                }
                Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show()
                activeDialogBinding = null
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                activeDialogBinding = null
            }
            .setOnDismissListener {
                activeDialogBinding = null
            }
            .show()
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
        setupTabs()
        setupViewerRecyclerView()

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

    private fun setupViewerRecyclerView() {
        remoteFilesAdapter = RemoteFilesAdapter { selectedCount ->
            binding.btnDeleteSelected.text = getString(R.string.btn_delete_selected, selectedCount)
            binding.btnSelectAll.text = if (remoteFilesAdapter.isAllSelected()) {
                getString(R.string.btn_deselect_all)
            } else {
                getString(R.string.btn_select_all)
            }
        }
        binding.remoteFilesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.remoteFilesRecyclerView.adapter = remoteFilesAdapter

        binding.btnSelectAll.setOnClickListener {
            if (remoteFilesAdapter.isAllSelected()) {
                remoteFilesAdapter.deselectAll()
            } else {
                remoteFilesAdapter.selectAll()
            }
        }

        binding.btnDeleteSelected.setOnClickListener {
            val selectedFiles = remoteFilesAdapter.getSelectedFiles()
            if (selectedFiles.isEmpty()) {
                Toast.makeText(this, "No files selected.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.btn_delete)
                .setMessage(getString(R.string.msg_confirm_delete, selectedFiles.size))
                .setPositiveButton(R.string.btn_delete) { _, _ ->
                    performBatchDelete(selectedFiles)
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    private fun fetchRemoteFiles() {
        binding.viewerProgressBar.visibility = View.VISIBLE
        binding.emptyViewerTextView.visibility = View.GONE
        binding.remoteFilesRecyclerView.visibility = View.GONE

        lifecycleScope.launch {
            httpClient.updateNetworkInfo()
            val token = httpClient.sessionToken

            if (token == null) {
                binding.viewerProgressBar.visibility = View.GONE
                binding.emptyViewerTextView.text = getString(R.string.msg_no_remote_files)
                binding.emptyViewerTextView.visibility = View.VISIBLE
                return@launch
            }

            val fileTypes = (0..15).toList()
            val allFiles = mutableListOf<FileInfo>()

            withContext(Dispatchers.IO) {
                httpClient.registerClient()
                var albumActive = false
                try {
                    albumActive = httpClient.setAlbumMode(true)
                    kotlinx.coroutines.delay(2000)
                    for (type in fileTypes) {
                        val files = httpClient.getFileList(type)
                        if (files != null) {
                            allFiles.addAll(files)
                        }
                    }
                } finally {
                    if (albumActive) {
                        httpClient.setAlbumMode(false)
                    }
                }
            }

            binding.viewerProgressBar.visibility = View.GONE

            if (allFiles.isEmpty()) {
                binding.emptyViewerTextView.text = getString(R.string.msg_no_remote_files)
                binding.emptyViewerTextView.visibility = View.VISIBLE
                binding.remoteFilesRecyclerView.visibility = View.GONE
            } else {
                val uniqueFiles = allFiles.distinctBy { "${it.path}/${it.name}" }.sortedByDescending { it.name }
                remoteFilesAdapter.submitList(uniqueFiles)
                binding.emptyViewerTextView.visibility = View.GONE
                binding.remoteFilesRecyclerView.visibility = View.VISIBLE
            }
        }
    }

    private fun performBatchDelete(filesToDelete: List<FileInfo>) {
        binding.viewerProgressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val token = httpClient.sessionToken
            if (token == null) {
                binding.viewerProgressBar.visibility = View.GONE
                Toast.makeText(this@SettingsActivity, "Authentication required.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            var successCount = 0
            withContext(Dispatchers.IO) {
                httpClient.registerClient()
                for (file in filesToDelete) {
                    val deleted = httpClient.deleteRemoteFile(file.path, file.name, token)
                    if (deleted) {
                        successCount++
                    }
                }
            }

            binding.viewerProgressBar.visibility = View.GONE
            Toast.makeText(
                this@SettingsActivity,
                "Deleted $successCount / ${filesToDelete.size} file(s).",
                Toast.LENGTH_LONG
            ).show()

            fetchRemoteFiles()
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.controlsLayout.visibility = View.VISIBLE
                        binding.viewerLayout.visibility = View.GONE
                    }
                    1 -> {
                        binding.controlsLayout.visibility = View.GONE
                        binding.viewerLayout.visibility = View.VISIBLE
                        fetchRemoteFiles()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

}
