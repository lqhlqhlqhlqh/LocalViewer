package com.hippo.ehviewer.ui.reader

import com.hippo.ehviewer.gallery.ReaderOriginalFile
import com.hippo.ehviewer.gallery.ReaderSession
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.library.TransferControl
import com.hippo.ehviewer.library.TransferFile
import com.hippo.ehviewer.library.TransferLocation
import com.hippo.ehviewer.library.TrashFiles
import com.hippo.ehviewer.library.canTrash
import java.time.LocalDate

// Direct delete from the reader: one tap moves the original network file into the
// share's dated `.trash` folder, exactly like the folder browser's delete job.

/** Original standalone network file behind [index]; archive members have no delete target. */
internal fun readerDeleteTarget(pageLoader: ReaderSession, index: Int): TransferFile? {
    if (index !in 0 until pageLoader.size) return null
    val location = when (val original = pageLoader.originalFile(index)) {
        is ReaderOriginalFile.Smb -> TransferLocation(ExplorerWindows.Kind.Smb, original.sourceId, original.relativePath)
        is ReaderOriginalFile.WebDav -> TransferLocation(ExplorerWindows.Kind.WebDav, original.sourceId, original.relativePath)
        null -> return null
    }
    // Never offer a move for `.trash` descendants or archive members.
    if (!canTrash(location)) return null
    return TransferFile(
        source = location,
        relative = location.path.substringAfterLast('/'),
        directory = false,
        size = 0,
        mtime = 0,
    )
}

/** Same destination, collision suffixes and browse-cache invalidation as [TrashFiles]. */
internal suspend fun moveReaderFileToTrash(file: TransferFile): String {
    val name = TrashFiles.move(file, LocalDate.now().toString(), TransferControl())
    val parent = file.source.copy(path = file.source.path.substringBeforeLast('/', ""))
    when (file.source.kind) {
        ExplorerWindows.Kind.Smb -> {
            BrowseSession.invalidateSmbListing(file.source.sourceId, parent.path)
            BrowseSession.invalidateSmbListingsUnder(file.source.sourceId, file.source.path)
        }
        ExplorerWindows.Kind.WebDav -> {
            BrowseSession.invalidateWebDavListing(file.source.sourceId, parent.path)
            BrowseSession.invalidateWebDavListingsUnder(file.source.sourceId, file.source.path)
        }
        ExplorerWindows.Kind.Local -> BrowseSession.invalidateLocalListing()
    }
    return name
}
