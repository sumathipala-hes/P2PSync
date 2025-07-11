package com.example.p2psync.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.p2psync.data.FileMessage
import com.example.p2psync.utils.FolderUtils
import com.example.p2psync.utils.FolderOption
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoWaySyncScreen(
    paddingValues: PaddingValues,
    fileMessages: List<FileMessage>,
    isListening: Boolean,
    connectionStatus: String,
    isConnected: Boolean,
    hostAddress: String?,
    transferProgress: Map<String, Int>,
    selectedLocalFolder: File?,
    selectedLocalFolderUri: Uri?,
    twoWaySyncStatus: String = "",
    filesToSyncToRemote: List<String> = emptyList(),
    filesToSyncToLocal: List<String> = emptyList(),
    twoWaySyncProgress: Map<String, Int> = emptyMap(),
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onSetSelectedLocalFolder: (File?) -> Unit = {},
    onSetSelectedLocalFolderUri: (Uri?, String?) -> Unit = { _, _ -> },
    onStartTwoWayComparison: () -> Unit = {},
    onStartTwoWaySync: () -> Unit = {},
    onRefreshClients: () -> Unit = {},
    onAnnouncePresence: () -> Unit = {},
    isGroupOwner: Boolean = false,
    connectedClients: List<String> = emptyList(),
    onOpenFile: (FileMessage) -> Unit = {},
    onClearMessages: () -> Unit = {}
) {
    val context = LocalContext.current
    val availableFolders = remember { FolderUtils.getDefaultSendFolders(context) }
    var showFolderSelection by remember { mutableStateOf(false) }

    // Folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { folderUri ->
            try {
                val documentFile = DocumentFile.fromTreeUri(context, folderUri)
                documentFile?.let { doc ->
                    val folderName = doc.name ?: "Selected Folder"
                    onSetSelectedLocalFolderUri(folderUri, folderName)
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SyncAlt,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Column {
                        Text(
                            "Two-Way Folder Synchronization",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "Connect both devices, start servers, select folders, then compare and sync using sequential one-way transfers",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        // Connection Status Card
        TwoWayConnectionStatusCard(
            isConnected = isConnected,
            connectionStatus = connectionStatus,
            hostAddress = hostAddress,
            isGroupOwner = isGroupOwner,
            connectedClients = connectedClients,
            isListening = isListening,
            onStartServer = onStartServer,
            onStopServer = onStopServer,
            onRefreshClients = onRefreshClients,
            onAnnouncePresence = onAnnouncePresence
        )

        // Folder Selection Card
        TwoWayFolderSelectionCard(
            title = "Select Folder to Sync",
            selectedFolder = selectedLocalFolder,
            selectedFolderUri = selectedLocalFolderUri,
            onSelectFolder = { showFolderSelection = true },
            onClearSelection = { 
                onSetSelectedLocalFolder(null)
                onSetSelectedLocalFolderUri(null, null)
            },
            availableFolders = availableFolders,
            onFolderSelected = { folder ->
                onSetSelectedLocalFolder(folder.folder)
                showFolderSelection = false
            },
            onCustomFolderPicker = {
                folderPickerLauncher.launch(null)
                showFolderSelection = false
            },
            showSelection = showFolderSelection,
            onDismiss = { showFolderSelection = false },
            context = context
        )

        // Two-Way Sync Operations Card
        TwoWaySyncOperationsCard(
            isConnected = isConnected,
            selectedLocalFolder = selectedLocalFolder,
            selectedLocalFolderUri = selectedLocalFolderUri,
            filesToSyncToRemote = filesToSyncToRemote,
            filesToSyncToLocal = filesToSyncToLocal,
            twoWaySyncStatus = twoWaySyncStatus,
            twoWaySyncProgress = twoWaySyncProgress,
            onStartTwoWayComparison = onStartTwoWayComparison,
            onStartTwoWaySync = onStartTwoWaySync
        )

        // File Messages Card (if any)
        if (fileMessages.isNotEmpty()) {
            TwoWayFileMessagesCard(
                fileMessages = fileMessages,
                transferProgress = transferProgress,
                onOpenFile = onOpenFile,
                onClearMessages = onClearMessages
            )
        }
    }
}

@Composable
private fun TwoWaySyncOperationsCard(
    isConnected: Boolean,
    selectedLocalFolder: File?,
    selectedLocalFolderUri: Uri?,
    filesToSyncToRemote: List<String>,
    filesToSyncToLocal: List<String>,
    twoWaySyncStatus: String,
    twoWaySyncProgress: Map<String, Int>,
    onStartTwoWayComparison: () -> Unit,
    onStartTwoWaySync: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Two-Way Sync Operations",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Info about 2-way sync
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SyncAlt,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Sequential Two-Way Sync Process",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Phase 1: Device A → Device B, then Phase 2: Device B → Device A using one-way sync",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Sync operation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartTwoWayComparison,
                    modifier = Modifier.weight(1f),
                    enabled = isConnected && (selectedLocalFolder != null || selectedLocalFolderUri != null)
                ) {
                    Icon(
                        imageVector = Icons.Default.Compare,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Compare")
                }

                Button(
                    onClick = onStartTwoWaySync,
                    modifier = Modifier.weight(1f),
                    enabled = isConnected && (filesToSyncToRemote.isNotEmpty() || filesToSyncToLocal.isNotEmpty())
                ) {
                    Icon(
                        imageVector = Icons.Default.SyncAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sync Both Ways")
                }
            }

            // Sync status
            if (twoWaySyncStatus.isNotBlank()) {
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
                        twoWaySyncStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Files to sync summary and details
            if (filesToSyncToRemote.isNotEmpty() || filesToSyncToLocal.isNotEmpty()) {
                var showFilesToSend by remember { mutableStateOf(false) }
                var showFilesToReceive by remember { mutableStateOf(false) }
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Sync Summary:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    // Files to send section
                    if (filesToSyncToRemote.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showFilesToSend = !showFilesToSend },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Upload,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            "Files to send: ${filesToSyncToRemote.size}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Icon(
                                        imageVector = if (showFilesToSend) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (showFilesToSend) "Collapse" else "Expand",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                
                                if (showFilesToSend) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 200.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        items(filesToSyncToRemote) { fileDisplayName ->
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                                ),
                                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(8.dp),
                                                    verticalAlignment = Alignment.Top,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Upload,
                                                        contentDescription = null,
                                                        modifier = Modifier
                                                            .size(16.dp)
                                                            .padding(top = 2.dp),
                                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                                    )
                                                    Text(
                                                        text = fileDisplayName,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        modifier = Modifier.weight(1f),
                                                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Files to receive section
                    if (filesToSyncToLocal.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showFilesToReceive = !showFilesToReceive },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                        Text(
                                            "Files to receive: ${filesToSyncToLocal.size}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Icon(
                                        imageVector = if (showFilesToReceive) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (showFilesToReceive) "Collapse" else "Expand",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                
                                if (showFilesToReceive) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 200.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        items(filesToSyncToLocal) { fileDisplayName ->
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                                ),
                                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(8.dp),
                                                    verticalAlignment = Alignment.Top,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Download,
                                                        contentDescription = null,
                                                        modifier = Modifier
                                                            .size(16.dp)
                                                            .padding(top = 2.dp),
                                                        tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
                                                    )
                                                    Text(
                                                        text = fileDisplayName,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        modifier = Modifier.weight(1f),
                                                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sync progress
            if (twoWaySyncProgress.isNotEmpty()) {
                val overallProgress = twoWaySyncProgress["overall"] ?: 0
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Sync Progress: $overallProgress%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    LinearProgressIndicator(
                        progress = { (overallProgress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun TwoWayConnectionStatusCard(
    isConnected: Boolean,
    connectionStatus: String,
    hostAddress: String?,
    isGroupOwner: Boolean,
    connectedClients: List<String>,
    isListening: Boolean,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onRefreshClients: () -> Unit,
    onAnnouncePresence: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Connection Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Connection info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    connectionStatus,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (isConnected) {
                if (isGroupOwner && connectedClients.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "Connected Clients (${connectedClients.size})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            connectedClients.forEach { client ->
                                Text(
                                    "• $client",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
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
private fun TwoWayFolderSelectionCard(
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
                TwoWayFolderSelectionDialog(
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
private fun TwoWayFolderSelectionDialog(
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
                                    folder.folder.absolutePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCustomFolderPicker() },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                "Browse for folder...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun TwoWayFileMessagesCard(
    fileMessages: List<FileMessage>,
    transferProgress: Map<String, Int>,
    onOpenFile: (FileMessage) -> Unit,
    onClearMessages: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "File Messages (${fileMessages.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                if (fileMessages.isNotEmpty()) {
                    TextButton(onClick = onClearMessages) {
                        Text("Clear All")
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.height(200.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(fileMessages) { message ->
                    TwoWayFileMessageItem(
                        message = message,
                        progress = transferProgress[message.id] ?: 0,
                        onOpenFile = { onOpenFile(message) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TwoWayFileMessageItem(
    message: FileMessage,
    progress: Int,
    onOpenFile: () -> Unit
) {
    val canOpen = message.canBeOpened()
    val displayLocation = if (message.displayPath.isNotBlank()) {
        message.displayPath
    } else {
        message.filePath
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canOpen) { 
                if (canOpen) onOpenFile() 
            },
        colors = CardDefaults.cardColors(
            containerColor = if (canOpen) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    message.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        when (message.transferStatus) {
                            FileMessage.TransferStatus.PENDING -> "Pending"
                            FileMessage.TransferStatus.TRANSFERRING -> "$progress%"
                            FileMessage.TransferStatus.COMPLETED -> "✓"
                            FileMessage.TransferStatus.FAILED -> "✗"
                            FileMessage.TransferStatus.CANCELLED -> "Cancelled"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (message.transferStatus) {
                            FileMessage.TransferStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                            FileMessage.TransferStatus.FAILED -> MaterialTheme.colorScheme.error
                            FileMessage.TransferStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    
                    if (canOpen) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "Open file",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            // File location
            if (message.transferStatus == FileMessage.TransferStatus.COMPLETED && displayLocation.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Saved to: $displayLocation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            Text(
                "From: ${message.senderName} • ${message.getFormattedFileSize()} • ${message.getFormattedTime()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (message.transferStatus == FileMessage.TransferStatus.TRANSFERRING && progress > 0) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            
            // Click hint for completed files
            if (canOpen) {
                Text(
                    "Tap to open file",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontStyle = FontStyle.Italic
                )
            }
        }
    }
}
