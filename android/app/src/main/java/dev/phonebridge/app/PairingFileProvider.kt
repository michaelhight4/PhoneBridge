package dev.phonebridge.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves `.pbpair` files from the app's cache so the pairing screen can put one in the system share
 * sheet without exposing the rest of app storage. Exports are read-only and temporary; the cache is
 * pruned by Android.
 */
class PairingFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "dev.phonebridge.app.pairing"

        fun directory(context: android.content.Context): File =
            File(context.cacheDir, "pairing").apply { mkdirs() }

        fun uriFor(fileName: String): Uri = Uri.parse("content://$AUTHORITY/$fileName")
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/json"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("pairing exports are read-only")
        val name = uri.lastPathSegment ?: throw FileNotFoundException("missing file name")
        // Only plain file names: no separators, no traversal.
        if (name.contains('/') || name.contains('\\') || name.startsWith(".")) {
            throw FileNotFoundException("bad file name")
        }
        val file = File(directory(requireNotNull(context)), name)
        if (!file.isFile) throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
