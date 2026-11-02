package com.daymark.app.calm

/**
 * "Falling blocks", the calm activity in Take a moment that has nothing to win and nothing to lose
 * (#170). This file is its whole behaviour; the Compose screen only draws it and forwards taps.
 *
 * Import-free, the discipline `sky/` and `stats/` keep: no Android, no clock, no random source.
 * `tools/jvm-tests.sh calm` compiles and runs it on a plain JVM.
 *
 * ## What it must keep true
 *
 *  1. **Nothing moves on its own.** There is no gravity and no tick. A shape sits at the top until
 *     the person moves, turns or lets it fall, and each of those is one call here. The screen may
 *     show a fall as a short slide, but only in answer to that call, and never with animations
 *     removed (`docs/FEATURES.md` §7.2.1).
 *  2. **There is no way to fail.** A full row simply goes. When the stack reaches into the top
 *     [CLEAR_ROWS] rows, rows are taken from the bottom until it no longer does, so the next shape
 *     always fits and the stack just stops rising. There is no "game over" state to reach, and
 *     [next] always has room ([spawnFits] is true after every [drop]).
 *  3. **Nothing is counted.** No field here counts rows cleared, shapes placed or time spent,
 *     because a count is the first half of a score (`CLAUDE.md` §4). The state is the board and the
 *     shape in hand, and nothing else.
 *  4. **Deterministic.** The next shape is a written-out hash of [seed] and how many shapes have
 *     come before it, so a test can replay a board exactly.
 *
 * The shapes are small (one to four squares, at most three across in any turn), which keeps every
 * one inside the clear band at the top, and none is the familiar set of seven: this is a quiet
 * thing to do with the hands, not a copy of a game.
 */
data class CalmBlocks(
    /** Shade index of each cell, row by row from the top; [EMPTY] where nothing has settled. */
    val cells: List<Int>,
    /** The shape in hand. */
    val piece: Piece,
    val seed: Int,
    /** How many shapes have been handed out, so the next one can be chosen without a random source. */
    val handedOut: Int,
) {
    data class Cell(val x: Int, val y: Int)

    /** A shape in hand: its squares relative to [x], [y], and the shade it settles as. */
    data class Piece(val squares: List<Cell>, val x: Int, val y: Int, val shade: Int) {
        val absolute: List<Cell> get() = squares.map { Cell(it.x + x, it.y + y) }
    }

    fun at(x: Int, y: Int): Int = cells[y * WIDTH + x]

    fun left(): CalmBlocks = shifted(-1)

    fun right(): CalmBlocks = shifted(1)

    /**
     * A quarter turn clockwise. If the turned shape would overlap a wall or a settled square, one
     * step left or right is tried, and if neither fits the shape stays as it was, without comment.
     */
    fun turn(): CalmBlocks {
        val turned = normalise(piece.squares.map { Cell(-it.y, it.x) })
        for (dx in listOf(0, -1, 1)) {
            val candidate = piece.copy(squares = turned, x = piece.x + dx)
            if (fits(candidate)) return copy(piece = candidate)
        }
        return this
    }

    /** The row the shape in hand would settle at if let fall now; drawn as its faint outline. */
    fun landing(): Piece {
        var p = piece
        while (fits(p.copy(y = p.y + 1))) p = p.copy(y = p.y + 1)
        return p
    }

    /**
     * The rows the shape in hand would fill if let fall now, top first. The screen fades exactly
     * these before [drop] takes them, so what fades is what goes.
     */
    fun fullRowsOnLanding(): List<Int> {
        val landed = landing().absolute.toSet()
        return (0 until HEIGHT).filter { y -> (0 until WIDTH).all { x -> at(x, y) != EMPTY || Cell(x, y) in landed } }
    }

    /**
     * Let the shape fall straight down and settle. Full rows go, the stack is kept out of the clear
     * band, and the next shape is handed out. Always returns a board the next shape fits on.
     */
    fun drop(): CalmBlocks {
        val landed = landing()
        val placed = cells.toMutableList()
        landed.absolute.forEach { placed[it.y * WIDTH + it.x] = landed.shade }
        val stack = placed.chunked(WIDTH)
            .filterNot { row -> row.all { it != EMPTY } }
            .dropWhile { row -> row.all { it == EMPTY } }
        // Keep the top band clear by taking rows from the bottom: the stack stops rising.
        val kept = stack.take(HEIGHT - CLEAR_ROWS)
        val padded = List(HEIGHT - kept.size) { List(WIDTH) { EMPTY } } + kept
        return CalmBlocks(padded.flatten(), spawn(seed, handedOut), seed, handedOut + 1)
    }

    /** A fresh, empty board with a new first shape: what "Clear" gives. */
    fun cleared(): CalmBlocks = start(seed + handedOut)

    /** Whether the shape in hand sits on the board without overlap; true on every reachable board. */
    fun spawnFits(): Boolean = fits(piece)

    private fun shifted(dx: Int): CalmBlocks {
        val moved = piece.copy(x = piece.x + dx)
        return if (fits(moved)) copy(piece = moved) else this
    }

    private fun fits(p: Piece): Boolean = p.absolute.all {
        it.x in 0 until WIDTH && it.y in 0 until HEIGHT && at(it.x, it.y) == EMPTY
    }

    companion object {
        const val WIDTH = 8
        const val HEIGHT = 14
        const val EMPTY = -1

        /** How many muted shades the squares come in; the screen maps each to a semantic role. */
        const val SHADES = 3

        /** Rows at the top that are kept clear, so a new shape always has somewhere to be. */
        const val CLEAR_ROWS = 3

        /** Small shapes, one to four squares, none wider or taller than [CLEAR_ROWS] in any turn. */
        val SHAPES: List<List<Cell>> = listOf(
            listOf(Cell(0, 0), Cell(1, 0)),
            listOf(Cell(0, 0), Cell(1, 0), Cell(2, 0)),
            listOf(Cell(0, 0), Cell(0, 1), Cell(1, 1)),
            listOf(Cell(0, 0), Cell(1, 0), Cell(0, 1), Cell(1, 1)),
            listOf(Cell(0, 0), Cell(1, 0), Cell(2, 0), Cell(1, 1)),
            listOf(Cell(0, 0), Cell(0, 1), Cell(1, 1), Cell(1, 2)),
            listOf(Cell(0, 0), Cell(0, 1), Cell(0, 2), Cell(1, 2)),
        )

        fun start(seed: Int): CalmBlocks =
            CalmBlocks(List(WIDTH * HEIGHT) { EMPTY }, spawn(seed, 0), seed, 1)

        /** The [n]th shape for [seed], centred in the top row. A hash, so no random source is read. */
        internal fun spawn(seed: Int, n: Int): Piece {
            val h = mix(seed * 31 + n)
            val shape = normalise(SHAPES[Math.floorMod(h, SHAPES.size)])
            val width = shape.maxOf { it.x } + 1
            return Piece(shape, (WIDTH - width) / 2, 0, Math.floorMod(h ushr 8, SHADES))
        }

        private fun normalise(squares: List<Cell>): List<Cell> {
            val minX = squares.minOf { it.x }
            val minY = squares.minOf { it.y }
            return squares.map { Cell(it.x - minX, it.y - minY) }.sortedWith(compareBy({ it.y }, { it.x }))
        }

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
