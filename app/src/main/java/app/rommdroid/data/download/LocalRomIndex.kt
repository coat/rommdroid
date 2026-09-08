package app.rommdroid.data.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import app.rommdroid.data.repository.DownloadTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What one download folder already holds. [readable] is false only when the
 * folder could not be listed at all, kept distinct from "listed, and empty" so
 * a revoked grant does not read as a missing library.
 */
class FolderContents private constructor(
    val readable: Boolean,
    /** Lower-cased file name -> size in bytes. */
    private val sizesByName: Map<String, Long>,
) {
    /** Size of [fileName] as it sits on disk, or null when it is not there. */
    fun sizeOf(fileName: String): Long? = sizesByName[fileName.lowercase()]

    fun contains(fileName: String): Boolean = sizeOf(fileName) != null

    companion object {
        /** The folder could not be read, so nothing can be said about it. */
        val Unreadable = FolderContents(readable = false, sizesByName = emptyMap())

        fun of(sizes: Map<String, Long>) =
            FolderContents(true, sizes.mapKeys { (name, _) -> name.lowercase() })
    }
}

/**
 * The ROMs the user already has on disk. The queue only knows transfers this
 * install performed, so a library copied from a PC or kept across a reinstall
 * reads as never downloaded; the folder is the authority.
 *
 * Listings are cached per folder, keyed by the target, so re-pointing a platform
 * reads the new directory without an explicit [invalidate] - only a change to a
 * folder's contents needs one.
 */
@Singleton
class LocalRomIndex @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val cache = ConcurrentHashMap<String, FolderContents>()

    private val _revision = MutableStateFlow(0)

    /** Bumped when the folders may have changed, so collectors re-read a listing
     *  rather than polling the filesystem. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Contents of [target]'s folder, read once and then cached. */
    suspend fun listing(target: DownloadTarget): FolderContents {
        val key = cacheKey(target)
        cache[key]?.let { return it }
        // A race here costs one extra cursor query and settles the same way.
        val contents = withContext(Dispatchers.IO) { read(target) }
        cache[key] = contents
        return contents
    }

    /** Forget every listing, e.g. because a download just landed in one. */
    fun invalidate() {
        cache.clear()
        _revision.update { it + 1 }
    }

    private fun cacheKey(target: DownloadTarget) = "${target.treeUri}|${target.subfolder.orEmpty()}"

    private fun read(target: DownloadTarget): FolderContents {
        val tree = target.treeUri.toUri()
        return try {
            var documentId = DocumentsContract.getTreeDocumentId(tree)
            for (segment in target.subfolder.orEmpty().split('/')) {
                if (segment.isBlank()) continue
                // No subfolder yet: the first download creates it.
                documentId = childDirectoryId(tree, documentId, segment)
                    ?: return FolderContents.of(emptyMap())
            }
            FolderContents.of(fileSizes(tree, documentId))
        } catch (e: Exception) {
            Log.w(TAG, "Could not read ${target.displayPath}: ${e.message}")
            FolderContents.Unreadable
        }
    }

    /** File name -> size for every non-directory child of [parentId]. Through
     *  [DocumentsContract] rather than `DocumentFile.listFiles()`, which
     *  allocates an object per row over a folder of thousands. */
    private fun fileSizes(tree: Uri, parentId: String): Map<String, Long> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val sizes = HashMap<String, Long>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(0) ?: continue
                sizes[name] = if (cursor.isNull(1)) 0L else cursor.getLong(1)
            }
        }
        return sizes
    }

    /** Document id of the subdirectory named [name], or null when absent. */
    private fun childDirectoryId(tree: Uri, parentId: String, name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) continue
                // SD cards are usually exFAT, where "SNES" and "snes" are one
                // directory.
                if (cursor.getString(1).equals(name, ignoreCase = true)) return cursor.getString(0)
            }
        }
        return null
    }

    private companion object {
        const val TAG = "LocalRomIndex"
    }
}
