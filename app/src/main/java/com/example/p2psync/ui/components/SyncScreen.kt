package com.example.p2psync.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.p2psync.utils.FolderUtils
import com.example.p2psync.utils.FolderOption
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    isListening: Boolean,
    connectionStatus: String,
    isConnected: Boolean,
    hostAddress: String?,
    selectedSendFolder: File?,
    selectedSendFolderUri: Uri?,
    selectedReceiveFolder: File?,
    selectedReceiveFolderUri: Uri?,
    syncStatus: String = "",
    filesToSync: List<String> = emptyList(),
    syncProgress: Map<String, Int> = emptyMap(),
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onSetSelectedSendFolder: (File?) -> Unit = {},
    onSetSelectedSendFolderUri: (Uri?, String?) -> Unit = { _, _ -> },
    onSetSelectedReceiveFolder: (File?, Uri?) -> Unit = { _, _ -> },
    onStartComparison: () -> Unit = {},
    onStartSync: () -> Unit = {},
    onRefreshClients: () -> Unit = {},
    onAnnouncePresence: () -> Unit = {},
    isGroupOwner: Boolean = false,
    connectedClients: List<String> = emptyList()
) {
    val context = LocalContext.current

    // Available folders for selection
    val availableFolders = remember { FolderUtils.getDefaultSendFolders(context) }
    var showSendFolderSelection by remember { mutableStateOf(false) }
    var showReceiveFolderSelection by remember { mutableStateOf(false) }

    // Folder picker launcher for sending
    val sendFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { folderUri ->
            try {
                val documentFile = DocumentFile.fromTreeUri(context, folderUri)
                documentFile?.let { doc ->
                    val folderName = doc.name ?: "Selected Folder"
                    onSetSelectedSendFolderUri(folderUri, folderName)
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    // Folder picker launcher for receiving  
    val receiveFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { folderUri ->
            try {
                val documentFile = DocumentFile.fromTreeUri(context, folderUri)
                documentFile?.let { doc ->
                    val folderName = doc.name ?: "Selected Folder"
                    onSetSelectedReceiveFolder(null, folderUri)
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "One-Way Folder Synchronization",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Sync only files that don't exist or have different sizes in the destination",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Connection Status
        ConnectionStatusCard(
            isListening = isListening,
            connectionStatus = connectionStatus,
            isConnected = isConnected,
            hostAddress = hostAddress,
            onStartServer = onStartServer,
            onStopServer = onStopServer,
            onRefreshClients = onRefreshClients,
            onAnnouncePresence = onAnnouncePresence,
            isGroupOwner = isGroupOwner,
            connectedClients = connectedClients
        )

        // Source Folder Selection
        SyncFolderSelectionCard(
            title = "Source Folder",
            selectedFolder = selectedSendFolder,
            selectedFolderUri = selectedSendFolderUri,
            onSelectFolder = { showSendFolderSelection = true },
            onClearSelection = { 
                onSetSelectedSendFolder(null)
                onSetSelectedSendFolderUri(null, null)
            },
            availableFolders = availableFolders,
            onFolderSelected = { folder ->
                onSetSelectedSendFolder(folder.folder)
                showSendFolderSelection = false
            },
            onCustomFolderPicker = { 
                sendFolderPickerLauncher.launch(null)
                showSendFolderSelection = false 
            },
            showSelection = showSendFolderSelection,
            onDismiss = { showSendFolderSelection = false },
            context = context
        )

        // Destination Folder Selection
        SyncFolderSelectionCard(
            title = "Destination Folder",
            selectedFolder = selectedReceiveFolder,
            selectedFolderUri = selectedReceiveFolderUri,
            onSelectFolder = { showReceiveFolderSelection = true },
            onClearSelection = { onSetSelectedReceiveFolder(null, null) },
            availableFolders = availableFolders,
            onFolderSelected = { folder ->
                onSetSelectedReceiveFolder(folder.folder, null)
                showReceiveFolderSelection = false
            },
            onCustomFolderPicker = { 
                receiveFolderPickerLauncher.launch(null)
                showReceiveFolderSelection = false 
            },
            showSelection = showReceiveFolderSelection,
            onDismiss = { showReceiveFolderSelection = false },
            context = context
        )

        // Sync Operations
        SyncOperationsCard(
            isConnected = isConnected,
            selectedSendFolder = selectedSendFolder,
            selectedSendFolderUri = selectedSendFolderUri,
            filesToSync = filesToSync,
            syncStatus = syncStatus,
            syncProgress = syncProgress,
            onStartComparison = onStartComparison,
            onStartSync = onStartSync
        )
    }
}

@Composable
fun SyncFolderSelectionCard(
    title: String,
    selectedFolder: File?,
    selectedFolderUri: Uri?,
    onSelectFolder: () -> Unit,
    onClearSelection: () -> Unit,
    availableFolders: List<FolderOption> = emptyList(),
    onFolderSelected: (FolderOption) -> Unit = {},
    onCustomFolderPicker: () -> Unit = {},
    showSelection: Boolean = false,
    onDismiss: () -> Unit = {},
    context: Context
) {
    // Create a synthetic File for display if URI is selected
    val displayFolder = selectedFolder ?: selectedFolderUri?.let { uri ->
        try {
            val documentFile = DocumentFile.fromTreeUri(context, uri)
            val folderName = documentFile?.name ?: "Selected Folder"
            // Create a fake File for display purposes only
            File("/storage/emulated/0/$folderName")
        } catch (e: Exception) {
            null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )

            if (displayFolder != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            displayFolder.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            displayFolder.absolutePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onClearSelection) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear selection",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onSelectFolder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Folder")
                }
            }

            // Folder selection dialog
            if (showSelection) {
                FolderSelectionDialog(
                    availableFolders = availableFolders,
                    onFolderSelected = onFolderSelected,
                    onCustomFolderPicker = onCustomFolderPicker,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
fun SyncOperationsCard(
    isConnected: Boolean,
    selectedSendFolder: File?,
    selectedSendFolderUri: Uri?,
    filesToSync: List<String>,
    syncStatus: String,
    syncProgress: Map<String, Int>,
    onStartComparison: () -> Unit,
    onStartSync: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Sync Operations",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Sync operation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartComparison,
                    modifier = Modifier.weight(1f),
                    enabled = isConnected && (selectedSendFolder != null || selectedSendFolderUri != null)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Compare")
                }

                Button(
                    onClick = onStartSync,
                    modifier = Modifier.weight(1f),
                    enabled = isConnected && filesToSync.isNotEmpty()
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sync")
                }
            }

            // Sync status
            if (syncStatus.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        syncStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Files to sync list
            if (filesToSync.isNotEmpty()) {
                Text(
                    "Files to sync (${filesToSync.size}):",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    LazyColumn(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        items(filesToSync) { file ->
                            Text(
                                file,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Sync progress
            if (syncProgress.isNotEmpty()) {
                val overallProgress = syncProgress["overall"] ?: 0
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Sync Progress: $overallProgress%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    LinearProgressIndicator(
                        progress = { overallProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(
    isListening: Boolean,
    connectionStatus: String,
    isConnected: Boolean,
    hostAddress: String?,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onRefreshClients: () -> Unit,
    onAnnouncePresence: () -> Unit,
    isGroupOwner: Boolean,
    connectedClients: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = if (isConnected) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    connectionStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isConnected) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onErrorContainer
                )
            }

            if (isConnected && isGroupOwner && connectedClients.isNotEmpty()) {
                Text(
                    "Connected devices: ${connectedClients.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            if (isConnected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        if (isGroupOwner) {
                            "Connected clients: ${connectedClients.size}"
                        } else {
                            "Connected to: ${hostAddress ?: "Group Owner"}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                
                // Client detection buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isGroupOwner) {
                        OutlinedButton(
                            onClick = onRefreshClients,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Detect Clients", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onAnnouncePresence,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Announce Presence", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Server Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = if (isListening) onStopServer else onStartServer,
                    modifier = Modifier.weight(1f),
                    colors = if (isListening) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isListening) "Stop Server" else "Start Server")
                }
            }
        }
    }
}

@Composable
fun FolderSelectionDialog(
    availableFolders: List<FolderOption>,
    onFolderSelected: (FolderOption) -> Unit,
    onCustomFolderPicker: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Folder") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(availableFolders) { folder ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFolderSelected(folder) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    folder.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "${folder.fileCount} files",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                
                item {
                    OutlinedButton(
                        onClick = onCustomFolderPicker,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Browse for Other Folder")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
