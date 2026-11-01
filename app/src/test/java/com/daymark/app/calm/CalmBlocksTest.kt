package com.daymark.app.calm

import com.daymark.app.calm.CalmBlocks.Companion.CLEAR_ROWS
import com.daymark.app.calm.CalmBlocks.Companion.EMPTY
import com.daymark.app.calm.CalmBlocks.Companion.HEIGHT
import com.daymark.app.calm.CalmBlocks.Companion.WIDTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalmBlocksTest {

    private fun filled(board: CalmBlocks) = board.cells.count { it != EMPTY }

    @Test
    fun `nothing moves until the person moves it`() {
        val board = CalmBlocks.start(7)
        // There is no step, tick or gravity call to make: the only ways to change a board are the
        // person's own. The shape in hand is still in the top row however long it sits.
        assertEquals(0, board.piece.y)
        assertEquals(board, board.copy())
    }

    @Test
    fun `the stack never ends it, however many shapes are let fall in one place`() {
        var board = CalmBlocks.start(3)
        // Positive control: dropping in one column with no moves would overflow a board that had
        // no rule keeping the top clear — far more squares arrive than the board holds.
        var arrived = 0
        repeat(400) {
            arrived += board.piece.squares.size
            board = board.drop()
            assertTrue("the next shape has room after drop ${it + 1}", board.spawnFits())
            for (y in 0 until CLEAR_ROWS) for (x in 0 until WIDTH) assertEquals(EMPTY, board.at(x, y))
        }
        assertTrue(arrived > WIDTH * HEIGHT)
    }

    @Test
    fun `a full row simply goes`() {
        // Seven of eight squares in the bottom row are settled; a shape that fills the gap clears it.
        val cells = MutableList(WIDTH * HEIGHT) { EMPTY }
        for (x in 0 until WIDTH - 1) cells[(HEIGHT - 1) * WIDTH + x] = 0
        cells[(HEIGHT - 2) * WIDTH + 0] = 1 // one square above the row, which must come down a row
        val single = CalmBlocks.Piece(listOf(CalmBlocks.Cell(0, 0)), WIDTH - 1, 0, 2)
        val board = CalmBlocks(cells, single, 1, 1).drop()
        for (x in 0 until WIDTH) {
            if (x == 0) assertEquals(1, board.at(x, HEIGHT - 1)) else assertEquals(EMPTY, board.at(x, HEIGHT - 1))
        }
        assertEquals(1, filled(board))
    }

    @Test
    fun `a wall stops a shape without anything happening`() {
        var board = CalmBlocks.start(11)
        repeat(WIDTH) { board = board.left() }
        assertEquals(0, board.piece.absolute.minOf { it.x })
        assertEquals(board, board.left())
        repeat(WIDTH) { board = board.right() }
        assertEquals(WIDTH - 1, board.piece.absolute.maxOf { it.x })
        assertEquals(board, board.right())
    }

    @Test
    fun `turning against the wall steps off it, and four turns come back round`() {
        var board = CalmBlocks.start(5)
        // Find a shape that changes when turned, so the check below cannot pass on a no-op.
        var n = 0
        while (board.turn().piece.squares == board.piece.squares) { board = board.drop(); n++; check(n < 50) }
        val turned = board.turn()
        assertNotEquals(board.piece.squares, turned.piece.squares)
        assertEquals(board.piece.squares, board.turn().turn().turn().turn().piece.squares)
        repeat(WIDTH) { board = board.right() }
        val atWall = board.turn()
        assertTrue(atWall.piece.absolute.all { it.x in 0 until WIDTH })
    }

    @Test
    fun `every shape stays inside the clear band in every turn`() {
        CalmBlocks.SHAPES.forEach { shape ->
            var squares = shape
            repeat(4) {
                val w = squares.maxOf { it.x } - squares.minOf { it.x } + 1
                val h = squares.maxOf { it.y } - squares.minOf { it.y } + 1
                assertTrue(w <= CLEAR_ROWS && h <= CLEAR_ROWS)
                squares = squares.map { CalmBlocks.Cell(-it.y, it.x) }
            }
        }
    }

    @Test
    fun `the landing outline is where the shape settles`() {
        val board = CalmBlocks.start(9)
        val landing = board.landing().absolute.toSet()
        val dropped = board.drop()
        landing.forEach { assertEquals(board.piece.shade, dropped.at(it.x, it.y)) }
    }

    @Test
    fun `the same seed hands out the same shapes`() {
        var a = CalmBlocks.start(42)
        var b = CalmBlocks.start(42)
        repeat(30) { a = a.drop(); b = b.drop() }
        assertEquals(a, b)
        // And a different seed really is different, so the line above is not true of everything.
        var c = CalmBlocks.start(43)
        repeat(30) { c = c.drop() }
        assertNotEquals(a, c)
    }

    @Test
    fun `nothing is counted`() {
        // The state is the board, the shape in hand, the seed and the hand-out position. A new field
        // (rows cleared, shapes placed, time) is the first half of a score and must be argued for.
        val fields = CalmBlocks::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }.toSet()
        assertEquals(setOf("cells", "piece", "seed", "handedOut"), fields)
    }

    @Test
    fun `clear gives an empty board`() {
        var board = CalmBlocks.start(1)
        repeat(10) { board = board.drop() }
        assertTrue(filled(board) > 0)
        assertEquals(0, filled(board.cleared()))
    }
}
