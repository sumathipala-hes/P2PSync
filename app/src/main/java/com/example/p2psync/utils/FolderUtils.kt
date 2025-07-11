package com.example.p2psync.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

object FolderUtils {
    private const val TAG = "FolderUtils"

    /**
     * Get files from DocumentFile URI (for folder picker)
     */
    /**
     * Create a temporary folder structure for selected files
     */
    /**
     * Get default folders that are commonly accessible
     */
    fun getDefaultSendFolders(context: Context): List<FolderOption> {
        val folders = mutableListOf<FolderOption>()
        
        try {
            // Downloads
            val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
            if (downloadsDir.exists() && downloadsDir.canRead()) {
                folders.add(FolderOption("Downloads", downloadsDir, getFileCount(downloadsDir)))
            }
            
            // App Documents
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { appDocs ->
                if (appDocs.exists()) {
                    folders.add(FolderOption("App Documents", appDocs, getFileCount(appDocs)))
                }
            }
            
            // App Downloads
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { appDownloads ->
                if (appDownloads.exists()) {
                    folders.add(FolderOption("App Downloads", appDownloads, getFileCount(appDownloads)))
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Error getting default folders: ${e.message}")
        }
        
        return folders
    }

    /**
     * Get count of files in a folder (non-recursive for performance)
     */
    private fun getFileCount(folder: File): Int {
        return try {
            folder.listFiles()?.count { it.isFile } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Get all files in a folder recursively
     */
    fun getAllFilesInFolder(folder: File): List<File> {
        val files = mutableListOf<File>()
        
        fun addFiles(dir: File) {
            try {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        files.add(file)
                    } else if (file.isDirectory) {
                        addFiles(file) // Recursive call for subdirectories
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading directory ${dir.absolutePath}: ${e.message}")
            }
        }
        
        addFiles(folder)
        return files
    }

    /**
     * Get a File object from URI-based folder and relative path
     * Creates a temporary file for sending
     */
    fun getFileFromUriFolder(context: Context, folderUri: Uri, relativePath: String): File? {
        try {
            val documentFile = DocumentFile.fromTreeUri(context, folderUri) ?: return null
            
            // Navigate to the file using the relative path
            var currentDir = documentFile
            val pathParts = relativePath.split("/")
            
            for (i in 0 until pathParts.size - 1) {
                val dirName = pathParts[i]
                currentDir = currentDir.findFile(dirName) ?: return null
                if (!currentDir.isDirectory) return null
            }
            
            val fileName = pathParts.last()
            val targetFile = currentDir.findFile(fileName) ?: return null
            
            if (!targetFile.isFile) return null
            
            // Create a temporary file with original name preserved
            val tempDir = File(context.cacheDir, "temp_sync_files")
            tempDir.mkdirs()
            
            // Use a unique temporary filename but preserve original for sending
            val uniqueSuffix = System.currentTimeMillis()
            val tempFile = File(tempDir, "${fileName}_$uniqueSuffix")
            
            // Copy content to temporary file
            context.contentResolver.openInputStream(targetFile.uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            return tempFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file from URI folder: ${e.message}", e)
            return null
        }
    }

    /**
     * Get file data directly from URI folder without creating temporary files
     * This preserves the original file name during sync operations
     */
    fun getFileDataFromUriFolder(context: Context, folderUri: Uri, relativePath: String): ByteArray? {
        try {
            val documentFile = DocumentFile.fromTreeUri(context, folderUri) ?: return null
            
            // Navigate to the file using the relative path
            var currentDir = documentFile
            val pathParts = relativePath.split("/")
            
            for (i in 0 until pathParts.size - 1) {
                val dirName = pathParts[i]
                currentDir = currentDir.findFile(dirName) ?: return null
                if (!currentDir.isDirectory) return null
            }
            
            val fileName = pathParts.last()
            val targetFile = currentDir.findFile(fileName) ?: return null
            
            if (!targetFile.isFile) return null
            
            // Read file data directly
            return context.contentResolver.openInputStream(targetFile.uri)?.use { input ->
                input.readBytes()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file data from URI folder: ${e.message}", e)
            return null
        }
    }

    /**
     * Create a temporary file with original name for sending
     * This ensures the file name is preserved during transmission
     */
    fun createTempFileWithOriginalName(context: Context, originalFileName: String, fileData: ByteArray): File? {
        try {
            val tempDir = File(context.cacheDir, "sync_temp_files")
            tempDir.mkdirs()
            
            // Clean up old temp files first
            tempDir.listFiles()?.forEach { file ->
                if (file.lastModified() < System.currentTimeMillis() - 3600000) { // 1 hour old
                    file.delete()
                }
            }
            
            // Create temp file with original name
            val tempFile = File(tempDir, originalFileName)
            
            // If file already exists, delete it first
            if (tempFile.exists()) {
                tempFile.delete()
            }
            
            tempFile.writeBytes(fileData)
            
            Log.d(TAG, "Created temp file with original name: ${tempFile.name}")
            return tempFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating temp file with original name: ${e.message}", e)
            return null
        }
    }
}

data class FileInfo(
    val name: String,
    val uri: Uri,
    val size: Long,
    val mimeType: String,
    val path: String
)

data class FolderOption(
    val displayName: String,
    val folder: File,
    val fileCount: Int
)
