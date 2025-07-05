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
     * Get a list of accessible folders for the user to choose from
     */
    fun getAccessibleFolders(context: Context): List<File> {
        val folders = mutableListOf<File>()
        
        try {
            // Add Downloads folder
            val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
            if (downloadsDir.exists() && downloadsDir.canRead()) {
                folders.add(downloadsDir)
            }
            
            // Add Documents folder
            val documentsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath)
            if (documentsDir.exists() && documentsDir.canRead()) {
                folders.add(documentsDir)
            }
            
            // Add Pictures folder
            val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath)
            if (picturesDir.exists() && picturesDir.canRead()) {
                folders.add(picturesDir)
            }
            
            // Add Movies folder
            val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath)
            if (moviesDir.exists() && moviesDir.canRead()) {
                folders.add(moviesDir)
            }
            
            // Add app-specific directories
            context.getExternalFilesDir(null)?.let { appDir ->
                if (appDir.exists()) {
                    folders.add(appDir)
                }
            }
            
            // Add cache directory as fallback
            folders.add(context.cacheDir)
            
        } catch (e: Exception) {
            Log.w(TAG, "Error getting accessible folders: ${e.message}")
        }
        
        return folders
    }

    /**
     * Get files from DocumentFile URI (for folder picker)
     */
    fun getFilesFromDocumentUri(context: Context, uri: Uri): List<FileInfo> {
        val files = mutableListOf<FileInfo>()
        
        try {
            val documentFile = DocumentFile.fromTreeUri(context, uri)
            documentFile?.let { doc ->
                getAllFilesRecursive(doc, files)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting files from document URI: ${e.message}")
        }
        
        return files
    }

    /**
     * Recursively get all files from DocumentFile
     */
    private fun getAllFilesRecursive(documentFile: DocumentFile, files: MutableList<FileInfo>) {
        try {
            documentFile.listFiles().forEach { file ->
                if (file.isFile) {
                    files.add(
                        FileInfo(
                            name = file.name ?: "unknown",
                            uri = file.uri,
                            size = file.length(),
                            mimeType = file.type ?: "application/octet-stream",
                            path = file.uri.toString()
                        )
                    )
                } else if (file.isDirectory) {
                    getAllFilesRecursive(file, files)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading directory: ${e.message}")
        }
    }

    /**
     * Create a temporary folder structure for selected files
     */
    fun createTemporaryFolderStructure(context: Context, folderName: String, files: List<FileInfo>): File? {
        try {
            val tempDir = File(context.cacheDir, "temp_folders")
            tempDir.mkdirs()
            
            val folderDir = File(tempDir, folderName)
            folderDir.mkdirs()
            
            // Copy files to temporary structure
            files.forEach { fileInfo ->
                try {
                    val inputStream = context.contentResolver.openInputStream(fileInfo.uri)
                    val outputFile = File(folderDir, fileInfo.name)
                    
                    inputStream?.use { input ->
                        outputFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error copying file ${fileInfo.name}: ${e.message}")
                }
            }
            
            return folderDir
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating temporary folder structure: ${e.message}")
            return null
        }
    }

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
            
            // Create a temporary file
            val tempDir = File(context.cacheDir, "temp_sync_files")
            tempDir.mkdirs()
            
            val tempFile = File(tempDir, fileName)
            
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
