package com.daymark.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Stores entry photos as JPEGs in the app's private storage (`filesDir/entry_photos`). Nothing
 * here ever leaves the device — the directory is inside the app sandbox and excluded from cloud
 * backup (`allowBackup="false"`). The database holds only a relative filename; this class maps it
 * to a [File]. Images are downscaled on import so a backup of many photos stays reasonable.
 */
@Singleton
class PhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File by lazy { File(context.filesDir, DIR).apply { mkdirs() } }

    fun fileFor(relPath: String): File = File(dir, relPath)

    /**
     * Copies the picked image into private storage (downscaled), returning the new relative
     * filename. Throws if the source can't be read.
     */
    fun copyFromUri(uri: Uri): String {
        val name = "${UUID.randomUUID()}.jpg"
        val bitmap = decodeDownsampled(uri) ?: error("Could not read the selected image")
        try {
            writeJpeg(bitmap, File(dir, name))
        } finally {
            bitmap.recycle()
        }
        return name
    }

    /** Deletes a stored photo by relative filename. Safe to call with a null/missing path. */
    fun delete(relPath: String?) {
        if (relPath.isNullOrEmpty()) return
        runCatching { File(dir, relPath).delete() }
    }

    /** Raw bytes of a stored photo (for embedding in a backup), or null if it's gone. */
    fun readBytes(relPath: String): ByteArray? =
        runCatching { File(dir, relPath).readBytes() }.getOrNull()

    /** Writes bytes for a relative filename (restoring from a backup). */
    fun writeBytes(relPath: String, bytes: ByteArray) {
        File(dir, relPath).writeBytes(bytes)
    }

    /** True if a stored photo with this filename already exists. */
    fun exists(relPath: String): Boolean = File(dir, relPath).exists()

    /** A fresh unique filename, used when a backup-supplied name would collide on merge. */
    fun freshName(): String = "${UUID.randomUUID()}.jpg"

    /** Removes every stored photo (used by a REPLACE import before restoring the backup's photos). */
    fun clearAll() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun decodeDownsampled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > MAX_DIMEN) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
    }

    companion object {
        const val DIR = "entry_photos"
        /** Longest edge after downscaling; plenty for a journal thumbnail and full-view. */
        private const val MAX_DIMEN = 1600
        private const val JPEG_QUALITY = 85

        /** Resolves a relative photo filename to a [File] without needing the injected store. */
        fun fileFor(context: Context, relPath: String): File =
            File(File(context.filesDir, DIR), relPath)
    }
}
