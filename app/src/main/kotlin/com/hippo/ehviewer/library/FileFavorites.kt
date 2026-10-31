package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings
import kotlin.time.Clock

/**
 * Single-image favourites that live on SMB sources.
 *
 * Remote images never exist locally, so a favourite only stores a *pointer*:
 * `sourceId + path inside the share + when it was added`. Thumbnails are not stored here —
 * the favourites grid reuses the normal `smb_thumb_cache` via `BrowseCover.Smb`, so
 * offline items still show a cached cover when the NAS is unreachable.
 *
 * The entry set uses `\u0001` separators (never valid in SMB file names) so parsing stays
 * allocation-free, and the timestamp keeps a stable newest-first order because a
 * `Set<String>` has no ordering of its own.
 *
 * Reads take the [Set] explicitly so Compose callers can subscribe through
 * `Settings.favoriteFiles.collectAsState()`; [Settings] values are a snapshot, not a
 * Compose state.
 */
object FileFavorites {
    private const val PREFIX = "sfi"
    private const val SEP = '\u0001'
    private const val ID_SEP = '\u0002'

    data class FavoriteFile(
        val sourceId: Long,
        val remotePath: String,
        val addedAt: Long,
    ) {
        /** Cache key for list diffing: the same file re-added later is a new entry. */
        val key: String get() = "$sourceId$ID_SEP$remotePath$ID_SEP$addedAt"

        val fileName: String get() = remotePath.substringAfterLast('/')
        val parentDir: String get() = parentRelativeOfFile(remotePath)
    }

    fun normalize(remotePath: String): String = remotePath.replace('\\', '/').trim('/')

    /** New entry key; [addedAt] is overridable for tests / re-import. */
    fun entryKey(
        sourceId: Long,
        remotePath: String,
        addedAt: Long = Clock.System.now().toEpochMilliseconds(),
    ): String = listOf(PREFIX, addedAt.toString(), sourceId.toString(), normalize(remotePath))
        .joinToString(SEP.toString())

    fun parseEntry(key: String): FavoriteFile? {
        val parts = key.split(SEP, limit = 4)
        if (parts.size != 4 || parts[0] != PREFIX) return null
        val addedAt = parts[1].toLongOrNull() ?: return null
        val sourceId = parts[2].toLongOrNull() ?: return null
        val remotePath = normalize(parts[3])
        if (remotePath.isEmpty()) return null
        return FavoriteFile(sourceId, remotePath, addedAt)
    }

    /** Parsed snapshot, newest first, for the given DataStore set. */
    fun list(keys: Set<String> = Settings.favoriteFiles.value): List<FavoriteFile> = synchronized(this) {
        ensureParsed(keys)
        cachedFiles
    }

    fun isFavorite(keys: Set<String>, sourceId: Long, remotePath: String): Boolean = synchronized(this) {
        ensureParsed(keys)
        "${sourceId}$ID_SEP${normalize(remotePath)}" in cachedIds
    }

    /** Non-reactive convenience for background code; UI should pass [keys] explicitly. */
    fun isFavorite(sourceId: Long, remotePath: String): Boolean =
        isFavorite(Settings.favoriteFiles.value, sourceId, remotePath)

    /** @return true when the image is favourited afterwards. */
    fun toggle(sourceId: Long, remotePath: String): Boolean {
        val path = normalize(remotePath)
        if (path.isEmpty()) return false
        val cur = Settings.favoriteFiles.value
        val existing = cur.filter { entry ->
            parseEntry(entry)?.let { it.sourceId == sourceId && it.remotePath == path } == true
        }
        return if (existing.isEmpty()) {
            Settings.favoriteFiles.value = cur + entryKey(sourceId, path)
            true
        } else {
            Settings.favoriteFiles.value = cur - existing.toSet()
            false
        }
    }

    /** Drop every favourite of a removed SMB source (mirrors history cleanup). */
    fun removeSource(sourceId: Long) {
        val cur = Settings.favoriteFiles.value
        val next = cur.filter { parseEntry(it)?.sourceId != sourceId }.toSet()
        if (next.size != cur.size) Settings.favoriteFiles.value = next
    }

    private var cachedSource: Set<String>? = null
    private var cachedFiles: List<FavoriteFile> = emptyList()
    private var cachedIds: Set<String> = emptySet()

    /** Must be called under the object lock. Identity check: DataStore emits new sets. */
    private fun ensureParsed(keys: Set<String>) {
        if (cachedSource === keys) return
        cachedSource = keys
        val parsed = ArrayList<FavoriteFile>(keys.size)
        val ids = HashSet<String>(keys.size)
        for (entry in keys) {
            val favorite = parseEntry(entry) ?: continue
            parsed += favorite
            ids += "${favorite.sourceId}$ID_SEP${favorite.remotePath}"
        }
        parsed.sortByDescending { it.addedAt }
        cachedFiles = parsed
        cachedIds = ids
    }
}
