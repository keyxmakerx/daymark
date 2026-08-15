package com.daymark.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photo-import decisions, executed.
 *
 * All of this used to live inside [PhotoStore] where it could only run on a device, which is how a
 * sampling bug that quartered the pixels of some photos, and an orientation bug that stored portrait
 * photos sideways, both survived in shipped code. Nothing here touches Android.
 */
class ImageStripTest {

    // ---------------------------------------------------------------------------------------
    // Sample size
    // ---------------------------------------------------------------------------------------

    @Test
    fun `never samples an image that is already small enough`() {
        assertEquals(1, ImageStrip.sampleSizeFor(800, 600))
        assertEquals(1, ImageStrip.sampleSizeFor(1600, 1200))
        // Just over: still 1, because halving would take it to 900 — below the target.
        assertEquals(1, ImageStrip.sampleSizeFor(1800, 1200))
    }

    @Test
    fun `samples only while the result stays at or above the target`() {
        assertEquals(2, ImageStrip.sampleSizeFor(3200, 2400))
        assertEquals(2, ImageStrip.sampleSizeFor(3300, 2475))
        assertEquals(4, ImageStrip.sampleSizeFor(6400, 4800))
        assertEquals(8, ImageStrip.sampleSizeFor(12800, 9600))
    }

    @Test
    fun `the old rule quartered some photos and this one does not`() {
        /*
         * The regression this exists for. The previous rule sampled while `longest / sample >
         * MAX`, which overshoots: at 3300px it chose 4, decoding to 825 — a quarter of the pixels
         * of a 3200px photo that landed on exactly 1600. Two near-identical originals, one visibly
         * blurry, no way for the person to tell why.
         */
        for (longest in 1601..6400) {
            val decoded = longest / ImageStrip.sampleSizeFor(longest, longest / 2)
            assertTrue(
                "a $longest px photo decoded to $decoded, below the target",
                decoded >= ImageStrip.MAX_DIMEN,
            )
            assertTrue(
                "a $longest px photo decoded to $decoded, more than twice the target",
                decoded < 2 * ImageStrip.MAX_DIMEN,
            )
        }
    }

    @Test
    fun `is always a power of two, which is the only thing BitmapFactory honours`() {
        // Anything else is silently rounded down to 1 and you get a full-size decode.
        for (longest in 1..20_000 step 7) {
            val s = ImageStrip.sampleSizeFor(longest, longest)
            assertTrue("sample $s for $longest is not a power of two", s > 0 && (s and (s - 1)) == 0)
        }
    }

    @Test
    fun `degenerate input returns 1 rather than throwing or looping`() {
        assertEquals(1, ImageStrip.sampleSizeFor(0, 0))
        assertEquals(1, ImageStrip.sampleSizeFor(-5, 100))
        assertEquals(1, ImageStrip.sampleSizeFor(100, 100, maxDimen = 0))
    }

    // ---------------------------------------------------------------------------------------
    // Final scale
    // ---------------------------------------------------------------------------------------

    @Test
    fun `no scale step when the image is already within the target`() {
        assertNull(ImageStrip.scaleFor(1600, 1200))
        assertNull(ImageStrip.scaleFor(800, 600))
        assertNull(ImageStrip.scaleFor(1, 1))
    }

    @Test
    fun `scales the longest edge to exactly the target`() {
        val s = ImageStrip.scaleFor(3200, 2400)
        assertNotNull(s)
        assertEquals(ImageStrip.MAX_DIMEN, s!!.width)
        assertEquals(1200, s.height)
    }

    @Test
    fun `puts the longest edge exactly on the target and rounds the other to a whole pixel`() {
        /*
         * Stated as a per-dimension property rather than as a percentage of the aspect ratio,
         * because the percentage version is wrong at the extremes and this test asserted it for a
         * while: an image 9px tall scales its short edge to something like 8.47, and no integer is
         * within 2% of that — one whole pixel *is* 11% of nine. The property that actually holds is
         * that each dimension is the exact value rounded, which is the best an integer bitmap can
         * do, and the aspect follows from it.
         */
        for (w in 1700..4000 step 13) {
            for (h in intArrayOf(9, 100, 1200, 3000, 4000)) {
                val s = ImageStrip.scaleFor(w, h) ?: continue
                assertEquals("longest edge missed the target for ${w}x$h", ImageStrip.MAX_DIMEN, maxOf(s.width, s.height))
                val ratio = ImageStrip.MAX_DIMEN.toDouble() / maxOf(w, h)
                assertTrue(
                    "width off by more than a pixel: ${w}x$h -> ${s.width}x${s.height}",
                    kotlin.math.abs(s.width - w * ratio) <= 0.5 || s.width == 1,
                )
                assertTrue(
                    "height off by more than a pixel: ${w}x$h -> ${s.width}x${s.height}",
                    kotlin.math.abs(s.height - h * ratio) <= 0.5 || s.height == 1,
                )
            }
        }
    }

    @Test
    fun `aspect ratio holds for shapes where a whole pixel is a fine enough unit to say so`() {
        // Restricted to a short edge of 50px or more, which is where pixel quantisation is worth
        // under 2%. Below that the previous test is the one that means anything.
        for (w in 1700..4000 step 13) {
            for (h in intArrayOf(100, 1200, 3000, 4000)) {
                val s = ImageStrip.scaleFor(w, h) ?: continue
                if (minOf(s.width, s.height) < 50) continue
                val before = w.toDouble() / h
                val after = s.width.toDouble() / s.height
                assertTrue(
                    "aspect drifted: ${w}x$h -> ${s.width}x${s.height}",
                    kotlin.math.abs(before - after) < 0.02 * before,
                )
            }
        }
    }

    @Test
    fun `an extreme panorama keeps a non-zero short edge`() {
        // Bitmap.createScaledBitmap throws on a zero dimension, so truncation here is a crash on
        // a real photo — a 4000x11 stitched panorama rounds its short edge to 0.0044.
        val s = ImageStrip.scaleFor(4000, 11)
        assertNotNull(s)
        assertEquals(ImageStrip.MAX_DIMEN, s!!.width)
        assertTrue("short edge collapsed to ${s.height}", s.height >= 1)
    }

    @Test
    fun `degenerate input scales to nothing rather than throwing`() {
        assertNull(ImageStrip.scaleFor(0, 100))
        assertNull(ImageStrip.scaleFor(100, -1))
        assertNull(ImageStrip.scaleFor(100, 100, maxDimen = 0))
    }

    // ---------------------------------------------------------------------------------------
    // Orientation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `all eight orientations are handled, and only normal is a no-op`() {
        assertEquals(8, ImageStrip.ORIENTATIONS.size)
        for (o in ImageStrip.ORIENTATIONS) {
            val t = ImageStrip.transformFor(o)
            assertTrue("rotation ${t.rotationDegrees} is not a right angle", t.rotationDegrees in setOf(0, 90, 180, 270))
            if (o == ImageStrip.ORIENTATION_NORMAL) {
                assertTrue("NORMAL should be identity", t.isIdentity)
            } else {
                assertFalse("orientation $o was treated as already upright", t.isIdentity)
            }
        }
    }

    @Test
    fun `the four mirrored orientations are mirrored, not merely rotated`() {
        // Front cameras and some scanning tools produce these. Treating them as "unknown, leave it"
        // gives a mirrored photo, which anyone notices immediately in a picture containing text.
        for (o in intArrayOf(
            ImageStrip.ORIENTATION_FLIP_HORIZONTAL,
            ImageStrip.ORIENTATION_FLIP_VERTICAL,
            ImageStrip.ORIENTATION_TRANSPOSE,
            ImageStrip.ORIENTATION_TRANSVERSE,
        )) {
            assertTrue("orientation $o lost its mirror", ImageStrip.transformFor(o).mirrorHorizontally)
        }
    }

    @Test
    fun `the four unmirrored orientations are not mirrored`() {
        for (o in intArrayOf(
            ImageStrip.ORIENTATION_NORMAL,
            ImageStrip.ORIENTATION_ROTATE_90,
            ImageStrip.ORIENTATION_ROTATE_180,
            ImageStrip.ORIENTATION_ROTATE_270,
        )) {
            assertFalse("orientation $o gained a mirror", ImageStrip.transformFor(o).mirrorHorizontally)
        }
    }

    @Test
    fun `the common camera rotations turn the picture the documented way`() {
        assertEquals(90, ImageStrip.transformFor(ImageStrip.ORIENTATION_ROTATE_90).rotationDegrees)
        assertEquals(180, ImageStrip.transformFor(ImageStrip.ORIENTATION_ROTATE_180).rotationDegrees)
        assertEquals(270, ImageStrip.transformFor(ImageStrip.ORIENTATION_ROTATE_270).rotationDegrees)
    }

    @Test
    fun `only the quarter turns swap the axes`() {
        assertTrue(ImageStrip.transformFor(ImageStrip.ORIENTATION_ROTATE_90).swapsAxes)
        assertTrue(ImageStrip.transformFor(ImageStrip.ORIENTATION_ROTATE_270).swapsAxes)
        assertTrue(ImageStrip.transformFor(ImageStrip.ORIENTATION_TRANSPOSE).swapsAxes)
        assertTrue(ImageStrip.transformFor(ImageStrip.ORIENTATION_TRANSVERSE).swapsAxes)
        assertFalse(ImageStrip.transformFor(ImageStrip.ORIENTATION_NORMAL).swapsAxes)
        assertFalse(ImageStrip.transformFor(ImageStrip.ORIENTATION_ROTATE_180).swapsAxes)
        assertFalse(ImageStrip.transformFor(ImageStrip.ORIENTATION_FLIP_HORIZONTAL).swapsAxes)
        assertFalse(ImageStrip.transformFor(ImageStrip.ORIENTATION_FLIP_VERTICAL).swapsAxes)
    }

    @Test
    fun `an unknown or absent orientation stores the picture as it decoded`() {
        // Guessing from dimensions is the alternative and it is wrong often enough to be worse.
        assertTrue(ImageStrip.transformFor(ImageStrip.ORIENTATION_UNDEFINED).isIdentity)
        assertTrue(ImageStrip.transformFor(-1).isIdentity)
        assertTrue(ImageStrip.transformFor(9).isIdentity)
        assertTrue(ImageStrip.transformFor(Int.MAX_VALUE).isIdentity)
        assertTrue(ImageStrip.transformFor(Int.MIN_VALUE).isIdentity)
    }

    @Test
    fun `every transform composes to a distinct result, so none of the eight collapses`() {
        /*
         * Eight orientations must produce eight different pictures. Two mapping to the same
         * transform means one of them is silently wrong — the class of bug where "it looks fine on
         * my phone" holds right up until someone uses the front camera.
         *
         * Applied to a marked corner: track where (1,0) of a unit square lands under mirror-then-
         * rotate, plus the handedness, which is what distinguishes a rotation from a reflection.
         */
        val seen = mutableSetOf<Triple<Int, Int, Boolean>>()
        for (o in ImageStrip.ORIENTATIONS) {
            val t = ImageStrip.transformFor(o)
            var x = 1
            var y = 0
            if (t.mirrorHorizontally) x = -x
            repeat(t.rotationDegrees / 90) {
                val nx = -y
                y = x
                x = nx
            }
            assertTrue("orientation $o duplicates another", seen.add(Triple(x, y, t.mirrorHorizontally)))
        }
        assertEquals(8, seen.size)
    }

    // ---------------------------------------------------------------------------------------
    // Stored photo names — the path-traversal rule
    // ---------------------------------------------------------------------------------------

    @Test
    fun `accepts the names this app actually writes`() {
        // Non-vacuity for every rejection below: if this rule said no to everything, the tests
        // that follow would pass while the photo feature was entirely broken.
        for (i in 0 until 200) {
            val name = java.util.UUID.randomUUID().toString() + ".jpg"
            assertTrue("rejected a name the app itself produces: $name", ImageStrip.isStoredPhotoName(name))
        }
    }

    @Test
    fun `rejects the traversal that unlinked the journal database`() {
        // The actual payload. A backup carrying this as an entry's photoPath, with no matching
        // blob in its `photos` map, was written verbatim and resolved by the next swipe-delete to
        // <filesDir>/../databases/daymark.db.
        assertFalse(ImageStrip.isStoredPhotoName("../../databases/daymark.db"))
    }

    @Test
    fun `rejects every shape of escape, separator and encoding trick`() {
        val uuid = java.util.UUID.randomUUID().toString()
        for (hostile in listOf(
            "../../databases/daymark.db",
            "../$uuid.jpg",
            "..%2F..%2Fdatabases%2Fdaymark.db",
            "subdir/$uuid.jpg",
            "/$uuid.jpg",
            "/etc/passwd",
            "$uuid.jpg/../../x",
            "..\\..\\databases\\daymark.db",
            "$uuid.jpg\u0000.txt", // NUL truncation
            "$uuid.jpg\n",         // the regex must be anchored, not line-anchored
            "\n$uuid.jpg",
            "$uuid.JPG",           // our writer only ever emits lowercase
            "$uuid.jpg.db",
            "$uuid.png",
            "$uuid",               // no extension
            ".jpg",
            "",
            "   ",
            ".",
            "..",
        )) {
            assertFalse("accepted a hostile name: ${hostile.replace("\u0000", "\\0")}", ImageStrip.isStoredPhotoName(hostile))
        }
    }

    @Test
    fun `the anchors are real — a valid name embedded in junk is still rejected`() {
        // `Regex.matches` is whole-string in Kotlin, but this is the assumption the whole guard
        // rests on, so it is asserted rather than assumed. A `find`-style rule here would accept
        // every one of these.
        val uuid = java.util.UUID.randomUUID().toString()
        assertFalse(ImageStrip.isStoredPhotoName("../$uuid.jpg"))
        assertFalse(ImageStrip.isStoredPhotoName("$uuid.jpg/x"))
        assertFalse(ImageStrip.isStoredPhotoName("x$uuid.jpg"))
        assertTrue(ImageStrip.isStoredPhotoName("$uuid.jpg"))
    }

    // ---------------------------------------------------------------------------------------
    // What the person is told
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the notice names what is removed and claims nothing broader`() {
        val notice = ImageStrip.STRIP_NOTICE
        for (word in listOf("Location", "time", "camera")) {
            assertTrue("the notice does not mention $word", notice.contains(word, ignoreCase = true))
        }
        // "Your privacy is protected" is unfalsifiable and therefore worthless to the reader; the
        // notice must stay a checkable statement about what happens to the file.
        assertFalse(notice.contains("privacy", ignoreCase = true))
        assertFalse(notice.contains("secure", ignoreCase = true))
        assertFalse(notice.contains("anonym", ignoreCase = true))
    }
}
