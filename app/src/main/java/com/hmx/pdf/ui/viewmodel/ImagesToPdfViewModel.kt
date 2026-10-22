package com.hmx.pdf.ui.viewmodel

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmx.pdf.data.repository.GitHubStarPromptManager
import com.hmx.pdf.data.repository.RecentFile
import com.hmx.pdf.data.repository.RecentFilesManager
import com.hmx.pdf.data.repository.SaveLocationManager
import com.hmx.pdf.R
import com.hmx.pdf.ui.utils.StarPromptEventBus
import com.kyant.pdfcore.converter.PdfConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One selected image plus its non-destructive quarter-turn rotation for this session. */
data class SelectedImage(
    val uri: Uri,
    val rotationQuarterTurns: Int = 0
)

/** Keep rotation in 0..3 so repeated turns can never accumulate into an invalid angle. */
internal fun normalizeQuarterTurns(turns: Int): Int = ((turns % 4) + 4) % 4

internal fun rotateQuarterTurns(turns: Int, delta: Int): Int = normalizeQuarterTurns(turns + delta)

/** Returns a copy with one entry rotated; duplicate URIs stay independent because this is index-based. */
internal fun rotateQuarterTurnsAt(rotations: List<Int>, index: Int, delta: Int): List<Int> {
    if (index !in rotations.indices) return rotations
    return rotations.toMutableList().also { it[index] = rotateQuarterTurns(it[index], delta) }
}

/** Keeps the viewer page valid when its item is removed or the list becomes empty. */
internal fun viewerIndexAfterRemoval(current: Int, removed: Int, newSize: Int): Int {
    if (current < 0 || newSize <= 0) return -1
    if (current == removed) return -1
    val shifted = if (current > removed) current - 1 else current
    return shifted.coerceIn(0, newSize - 1)
}

data class ImagesToPdfUiState(
    val images: List<SelectedImage> = emptyList(),
    val fitToA4: Boolean = true,
    val isSaving: Boolean = false,
    val resultMessage: String? = null,
    val errorMessage: String? = null,
    val lastOutputUri: Uri? = null,
    val saveLocationLabel: String = SaveLocationManager.DEFAULT_DOWNLOAD_LABEL
)

class ImagesToPdfViewModel(private val converter: PdfConverter) : ViewModel() {
    private val _uiState = MutableStateFlow(ImagesToPdfUiState())
    val uiState: StateFlow<ImagesToPdfUiState> = _uiState.asStateFlow()

    fun onPickImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            images = _uiState.value.images + uris.map { SelectedImage(it) },
            resultMessage = null,
            errorMessage = null
        )
    }

    fun removeImage(index: Int) {
        val list = _uiState.value.images.toMutableList()
        if (index in list.indices) list.removeAt(index)
        _uiState.value = _uiState.value.copy(images = list)
    }

    fun clearImages() {
        _uiState.value = _uiState.value.copy(images = emptyList(), resultMessage = null, errorMessage = null)
    }

    /** Applies the viewer's draft rotations after Done; order and URI identity are preserved. */
    fun applyRotations(rotations: List<Int>) {
        val images = _uiState.value.images
        if (rotations.size != images.size) return
        _uiState.value = _uiState.value.copy(
            images = images.mapIndexed { index, image ->
                image.copy(rotationQuarterTurns = normalizeQuarterTurns(rotations[index]))
            }
        )
    }

    fun setFitToA4(fit: Boolean) {
        _uiState.value = _uiState.value.copy(fitToA4 = fit)
    }

    fun save(context: Context, fileName: String, overrideUri: Uri? = null) {
        val images = _uiState.value.images
        if (images.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = context.getString(R.string.images_min_one))
            return
        }
        _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null, resultMessage = null, lastOutputUri = null)
        viewModelScope.launch {
            try {
                val target = if (fileName.endsWith(".pdf", true)) fileName else "$fileName.pdf"
                val saveLabel = SaveLocationManager.getSavePathDisplay(context)
                val outUri = withContext(Dispatchers.IO) { createOutputUri(context, target, overrideUri) }
                val document = withContext(Dispatchers.IO) {
                    converter.imagesToPdf(
                        context = context,
                        imageUris = images.map { it.uri },
                        outputUri = outUri,
                        fitToA4 = _uiState.value.fitToA4,
                        rotationQuarterTurns = images.map { it.rotationQuarterTurns }
                    )
                }
                RecentFilesManager.addRecent(context, RecentFile(
                    name = target,
                    uriString = outUri.toString(),
                    timestamp = System.currentTimeMillis(),
                    pageCount = document.pageCount
                ))
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    lastOutputUri = outUri,
                    saveLocationLabel = saveLabel,
                    resultMessage = context.getString(R.string.images_success, document.pageCount, saveLabel)
                )
                if (GitHubStarPromptManager.recordSuccess(context)) StarPromptEventBus.requestPrompt()
            } catch (e: Exception) {
                GitHubStarPromptManager.recordFailure(context)
                _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = context.getString(R.string.conversion_failed))
            }
        }
    }

    fun clearFeedback() {
        _uiState.value = _uiState.value.copy(resultMessage = null, errorMessage = null)
    }

    private fun createOutputUri(context: Context, fileName: String, overrideUri: Uri?): Uri {
        val customUri = overrideUri ?: SaveLocationManager.getSaveUri(context)
        if (customUri != null) {
            runCatching {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, customUri)
                    ?.createFile("application/pdf", fileName)?.uri
            }.getOrNull()?.let { return it }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, SaveLocationManager.defaultDownloadRelativePath())
            }
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("Unable to create output in Downloads")
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, fileName)
            if (!file.exists()) file.createNewFile()
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }
    }
}
