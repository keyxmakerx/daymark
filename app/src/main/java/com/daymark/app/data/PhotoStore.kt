package com.daymark.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores entry photos as JPEGs in the app's private storage (`filesDir/entry_photos`). Nothing
 * here ever leaves the device — the directory is inside the app sandbox and excluded from cloud
 * backup (`allowBackup="false"`). The database holds only a relative filename; this class maps it
 * to a [File].
 *
 * ## Every photo is re-encoded from pixels, and that is load-bearing
 *
 * Nothing here ever copies image bytes from a source into storage. Both entry points —
 * [copyFromUri] for a picked photo and [writeBytes] for one arriving in a backup — decode to a
 * [Bitmap] and re-encode with [Bitmap.compress]. A `Bitmap` is pixels; it has nowhere to keep the
 * GPS coordinates, capture timestamp, device serial or original filename that a camera JPEG carries,
 * so they are gone by construction rather than by a filter that has to enumerate them correctly.
 *
 * The reasoning, the cost, and the orientation bug this used to cause are in [ImageStrip]'s
 * documentation. `PhotoStoreSourceTest` fails the build if a byte-copy shortcut appears here.
 */
@Singleton
class PhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File by lazy { File(context.filesDir, DIR).apply { mkdirs() } }

    /**
     * Resolves a stored photo name to a file, or **null** if the name is not one we could have
     * written.
     *
     * Nullable on purpose. The previous signature returned a `File` unconditionally, which made
     * "this name is not safe" unrepresentable and pushed the decision onto every caller — and every
     * caller skipped it, because a non-null `File` looks like a settled answer.
     */
    fun fileFor(relPath: String): File? = if (isSafeName(relPath)) File(dir, relPath) else null

    /**
     * Guards against path traversal: photo names come from our own UUIDs, but on import they
     * arrive as untrusted JSON map keys. Only accept a bare `<uuid>.jpg` filename — never a path.
     *
     * ## Every door, not most of them
     *
     * This guard existed and was applied to [readBytes], [writeBytes] and [exists] — but not to
     * [delete] and not to either `fileFor`, and a partial guard on a path-traversal defence is
     * worth approximately nothing. A backup carrying
     * `photoPath: "../../databases/daymark.db"` and no matching entry in its `photos` map never
     * touched the guarded [writeBytes] door at all: `BackupManager` wrote the string straight onto
     * the entry, and the first ordinary swipe-delete of that entry resolved it to
     * `filesDir/../databases/daymark.db` and unlinked the person's entire journal — silently,
     * because the failure is swallowed by `runCatching`, and unrecoverably, because
     * `allowBackup="false"` means there is no OS copy.
     *
     * The lesson is in where the hole was: the doc comment above already said import names are
     * untrusted. Knowing it was never the problem. Applying it at four of six doors was.
     */
    private fun isSafeName(relPath: String): Boolean = ImageStrip.isStoredPhotoName(relPath)

    /**
     * Imports the picked image into private storage — downscaled, turned the right way up, and with
     * every camera tag dropped — returning the new relative filename. Throws if it can't be read.
     */
    fun copyFromUri(uri: Uri): String {
        val name = "${UUID.randomUUID()}.jpg"
        val orientation = readOrientation(uri)
        val bitmap = decodeDownsampled(uri) ?: error("Could not read the selected image")
        writeStripped(bitmap, orientation, File(dir, name))
        return name
    }

    /**
     * Deletes a stored photo by relative filename. Safe to call with a null, missing, or hostile
     * path — an unsafe name deletes nothing.
     *
     * This is the sink that made the traversal bug destructive rather than merely ugly: it is
     * reached from an ordinary swipe-delete once the undo window closes
     * ([com.daymark.app.ui.entry.EntryActionsViewModel]), which is about as far from a suspicious
     * action as it gets.
     */
    fun delete(relPath: String?) {
        if (relPath.isNullOrEmpty() || !isSafeName(relPath)) return
        runCatching { File(dir, relPath).delete() }
    }

    /** Raw bytes of a stored photo (for embedding in a backup), or null if it's gone. */
    fun readBytes(relPath: String): ByteArray? =
        if (!isSafeName(relPath)) null else runCatching { File(dir, relPath).readBytes() }.getOrNull()

    /**
     * Stores a photo arriving in a backup. Ignores unsafe names.
     *
     * **Re-encoded, not written through.** A backup file is not trusted input: it may have been
     * hand-edited, merged from another install, or produced by a version of this app that predates
     * the strip — and a JPEG with intact GPS entering by this door is the same leak as one entering
     * by the picker, arriving through the path nobody thinks to look at. The strip is applied on the
     * way *in* rather than only on the way out, because the file is going to sit in storage until
     * the person deletes the entry, and by then the next backup has carried it onward.
     *
     * Costs a generation of JPEG quality on every restore. That is the right trade: the alternative
     * is an import path whose privacy depends on which version wrote the file.
     *
     * Undecodable bytes are dropped rather than stored. A file that is not an image cannot be shown
     * as one, and writing it through would only be preserving something unusable — with whatever it
     * actually contains left sitting in the photo directory.
     */
    fun writeBytes(relPath: String, bytes: ByteArray) {
        if (!isSafeName(relPath)) return
        val orientation = runCatching {
            bytes.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, DEFAULT_ORIENTATION) }
        }.getOrDefault(DEFAULT_ORIENTATION)
        val bitmap = decodeDownsampled { bytes.inputStream() } ?: return
        writeStripped(bitmap, orientation, File(dir, relPath))
    }

    /** True if a stored photo with this filename already exists. */
    fun exists(relPath: String): Boolean = isSafeName(relPath) && File(dir, relPath).exists()

    /** A fresh unique filename, used when a backup-supplied name would collide on merge. */
    fun freshName(): String = "${UUID.randomUUID()}.jpg"

    /** Removes every stored photo (used by a REPLACE import before restoring the backup's photos). */
    fun clearAll() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    /**
     * The `Orientation` tag, read before decoding because decoding is what destroys it.
     *
     * Failure is [ImageStrip.ORIENTATION_UNDEFINED] — store the pixels as they came. A file with no
     * EXIF, an unreadable stream, and a format the platform parser does not know all land here, and
     * all three mean the same thing: nothing here knows which way is up, so nothing here should turn
     * the picture.
     *
     * One honest limitation: `android.media.ExifInterface` only reads HEIF from API 28, so on API
     * 26–27 a HEIC photo's orientation is unavailable and it is stored as it decodes. It is still
     * fully stripped — that is the decode/re-encode, which does not depend on this — so the failure
     * is a sideways picture on two old API levels, never a leaked coordinate.
     */
    private fun readOrientation(uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, DEFAULT_ORIENTATION)
        } ?: DEFAULT_ORIENTATION
    }.getOrDefault(DEFAULT_ORIENTATION)

    /**
     * Decodes at a size near [ImageStrip.MAX_DIMEN], in two passes: bounds first, then the real
     * decode at the sample size those bounds imply.
     *
     * Takes a stream *factory* rather than a stream because both passes consume one and an
     * `InputStream` from a `ContentResolver` is not reliably resettable.
     */
    private fun decodeDownsampled(open: () -> java.io.InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = ImageStrip.sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        return open()?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun decodeDownsampled(uri: Uri): Bitmap? =
        decodeDownsampled { context.contentResolver.openInputStream(uri) }

    /**
     * Scales to the target, applies the orientation to the pixels, and writes a plain JPEG.
     *
     * Order matters: scale first so the rotation works on the smaller bitmap, and rotate last so the
     * scale can be computed from the decoded dimensions without having to reason about whether the
     * axes are about to swap.
     *
     * Every intermediate is recycled as soon as it is superseded. Photo import is the one place in
     * this app that allocates tens of megabytes at once, and on a low-memory device the difference
     * between recycling and waiting for the collector is whether a person's photo attaches or the
     * app dies holding it.
     */
    private fun writeStripped(decoded: Bitmap, exifOrientation: Int, file: File) {
        var current = decoded
        try {
            ImageStrip.scaleFor(current.width, current.height)?.let { target ->
                val scaled = Bitmap.createScaledBitmap(current, target.width, target.height, true)
                if (scaled !== current) {
                    current.recycle()
                    current = scaled
                }
            }
            val transform = ImageStrip.transformFor(exifOrientation)
            if (!transform.isIdentity) {
                val matrix = Matrix().apply {
                    // Mirror before rotating — the same order ImageStrip.transformFor assumes when
                    // it folds the two vertical-flip orientations into a horizontal one.
                    if (transform.mirrorHorizontally) postScale(-1f, 1f)
                    if (transform.rotationDegrees != 0) postRotate(transform.rotationDegrees.toFloat())
                }
                val upright = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
                if (upright !== current) {
                    current.recycle()
                    current = upright
                }
            }
            file.outputStream().use {
                current.compress(Bitmap.CompressFormat.JPEG, ImageStrip.JPEG_QUALITY, it)
            }
        } finally {
            current.recycle()
        }
    }

    companion object {
        const val DIR = "entry_photos"
        /** What an unreadable or absent orientation tag counts as: don't turn the picture. */
        private const val DEFAULT_ORIENTATION = ImageStrip.ORIENTATION_UNDEFINED

        /**
         * Is this a name this app could have written? Every door in this class asks before opening.
         *
         * Public and in the companion so the *boundary* can ask too. Depending on the sinks alone
         * would mean a hostile path is allowed to sit in the database until something dereferences
         * it — safe only for exactly as long as nobody adds a seventh door.
         */
        fun isSafeName(relPath: String): Boolean = ImageStrip.isStoredPhotoName(relPath)

        /**
         * Resolves a relative photo filename to a [File] without needing the injected store, or
         * null if the name is not one we could have written.
         */
        fun fileFor(context: Context, relPath: String): File? =
            if (isSafeName(relPath)) File(File(context.filesDir, DIR), relPath) else null
    }
}
