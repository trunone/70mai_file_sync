package com.lz1bgb.camhttp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.util.Locale
import kotlinx.coroutines.cancel

/**
 * @brief Data class for reporting service status to the UI.
 */
data class ServiceStatus(
    val generalStatus: String,          /**< Main status message (Waiting/Running) */
    val lastDownloadResult: String,     /**< Result of the last dashcam download */
    val totalFilesDownloaded: Int,      /**< Total files saved to phone */
    val pendingCameraFiles: Int,        /**< Number of files remaining on camera */
    val lastDownloadDuration: String,   /**< Time taken for last download */
    val currentFileName: String,        /**< Name of the file currently being transferred */
    val currentSpeed: String,           /**< Current transfer speed (MB/s or KB/s) */
    val currentProgress: Int,           /**< Current file transfer progress percentage (0-100) */
)

/**
 * @brief Foreground service that orchestrates file transfers from Dashcam (HTTP) to Local Storage.
 * 
 * It periodically checks for the camera Wi-Fi. If found, it downloads new files to the local directory or document tree.
 */
class FileTransferService : Service() {

    companion object {
        var isServiceRunningInForeground = false
            private set
    }

    private var job = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var httpClient: HttpClient
    private lateinit var httpAuth: HttpAuthentication

    // Handler and Runnable for periodic execution
    private lateinit var handler: Handler
    private val checkInterval: Long = 30 * 1000 // 30 seconds

    private val isTaskRunning = AtomicBoolean(false)
    private val isScheduled = AtomicBoolean(false)
    private var nextExecutionTime: Long = 0L

    // Binder for communication with Activity
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): FileTransferService = this@FileTransferService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val periodicCheck = Runnable {
        // Start the check and transfer cycle
        if (isTaskRunning.compareAndSet(false, true)) {
            nextExecutionTime = 0L // Reset while running
            scope.launch {
                try {
                    FileLogger.logToFile(this@FileTransferService, "FileService", "Starting periodic check...")
                    performTransferCycle()
                } catch (e: Exception) {
                    FileLogger.logToFile(this@FileTransferService, "FileService", "Error in cycle: ${e.message}")
                    Log.e("FileService", "Error in cycle: ${e.message}")
                } finally {
                    isTaskRunning.set(false)
                    if (job.isActive) {
                        scheduleNextRun()
                    }
                }
            }
        } else {
            FileLogger.logToFile(this, "FileService", "Skip check - task still running.")
        }
    }

    private var pendingCameraFiles = 0
    private var currentFileName = ""
    private var currentSpeed = ""
    private var currentProgress = 0

    private var lastBytes = 0L
    private var lastTime = 0L

    private suspend fun performTransferCycle() {
        httpClient.updateNetworkInfo()
        val dhcp = httpClient.dhcpServerIpAddress
        val camIp = httpClient.cameraIp

        // Try HTTP (Camera) if connected
        if (dhcp == camIp) {
            FileLogger.logToFile(this, "FileService", "Camera detected ($camIp). Starting HTTP cycle.")
            val success = runHttpCycle()
            if (success) {
                updateStatus(getString(R.string.msg_http_success), "SUCCESS")
            } else {
                updateStatus(getString(R.string.msg_http_error), "ERROR")
            }
        } else {
            FileLogger.logToFile(this, "FileService", "Camera network not detected.")
            pendingCameraFiles = 0
            updateStatus(getString(R.string.msg_camera_not_found), "IDLE")
        }
    }

    private fun isLocalFileExisting(
        customLocalPath: String?,
        cleanPath: String,
        fileName: String,
        fileSizeString: String?
    ): Boolean {
        return try {
            val expectedSize = fileSizeString?.toLongOrNull()
            if (!customLocalPath.isNullOrEmpty() && customLocalPath.startsWith("content://")) {
                val treeUri = Uri.parse(customLocalPath)
                val rootDoc = DocumentFile.fromTreeUri(this, treeUri) ?: return false
                var currentDir = rootDoc
                val subFolders = cleanPath.split('/').filter { it.isNotEmpty() }
                for (folderName in subFolders) {
                    val existing = currentDir.findFile(folderName) ?: return false
                    if (!existing.isDirectory) return false
                    currentDir = existing
                }
                val targetFile = currentDir.findFile(fileName)
                targetFile != null && targetFile.exists() && targetFile.length() > 0 &&
                        (expectedSize == null || expectedSize <= 0 || targetFile.length() == expectedSize)
            } else {
                val localDirFile = if (!customLocalPath.isNullOrEmpty()) {
                    File(customLocalPath, cleanPath)
                } else {
                    File(getExternalFilesDir(null), "camera/$cleanPath")
                }
                val localFile = File(localDirFile, fileName)
                localFile.exists() && localFile.length() > 0 &&
                        (expectedSize == null || expectedSize <= 0 || localFile.length() == expectedSize)
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun runHttpCycle(): Boolean {
        var token = httpClient.sessionToken
        
        // If no token, attempt pairing
        if (token == null) {
            FileLogger.logToFile(this, "FileService", "No token. Starting pairing...")
            val authSuccess = httpAuth.performAuthenticationLogic()
            if (!authSuccess) return false
            token = httpClient.sessionToken ?: return false
        } else {
            // Register periodically for keep-alive
            httpClient.registerClient()
        }

        // 0: Front Normal, 8: Back Normal, 1: Front Emergency, 6: Back Emergency, 2: Parking, 3: Photo
        val fileTypes = listOf(0, 8, 1, 6, 2, 3)
        val allFiles = mutableListOf<FileInfo>()

        for (type in fileTypes) {
            // Register before each list request
            httpClient.registerClient()
            val files = httpClient.getFileList(type)
            if (files != null) {
                FileLogger.logToFile(this, "FileService", "Type $type: Found ${files.size} files.")
                
                // Remove last 2 files from video categories to ensure recording is finished
                val typeFiles = if (type in listOf(0, 8, 1, 6, 2)) {
                    if (files.size > 2) {
                        files.sortedBy { it.name }.dropLast(2)
                    } else {
                        FileLogger.logToFile(this, "FileService", "Type $type has only ${files.size} files. Waiting for finalization.")
                        emptyList()
                    }
                } else {
                    files // Photos etc.
                }
                
                allFiles.addAll(typeFiles)
            }
        }

        if (allFiles.isEmpty()) {
            FileLogger.logToFile(this, "FileService", "No new ready files for download.")
            return true
        }

        // Deduplicate files and sort by name
        val uniqueFiles = allFiles.distinctBy { "${it.path}/${it.name}" }
        val sortedFiles = uniqueFiles.sortedBy { it.name }
        pendingCameraFiles = sortedFiles.size

        // Group files by stripping the 70mai suffixes (F/B + extension)
        // Example: NO20260513-055652-000046B.MP4 -> NO20260513-055652-000046
        val groupedFiles = sortedFiles.groupBy { 
            it.name.replace(Regex("[FB]\\.(MP4|thm|jpg|JPG)$", RegexOption.IGNORE_CASE), "")
        }

        val prefs = getSharedPreferences("FtpSettings", MODE_PRIVATE)
        val customLocalPath = prefs.getString("PATH_LOCAL", null)?.ifBlank {
            prefs.getString("PATH_SERVER", null)?.ifBlank { null }
        }

        for ((groupKey, filesInGroup) in groupedFiles) {
            FileLogger.logToFile(this, "FileService", "Processing group $groupKey with files: ${filesInGroup.joinToString { it.name }}")
            
            val successfullyDownloadedFiles = mutableListOf<FileInfo>()
            var networkErrorOccurred = false

            for (file in filesInGroup) {
                FileLogger.logToFile(this, "FileService", "Checking ${file.name} (Path: ${file.path})")
                val parentFolder = file.path.substringAfterLast('/').ifEmpty { file.path }
                currentFileName = "$parentFolder/${file.name}"

                // Remove /mnt/sd for local path
                val cleanPath = file.path.removePrefix("/mnt/sd").trimStart('/')

                // Check if file already exists locally
                if (isLocalFileExisting(customLocalPath, cleanPath, file.name, file.size)) {
                    FileLogger.logToFile(this, "FileService", "File ${file.name} already exists locally. Skipping download.")
                    Log.i("FileService", "File ${file.name} already exists locally. Skipping download.")
                    successfullyDownloadedFiles.add(file)
                    continue
                }

                resetSpeed()
                
                // Construct URL
                val downloadUrl = "http://${httpClient.cameraIp}${file.path}/${file.name}"
                
                httpClient.registerClient()
                val startTime = System.currentTimeMillis()
                
                try {
                    val isDownloaded = if (!customLocalPath.isNullOrEmpty() && customLocalPath.startsWith("content://")) {
                        downloadFileToDocumentTree(downloadUrl, customLocalPath, cleanPath, file.name) { bytes, total ->
                            updateSpeed(bytes)
                            if (total > 0) {
                                currentProgress = ((bytes * 100) / total).toInt()
                            }
                        }
                    } else {
                        val localDirFile = if (!customLocalPath.isNullOrEmpty()) {
                            File(customLocalPath, cleanPath)
                        } else {
                            File(getExternalFilesDir(null), "camera/$cleanPath")
                        }
                        if (!localDirFile.exists()) localDirFile.mkdirs()
                        val localPath = localDirFile.absolutePath

                        httpClient.downloadFile(downloadUrl, localPath) { bytes, total ->
                            updateSpeed(bytes)
                            if (total > 0) {
                                currentProgress = ((bytes * 100) / total).toInt()
                            }
                        }
                    }
                    
                    val endTime = System.currentTimeMillis()
                    
                    if (isDownloaded) {
                        Log.i("FileService", "Successfully downloaded: ${file.name}")
                        val duration = (endTime - startTime) / 1000.0
                        saveDownloadDuration(duration)
                        incrementDownloadCount()
                        successfullyDownloadedFiles.add(file)
                    } else {
                        Log.e("FileService", "Error downloading ${file.name}. Group incomplete.")
                    }
                } catch (e: Exception) {
                    FileLogger.logToFile(this, "FileService", "Critical download error: ${e.message}")
                    networkErrorOccurred = true
                    break // Stop processing this group
                }
            }

            if (networkErrorOccurred) {
                FileLogger.logToFile(this, "FileService", "Network error detected. Aborting cycle.")
                break // Stop all groups
            }

            // Only delete the group if ALL files in the group were successfully downloaded / already exist locally
            if (successfullyDownloadedFiles.size == filesInGroup.size) {
                FileLogger.logToFile(this, "FileService", "Group $groupKey downloaded/verified successfully. Deleting from camera...")
                for (file in successfullyDownloadedFiles) {
                    httpClient.registerClient()
                    val deleted = httpClient.deleteRemoteFile(file.path, file.name, token)
                    if (deleted) {
                        FileLogger.logToFile(this, "FileService", "Deleted from camera: ${file.name}")
                        if (pendingCameraFiles > 0) pendingCameraFiles--
                    } else {
                        Log.e("FileService", "Failed to delete ${file.name} from camera.")
                    }
                }
            } else {
                FileLogger.logToFile(this, "FileService", "Group $groupKey was NOT fully downloaded (Success: ${successfullyDownloadedFiles.size}/${filesInGroup.size}). Deletion skipped.")
                // Update pending count by removing processed group from the count to reflect UI correctly
                pendingCameraFiles -= filesInGroup.size
            }
        }
        currentFileName = ""
        currentSpeed = ""
        currentProgress = 0
        
        return true
    }

    private suspend fun downloadFileToDocumentTree(
        url: String,
        treeUriString: String,
        cleanSubPath: String,
        fileName: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val treeUri = Uri.parse(treeUriString)
                val rootDoc = DocumentFile.fromTreeUri(this@FileTransferService, treeUri)
                    ?: return@withContext false

                if (!rootDoc.canWrite()) {
                    FileLogger.logToFile(this@FileTransferService, "FileService", "Cannot write to selected folder Uri.")
                    return@withContext false
                }

                var currentDir = rootDoc
                val subFolders = cleanSubPath.split('/').filter { it.isNotEmpty() }
                for (folderName in subFolders) {
                    val existing = currentDir.findFile(folderName)
                    currentDir = if (existing != null && existing.isDirectory) {
                        existing
                    } else {
                        currentDir.createDirectory(folderName) ?: return@withContext false
                    }
                }

                var targetDoc = currentDir.findFile(fileName)
                if (targetDoc == null) {
                    val mimeType = when {
                        fileName.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
                        fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                        else -> "application/octet-stream"
                    }
                    targetDoc = currentDir.createFile(mimeType, fileName) ?: return@withContext false
                }

                val request = okhttp3.Request.Builder().url(url).build()
                val activeClient = httpClient.getOkHttpClientForUrl(url)
                val response = activeClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    response.close()
                    return@withContext false
                }

                val body = response.body ?: run {
                    response.close()
                    return@withContext false
                }

                val totalBytes = body.contentLength()
                var bytesDownloaded = 0L

                body.use { responseBody ->
                    responseBody.byteStream().use { inputStream ->
                        contentResolver.openOutputStream(targetDoc.uri)?.use { outputStream ->
                            val buffer = ByteArray(65536)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                bytesDownloaded += bytesRead
                                onProgress?.invoke(bytesDownloaded, totalBytes)
                            }
                        } ?: return@withContext false
                    }
                }

                return@withContext bytesDownloaded > 0
            } catch (e: Exception) {
                FileLogger.logToFile(this@FileTransferService, "FileService", "DocumentTree download error: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }

    private fun resetSpeed() {
        lastBytes = 0L
        lastTime = System.currentTimeMillis()
        currentSpeed = "0 KB/s"
        currentProgress = 0
    }

    /**
     * @brief Calculates and updates the current transfer speed.
     */
    private fun updateSpeed(currentBytes: Long) {
        val now = System.currentTimeMillis()
        val timeDiff = now - lastTime
        if (timeDiff >= 1000) { // Update every second
            val bytesDiff = currentBytes - lastBytes
            val speedBytesPerSec = (bytesDiff * 1000) / timeDiff
            
            currentSpeed = when {
                speedBytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB/s", speedBytesPerSec / (1024.0 * 1024.0))
                speedBytesPerSec >= 1024 -> String.format(Locale.US, "%.2f KB/s", speedBytesPerSec / 1024.0)
                else -> "$speedBytesPerSec B/s"
            }
            
            lastBytes = currentBytes
            lastTime = now
        }
    }

    private fun incrementDownloadCount() {
        val prefs = getSharedPreferences("FtpStats", MODE_PRIVATE)
        val current = prefs.getInt("TOTAL_DOWNLOADS", 0)
        prefs.edit {
            putInt("TOTAL_DOWNLOADS", current + 1)
        }
    }

    private fun saveDownloadDuration(seconds: Double) {
        val prefs = getSharedPreferences("FtpStats", MODE_PRIVATE)
        prefs.edit {
            putString("LAST_DOWNLOAD_DURATION", String.format(Locale.US, "%.2f sec.", seconds))
        }
    }

    private fun updateStatus(dlStatus: String?, dlRes: String?) {
        val prefs = getSharedPreferences("FtpStats", MODE_PRIVATE)
        prefs.edit {
            dlStatus?.let { putString("FTP_STATUS", it) }
            dlRes?.let { putString("FTP_RESULT", it) }
        }
    }

    private fun scheduleNextRun() {
        nextExecutionTime = System.currentTimeMillis() + checkInterval
        isScheduled.set(true)
        handler.postDelayed(periodicCheck, checkInterval)
        FileLogger.logToFile(this, "FileService", "Next check scheduled.")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Initialize Handler with main thread
        handler = Handler(Looper.getMainLooper())

        httpClient = HttpClient(this)
        httpAuth = HttpAuthentication(httpClient)
    }

    fun stopTransfer() {
        handler.removeCallbacks(periodicCheck)
        isScheduled.set(false)
        job.cancel() 
        isTaskRunning.set(false)
        nextExecutionTime = 0L

        // Zeroes statistics
        currentFileName = ""
        currentSpeed = ""

        // Critical remove from foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        // Critical system should fully stop the service
        stopSelf()
        
        // Re-initialize job and scope for the next Start command
        job = SupervisorJob()
        scope = CoroutineScope(Dispatchers.IO + job)
        
        FileLogger.logToFile(this, "FileService", "Transfer manually stopped and reset.")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunningInForeground = false
        // Stop scheduled checks
        handler.removeCallbacks(periodicCheck)
        isScheduled.set(false)
        // Cancel all active Coroutines
        job.cancel()

        scope.cancel()
        FileLogger.logToFile(this, "FileService", "Service stopped and cleaned up.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
        isServiceRunningInForeground = true

        // Only start if not already scheduled or running
        if (!isTaskRunning.get() && !isScheduled.get()) {
            handler.removeCallbacks(periodicCheck)
            isScheduled.set(true)
            nextExecutionTime = System.currentTimeMillis() + checkInterval
            handler.postDelayed(periodicCheck, checkInterval)
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel("FileTransferChannel", "File Transfer Service", NotificationManager.IMPORTANCE_DEFAULT)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "FileTransferChannel")
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.foreground_service_justification))
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun getServiceStatus(): ServiceStatus {
        val now = System.currentTimeMillis()
        val remainingMillis = nextExecutionTime - now

        val generalStatus = when {
            isScheduled.get() || isTaskRunning.get() -> {
                if (isTaskRunning.get()) {
                    getString(R.string.status_running)
                } else if (nextExecutionTime == 0L) {
                    getString(R.string.status_waiting_initial)
                } else if (remainingMillis <= 0) {
                    getString(R.string.status_waiting_start)
                } else {
                    val minutes = (remainingMillis / 1000) / 60
                    val seconds = (remainingMillis / 1000) % 60
                    getString(R.string.status_next_check, minutes, seconds)
                }
            }
            else -> "Status: Stopped"
        }
        val statsPrefs = getSharedPreferences("FtpStats", MODE_PRIVATE)
        val totalDownloads = statsPrefs.getInt("TOTAL_DOWNLOADS", 0)
        val lastDuration = statsPrefs.getString("LAST_DOWNLOAD_DURATION", "0 sec.") ?: "0 sec."

        val lastDownloadResult = statsPrefs.getString("FTP_STATUS", null) ?: getString(R.string.status_idle)

        return ServiceStatus(
            generalStatus, 
            lastDownloadResult, 
            totalDownloads, 
            pendingCameraFiles,
            lastDuration, 
            currentFileName,
            currentSpeed,
            currentProgress
        )
    }
}
