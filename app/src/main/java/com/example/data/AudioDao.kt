package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioDao {
    @Query("SELECT * FROM audio_files")
    fun getAllAudioFilesFlow(): Flow<List<AudioFile>>

    @Query("SELECT * FROM audio_files")
    suspend fun getAllAudioFiles(): List<AudioFile>

    @Query("SELECT * FROM audio_files WHERE sha256Hash IN (SELECT sha256Hash FROM audio_files GROUP BY sha256Hash HAVING COUNT(*) > 1) ORDER BY sha256Hash, id ASC")
    fun getDuplicateFilesFlow(): Flow<List<AudioFile>>

    @Query("SELECT * FROM audio_files WHERE sha256Hash IN (SELECT sha256Hash FROM audio_files GROUP BY sha256Hash HAVING COUNT(*) > 1) ORDER BY sha256Hash, id ASC")
    suspend fun getDuplicateFiles(): List<AudioFile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<AudioFile>)

    @Query("DELETE FROM audio_files WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM audio_files WHERE filePath = :filePath")
    suspend fun deleteByPath(filePath: String)

    @Query("DELETE FROM audio_files")
    suspend fun clearAll()
}
