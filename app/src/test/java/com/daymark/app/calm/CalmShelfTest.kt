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
        val all = s.shelves.flatten() + s.loose.keys
        assertEquals(BOOKS.map { it.id }.sorted(), all.sorted())
    }

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
        listOf(0, 1, 2).forEach { a = a.place(it, 0, 99) }
        listOf(2, 0, 1).forEach { b = b.place(it, 0, 99) }
        assertEquals(listOf(0, 1, 2), a.shelves[0])
        assertEquals(listOf(2, 0, 1), b.shelves[0])
        everyBookOnce(a); everyBookOnce(b)
    }

    @Test
    fun `a book goes in between others where it is put`() {
        var s = CalmShelf.start(1).place(0, 0, 0).place(1, 0, 1).place(2, 0, 1)
        assertEquals(listOf(0, 2, 1), s.shelves[0])
        s = s.place(0, 1, 0) // moved to the other shelf, gone from the first
        assertEquals(listOf(2, 1), s.shelves[0])
        assertEquals(listOf(0), s.shelves[1])
        everyBookOnce(s)
    }

    @Test
    fun `every book fits across the two shelves`() {
        var s = CalmShelf.start(2)
        BOOKS.forEach { book -> s = s.place(book.id, s.shelfWithRoomFor(book.id)!!, 99) }
        assertTrue(s.loose.isEmpty())
        everyBookOnce(s)
    }

    @Test
    fun `a full shelf leaves the book where it was, without comment`() {
        var s = CalmShelf.start(3)
        // Fill shelf 0 as far as it goes.
        BOOKS.forEach { s = s.place(it.id, 0, 99) }
        val left = s.loose.keys.first()
        assertTrue("positive control: shelf 0 really is too full for it", left !in s.shelves[0])
        assertEquals(s, s.place(left, 0, 0))
        assertEquals(1, s.shelfWithRoomFor(left))
    }

    @Test
    fun `tumbling takes every book down, and the same seed tumbles the same way`() {
        var s = CalmShelf.start(5)
        BOOKS.forEach { s = s.place(it.id, s.shelfWithRoomFor(it.id)!!, 99) }
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
        val s = CalmShelf.start(1).place(3, 0, 0).putDown(3, 0.5f)
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
