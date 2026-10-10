package com.daymark.app.ui.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.export.PdfExportOptions
import com.daymark.app.export.PdfReportGenerator
import com.daymark.app.export.ReportDataBuilder
import com.daymark.app.security.AutoLockController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Where the preview stands. */
sealed interface ReportPreviewState {
    data object Building : ReportPreviewState
    data class Ready(val pageCount: Int) : ReportPreviewState
    data object Failed : ReportPreviewState
}

/**
 * Shows the report before it is saved, as the clinician will see it (#198).
 *
 * **One rendering.** The report is generated once, by the same [PdfReportGenerator] from the same
 * [ReportDataBuilder] data, into one file on the phone. The pages on screen are that file's pages,
 * drawn by Android's own PDF renderer, and saving copies that file's bytes to the place the person
 * chose. So what was looked at is what is saved, byte for byte; there is no second layout that could
 * drift from it, and no second build whose time stamp or data could differ.
 *
 * **The file stays on the phone and does not outlive the flow.** It is written to the app's own
 * cache under [PREVIEW_DIR], is deleted when this view model is cleared (saved, backed out of, or the
 * flow otherwise left), and the folder is emptied when a new preview starts, so a copy left behind by
 * a process that died mid-flow goes the next time a report is made.
 */
@HiltViewModel
class ReportPreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reportDataBuilder: ReportDataBuilder,
    private val pdfReportGenerator: PdfReportGenerator,
    private val autoLock: AutoLockController,
) : ViewModel() {

    private val _state = MutableStateFlow<ReportPreviewState>(ReportPreviewState.Building)
    val state: StateFlow<ReportPreviewState> = _state.asStateFlow()

    /** One message per save attempt; [saved] is true only when the bytes reached the chosen file. */
    data class SaveResult(val saved: Boolean, val message: String)

    private val _saves = MutableSharedFlow<SaveResult>(extraBufferCapacity = 1)
    val saves: SharedFlow<SaveResult> = _saves.asSharedFlow()

    private var file: File? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    /** PdfRenderer opens one page at a time and is not safe across threads. */
    private val pages = Mutex()

    private var built: PdfExportOptions? = null

    /** Builds the report for [options] once; a recomposition asking again changes nothing. */
    fun preview(options: PdfExportOptions) {
        if (built == options) return
        built = options
        _state.value = ReportPreviewState.Building
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    pages.withLock {
                        close()
                        val dir = File(context.cacheDir, PREVIEW_DIR)
                        dir.deleteRecursively()
                        dir.mkdirs()
                        val out = File(dir, "report.pdf")
                        val data = reportDataBuilder.build(options, System.currentTimeMillis())
                        out.outputStream().use { pdfReportGenerator.generate(data, options, it) }
                        file = out
                        val fd = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)
                        descriptor = fd
                        PdfRenderer(fd).also { renderer = it }.pageCount
                    }
                }
            }
            _state.value = result.fold({ ReportPreviewState.Ready(it) }, { ReportPreviewState.Failed })
        }
    }

    /**
     * Page [index] drawn [widthPx] wide on white paper, which is what the printed sheet is: the
     * renderer leaves unpainted areas transparent, and a page drawn over the app's own surface
     * would not be the page the clinician sees. Null if the report is gone.
     */
    suspend fun page(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        pages.withLock {
            val r = renderer ?: return@withLock null
            if (index !in 0 until r.pageCount) return@withLock null
            r.openPage(index).use { page ->
                val w = widthPx.coerceIn(1, MAX_WIDTH_PX)
                val h = (w.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    /** Call right before opening the file picker, so returning from it does not lock the app. */
    fun prepareForFilePicker() = autoLock.suppressNextBackgroundLock()

    /** Copies the previewed file, unchanged, to [uri]. */
    fun saveTo(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val source = file ?: error("The report is no longer ready")
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    } ?: error("Could not open file")
                }
            }
            _saves.tryEmit(
                result.fold(
                    { SaveResult(true, "PDF report saved") },
                    { SaveResult(false, "The report was not saved: ${it.message}") },
                ),
            )
        }
    }

    private fun close() {
        renderer?.close()
        renderer = null
        descriptor?.close()
        descriptor = null
        file?.delete()
        file = null
    }

    override fun onCleared() {
        // After any render still in flight, never during one, and off the main thread. The scope
        // is not the view model's, which is already cancelled by the time this runs.
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            pages.withLock {
                runCatching { close() }
                File(context.cacheDir, PREVIEW_DIR).deleteRecursively()
            }
        }
    }

    private companion object {
        const val PREVIEW_DIR = "report_preview"

        /** A ceiling on one page's bitmap, so a tablet's width cannot ask for an enormous one. */
        const val MAX_WIDTH_PX = 1600
    }
}
