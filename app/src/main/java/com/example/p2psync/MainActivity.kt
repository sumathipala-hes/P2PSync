package com.example.p2psync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.p2psync.data.P2PDevice
import com.example.p2psync.ui.P2PSyncViewModel
import com.example.p2psync.ui.components.FileSharingScreen
import com.example.p2psync.ui.components.FolderSharingScreen
import com.example.p2psync.ui.components.SyncScreen
import com.example.p2psync.ui.components.TwoWaySyncScreen
import com.example.p2psync.ui.theme.P2PSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            P2PSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    P2PSyncApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2PSyncApp(viewModel: P2PSyncViewModel = viewModel()) {
    val context = LocalContext.current
    
    // State collection
    val isWifiP2pEnabled by viewModel.isWifiP2pEnabled.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val connectionInfo by viewModel.connectionInfo.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val permissionsGranted by viewModel.permissionsGranted.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val thisDevice by viewModel.thisDevice.collectAsState()
    
    // File sharing state
    val fileMessages by viewModel.fileMessages.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val messagingConnectionStatus by viewModel.messagingConnectionStatus.collectAsState()
    val fileTransferProgress by viewModel.fileTransferProgress.collectAsState()
    val transferMode by viewModel.transferMode.collectAsState()
    val connectedClients by viewModel.connectedClientsInfo.collectAsState()
    
    // Folder sharing state
    val folderTransferMode by viewModel.folderTransferMode.collectAsState()
    val selectedSendFolder by viewModel.selectedSendFolder.collectAsState()
    val selectedSendFolderUri by viewModel.selectedSendFolderUri.collectAsState()
    val selectedReceiveFolder by viewModel.selectedReceiveFolder.collectAsState()
    val selectedReceiveFolderUri by viewModel.selectedReceiveFolderUri.collectAsState()
    val folderTransferProgress by viewModel.folderTransferProgress.collectAsState()
    val isFolderTransferring by viewModel.isFolderTransferring.collectAsState()
    val folderTransferStatus by viewModel.folderTransferStatus.collectAsState()
    
    // Sync state
    val isSyncMode by viewModel.isSyncMode.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val filesToSync by viewModel.filesToSync.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    
    // Two-way sync state
    val selectedLocalFolder by viewModel.selectedLocalFolder.collectAsState()
    val selectedLocalFolderUri by viewModel.selectedLocalFolderUri.collectAsState()
    val twoWaySyncStatus by viewModel.twoWaySyncStatus.collectAsState()
    val filesToSyncToRemote by viewModel.filesToSyncToRemote.collectAsState()
    val filesToSyncToLocal by viewModel.filesToSyncToLocal.collectAsState()
    val twoWaySyncProgress by viewModel.twoWaySyncProgress.collectAsState()
    
    // Navigation state
    var currentScreen by remember { mutableStateOf("devices") }
    var previousScreen by remember { mutableStateOf("devices") }
    // Check if connected for file sharing
    val isConnected = connectionInfo?.groupFormed == true

    // Permission launcher for WiFi Direct
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.checkPermissions()
            Toast.makeText(context, "WiFi Direct permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "WiFi Direct permissions required", Toast.LENGTH_LONG).show()
        }
    }

    // Permission launcher for file access (separate)
    val filePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(context, "File access permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "File access limited on this device", Toast.LENGTH_SHORT).show()
        }
    }

    // Request WiFi Direct permissions if not granted
    LaunchedEffect(permissionsGranted) {
        if (!permissionsGranted) {
            permissionLauncher.launch(viewModel.getRequiredPermissions())
        }
    }

    // Check and request file permissions when switching to file sharing or folder sharing
    LaunchedEffect(currentScreen) {
        // Request permissions for file/folder sharing screens
        if ((currentScreen == "filesharing" || currentScreen == "foldersharing" || currentScreen == "sync" || currentScreen == "twowaysync") && !viewModel.checkFilePermissions()) {
            val filePermissions = viewModel.getFilePermissions()
            if (filePermissions.isNotEmpty()) {
                filePermissionLauncher.launch(filePermissions)
            }
        }
    }
    
    // Cleanup when switching screens
    LaunchedEffect(currentScreen) {
        // When leaving any feature screen, cleanup
        if (previousScreen in listOf("filesharing", "foldersharing", "sync", "twowaysync") && currentScreen != previousScreen) {
            // Stop server and clear state from previous screen
            viewModel.stopFileServer()
            viewModel.clearFileMessages()
            viewModel.clearFolderTransfer()
        }
        previousScreen = currentScreen
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        when (currentScreen) {
                            "devices" -> "P2P Sync"
                            "filesharing" -> "File Sharing"
                            "foldersharing" -> "Folder Sharing"
                            "sync" -> "One-Way Sync"
                            "twowaysync" -> "Two-Way Sync"
                            else -> "P2P Sync"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                navigationIcon = {
                    // Back button for non-main screens
                    if (currentScreen != "devices") {
                        IconButton(
                            onClick = { 
                                currentScreen = "devices" 
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back to Main",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        when (currentScreen) {
            "devices" -> DevicesScreen(
                paddingValues = paddingValues,
                viewModel = viewModel,
                isWifiP2pEnabled = isWifiP2pEnabled,
                discoveredDevices = discoveredDevices,
                connectionStatus = connectionStatus,
                isDiscovering = isDiscovering,
                permissionsGranted = permissionsGranted,
                statusMessage = statusMessage,
                thisDevice = thisDevice,
                onNavigateToFeature = { screen -> 
                    currentScreen = screen 
                }
            )
            "filesharing" -> FileSharingScreen(
                fileMessages = fileMessages,
                isListening = isListening,
                connectionStatus = messagingConnectionStatus,
                isConnected = isConnected,
                hostAddress = viewModel.getTargetAddress(),
                transferProgress = fileTransferProgress,
                onSendFile = { file ->
                    viewModel.sendFileAuto(file)
                },
                onStartServer = { viewModel.startFileServer() },
                onStopServer = { viewModel.stopFileServer() },
                onClearMessages = { viewModel.clearFileMessages() },
                onOpenFile = { fileMessage ->
                    viewModel.openFile(fileMessage)
                },
                onSetSendMode = { viewModel.setSendMode() },
                onSetReceiveMode = { viewModel.setReceiveMode() },
                currentMode = transferMode,
                isGroupOwner = connectionInfo?.isGroupOwner == true,
                connectedClients = if (connectionInfo?.isGroupOwner == true) {
                    connectedClients
                } else {
                    emptyList()
                },
                onSendToAllClients = { file ->
                    viewModel.sendFileToAllClients(file)
                },
                onDebugClients = {
                    val debugInfo = viewModel.debugClientState()
                    android.util.Log.d("MainActivity", debugInfo)
                },
                onAnnouncePresence = {
                    viewModel.sendClientHello()
                }
            )
            "foldersharing" -> FolderSharingScreen(
                fileMessages = fileMessages,
                isListening = isListening,
                connectionStatus = messagingConnectionStatus,
                isConnected = isConnected,
                hostAddress = viewModel.getTargetAddress(),
                transferProgress = fileTransferProgress,
                folderTransferProgress = folderTransferProgress,
                isFolderTransferring = isFolderTransferring,
                folderTransferStatus = folderTransferStatus,
                folderTransferMode = folderTransferMode,
                selectedSendFolder = selectedSendFolder,
                selectedSendFolderUri = selectedSendFolderUri,
                selectedReceiveFolder = selectedReceiveFolder,
                selectedReceiveFolderUri = selectedReceiveFolderUri,
                onStartServer = { viewModel.startFileServer() },
                onStopServer = { viewModel.stopFileServer() },
                onClearMessages = { viewModel.clearFileMessages() },
                onOpenFile = { fileMessage ->
                    viewModel.openFile(fileMessage)
                },
                onSetFolderSendMode = { viewModel.setFolderTransferMode("send") },
                onSetFolderReceiveMode = { viewModel.setFolderTransferMode("receive") },
                onSetSelectedSendFolder = { folder -> viewModel.setSelectedSendFolder(folder) },
                onSetSelectedSendFolderUri = { uri, name -> viewModel.setSelectedSendFolderUri(uri, name) },
                onSetSelectedReceiveFolder = { folder, uri -> viewModel.setSelectedReceiveFolder(folder, uri) },
                onSendFolder = { viewModel.sendFolder() },
                onClearFolderTransfer = { viewModel.clearFolderTransfer() },
                onRefreshClients = { viewModel.detectClientsForFolderSharing() },
                onAnnouncePresence = { viewModel.detectClientsForFolderSharing() },
                isGroupOwner = connectionInfo?.isGroupOwner == true,
                connectedClients = if (connectionInfo?.isGroupOwner == true) {
                    connectedClients
                } else {
                    emptyList()
                }
            )
            "sync" -> SyncScreen(
                isListening = isListening,
                connectionStatus = messagingConnectionStatus,
                isConnected = isConnected,
                hostAddress = viewModel.getTargetAddress(),
                selectedSendFolder = selectedSendFolder,
                selectedSendFolderUri = selectedSendFolderUri,
                selectedReceiveFolder = selectedReceiveFolder,
                selectedReceiveFolderUri = selectedReceiveFolderUri,
                syncStatus = syncStatus,
                filesToSync = filesToSync,
                syncProgress = syncProgress,
                onStartServer = { viewModel.startFileServer() },
                onStopServer = { viewModel.stopFileServer() },
                onSetSelectedSendFolder = { folder -> viewModel.setSelectedSendFolder(folder) },
                onSetSelectedSendFolderUri = { uri, name -> viewModel.setSelectedSendFolderUri(uri, name) },
                onSetSelectedReceiveFolder = { folder, uri -> viewModel.setSelectedReceiveFolder(folder, uri) },
                onStartComparison = { viewModel.startFolderComparison() },
                onStartSync = { viewModel.startSync() },
                onRefreshClients = { viewModel.detectClientsForFolderSharing() },
                onAnnouncePresence = { viewModel.detectClientsForFolderSharing() },
                isGroupOwner = connectionInfo?.isGroupOwner == true,
                connectedClients = if (connectionInfo?.isGroupOwner == true) {
                    connectedClients
                } else {
                    emptyList()
                }
            )
            "twowaysync" -> TwoWaySyncScreen(
                fileMessages = fileMessages,
                isListening = isListening,
                connectionStatus = messagingConnectionStatus,
                isConnected = isConnected,
                hostAddress = viewModel.getTargetAddress(),
                transferProgress = fileTransferProgress,
                selectedLocalFolder = selectedLocalFolder,
                selectedLocalFolderUri = selectedLocalFolderUri,
                twoWaySyncStatus = twoWaySyncStatus,
                filesToSyncToRemote = filesToSyncToRemote,
                filesToSyncToLocal = filesToSyncToLocal,
                twoWaySyncProgress = twoWaySyncProgress,
                onStartServer = { viewModel.startFileServer() },
                onStopServer = { viewModel.stopFileServer() },
                onSetSelectedLocalFolder = { folder -> viewModel.setSelectedLocalFolder(folder) },
                onSetSelectedLocalFolderUri = { uri, name -> viewModel.setSelectedLocalFolderUri(uri, name) },
                onStartTwoWayComparison = { viewModel.startTwoWayComparison() },
                onStartTwoWaySync = { viewModel.startTwoWaySync() },
                onRefreshClients = { viewModel.detectClientsForFolderSharing() },
                onAnnouncePresence = { viewModel.detectClientsForFolderSharing() },
                isGroupOwner = connectionInfo?.isGroupOwner == true,
                connectedClients = if (connectionInfo?.isGroupOwner == true) {
                    connectedClients
                } else {
                    emptyList()
                },
                onOpenFile = { fileMessage -> viewModel.openFile(fileMessage) },
                onClearMessages = { viewModel.clearFileMessages() }
            )
        }
    }
}

@Composable
fun StatusCard(
    isWifiP2pEnabled: Boolean,
    connectionStatus: String,
    statusMessage: String,
    thisDevice: P2PDevice?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isWifiP2pEnabled) Icons.Default.Wifi else Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = if (isWifiP2pEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    "WiFi Direct: ${if (isWifiP2pEnabled) "Enabled" else "Disabled"}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeviceHub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Connection: $connectionStatus",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (thisDevice != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "This Device: ${thisDevice.deviceName}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            HorizontalDivider()
            
            Text(
                statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ControlButtons(
    isDiscovering: Boolean,
    permissionsGranted: Boolean,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onDisconnect: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = if (isDiscovering) onStopDiscovery else onStartDiscovery,
            enabled = permissionsGranted,
            modifier = Modifier.weight(1f)
        ) {
            if (isDiscovering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop Discovery")
            } else {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start Discovery")
            }
        }

        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.LinkOff, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Disconnect")
        }
    }
}

@Composable
fun DevicesList(
    devices: List<P2PDevice>,
    onDeviceClick: (P2PDevice) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "Discovered Devices (${devices.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (devices.isEmpty()) {
                Text(
                    "No devices found\nTap 'Start Discovery' to search for nearby devices",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices) { device ->
                        DeviceItem(
                            device = device,
                            onClick = { onDeviceClick(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: P2PDevice,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    device.deviceName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    device.deviceAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
              AssistChip(
                onClick = onClick,
                label = { Text(device.getStatusString()) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = when {
                        device.isConnected() -> MaterialTheme.colorScheme.primary
                        device.isAvailable() -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            )
        }
    }
}

@Composable
fun FeatureNavigationCards(
    onNavigateToFeature: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Select Feature",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // File Sharing Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateToFeature("filesharing") },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            "File Sharing",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Folder Sharing Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateToFeature("foldersharing") },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            "Folder Sharing",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // One-Way Sync Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateToFeature("sync") },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            "One-Way Sync",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Two-Way Sync Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateToFeature("twowaysync") },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SyncAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            "Two-Way Sync",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DevicesScreen(
    paddingValues: PaddingValues,
    viewModel: P2PSyncViewModel,
    isWifiP2pEnabled: Boolean,
    discoveredDevices: List<P2PDevice>,
    connectionStatus: String,
    isDiscovering: Boolean,
    permissionsGranted: Boolean,
    statusMessage: String,
    thisDevice: P2PDevice?,
    onNavigateToFeature: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Card
        StatusCard(
            isWifiP2pEnabled = isWifiP2pEnabled,
            connectionStatus = connectionStatus,
            statusMessage = statusMessage,
            thisDevice = thisDevice
        )

        // Control Buttons
        ControlButtons(
            isDiscovering = isDiscovering,
            permissionsGranted = permissionsGranted,
            onStartDiscovery = { viewModel.startDiscovery() },
            onStopDiscovery = { viewModel.stopDiscovery() },
            onDisconnect = { viewModel.disconnect() }
        )

        // Feature Navigation Cards
        FeatureNavigationCards(
            onNavigateToFeature = onNavigateToFeature
        )

        // Devices List
        DevicesList(
            devices = discoveredDevices,
            onDeviceClick = { device -> viewModel.connectToDevice(device) }
        )
    }
}