package com.example.p2psync.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
fun CommonSharingScreen(
    // Screen configuration
    title: String,
    subtitle: String,
    
    // Common data
    fileMessages: List<FileMessage>,
    isListening: Boolean,
    connectionStatus: String,
    isConnected: Boolean,
    hostAddress: String?,
    transferProgress: Map<String, Int>,
    currentMode: String = "none", // "send", "receive", "none"
    
    // Folder data
    selectedSendFolder: File?,
    selectedSendFolderUri: Uri?,
    selectedReceiveFolder: File?,
    selectedReceiveFolderUri: Uri?,
    
    // Common callbacks
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onSetSendMode: () -> Unit = {},
    onSetReceiveMode: () -> Unit = {},
    onSetSelectedSendFolder: (File?) -> Unit = {},
    onSetSelectedSendFolderUri: (Uri?, String?) -> Unit = { _, _ -> },
    onSetSelectedReceiveFolder: (File?, Uri?) -> Unit = { _, _ -> },
    onRefreshClients: () -> Unit = {},
    onAnnouncePresence: () -> Unit = {},
    isGroupOwner: Boolean = false,
    connectedClients: List<String> = emptyList(),
    onOpenFile: (FileMessage) -> Unit = {},
    onClearMessages: () -> Unit = {},
    
    // Screen-specific configuration
    showSendFolderSelection: Boolean = true,
    showReceiveFolderSelection: Boolean = true,
    
    // Screen-specific content
    additionalContent: @Composable () -> Unit = {}
) {
    val context = LocalContext.current

    // Available folders for selection
    val availableFolders = remember { FolderUtils.getDefaultSendFolders(context) }
    var showSendFolderSelection by remember { mutableStateOf(false) }
    var showReceiveFolderSelection by remember { mutableStateOf(false) }

    // LazyColumn state for file messages
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

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
        // Header Card
        CommonHeaderCard(
            title = title,
            subtitle = subtitle
        )

        // Connection Status Card
        CommonConnectionStatusCard(
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

        // Mode Selection Card (only show if connected)
        if (isConnected) {
            CommonModeSelectionCard(
                currentMode = currentMode,
                onSetSendMode = onSetSendMode,
                onSetReceiveMode = onSetReceiveMode
            )
        }

        // Folder Selection Cards (only show for selected mode and if enabled)
        if (showSendFolderSelection && currentMode == "send") {
            CommonFolderSelectionCard(
                title = "Source Folder (Device A - Sender)",
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
        } else if (showReceiveFolderSelection && currentMode == "receive") {
            CommonFolderSelectionCard(
                title = "Destination Folder (Device B - Receiver)",
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
        }

        // Additional content (screen-specific)
        additionalContent()

        // File Messages List
        CommonFileMessagesCard(
            fileMessages = fileMessages,
            transferProgress = transferProgress,
            listState = listState,
            onOpenFile = onOpenFile,
            onClearMessages = onClearMessages,
            emptyStateIcon = Icons.Default.Folder,
            emptyStateTitle = "No messages yet",
            emptyStateSubtitle = "File transfer messages will appear here",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun CommonHeaderCard(
    title: String,
    subtitle: String
) {
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
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CommonConnectionStatusCard(
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Connection Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Radio else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                Text(
                    "Server: ${if (isListening) "Listening" else "Stopped"}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Default.Link else Icons.Default.LinkOff,
                    contentDescription = null,
                    tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                Text(
                    "Connection: ${if (isConnected) "Connected" else "Not Connected"}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline
                )
                Text(
                    connectionStatus,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (hostAddress != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        if (isGroupOwner) "Group Owner - $hostAddress" else "Target: $hostAddress",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { if (isListening) onStopServer() else onStartServer() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isListening) "Stop Server" else "Start Server")
                }
            }

            if (isConnected) {
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
                            Text("Detect Clients")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onAnnouncePresence,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Announce Presence")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CommonModeSelectionCard(
    currentMode: String,
    onSetSendMode: () -> Unit,
    onSetReceiveMode: () -> Unit
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
                "Transfer Mode:",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSetSendMode,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentMode == "send") 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Upload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Send Mode")
                }

                Button(
                    onClick = onSetReceiveMode,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentMode == "receive") 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Receive Mode")
                }
            }
            
            // Mode status indicator
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (currentMode) {
                        "send" -> MaterialTheme.colorScheme.primaryContainer
                        "receive" -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = when (currentMode) {
                            "send" -> Icons.Default.Upload
                            "receive" -> Icons.Default.Download
                            else -> Icons.Default.Info
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = when (currentMode) {
                                "send" -> "Send Mode Active"
                                "receive" -> "Receive Mode Active"
                                else -> "Select transfer mode"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when (currentMode) {
                                "send" -> "Ready to send files and folders"
                                "receive" -> "Ready to receive files and folders"
                                else -> "Choose send or receive mode"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CommonFolderSelectionCard(
    title: String,
    selectedFolder: File?,
    selectedFolderUri: Uri?,
    onSelectFolder: () -> Unit,
    onClearSelection: () -> Unit,
    availableFolders: List<FolderOption>,
    onFolderSelected: (FolderOption) -> Unit,
    onCustomFolderPicker: () -> Unit,
    showSelection: Boolean,
    onDismiss: () -> Unit,
    context: Context
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            if (selectedFolder != null || selectedFolderUri != null) {
                // Show selected folder
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
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = selectedFolder?.name ?: selectedFolderUri?.let { uri ->
                                    DocumentFile.fromTreeUri(context, uri)?.name ?: "Selected Folder"
                                } ?: "Selected Folder",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = selectedFolder?.absolutePath ?: selectedFolderUri?.toString() ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = onClearSelection,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear selection",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            } else {
                // Show selection button
                OutlinedButton(
                    onClick = onSelectFolder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Folder")
                }
            }
            
            if (showSelection) {
                CommonFolderSelectionDialog(
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
fun CommonFolderSelectionDialog(
    availableFolders: List<FolderOption>,
    onFolderSelected: (FolderOption) -> Unit,
    onCustomFolderPicker: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Select Folder")
        },
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
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = folderOption.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = folderOption.folder.absolutePath,
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
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Browse for custom folder...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
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

@Composable
fun CommonFileMessagesCard(
    fileMessages: List<FileMessage>,
    transferProgress: Map<String, Int>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenFile: (FileMessage) -> Unit,
    onClearMessages: () -> Unit,
    emptyStateIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Folder,
    emptyStateTitle: String = "No messages yet",
    emptyStateSubtitle: String = "File transfer messages will appear here",
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Messages (${fileMessages.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onClearMessages,
                    modifier = Modifier.size(36.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

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
                            imageVector = emptyStateIcon,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            emptyStateTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            emptyStateSubtitle,
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
                        CommonFileMessageItem(
                            fileMessage = fileMessage,
                            progress = transferProgress[fileMessage.id] ?: fileMessage.progress,
                            onOpenFile = { onOpenFile(fileMessage) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CommonFileMessageItem(
    fileMessage: FileMessage,
    progress: Int,
    onOpenFile: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (fileMessage.canBeOpened()) {
                    Modifier.clickable { onOpenFile() }
                } else {
                    Modifier
                }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (fileMessage.isOutgoing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = fileMessage.fileName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (fileMessage.canBeOpened()) {
                            Icon(
                                imageVector = Icons.Default.Launch,
                                contentDescription = "Open",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${fileMessage.fileSize} bytes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        // Direction indicator
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (fileMessage.isOutgoing) Icons.Default.Upload else Icons.Default.Download,
                                contentDescription = if (fileMessage.isOutgoing) "Sent" else "Received",
                                modifier = Modifier.size(14.dp),
                                tint = if (fileMessage.isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = if (fileMessage.isOutgoing) "Sent" else "Received",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                // Status indicator
                when (fileMessage.transferStatus) {
                    FileMessage.TransferStatus.COMPLETED -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Completed",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    FileMessage.TransferStatus.FAILED -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Failed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    FileMessage.TransferStatus.TRANSFERRING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "Pending",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            
            // Progress bar for active transfers
            if (progress > 0 && progress < 100) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
