package com.daymark.app.calm

import com.daymark.app.calm.CalmShelf.Companion.BOOKS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalmShelfTest {

    private fun everyBookOnce(s: CalmShelf) {
        val all = s.shelves.flatten().map { it.id } + s.loose.keys
        assertEquals(BOOKS.map { it.id }.sorted(), all.sorted())
        // And no two books on a shelf overlap, and every one is on its shelf.
        s.shelves.forEach { row ->
            row.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", a.at + BOOKS[a.id].width + CalmShelf.GAP <= b.at + 1e-4f) }
            row.forEach { assertTrue("$it", it.at >= 0f && it.at + BOOKS[it.id].width <= CalmShelf.CAPACITY + 1e-4f) }
        }
    }

    private fun ids(row: List<CalmShelf.Standing>) = row.map { it.id }

    private fun atEnd(s: CalmShelf, book: Int, shelf: Int) = s.place(book, shelf, s.endOf(shelf))

    @Test
    fun `a visit starts with every book loose`() {
        val s = CalmShelf.start(4)
        assertTrue(s.shelves.all { it.isEmpty() })
        assertEquals(BOOKS.size, s.loose.size)
    }

    @Test
    fun `any order is accepted`() {
        var a = CalmShelf.start(1)
        var b = CalmShelf.start(1)
        listOf(0, 1, 2).forEach { a = atEnd(a, it, 0) }
        listOf(2, 0, 1).forEach { b = atEnd(b, it, 0) }
        assertEquals(listOf(0, 1, 2), ids(a.shelves[0]))
        assertEquals(listOf(2, 0, 1), ids(b.shelves[0]))
        everyBookOnce(a); everyBookOnce(b)
    }

    @Test
    fun `a book stands where it is put`() {
        val s = CalmShelf.start(1).place(4, 0, 20f)
        assertEquals(listOf(CalmShelf.Standing(4, 20f)), s.shelves[0])
        everyBookOnce(s)
        // Past the end it is kept on the shelf.
        assertEquals(CalmShelf.CAPACITY - BOOKS[4].width.toFloat(), CalmShelf.start(1).place(4, 0, 99f).shelves[0].single().at)
    }

    @Test
    fun `its neighbours scoot along just far enough, and books further off stay put`() {
        // 0 (3 wide) at 10, 1 (4 wide) at 14, 6 (4 wide) at 28: a gap of one after each.
        var s = CalmShelf.start(1).place(0, 0, 10f).place(1, 0, 14f).place(6, 0, 28f)
        everyBookOnce(s)
        // Book 4 (5 wide) put down at 12, its middle between 0 and 1.
        s = s.place(4, 0, 12f)
        everyBookOnce(s)
        val at = s.shelves[0].associate { it.id to it.at }
        assertEquals(listOf(0, 4, 1, 6), ids(s.shelves[0]))
        assertEquals("the new book is where it was put", 12f, at.getValue(4))
        assertEquals("the left neighbour moves left by exactly enough", 12f - 1 - 3, at.getValue(0))
        assertEquals("the right neighbour moves right by exactly enough", 12f + 5 + 1, at.getValue(1))
        assertEquals("a book with room to spare does not move", 28f, at.getValue(6))
        // Moved to the other shelf, it is gone from the first and the rest stay where they were.
        val moved = s.place(4, 1, 0f)
        assertEquals(listOf(0, 1, 6), ids(moved.shelves[0]))
        assertEquals(listOf(8f, 18f, 28f), moved.shelves[0].map { it.at })
        assertEquals(listOf(4), ids(moved.shelves[1]))
    }

    @Test
    fun `against the end of the shelf, the new book gives ground too`() {
        // Book 2 (2 wide) sits at the right end; book 4 (5 wide) is put down on top of it.
        val s = CalmShelf.start(1).place(2, 0, 32f).place(4, 0, 31f)
        everyBookOnce(s)
        assertEquals(listOf(4, 2), ids(s.shelves[0]))
        assertEquals(listOf(26f, 32f), s.shelves[0].map { it.at })
    }

    @Test
    fun `every book fits across the two shelves`() {
        var s = CalmShelf.start(2)
        BOOKS.forEach { book -> s = atEnd(s, book.id, s.shelfWithRoomFor(book.id)!!) }
        assertTrue(s.loose.isEmpty())
        everyBookOnce(s)
    }

    @Test
    fun `a full shelf leaves the book where it was, without comment`() {
        var s = CalmShelf.start(3)
        // Fill shelf 0 as far as it goes.
        BOOKS.forEach { s = s.place(it.id, 0, 99f) }
        everyBookOnce(s)
        val left = s.loose.keys.first()
        assertTrue("positive control: shelf 0 really is too full for it", left !in ids(s.shelves[0]))
        assertEquals(s, s.place(left, 0, 0f))
        assertEquals(1, s.shelfWithRoomFor(left))
    }

    @Test
    fun `tumbling takes every book down, and the same seed tumbles the same way`() {
        var s = CalmShelf.start(5)
        BOOKS.forEach { s = atEnd(s, it.id, s.shelfWithRoomFor(it.id)!!) }
        val t = s.tumble(9)
        assertTrue(t.shelves.all { it.isEmpty() })
        everyBookOnce(t)
        assertEquals(t, s.tumble(9))
        assertNotEquals(t, s.tumble(10))
        assertTrue(t.loose.values.all { it.x in 0f..CalmShelf.MAX_LOOSE_X })
        assertTrue("some lie flat", t.loose.values.any { it.lying })
        assertTrue("some stand", t.loose.values.any { !it.lying })
    }

    @Test
    fun `putting a book down takes it off the shelf`() {
        val s = CalmShelf.start(1).place(3, 0, 0f).putDown(3, 0.5f)
        assertTrue(s.shelves[0].isEmpty())
        assertEquals(CalmShelf.Loose(0.5f, lying = false), s.loose[3])
    }

    @Test
    fun `nothing knows when the shelf is done`() {
        // No "isDone", "isFull", "isTidy" or count: the first thing a screen would do with one is say so.
        val names = CalmShelf::class.java.declaredMethods.map { it.name.lowercase() }
        listOf("done", "full", "tidy", "complete", "count", "progress", "score").forEach { word ->
            assertNull(word, names.firstOrNull { it.contains(word) })
        }
        // Positive control: the same scan does see a method that is there.
        assertTrue(names.any { it.contains("tumble") })
        val fields = CalmShelf::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("shelves", "loose"), fields)
    }
}
