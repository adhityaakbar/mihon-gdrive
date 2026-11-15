package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.gdrive.GDriveDownloadProgress
import java.util.Locale

/**
 * Full-screen loading state for the reader that shows how far a Google Drive chapter has been
 * fetched, falling back to a plain spinner for everything else.
 */
@Composable
fun ReaderLoadingScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            GDriveLoadingProgress()
        }
    }
}

/**
 * The newest Google Drive file being fetched: a progress bar plus downloaded size and percentage.
 * Shows nothing when no Drive download is running.
 */
@Composable
fun GDriveLoadingProgress(modifier: Modifier = Modifier) {
    val active by GDriveDownloadProgress.active.collectAsState()
    val entry = active.values.lastOrNull() ?: return

    Column(
        modifier = modifier
            .width(280.dp)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val fraction = entry.fraction
        if (entry.stage == GDriveDownloadProgress.Stage.DOWNLOADING && fraction != null) {
            LinearProgressIndicator(progress = { fraction })
        } else {
            LinearProgressIndicator()
        }
        Text(
            text = statusText(entry),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = entry.fileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

private fun statusText(entry: GDriveDownloadProgress.Entry): String = when (entry.stage) {
    GDriveDownloadProgress.Stage.CONNECTING -> "Menghubungi Google Drive…"
    GDriveDownloadProgress.Stage.PREPARING -> "Menyiapkan halaman…"
    GDriveDownloadProgress.Stage.DOWNLOADING -> {
        val read = megabytes(entry.bytesRead)
        val fraction = entry.fraction
        if (fraction != null) {
            "Mengunduh $read / ${megabytes(entry.totalBytes)} MB (${(fraction * 100).toInt()}%)"
        } else {
            "Mengunduh $read MB"
        }
    }
}

private fun megabytes(bytes: Long): String =
    String.format(Locale.forLanguageTag("id"), "%.1f", bytes / (1024.0 * 1024.0))
