package com.daymark.app.calm

/**
 * "Tidy a little shelf", the calm activity in Take a moment where books go on a shelf in any order
 * and a shake tumbles them loose again (#170). This file is its whole behaviour; the Compose screen
 * only draws it and forwards drags, taps and shakes.
 *
 * Import-free like [CalmBlocks], so `tools/jvm-tests.sh calm` runs it.
 *
 * ## What it must keep true
 *
 *  1. **There is no right order and no "done".** A book can go anywhere along a shelf, in any order,
 *     and nothing here knows or says when every book is up. There is deliberately no function that
 *     asks whether the shelves are full or tidy, because the first thing a screen would do with one
 *     is say so (`docs/FEATURES.md` §7.2.1).
 *  2. **Books never fall by themselves.** The only ways a book moves are [place], [putDown] and
 *     [tumble], and the screen calls each only from the person's own drag, tap, shake or button.
 *  3. **Nothing is counted.** The state is where each book is, and nothing else.
 *  4. **Deterministic.** [tumble] scatters by a written-out hash of its seed, so a test can replay it.
 *  5. **A book stays where it is put.** [place] stands it with its left edge where the person let go,
 *     and the books beside it scoot along only as far as they must to make room. Only the end of the
 *     shelf can push the new book itself aside. No two books on a shelf ever overlap.
 */
data class CalmShelf(
    /** Each shelf's books, left to right. */
    val shelves: List<List<Standing>>,
    /** Books lying loose on the floor, by id. */
    val loose: Map<Int, Loose>,
) {
    /** A book's size in shelf units, and its shade (the screen maps each shade to a semantic role). */
    data class Book(val id: Int, val width: Int, val height: Int, val shade: Int)

    /**
     * Where a loose book lies: [x] is its left edge as a fraction of the floor's width, and a book
     * [lying] flat is drawn on its side, so its width and height swap.
     */
    data class Loose(val x: Float, val lying: Boolean)

    /** A book standing on a shelf: [at] is its left edge, in shelf units from the shelf's left end. */
    data class Standing(val id: Int, val at: Float)

    /**
     * Stand [book] on [shelf] with its left edge at [at] (kept on the shelf), taking it from wherever
     * it was. Its neighbours scoot along just far enough to make room, and only when the shelf's end
     * is in the way does the new book give ground too. If the books would not all fit, nothing
     * changes, without comment.
     */
    fun place(book: Int, shelf: Int, at: Float): CalmShelf {
        if (shelf !in shelves.indices) return this
        val without = shelves.map { row -> row.filterNot { it.id == book } }
        val width = BOOKS[book].width
        if (!hasRoomFor(book, shelf)) return this
        val others = without[shelf]
        val wanted = at.coerceIn(0f, (CAPACITY - width).toFloat())
        // The new book goes before the first neighbour whose middle is right of its own middle.
        val index = others.indexOfFirst { it.at + BOOKS[it.id].width / 2f > wanted + width / 2f }
            .let { if (it < 0) others.size else it }
        val ids = others.take(index).map { it.id } + book + others.drop(index).map { it.id }
        val left = (others.take(index).map { it.at } + wanted + others.drop(index).map { it.at }).toFloatArray()
        val w = ids.map { BOOKS[it].width.toFloat() }
        // The books to its left step left, each only as far as it must. The pass from the left end
        // that follows does the same for the books to its right, and keeps everything on the shelf.
        for (i in index - 1 downTo 0) left[i] = minOf(left[i], left[i + 1] - GAP - w[i])
        var edge = 0f
        for (i in ids.indices) { left[i] = maxOf(left[i], edge); edge = left[i] + w[i] + GAP }
        edge = CAPACITY.toFloat()
        for (i in ids.indices.reversed()) { left[i] = minOf(left[i], edge - w[i]); edge = left[i] - GAP }
        val row = ids.mapIndexed { i, id -> Standing(id, left[i]) }
        return CalmShelf(without.mapIndexed { i, r -> if (i == shelf) row else r }, loose - book)
    }

    /** Where a book stands if put just after the last one on [shelf]: what a tap on a loose book uses. */
    fun endOf(shelf: Int): Float =
        shelves[shelf].maxOfOrNull { it.at + BOOKS[it.id].width + GAP } ?: 0f

    /** Put [book] on the floor at [x], standing up, taking it from wherever it was. */
    fun putDown(book: Int, x: Float): CalmShelf = CalmShelf(
        shelves.map { row -> row.filterNot { it.id == book } },
        loose + (book to Loose(x.coerceIn(0f, MAX_LOOSE_X), lying = false)),
    )

    /** Every book off the shelves and onto the floor, some standing and some on their sides. */
    fun tumble(seed: Int): CalmShelf = CalmShelf(
        List(SHELVES) { emptyList() },
        BOOKS.associate { book ->
            val h = mix(seed * 131 + book.id)
            book.id to Loose(x = Math.floorMod(h, 1000) / 1000f * MAX_LOOSE_X, lying = (h ushr 12) and 1 == 1)
        },
    )

    /**
     * The first shelf with room for [book], top first, or null when none has. A tap on a loose book
     * stands it at the end of this shelf: the one-tap way to do it, for anyone who cannot drag.
     */
    fun shelfWithRoomFor(book: Int): Int? = shelves.indices.firstOrNull { hasRoomFor(book, it) }

    /** Whether [book] fits on [shelf] beside what is already there, counting it out if it is there now. */
    fun hasRoomFor(book: Int, shelf: Int): Boolean =
        shelf in shelves.indices && used(shelves[shelf].filterNot { it.id == book }) + BOOKS[book].width <= CAPACITY

    /** Shelf units taken along [row], including a gap after each book. */
    private fun used(row: List<Standing>): Int = row.sumOf { BOOKS[it.id].width + GAP }

    companion object {
        const val SHELVES = 2

        /** Shelf units along each shelf. Every book fits with room to spare across the two. */
        const val CAPACITY = 34
        const val GAP = 1

        /** How many muted shades the books come in. */
        const val SHADES = 3

        /** The furthest right a loose book's left edge goes, so even a book on its side stays in view. */
        const val MAX_LOOSE_X = 0.75f

        /** Nine books of different sizes. None is the "right" one for any place. */
        val BOOKS: List<Book> = listOf(
            Book(0, 3, 12, 0), Book(1, 4, 14, 1), Book(2, 2, 10, 2),
            Book(3, 4, 15, 0), Book(4, 5, 12, 2), Book(5, 3, 13, 1),
            Book(6, 4, 10, 0), Book(7, 2, 14, 2), Book(8, 3, 11, 1),
        )

        /** Where a visit starts: everything on the floor, as if just tumbled. */
        fun start(seed: Int): CalmShelf = CalmShelf(List(SHELVES) { emptyList() }, emptyMap()).tumble(seed)

        private fun mix(v: Int): Int {
            var x = v
            x = x xor (x ushr 16)
            x *= 0x45d9f3b
            x = x xor (x ushr 16)
            x *= 0x45d9f3b
            return x xor (x ushr 16)
        }
    }
}

/**
 * Tells a deliberate shake from a jolt. Fed the accelerometer's readings, including gravity, it
 * says yes only after [JOLTS] sharp peaks above [THRESHOLD_G] within [WINDOW_MS] of one another,
 * each at least [MIN_GAP_MS] after the last, so one long peak counts once. A phone dropped into a
 * bag, set down hard or knocked in a pocket makes one or two peaks and never gets there. After a
 * shake it waits [COOLDOWN_MS] before it can say yes again, so one shake is one tumble.
 *
 * The numbers are a judgement, not a measurement, and have not yet been tried on a phone: three
 * peaks at 2.5 g within a second is a hand shaking on purpose, where walking stays near 1.2 g. The
 * screen also has a Shake button, so nothing depends on them.
 */
class ShakeDetector {
    private val peaks = ArrayDeque<Long>()
    private var lastPeakAt = Long.MIN_VALUE / 2
    private var lastShakeAt = Long.MIN_VALUE / 2

    /** One reading: time in milliseconds, acceleration in m/s² on each axis. True once per shake. */
    fun onReading(atMs: Long, x: Float, y: Float, z: Float): Boolean {
        val g = kotlin.math.sqrt(x * x + y * y + z * z) / EARTH_G
        if (g < THRESHOLD_G) return false
        if (atMs - lastPeakAt < MIN_GAP_MS) return false
        lastPeakAt = atMs
        if (atMs - lastShakeAt < COOLDOWN_MS) return false
        while (peaks.isNotEmpty() && atMs - peaks.first() > WINDOW_MS) peaks.removeFirst()
        peaks.addLast(atMs)
        if (peaks.size < JOLTS) return false
        peaks.clear()
        lastShakeAt = atMs
        return true
    }

    companion object {
        const val EARTH_G = 9.80665f
        const val THRESHOLD_G = 2.5f
        const val JOLTS = 3
        const val WINDOW_MS = 1_000L
        const val MIN_GAP_MS = 120L
        const val COOLDOWN_MS = 1_500L
    }
}
