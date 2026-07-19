package com.example.data

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

sealed class ScanState {
    object Idle : ScanState()
    data class Scanning(val current: Int, val total: Int, val currentFile: String) : ScanState()
    data class Success(val totalFiles: Int, val duplicatesCount: Int) : ScanState()
    data class Error(val message: String) : ScanState()
}

class AudioRepository(
    private val context: Context,
    private val audioDao: AudioDao
) {
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    val allAudioFiles: Flow<List<AudioFile>> = audioDao.getAllAudioFilesFlow()
    val duplicateFiles: Flow<List<AudioFile>> = audioDao.getDuplicateFilesFlow()

    suspend fun clearDatabase() {
        withContext(Dispatchers.IO) {
            audioDao.clearAll()
        }
    }

    suspend fun deleteFile(audioFile: AudioFile): Boolean {
        return withContext(Dispatchers.IO) {
            val file = File(audioFile.filePath)
            var deletedOnDisk = false
            try {
                if (file.exists()) {
                    deletedOnDisk = file.delete()
                }
            } catch (e: Exception) {
                Log.e("AudioRepository", "Error deleting file on disk: ${audioFile.filePath}", e)
            }

            // Also try ContentResolver deletion as fallback/primary if on-disk fails
            if (!deletedOnDisk) {
                try {
                    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    val selection = "${MediaStore.Audio.Media.DATA} = ?"
                    val selectionArgs = arrayOf(audioFile.filePath)
                    val rows = context.contentResolver.delete(uri, selection, selectionArgs)
                    deletedOnDisk = rows > 0
                } catch (e: Exception) {
                    Log.e("AudioRepository", "Error deleting via MediaStore contentResolver", e)
                }
            }

            // Always remove from database if delete requested
            audioDao.deleteById(audioFile.id)
            true
        }
    }

    suspend fun scanAudioFiles() {
        withContext(Dispatchers.IO) {
            try {
                _scanState.value = ScanState.Scanning(0, 0, "Querying library...")

                // 1. Fetch current database entries
                val cachedFiles = audioDao.getAllAudioFiles().associateBy { it.filePath }

                // 2. Query MediaStore
                val mediaStoreFiles = mutableListOf<TempAudioInfo>()
                val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATE_MODIFIED
                )

                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                    val supportedExtensions = setOf("mp3", "wav", "m4a", "aac", "flac", "ogg")

                    while (cursor.moveToNext()) {
                        val path = cursor.getString(dataCol) ?: continue
                        val file = File(path)
                        if (!file.exists()) continue

                        val ext = file.extension.lowercase()
                        if (ext !in supportedExtensions) continue

                        val name = cursor.getString(nameCol) ?: file.name
                        val size = cursor.getLong(sizeCol)
                        val duration = cursor.getLong(durCol)
                        // DATE_MODIFIED is in seconds, convert to ms
                        val lastModified = cursor.getLong(dateModifiedCol) * 1000L

                        mediaStoreFiles.add(TempAudioInfo(path, name, size, duration, lastModified))
                    }
                }

                val totalCount = mediaStoreFiles.size
                if (totalCount == 0) {
                    audioDao.clearAll()
                    _scanState.value = ScanState.Success(0, 0)
                    return@withContext
                }

                // 3. Keep tracking of scan progress and update DB
                val updatedList = mutableListOf<AudioFile>()
                val filesToRemoveFromDb = cachedFiles.keys.toMutableSet()

                for ((index, info) in mediaStoreFiles.withIndex()) {
                    _scanState.value = ScanState.Scanning(index + 1, totalCount, info.name)

                    val cached = cachedFiles[info.path]
                    filesToRemoveFromDb.remove(info.path)

                    if (cached != null && cached.lastModified == info.lastModified && cached.fileSize == info.size) {
                        // File hasn't changed, reuse its SHA-256
                        updatedList.add(cached)
                    } else {
                        // File is new or modified, generate hash
                        val hash = calculateSHA256(File(info.path))
                        updatedList.add(
                            AudioFile(
                                filePath = info.path,
                                fileName = info.name,
                                fileSize = info.size,
                                duration = info.duration,
                                sha256Hash = hash,
                                lastModified = info.lastModified
                            )
                        )
                    }

                    // Bulk save periodically to prevent memory bloating and lock issues
                    if (updatedList.size >= 100) {
                        audioDao.insertAll(updatedList)
                        updatedList.clear()
                    }
                }

                // Insert the remaining items
                if (updatedList.isNotEmpty()) {
                    audioDao.insertAll(updatedList)
                }

                // Remove files from DB that are no longer present on device
                for (path in filesToRemoveFromDb) {
                    audioDao.deleteByPath(path)
                }

                // Query duplicates count
                val duplicates = audioDao.getDuplicateFiles()
                val uniqueHashes = duplicates.map { it.sha256Hash }.distinct().size

                _scanState.value = ScanState.Success(totalCount, duplicates.size - uniqueHashes)

            } catch (e: Exception) {
                Log.e("AudioRepository", "Error scanning files", e)
                _scanState.value = ScanState.Error(e.localizedMessage ?: "Unknown scanning error")
            }
        }
    }

    private fun calculateSHA256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(16384) // 16KB buffer
                var bytesRead = fis.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = fis.read(buffer)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("AudioRepository", "Hashing error: ${file.path}", e)
            ""
        }
    }

    private data class TempAudioInfo(
        val path: String,
        val name: String,
        val size: Long,
        val duration: Long,
        val lastModified: Long
    )
}
