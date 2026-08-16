package com.daymark.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the OS is allowed to copy off the device.
 *
 * `PRIVACY.md`: *"`android:allowBackup="false"` is set, so the OS won't copy your data into
 * cloud/adb backups."* That attribute was the app's only enforcement of that sentence, and since
 * Android 12 it no longer covers every channel: the documented note on `android:allowBackup` is
 * that setting it false does **not** disable device-to-device transfer. `targetSdk` is 35, so on
 * every phone from 12 onwards D2D was governed by the platform default — copy everything — and a
 * person migrating to a new handset would have had the journal, the photos and the safety plan
 * carried across by the setup wizard without ever choosing to export anything.
 *
 * This is testable without a device because it is entirely declaration: a manifest attribute and an
 * XML resource. Nothing here needs an emulator, so unlike most Android backup behaviour it can be
 * checked on every run.
 */
class BackupExtractionRulesTest {

    private companion object {
        const val MANIFEST = "app/src/main/AndroidManifest.xml"
        const val RULES_DIR = "app/src/main/res/xml"

        fun parse(text: String): Document =
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(text)))
    }

    private val manifestText = repoFile(MANIFEST).readText()
    private val application: Element =
        parse(manifestText).getElementsByTagName("application").item(0) as Element

    /** The resource the manifest actually points at, so the two cannot drift apart. */
    private val rulesText: String
        get() {
            val ref = application.getAttribute("android:dataExtractionRules")
            assertTrue("manifest declares no dataExtractionRules", ref.startsWith("@xml/"))
            return repoFile("$RULES_DIR/${ref.removePrefix("@xml/")}.xml").readText()
        }

    /** The domains a section excludes, or an empty list when the section is not there at all. */
    private fun excludedDomains(text: String, section: String): List<String> {
        val sections = parse(text).getElementsByTagName(section)
        return (0 until sections.length).flatMap { s ->
            val excludes = (sections.item(s) as Element).getElementsByTagName("exclude")
            (0 until excludes.length).map { (excludes.item(it) as Element).getAttribute("domain") }
        }
    }

    private fun includeCount(text: String): Int = parse(text).getElementsByTagName("include").length

    @Test
    fun `allowBackup stays false`() {
        // The rules resource is only read on API 31+. Below that this attribute is the whole
        // defence, so it is not something the new file replaces.
        assertEquals("false", application.getAttribute("android:allowBackup"))
    }

    @Test
    fun `both extraction channels exclude the app's storage`() {
        // device-transfer is the one that was missing entirely. cloud-backup is here so the file
        // does not silently start permitting cloud backup if allowBackup is ever flipped.
        for (section in listOf("cloud-backup", "device-transfer")) {
            assertTrue(
                "<$section> does not exclude domain=\"root\" — that domain is the journal " +
                    "database, the entry photos and the PIN hash",
                excludedDomains(rulesText, section).contains("root"),
            )
        }
        // Storage nothing writes to today, excluded now so a later feature is covered on arrival
        // rather than exported until someone remembers this file exists.
        assertTrue(excludedDomains(rulesText, "device-transfer").containsAll(listOf("device_root", "external")))
    }

    @Test
    fun `nothing is included back in`() {
        // An <include> anywhere turns the file into an allow-list for that domain, which would
        // quietly undo the excludes around it.
        assertEquals(0, includeCount(rulesText))
    }

    @Test
    fun `the section check is not vacuous — it fails on the shape the app shipped with`() {
        /*
         * The pre-fix state is "no rules resource at all", which reads to the platform as the
         * default. The closest document is one with the D2D section removed; if the check above
         * passes against that, it is not checking anything.
         */
        val noTransfer = rulesText.replace(
            Regex("<device-transfer>[\\s\\S]*?</device-transfer>"),
            "",
        )
        assertFalse("mutation did not land", noTransfer.contains("device-transfer"))
        assertEquals(emptyList<String>(), excludedDomains(noTransfer, "device-transfer"))
        // ...while the section that is still there keeps reading, so the detector is not just
        // returning nothing for everything.
        assertTrue(excludedDomains(noTransfer, "cloud-backup").contains("root"))

        val rootIncluded = rulesText.replace("<exclude domain=\"root\" />", "<include domain=\"root\" />")
        assertTrue("mutation did not land", rootIncluded.contains("<include"))
        assertTrue(includeCount(rootIncluded) > 0)
        assertFalse(excludedDomains(rootIncluded, "device-transfer").contains("root"))
    }
}
