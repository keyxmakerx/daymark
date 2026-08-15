package com.daymark.app.data

/**
 * What a photo becomes when it enters Daymark, decided without Android.
 *
 * ## Why this file exists at all
 *
 * A photo picked from the gallery is the single richest piece of personal data this app can be
 * handed, and almost none of that richness is the picture. A JPEG straight off a phone camera
 * routinely carries GPS latitude/longitude to five decimal places, the exact capture timestamp with
 * a timezone offset, the device make/model/serial, lens and firmware identifiers, and — because
 * Android's share sheet re-encodes nothing — often the original filename with it.
 *
 * Daymark's position on the first of those is not a preference to be tuned. **There is no location
 * feature in this app, and there is not going to be one accidentally**: it would be an odd kind of
 * promise to make on the settings screen while silently filing the coordinates of the person's
 * bedroom in the journal, recoverable by anyone who later gets the backup file.
 *
 * ## The strip is already happening — that was the problem
 *
 * [PhotoStore] decodes to a `Bitmap` and re-encodes with `Bitmap.compress`, and a `Bitmap` holds
 * pixels and nothing else. So EXIF was already being dropped. But it was being dropped *as a side
 * effect of resizing*, with nothing anywhere saying so, and that is a worse position than not
 * having done it. The obvious optimisation — "we already have the bytes, why decode and re-encode,
 * just copy the file" — is a two-line change that a reasonable person would make on a Tuesday, it
 * makes the app faster and the photos sharper, and it silently reinstates every tag above.
 * `PhotoStoreSourceTest` exists to make that change fail with an explanation rather than pass.
 *
 * ## What discarding EXIF costs, and the bug it was already causing
 *
 * Orientation. Cameras do not rotate pixels; they store the sensor's landscape buffer and set
 * `Orientation` to say which way was up. `BitmapFactory` **does not apply that tag** — so a portrait
 * photo was being decoded as landscape pixels, re-encoded with the tag gone, and stored sideways
 * with no way to recover which way up it went. The person sees their photo rotated 90°.
 *
 * That is the one tag that has to survive, and it survives by being *applied to the pixels* rather
 * than carried alongside them: read the orientation, rotate the bitmap, write the upright result,
 * store no tag. [Transform] is that decision, and every one of the eight EXIF orientation values is
 * enumerated here rather than the usual three, because the mirrored ones come off front cameras and
 * screenshot tools and are not exotic.
 *
 * @see PhotoStore for the Android side that applies these decisions.
 */
object ImageStrip {

    /**
     * Longest edge of a stored photo, in pixels.
     *
     * Sized for what the photo is used for — a thumbnail in the timeline and a full-screen view on
     * a phone — and against what it costs, since photos are base64'd into the backup JSON and every
     * one of them is carried in full whenever the person exports. A 12-megapixel original is around
     * 4 MB; at 1600px it is nearer 400 KB, and after base64 that is the difference between a backup
     * a person can email and one they cannot.
     */
    const val MAX_DIMEN = 1600

    /**
     * JPEG quality. 85 is the conventional knee: the artefacts are not visible at this resolution
     * on a phone, and going higher grows the file faster than it improves the picture.
     */
    const val JPEG_QUALITY = 85

    /**
     * The `inSampleSize` to decode with — the largest power of two that does not take the longest
     * edge below [maxDimen].
     *
     * Power-of-two only, because that is the contract `BitmapFactory` honours: it rounds anything
     * else down to one and you silently get a full-size decode. Deliberately **under**-samples: the
     * result is between `maxDimen` and `2 × maxDimen`, and [scaleFor] finishes the job. Sampling
     * past the target to save one scale step is how you turn a 3300px photo into an 825px one.
     *
     * Returns 1 for degenerate input rather than throwing — a zero-dimension decode has already
     * failed by the time this is asked, and the caller checks for that.
     */
    fun sampleSizeFor(width: Int, height: Int, maxDimen: Int = MAX_DIMEN): Int {
        if (width <= 0 || height <= 0 || maxDimen <= 0) return 1
        val longest = maxOf(width, height)
        var sample = 1
        // Halve only while the *result* stays at or above the target.
        while (longest / (sample * 2) >= maxDimen) sample *= 2
        return sample
    }

    /**
     * The final scale factor for an already-sampled bitmap, or `null` when it is already small
     * enough and copying it would be pure waste.
     *
     * Without this step the stored size depends on how close the original happened to be to a power
     * of two: a 3200px photo landed at exactly 1600, and a 3300px photo — a barely bigger original —
     * landed at 825, a quarter of the pixels. Nobody could see why one of their photos was blurry.
     */
    fun scaleFor(width: Int, height: Int, maxDimen: Int = MAX_DIMEN): Scaled? {
        if (width <= 0 || height <= 0 || maxDimen <= 0) return null
        val longest = maxOf(width, height)
        if (longest <= maxDimen) return null
        val ratio = maxDimen.toDouble() / longest
        // Round rather than truncate, and never to zero: a 4000x11 panorama must keep its 11px edge
        // as at least 1px, because a bitmap with a zero dimension throws on creation.
        return Scaled(
            width = maxOf(1, Math.round(width * ratio).toInt()),
            height = maxOf(1, Math.round(height * ratio).toInt()),
        )
    }

    /** Target pixel size for the final scale step. */
    data class Scaled(val width: Int, val height: Int)

    /**
     * How to make the pixels upright, given an EXIF `Orientation` value.
     *
     * @param rotationDegrees clockwise rotation to apply, one of 0/90/180/270.
     * @param mirrorHorizontally whether to flip across the vertical axis **before** rotating.
     */
    data class Transform(val rotationDegrees: Int, val mirrorHorizontally: Boolean) {
        /** True when this transform is a no-op and the bitmap can be written as it decoded. */
        val isIdentity: Boolean get() = rotationDegrees == 0 && !mirrorHorizontally

        /** True when applying it swaps the image's width and height. */
        val swapsAxes: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270
    }

    /*
     * The EXIF orientation constants, spelled out rather than imported, so this file stays free of
     * Android and the values are visible to a reader. They are fixed by the TIFF specification and
     * `android.media.ExifInterface.ORIENTATION_*` carries exactly these numbers.
     */
    const val ORIENTATION_UNDEFINED = 0
    const val ORIENTATION_NORMAL = 1
    const val ORIENTATION_FLIP_HORIZONTAL = 2
    const val ORIENTATION_ROTATE_180 = 3
    const val ORIENTATION_FLIP_VERTICAL = 4
    const val ORIENTATION_TRANSPOSE = 5
    const val ORIENTATION_ROTATE_90 = 6
    const val ORIENTATION_TRANSVERSE = 7
    const val ORIENTATION_ROTATE_270 = 8

    /**
     * The transform that makes each orientation upright.
     *
     * All eight are handled. The four mirrored ones (2, 4, 5, 7) are not a curiosity — front-facing
     * cameras and some screenshot and scanning tools produce them — and treating them as "unknown,
     * leave it alone" gets you a mirrored photo, which is the one failure a person notices instantly
     * in a picture with any text in it.
     *
     * The two vertical-flip cases are expressed as *horizontal* mirror plus 180°, which is the same
     * thing and means the Android side only ever needs one flip axis:
     *
     * - `FLIP_VERTICAL` (4) = mirror horizontally, then rotate 180.
     * - `TRANSVERSE` (7) = mirror horizontally, then rotate 270.
     *
     * An unrecognised value — including [ORIENTATION_UNDEFINED], which is what a file with no EXIF
     * at all reports — maps to identity. That is the safe direction: the picture is stored as it
     * decoded. Guessing a rotation from image dimensions is the alternative and it is wrong often
     * enough to be worse than doing nothing.
     */
    fun transformFor(exifOrientation: Int): Transform = when (exifOrientation) {
        ORIENTATION_FLIP_HORIZONTAL -> Transform(0, mirrorHorizontally = true)
        ORIENTATION_ROTATE_180 -> Transform(180, mirrorHorizontally = false)
        ORIENTATION_FLIP_VERTICAL -> Transform(180, mirrorHorizontally = true)
        ORIENTATION_TRANSPOSE -> Transform(90, mirrorHorizontally = true)
        ORIENTATION_ROTATE_90 -> Transform(90, mirrorHorizontally = false)
        ORIENTATION_TRANSVERSE -> Transform(270, mirrorHorizontally = true)
        ORIENTATION_ROTATE_270 -> Transform(270, mirrorHorizontally = false)
        else -> Transform(0, mirrorHorizontally = false)
    }

    /**
     * Every EXIF orientation value that means something, for tests and for exhaustiveness.
     *
     * [ORIENTATION_UNDEFINED] is excluded: it is the absence of an answer, not one of the eight.
     */
    val ORIENTATIONS: List<Int> = (ORIENTATION_NORMAL..ORIENTATION_ROTATE_270).toList()

    /**
     * What the person is told, on the screen where they attach a photo.
     *
     * Kept next to the code that makes it true, and asserted against that code, because a privacy
     * claim in a string resource that drifts from the pipeline is worse than no claim: it is the
     * app telling someone their location was removed when it was not.
     *
     * Deliberately says *what happens to the file*, not "your privacy is protected" — the person
     * can check the first kind of statement and cannot check the second.
     */
    const val STRIP_NOTICE =
        "Photos are re-saved as plain images. Location, the capture time, and the camera that took " +
            "them are not kept — only the picture, rotated the right way up."
}
