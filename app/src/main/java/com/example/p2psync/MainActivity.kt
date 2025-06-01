package com.example.p2psync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : ComponentActivity(), P2PConnectionManager.ConnectionListener, P2PFileTransfer.TransferListener {
    
    // UI Components
    private lateinit var btnDiscoverDevices: Button
    private lateinit var spinnerSyncMode: Spinner
    private lateinit var btnSelectFolders: Button
    private lateinit var btnStartSync: Button
    private lateinit var progressBarSync: ProgressBar
    private lateinit var tvSyncStatus: TextView
    private lateinit var tvSelectedFolder: TextView
    private lateinit var cardSelectedFolder: LinearLayout
    private lateinit var layoutSyncProgress: LinearLayout
    
    // WiFi Direct components
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: WiFiDirectBroadcastReceiver
    private lateinit var intentFilter: IntentFilter
    private lateinit var connectionManager: P2PConnectionManager
      // File transfer components
    private lateinit var fileTransfer: P2PFileTransfer
    private var isGroupOwner = false
    private var groupOwnerAddress: String? = null
    private val discoveredDevices = mutableListOf<P2PDevice>()
    private var isWiFiP2PEnabled = false
      // Sync modes
    private val syncModes = arrayOf("Two-Way", "Backup", "Mirror")
    private var selectedFolderPath: String? = null
      // Required permissions for Wi-Fi Direct and storage access
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
    }
    
    // Permission launcher for handling runtime permissions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val deniedPermissions = permissions.filter { !it.value }.keys
        if (deniedPermissions.isEmpty()) {
            onPermissionsGranted()
        } else {
            onPermissionsDenied(deniedPermissions.toList())
        }
    }
      // Folder selection launcher
    private val folderSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                val path = uri.path ?: "Selected folder"
                selectedFolderPath = path
                updateSelectedFolderDisplay(path)
                updateSyncButtonState()
            }
        }
    }
    
    // Debug and testing flags
    private var isDebugMode = false
    private var isEmulator = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("P2PSync", "MainActivity onCreate started")
        
        try {
            setContentView(R.layout.activity_main)
            Log.d("P2PSync", "Layout set successfully")
            
            initializeViews()
            Log.d("P2PSync", "Views initialized successfully")
            
            initializeWiFiDirect()
            Log.d("P2PSync", "WiFi Direct initialized successfully")
            
            setupSyncModeSpinner()
            Log.d("P2PSync", "Spinner setup successfully")
            
            setupClickListeners()
            Log.d("P2PSync", "Click listeners setup successfully")
            
            // Check and request permissions
            checkAndRequestPermissions()
            Log.d("P2PSync", "Permission check initiated")
            
            // Check device capabilities and emulator status
            checkDeviceCapabilities()
            Log.d("P2PSync", "Device capabilities checked")

        } catch (e: Exception) {
            Log.e("P2PSync", "Error in onCreate: ${e.message}", e)
        }
    }
      private fun initializeViews() {
        Log.d("P2PSync", "Starting view initialization")
        try {
            btnDiscoverDevices = findViewById(R.id.btnDiscoverDevices)
            spinnerSyncMode = findViewById(R.id.spinnerSyncMode)
            btnSelectFolders = findViewById(R.id.btnSelectFolders)
            btnStartSync = findViewById(R.id.btnStartSync)
            progressBarSync = findViewById(R.id.progressBarSync)
            tvSyncStatus = findViewById(R.id.tvSyncStatus)
            tvSelectedFolder = findViewById(R.id.tvSelectedFolder)
            cardSelectedFolder = findViewById(R.id.cardSelectedFolder)
            layoutSyncProgress = findViewById(R.id.layoutSyncProgress)
            Log.d("P2PSync", "All views found successfully")
        } catch (e: Exception) {
            Log.e("P2PSync", "Error initializing views: ${e.message}", e)
        }
    }
      private fun setupSyncModeSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, syncModes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSyncMode.adapter = adapter
        spinnerSyncMode.setSelection(0) // Set default to Two-Way
    }
    
    private fun setupClickListeners() {
        btnDiscoverDevices.setOnClickListener {
            onDiscoverDevicesClicked()
        }
        
        btnSelectFolders.setOnClickListener {
            onSelectFoldersClicked()
        }
        
        btnStartSync.setOnClickListener {
            onStartSyncClicked()
        }
    }
      private fun onDiscoverDevicesClicked() {
        if (isEmulator || isDebugMode) {
            // Simulate device discovery for emulator testing
            simulateDeviceDiscovery()
            return
        }
        
        if (!isWiFiP2PEnabled) {
            updateSyncStatus("WiFi P2P is not enabled. Please enable WiFi.")
            Toast.makeText(this, "WiFi P2P is not available", Toast.LENGTH_SHORT).show()
            return
        }
        
        updateSyncStatus("Discovering devices...")
        Toast.makeText(this, "Starting device discovery...", Toast.LENGTH_SHORT).show()
        
        btnDiscoverDevices.isEnabled = false
        btnDiscoverDevices.text = "Discovering..."
        
        connectionManager.startDiscovery()
    }
    
    private fun onSelectFoldersClicked() {
        try {
            // For Android 10 and above, use Storage Access Framework
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                // Request persistable permissions
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            }
            
            folderSelectionLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening folder selector: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
      private fun onStartSyncClicked() {
        val selectedMode = spinnerSyncMode.selectedItem.toString()
        val folderPath = selectedFolderPath
        
        if (folderPath.isNullOrEmpty()) {
            Toast.makeText(this, "Please select a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        
        startSyncProcess(selectedMode, folderPath)
    }
      private fun startSyncProcess(mode: String, folderPath: String) {
        updateSyncStatus("Starting $mode sync...")
        showSyncProgress(true)
        
        btnStartSync.isEnabled = false
        btnStartSync.text = "Syncing..."
          // Simulate file sync process
        if (!isGroupOwner && groupOwnerAddress != null) {
            // Send a test file to demonstrate P2P transfer
            createTestFileAndSend(folderPath)
        } else {
            // Simulate sync progress for group owner
            simulateSyncProgress()
        }
    }
    
    private fun updateSelectedFolderDisplay(path: String) {
        tvSelectedFolder.text = path
        cardSelectedFolder.visibility = View.VISIBLE
    }
    
    private fun updateSyncStatus(status: String) {
        tvSyncStatus.text = status
    }
      private fun showSyncProgress(show: Boolean) {
        layoutSyncProgress.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            progressBarSync.progress = 0
        }
    }
    
    private fun updateSyncButtonState() {
        btnStartSync.isEnabled = !selectedFolderPath.isNullOrEmpty()
    }
    
    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            onPermissionsGranted()
        }
    }
    
    private fun onPermissionsGranted() {
        Toast.makeText(this, "All permissions granted. Ready for P2P sync!", Toast.LENGTH_SHORT).show()
        updateSyncStatus("Ready to sync - Discover devices to begin")
        // Initialize Wi-Fi Direct and other features here
    }
    
    private fun onPermissionsDenied(deniedPermissions: List<String>) {        val deniedList = deniedPermissions.joinToString(", ") { permission ->
            when (permission) {
                Manifest.permission.READ_EXTERNAL_STORAGE -> "Read Storage"
                Manifest.permission.WRITE_EXTERNAL_STORAGE -> "Write Storage"
                Manifest.permission.ACCESS_FINE_LOCATION -> "Fine Location"
                Manifest.permission.ACCESS_COARSE_LOCATION -> "Coarse Location"
                Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby WiFi Devices"
                else -> permission.substringAfterLast(".")
            }
        }
        
        Toast.makeText(
            this, 
            "Permissions denied: $deniedList. Some features may not work properly.", 
            Toast.LENGTH_LONG
        ).show()
        
        updateSyncStatus("Permissions required for sync functionality")
    }
    
    private fun initializeWiFiDirect() {
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifiP2pManager.initialize(this, mainLooper, null)
          connectionManager = P2PConnectionManager(this, wifiP2pManager, channel)
        connectionManager.setConnectionListener(this)
        
        // Initialize file transfer
        fileTransfer = P2PFileTransfer()
        fileTransfer.setTransferListener(this)
        
        // Set up broadcast receiver
        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        
        receiver = WiFiDirectBroadcastReceiver(wifiP2pManager, channel, this)
    }
    
    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, intentFilter)
    }
    
    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }
    
    // WiFi Direct callback methods
    fun onWiFiP2PEnabled() {
        isWiFiP2PEnabled = true
        updateSyncStatus("WiFi P2P enabled - Ready to discover devices")
    }
    
    fun onWiFiP2PDisabled() {
        isWiFiP2PEnabled = false
        updateSyncStatus("WiFi P2P disabled - Please enable WiFi")
    }
    
    fun onPeersChanged(peers: WifiP2pDeviceList) {
        connectionManager.updatePeerList(peers)
    }
    
    fun onConnectionChanged() {
        connectionManager.requestConnectionInfo()
    }
    
    fun onThisDeviceChanged(device: WifiP2pDevice?) {
        Log.d("P2PSync", "This device: ${device?.deviceName}")
    }
    
    // P2PConnectionManager.ConnectionListener implementation
    override fun onDeviceListUpdated(devices: List<P2PDevice>) {
        discoveredDevices.clear()
        discoveredDevices.addAll(devices)
        
        runOnUiThread {
            btnDiscoverDevices.isEnabled = true
            btnDiscoverDevices.text = "Discover Devices"
            
            if (devices.isEmpty()) {
                updateSyncStatus("No devices found. Try again.")
            } else {
                updateSyncStatus("Found ${devices.size} device(s). Select a folder to continue.")
                showDeviceSelectionDialog(devices)
            }
        }
    }
      override fun onConnectionEstablished(info: WifiP2pInfo) {
        runOnUiThread {
            if (info.groupFormed) {
                isGroupOwner = info.isGroupOwner
                groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                
                if (info.isGroupOwner) {
                    updateSyncStatus("Connected as group owner. Ready to sync.")
                    // Start server for receiving files
                    val outputDir = File(getExternalFilesDir(null), "received_files")
                    if (!outputDir.exists()) outputDir.mkdirs()
                    fileTransfer.startServer(outputDir)
                } else {
                    updateSyncStatus("Connected to group owner. Ready to sync.")
                }
                updateSyncButtonState()
            }
        }
    }
    
    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            updateSyncStatus("Connection failed: $reason")
            Toast.makeText(this, "Connection failed: $reason", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onDiscoveryStarted() {
        runOnUiThread {
            updateSyncStatus("Searching for devices...")
        }
    }
    
    override fun onDiscoveryStopped() {
        runOnUiThread {
            btnDiscoverDevices.isEnabled = true
            btnDiscoverDevices.text = "Discover Devices"
        }
    }
    
    private fun showDeviceSelectionDialog(devices: List<P2PDevice>) {
        val deviceNames = devices.map { "${it.name} (${it.getStatusString()})" }.toTypedArray()
        
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Select Device to Connect")
        builder.setItems(deviceNames) { _, which ->
            val selectedDevice = devices[which]
            connectToDevice(selectedDevice)
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }
    
    private fun connectToDevice(device: P2PDevice) {
        if (isEmulator || isDebugMode) {
            simulateConnection(device)
            return
        }
        
        updateSyncStatus("Connecting to ${device.name}...")
        connectionManager.connectToDevice(device)
    }
    
    private fun simulateConnection(device: P2PDevice) {
        updateSyncStatus("🔗 Simulating connection to ${device.name}...")
        
        Handler(Looper.getMainLooper()).postDelayed({
            // Simulate successful connection
            isGroupOwner = (0..1).random() == 1 // Randomly assign group owner role
            groupOwnerAddress = if (isGroupOwner) null else "192.168.49.1"
            
            val role = if (isGroupOwner) "Group Owner" else "Client"
            updateSyncStatus("✅ Connected as $role to ${device.name}")
            Toast.makeText(this, "Simulated connection successful!", Toast.LENGTH_SHORT).show()
            
            // Enable sync button
            updateSyncButtonState()
            
        }, 1500)
    }

    // Missing helper functions
    private fun createTestFileAndSend(folderPath: String) {
        try {
            // Create a test file in the selected folder
            val testFile = File(getExternalFilesDir(null), "test_sync_file.txt")
            val testContent = """
                P2P Sync Test File
                ==================
                
                This is a test file created for P2P synchronization.
                Created at: ${System.currentTimeMillis()}
                Folder: $folderPath
                Sync Mode: ${spinnerSyncMode.selectedItem}
                Device: ${android.os.Build.MODEL}
                
                This file demonstrates the P2P file transfer functionality.
            """.trimIndent()
            
            testFile.writeText(testContent)
              // Send the file to the group owner
            groupOwnerAddress?.let { address ->
                updateSyncStatus("Sending test file...")
                fileTransfer.sendFile(address, testFile)
            } ?: run {
                updateSyncStatus("Error: No group owner address available")
                resetSyncState()
            }
            
        } catch (e: Exception) {
            Log.e("P2PSync", "Error creating test file: ${e.message}", e)
            updateSyncStatus("Error creating test file: ${e.message}")
            resetSyncState()
        }
    }
    
    private fun simulateSyncProgress() {
        // Simulate sync progress for group owner
        Thread {
            try {
                for (progress in 0..100 step 10) {
                    Thread.sleep(200) // Simulate processing time
                    runOnUiThread {
                        progressBarSync.progress = progress
                        updateSyncStatus("Syncing files... ${progress}%")
                    }
                }
                
                runOnUiThread {
                    updateSyncStatus("Sync completed successfully!")
                    resetSyncState()
                }
                
            } catch (e: Exception) {
                runOnUiThread {
                    updateSyncStatus("Sync simulation error: ${e.message}")
                    resetSyncState()
                }
            }
        }.start()
    }
    
    private fun resetSyncState() {
        btnStartSync.isEnabled = true
        btnStartSync.text = "Start Sync"
        showSyncProgress(false)
    }
    
    private fun checkDeviceCapabilities() {
        // Check if running on emulator
        isEmulator = (Build.FINGERPRINT.startsWith("generic") ||
                     Build.FINGERPRINT.startsWith("unknown") ||
                     Build.MODEL.contains("google_sdk") ||
                     Build.MODEL.contains("Emulator") ||
                     Build.MODEL.contains("Android SDK"))
        
        // Check WiFi Direct support
        val hasWiFiDirect = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        
        Log.d("P2PSync", "Device capabilities:")
        Log.d("P2PSync", "- Is Emulator: $isEmulator")
        Log.d("P2PSync", "- Has WiFi Direct: $hasWiFiDirect")
        Log.d("P2PSync", "- Model: ${Build.MODEL}")
        Log.d("P2PSync", "- Fingerprint: ${Build.FINGERPRINT}")
        
        if (isEmulator) {
            isDebugMode = true
            updateSyncStatus("⚠️ Running on emulator - WiFi Direct features limited")
            Toast.makeText(this, 
                "Emulator detected: WiFi Direct requires physical devices for full testing", 
                Toast.LENGTH_LONG).show()
        } else if (!hasWiFiDirect) {
            updateSyncStatus("❌ WiFi Direct not supported on this device")
            Toast.makeText(this, 
                "This device doesn't support WiFi Direct", 
                Toast.LENGTH_LONG).show()
        } else {
            updateSyncStatus("✅ Ready for WiFi Direct - Discover devices to begin")
        }
    }

    // P2PFileTransfer.TransferListener implementation
    override fun onTransferStarted(fileName: String) {
        runOnUiThread {
            updateSyncStatus("Transferring: $fileName")
            progressBarSync.progress = 0
        }
    }
    
    override fun onTransferProgress(progress: Int) {
        runOnUiThread {
            progressBarSync.progress = progress
            updateSyncStatus("Transfer progress: ${progress}%")
        }
    }
    
    override fun onTransferCompleted(fileName: String) {
        runOnUiThread {
            updateSyncStatus("Transfer completed: $fileName")
            progressBarSync.progress = 100
            
            // Reset state after a short delay
            Handler(Looper.getMainLooper()).postDelayed({
                resetSyncState()
                updateSyncStatus("Ready for next sync")
            }, 2000)
        }
    }
    
    override fun onTransferFailed(error: String) {
        runOnUiThread {
            updateSyncStatus("Transfer failed: $error")
            Toast.makeText(this, "Transfer failed: $error", Toast.LENGTH_LONG).show()
            resetSyncState()
        }
    }

    private fun simulateDeviceDiscovery() {
        updateSyncStatus("🔍 Simulating device discovery (emulator mode)...")
        btnDiscoverDevices.isEnabled = false
        btnDiscoverDevices.text = "Discovering..."
        
        // Simulate discovery delay
        Handler(Looper.getMainLooper()).postDelayed({
            // Add some mock devices
            discoveredDevices.clear()
            discoveredDevices.addAll(listOf(
                P2PDevice("Simulated Device 1", "aa:bb:cc:dd:ee:f1", 0),
                P2PDevice("Simulated Device 2", "aa:bb:cc:dd:ee:f2", 0),
                P2PDevice("Test Phone", "aa:bb:cc:dd:ee:f3", 0)
            ))
              btnDiscoverDevices.isEnabled = true
            btnDiscoverDevices.text = "Discover Devices"
            updateSyncStatus("Found ${discoveredDevices.size} simulated devices")
            
            // Show device selection dialog
            showDeviceSelectionDialog(discoveredDevices)
        }, 2000)
    }
}
