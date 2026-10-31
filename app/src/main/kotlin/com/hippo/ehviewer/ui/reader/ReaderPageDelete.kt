package com.hippo.ehviewer.ui.reader

import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.gallery.ReaderOriginalFile
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.library.TransferControl
import com.hippo.ehviewer.library.TransferFile
import com.hippo.ehviewer.library.TransferLocation
import com.hippo.ehviewer.library.TrashFiles
import com.hippo.ehviewer.library.parentRelativeOfFile
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.webdav.WebDavCache
import java.io.File
import java.time.LocalDate
import okio.Path

/**
 * Moves the reader page's **original** network file into the share's dated `.trash`
 * folder, reusing the transfer panel's server-side rename path ([TrashFiles]) so this
 * is a reversible move rather than a destructive delete.
 *
 * Kept out of the reader composable so the browse listing invalidation and cache drops
 * stay in one place. Only the SMB / WebDAV folder loaders expose an
 * [ReaderOriginalFile]; archives, local folders and PDF sessions return null and keep
 * the delete button hidden.
 *
 * @return the entry name inside `.trash/<date>/`.
 */
internal suspend fun deleteReaderPageFile(original: ReaderOriginalFile): String = withIOContext {
    val remotePath = when (original) {
        is ReaderOriginalFile.Smb -> original.relativePath
        is ReaderOriginalFile.WebDav -> original.relativePath
    }.replace('\\', '/').trim('/')
    require(remotePath.isNotEmpty()) { "Empty reader page path" }
    val kind = when (original) {
        is ReaderOriginalFile.Smb -> ExplorerWindows.Kind.Smb
        is ReaderOriginalFile.WebDav -> ExplorerWindows.Kind.WebDav
    }
    val sourceId = when (original) {
        is ReaderOriginalFile.Smb -> original.sourceId
        is ReaderOriginalFile.WebDav -> original.sourceId
    }
    val file = TransferFile(
        source = TransferLocation(kind, sourceId, remotePath),
        relative = remotePath,
        directory = false,
        size = 0L,
        mtime = 0L,
    )
    val trashed = TrashFiles.move(file, LocalDate.now().toString(), TransferControl())
    // The reader page list is a snapshot from the folder listing; drop the parent listing
    // so going back to the browser shows the folder without the deleted file.
    val parent = parentRelativeOfFile(remotePath)
    when (kind) {
        ExplorerWindows.Kind.Smb -> {
            BrowseSession.invalidateSmbListing(sourceId, parent)
            BrowseSession.invalidateSmbListingsUnder(sourceId, remotePath)
            dropPageCaches(
                page = SmbCache.cachePathForRemoteFile(sourceId, remotePath),
                thumb = SmbCache.thumbCachePath(sourceId, remotePath),
                resolve = SmbCache::resolveReaderPath,
            )
        }
        ExplorerWindows.Kind.WebDav -> {
            BrowseSession.invalidateWebDavListing(sourceId, parent)
            BrowseSession.invalidateWebDavListingsUnder(sourceId, remotePath)
            dropPageCaches(
                page = WebDavCache.cachePathForRemoteFile(sourceId, remotePath),
                thumb = WebDavCache.thumbCachePath(sourceId, remotePath),
                resolve = WebDavCache::resolveReaderPath,
            )
        }
        // TrashFiles rejects local sources; the reader never exposes a local original.
        ExplorerWindows.Kind.Local -> Unit
    }
    trashed
}

/**
 * Best-effort cache drop after a confirmed trash move. The reader pages are a snapshot,
 * so without this a swipe back to the deleted index could paint a stale cached copy.
 */
private fun dropPageCaches(page: Path, thumb: Path, resolve: (Path) -> Path) {
    deleteQuietly(page)
    deleteQuietly(thumb)
    // HDR/RAW converted page next to the original cache entry, if any.
    deleteQuietly(resolve(page))
}

private fun deleteQuietly(path: Path) {
    runCatching { File(path.toString()).delete() }
}
