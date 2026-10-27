package com.daymark.app.export

import com.daymark.app.backup.repoFile
import com.daymark.app.export.ReportLayout.PageKind
import com.daymark.app.export.ReportLayout.PageKind.BACK
import com.daymark.app.export.ReportLayout.PageKind.BLANK
import com.daymark.app.export.ReportLayout.PageKind.CONTENT
import com.daymark.app.export.ReportLayout.PageKind.COVER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The optional cover and back page, and the blank pages that give each a sheet of its own when the
 * report is printed on both sides ([ReportLayout.blanksAfterCover], [ReportLayout.blanksBeforeBack]).
 *
 * Printed on both sides, page 1 is the front of sheet 1 and page 2 its reverse; odd pages are
 * right-hand pages. The promises, checked by [violations] over every content length and every
 * combination of switches:
 *
 *  - the cover is page 1 and the back page is the last page;
 *  - every content page prints, in one run, and nothing else is added on one side of the paper;
 *  - on both sides, side 1 opens on a right-hand page, the back page is the reverse of the last
 *    sheet, and neither bookend shares a sheet with a content page.
 *
 * [violations] is first shown documents that break each promise (`CLAUDE.md` §5), including the
 * one the obvious rule produces — a single blank before the back page whatever the content length.
 */
class ReportBookendsTest {

    private fun sheetOf(index: Int) = index / 2

    private fun violations(pages: List<PageKind>, contentPages: Int, cover: Boolean, back: Boolean, twoSided: Boolean): List<String> {
        val out = ArrayList<String>()
        if (cover && pages.firstOrNull() != COVER) out.add("the cover is not page 1")
        if (!cover && COVER in pages) out.add("a cover nobody asked for")
        if (back && pages.lastOrNull() != BACK) out.add("the back page is not the last page")
        if (!back && BACK in pages) out.add("a back page nobody asked for")
        val content = pages.indices.filter { pages[it] == CONTENT }
        if (content.size != contentPages) out.add("${content.size} content pages, not $contentPages")
        if (content.isNotEmpty() && content.last() - content.first() + 1 != content.size) {
            out.add("the content pages are not one run")
        }
        if (!twoSided) {
            if (BLANK in pages) out.add("a blank page on one side of the paper")
            return out
        }
        if (content.isNotEmpty() && content.first() % 2 != 0) out.add("side 1 opens on a left-hand page")
        val contentSheets = content.map(::sheetOf).toSet()
        pages.indices.filter { pages[it] == COVER }.forEach {
            if (sheetOf(it) in contentSheets) out.add("the cover shares a sheet with content")
        }
        pages.indices.filter { pages[it] == BACK }.forEach {
            if (it % 2 != 1) out.add("the back page is not the reverse of its sheet")
            if (sheetOf(it) in contentSheets) out.add("the back page shares a sheet with content")
        }
        return out
    }

    @Test
    fun theCheckerSeesEachBrokenDocument() {
        // Back page straight after an odd run of content: it lands on a front, on the content's sheet.
        assertTrue(
            violations(listOf(CONTENT, CONTENT, CONTENT, BACK), 3, cover = false, back = true, twoSided = true)
                .contains("the back page shares a sheet with content"),
        )
        // The obvious rule, one blank whatever came before: right after three pages, the back page
        // is a front and faces into the stack.
        assertTrue(
            violations(listOf(CONTENT, CONTENT, CONTENT, BLANK, BACK), 3, cover = false, back = true, twoSided = true)
                .contains("the back page is not the reverse of its sheet"),
        )
        // No blank behind the cover: side 1 is printed on the cover's reverse.
        val noBlank = violations(listOf(COVER, CONTENT, CONTENT), 2, cover = true, back = false, twoSided = true)
        assertTrue(noBlank.contains("side 1 opens on a left-hand page"))
        assertTrue(noBlank.contains("the cover shares a sheet with content"))
        // Padding on one side of the paper is a wasted sheet.
        assertTrue(
            violations(listOf(COVER, BLANK, CONTENT), 1, cover = true, back = false, twoSided = false)
                .contains("a blank page on one side of the paper"),
        )
    }

    @Test
    fun everyDocumentKeepsEveryPromise() {
        val flags = listOf(false, true)
        var checked = 0
        for (contentPages in 1..12) for (cover in flags) for (back in flags) for (twoSided in flags) {
            val pages = ReportLayout.documentPages(contentPages, cover, back, twoSided)
            val found = violations(pages, contentPages, cover, back, twoSided)
            assertEquals("$contentPages pages, cover=$cover back=$back twoSided=$twoSided: $pages", emptyList<String>(), found)
            checked++
        }
        assertEquals(12 * 8, checked)
    }

    @Test
    fun paddingIsTheLeastThatKeepsThePromises() {
        // Cover on both sides: one blank, its own reverse.
        assertEquals(listOf(COVER, BLANK, CONTENT), ReportLayout.documentPages(1, cover = true, back = false, twoSided = true))
        // Back after an even run: one blank, the front of the back page's sheet.
        assertEquals(
            listOf(CONTENT, CONTENT, BLANK, BACK),
            ReportLayout.documentPages(2, cover = false, back = true, twoSided = true),
        )
        // Back after an odd run: one blank finishes the content's sheet, one fronts the back page.
        assertEquals(
            listOf(CONTENT, CONTENT, CONTENT, BLANK, BLANK, BACK),
            ReportLayout.documentPages(3, cover = false, back = true, twoSided = true),
        )
        // One side of the paper: no blanks at all.
        assertEquals(
            listOf(COVER, CONTENT, CONTENT, CONTENT, BACK),
            ReportLayout.documentPages(3, cover = true, back = true, twoSided = false),
        )
    }

    @Test
    fun theBackPageRulesStayOutOfTheFooterBand() {
        for (h in listOf(PaperSize.A4.heightPt.toFloat(), PaperSize.LETTER.heightPt.toFloat())) {
            val top = ReportLayout.MARGIN + ReportLayout.HEADER_TITLE_ADVANCE
            val rules = ReportLayout.notesRules(top, h)
            assertTrue("no room for notes on a ${h}pt page", rules.size > 20)
            assertTrue(rules.all { it > top && it <= ReportLayout.contentLimit(h) })
            // Not one more rule would have fitted.
            assertTrue(rules.last() + ReportLayout.NOTES_RULE_STEP > ReportLayout.contentLimit(h))
        }
    }

    /**
     * The renderer learns its content length only as it draws, so it asks [ReportLayout.blanksBeforeBack]
     * after `endPage` has already opened the next page. The pages the sides took are every page
     * before that one; passing the open page's number too would flip every parity above.
     */
    @Test
    fun theRendererCountsOnlyThePagesTheSidesTook() {
        val source = repoFile(GENERATOR).readText()
        val call = Regex("""ReportLayout\.blanksBeforeBack\(([^,]+),""").findAll(source).map { it.groupValues[1].trim() }.toList()
        assertEquals("the renderer no longer pads before the back page in one place", 1, call.size)
        assertEquals("ctx.pageCount - 1", call.single())

        val planted = source.replace("blanksBeforeBack(ctx.pageCount - 1,", "blanksBeforeBack(ctx.pageCount,")
        assertTrue("the off-by-one was not planted", planted != source)
        assertTrue(
            Regex("""ReportLayout\.blanksBeforeBack\(([^,]+),""").find(planted)!!.groupValues[1].trim() != "ctx.pageCount - 1",
        )
    }

    private companion object {
        const val GENERATOR = "app/src/main/java/com/daymark/app/export/PdfReportGenerator.kt"
    }
}
