package eu.kanade.tachiyomi.source.gdrive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Progress of the Google Drive files being fetched for reading, so the reader can show how far a
 * chapter is instead of a bare spinner. A chapter is a whole PDF or archive that has to be
 * downloaded before its pages exist.
 */
object GDriveDownloadProgress {

    data class Entry(
        val fileName: String,
        val stage: Stage,
        val bytesRead: Long,
        /** -1 when Google Drive did not send a size. */
        val totalBytes: Long,
    ) {
        /** 0..1, or null when the size is unknown. */
        val fraction: Float?
            get() = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    enum class Stage {
        CONNECTING,
        DOWNLOADING,
        PREPARING,
    }

    private val mutableActive = MutableStateFlow<Map<String, Entry>>(emptyMap())

    /** Files in flight, keyed by Drive file id, in the order they started. */
    val active: StateFlow<Map<String, Entry>> = mutableActive.asStateFlow()

    internal fun set(fileId: String, entry: Entry) {
        mutableActive.update { it + (fileId to entry) }
    }

    internal fun finish(fileId: String) {
        mutableActive.update { it - fileId }
    }
}
