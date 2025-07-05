package com.example.p2psync.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.example.p2psync.data.FileMessage
import com.example.p2psync.utils.FolderUtils
import com.example.p2psync.utils.FolderOption
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSharingScreen(
    fileMessages: List<FileMessage>,
    isListening: Boolean,
    connectionStatus: String,
    isConnected: Boolean,
    hostAddress: String?,
    transferProgress: Map<String, Int>,
    folderTransferProgress: Map<String, Int>,
    isFolderTransferring: Boolean,
    folderTransferStatus: String,
    folderTransferMode: String,
    selectedSendFolder: File?,
    selectedReceiveFolder: File?,
    selectedReceiveFolderUri: Uri?,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onClearMessages: () -> Unit,
    onOpenFile: (FileMessage) -> Unit = {},
    onSetFolderSendMode: () -> Unit = {},
    onSetFolderReceiveMode: () -> Unit = {},
    onSetSelectedSendFolder: (File?) -> Unit = {},
    onSetSelectedReceiveFolder: (File?, Uri?) -> Unit = { _, _ -> },
    onSendFolder: () -> Unit = {},
    onClearFolderTransfer: () -> Unit = {},
    onRefreshClients: () -> Unit = {},
    onAnnouncePresence: () -> Unit = {},
    isGroupOwner: Boolean = false,
    connectedClients: List<String> = emptyList()
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Available folders for selection
    val availableFolders = remember { FolderUtils.getDefaultSendFolders(context) }
    var showFolderSelection by remember { mutableStateOf(false) }
    var showReceiveFolderSelection by remember { mutableStateOf(false) }

    // Folder picker launcher for sending (custom picker)
    val sendFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { folderUri ->
            try {
                val documentFile = DocumentFile.fromTreeUri(context, folderUri)
                documentFile?.let { doc ->
                    // Create a temporary folder representation
                    val folderName = doc.name ?: "Selected Folder"
                    val files = FolderUtils.getFilesFromDocumentUri(context, folderUri)
                    
                    // Create temporary folder structure
                    val tempFolder = FolderUtils.createTemporaryFolderStructure(context, folderName, files)
                    tempFolder?.let { onSetSelectedSendFolder(it) }
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

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(fileMessages.size) {
        if (fileMessages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(fileMessages.size - 1)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Folder Sharing Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text(
                        "Server: ${if (isListening) "Running" else "Stopped"}",
                        style = MaterialTheme.typography.bodyMedium
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
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            if (isGroupOwner) {
                                "Connected clients: ${connectedClients.size}"
                            } else {
                                "Connected to: ${hostAddress ?: "Group Owner"}"
                            },
                            style = MaterialTheme.typography.bodyMedium
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

                if (folderTransferStatus.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            folderTransferStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }

        // Folder Transfer Controls
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Folder Transfer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Mode Selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onSetFolderSendMode,
                        modifier = Modifier.weight(1f),
                        colors = if (folderTransferMode == "send") {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Upload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Send Folder")
                    }

                    OutlinedButton(
                        onClick = onSetFolderReceiveMode,
                        modifier = Modifier.weight(1f),
                        colors = if (folderTransferMode == "receive") {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Receive Folder")
                    }
                }

                // Folder Selection for Send Mode
                if (folderTransferMode == "send") {
                    FolderSelectionCard(
                        title = "Select Folder to Send",
                        selectedFolder = selectedSendFolder,
                        onSelectFolder = { showFolderSelection = true },
                        onClearSelection = { onSetSelectedSendFolder(null) },
                        availableFolders = availableFolders,
                        onFolderSelected = { folder ->
                            onSetSelectedSendFolder(folder.folder)
                            showFolderSelection = false
                        },
                        onCustomFolderPicker = { 
                            sendFolderPickerLauncher.launch(null)
                            showFolderSelection = false 
                        },
                        showSelection = showFolderSelection,
                        onDismiss = { showFolderSelection = false }
                    )

                    // Send Button
                    if (selectedSendFolder != null && !isFolderTransferring) {
                        Button(
                            onClick = onSendFolder,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = isConnected
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Send Folder")
                        }
                    }
                }

                // Folder Selection for Receive Mode
                if (folderTransferMode == "receive") {
                    FolderSelectionCard(
                        title = "Select Destination Folder",
                        selectedFolder = selectedReceiveFolder,
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
                        onDismiss = { showReceiveFolderSelection = false }
                    )
                }

                // Transfer Progress
                if (isFolderTransferring && folderTransferProgress.isNotEmpty()) {
                    val overallProgress = folderTransferProgress["overall"] ?: 0
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Transfer Progress: $overallProgress%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        LinearProgressIndicator(
                            progress = overallProgress / 100f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Clear button
                if (folderTransferMode != "none" || selectedSendFolder != null || selectedReceiveFolder != null) {
                    OutlinedButton(
                        onClick = onClearFolderTransfer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
            }
        }

        // Server Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isListening) "Stop Server" else "Start Server")
            }

            OutlinedButton(
                onClick = onClearMessages,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear")
            }
        }

        // File Messages List
        if (fileMessages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        "No folders shared yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        "Select a folder to share it with connected devices",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(fileMessages, key = { it.id }) { fileMessage ->
                    FileMessageItem(
                        fileMessage = fileMessage,
                        progress = transferProgress[fileMessage.id] ?: fileMessage.progress,
                        onOpenFile = { onOpenFile(fileMessage) }
                    )
                }
            }
        }
    }
}

@Composable
fun FolderSelectionCard(
    title: String,
    selectedFolder: File?,
    onSelectFolder: () -> Unit,
    onClearSelection: () -> Unit,
    availableFolders: List<FolderOption> = emptyList(),
    onFolderSelected: (FolderOption) -> Unit = {},
    onCustomFolderPicker: () -> Unit = {},
    showSelection: Boolean = false,
    onDismiss: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            if (selectedFolder != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            selectedFolder.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            selectedFolder.absolutePath,
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
                AlertDialog(
                    onDismissRequest = onDismiss,
                    title = { Text("Select Folder") },
                    text = {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(availableFolders) { folderOption ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onFolderSelected(folderOption) },
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
                                                folderOption.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                "${folderOption.fileCount} files",
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
                                    Text("Browse for Folder")
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
        }
    }
}
