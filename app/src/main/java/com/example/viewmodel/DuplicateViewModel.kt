package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AudioFile
import com.example.data.AudioRepository
import com.example.data.ScanState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SortBy {
    SIZE, COPIES
}

enum class SortOrder {
    ASCENDING, DESCENDING
}

data class DuplicateGroup(
    val sha256Hash: String,
    val files: List<AudioFile>,
    val totalSize: Long,
    val wastedSize: Long
)

class DuplicateViewModel(private val repository: AudioRepository) : ViewModel() {

    val scanState: StateFlow<ScanState> = repository.scanState
    val allAudioFiles: StateFlow<List<AudioFile>> = repository.allAudioFiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _sortBy = MutableStateFlow(SortBy.SIZE)
    val sortBy: StateFlow<SortBy> = _sortBy

    private val _sortOrder = MutableStateFlow(SortOrder.DESCENDING)
    val sortOrder: StateFlow<SortOrder> = _sortOrder

    private val _selectedHash = MutableStateFlow<String?>(null)
    val selectedHash: StateFlow<String?> = _selectedHash

    // Set of AudioFile IDs selected for deletion
    private val _selectedFileIdsForDeletion = MutableStateFlow<Set<Long>>(emptySet())
    val selectedFileIdsForDeletion: StateFlow<Set<Long>> = _selectedFileIdsForDeletion

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting

    // List of all duplicate groups, filtered and sorted
    val duplicateGroups: StateFlow<List<DuplicateGroup>> = combine(
        repository.duplicateFiles,
        _searchQuery,
        _sortBy,
        _sortOrder
    ) { files, query, sort, order ->
        val groups = files.groupBy { it.sha256Hash }
            .map { (hash, groupFiles) ->
                // Sort files inside group by id (stable addition order) or file name
                val sortedGroupFiles = groupFiles.sortedBy { it.id }
                val firstCopySize = sortedGroupFiles.firstOrNull()?.fileSize ?: 0L
                val totalSize = sortedGroupFiles.sumOf { it.fileSize }
                val wastedSize = maxOf(0L, totalSize - firstCopySize)
                DuplicateGroup(hash, sortedGroupFiles, totalSize, wastedSize)
            }
            .filter { group ->
                query.isEmpty() || group.files.any { it.fileName.contains(query, ignoreCase = true) }
            }

        val sortedGroups = when (sort) {
            SortBy.SIZE -> groups.sortedByDescending { it.wastedSize }
            SortBy.COPIES -> groups.sortedByDescending { it.files.size }
        }

        if (order == SortOrder.ASCENDING) {
            sortedGroups.reversed()
        } else {
            sortedGroups
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Currently selected group details
    val selectedGroup: StateFlow<DuplicateGroup?> = combine(
        duplicateGroups,
        _selectedHash
    ) { groups, hash ->
        if (hash == null) null else groups.find { it.sha256Hash == hash }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateSortBy(sort: SortBy) {
        _sortBy.value = sort
    }

    fun toggleSortOrder() {
        _sortOrder.value = if (_sortOrder.value == SortOrder.ASCENDING) {
            SortOrder.DESCENDING
        } else {
            SortOrder.ASCENDING
        }
    }

    fun selectGroup(hash: String?) {
        _selectedHash.value = hash
        // Reset selected deletion IDs when changing group to prevent accidental multi-group deletions
        _selectedFileIdsForDeletion.value = emptySet()
        
        // Auto-select duplicates except the first copy (smart cleanup assistant default)
        if (hash != null) {
            viewModelScope.launch {
                duplicateGroups.value.find { it.sha256Hash == hash }?.let { group ->
                    selectDuplicatesExceptOne(group)
                }
            }
        }
    }

    fun toggleFileSelection(fileId: Long) {
        val current = _selectedFileIdsForDeletion.value.toMutableSet()
        if (current.contains(fileId)) {
            current.remove(fileId)
        } else {
            current.add(fileId)
        }
        _selectedFileIdsForDeletion.value = current
    }

    fun selectDuplicatesExceptOne(group: DuplicateGroup) {
        if (group.files.size <= 1) return
        // Keep the first file (index 0) and select the others
        val duplicates = group.files.drop(1).map { it.id }.toSet()
        _selectedFileIdsForDeletion.value = duplicates
    }

    fun clearSelections() {
        _selectedFileIdsForDeletion.value = emptySet()
    }

    fun startScan() {
        viewModelScope.launch {
            repository.scanAudioFiles()
        }
    }

    fun deleteSelectedFiles(onComplete: (Int) -> Unit) {
        viewModelScope.launch {
            _isDeleting.value = true
            val fileIdsToDelete = _selectedFileIdsForDeletion.value
            var successCount = 0

            // Retrieve all audio files to match IDs
            val currentGroup = selectedGroup.value
            if (currentGroup != null) {
                val filesToDelete = currentGroup.files.filter { fileIdsToDelete.contains(it.id) }
                for (audioFile in filesToDelete) {
                    val deleted = repository.deleteFile(audioFile)
                    if (deleted) successCount++
                }
            }

            _selectedFileIdsForDeletion.value = emptySet()
            _isDeleting.value = false
            onComplete(successCount)
        }
    }
}

class DuplicateViewModelFactory(private val repository: AudioRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DuplicateViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DuplicateViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
