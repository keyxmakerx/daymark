package com.daymark.app.ui.settings

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline build reaches no server, by construction (#432; docs/COMPANION_PHONE.md §0).
 *
 * The `sync` flavour's server screen is reached through one interface in `main`, [ServerSyncDoor], and
 * one object per flavour, `FlavorDoors`: the `foss` one holds null. So what this holds true:
 *
 *  - Nothing under `app/src/main` or `app/src/foss` names a network client, `:sync-crypto`, libsodium
 *    or the `sync` flavour's own package, by import or by full name. `:sync-crypto` is not on the
 *    `foss` class path at all, so a name here would break that build; this says so before CI does,
 *    and names the file.
 *  - The `foss` door is null, and Settings and the navigation graph reach the screen only inside
 *    `FlavorDoors.serverSync?.let { … }`, so with a null door there is no row and no route.
 *  - `INTERNET` is asked for in the `sync` flavour's manifest and not in `main`'s. CI's check of the
 *    built `foss` APK's permissions is the final word; this is the source's half.
 *  - The `sync` flavour's screen draws no tick icon and no colour of its own: semantic roles only.
 *
 * Comments and string literals are blanked first ([codeOnly]), so a rule can be stated in the words it
 * forbids. Every scanner is shown a planted example, and every walk is shown the files it must find.
 */
class ServerSyncSeamSourceTest {

    private companion object {
        const val SETTINGS = "app/src/main/java/com/daymark/app/ui/settings/SettingsScreen.kt"
        const val SCAFFOLD = "app/src/main/java/com/daymark/app/ui/DaymarkAppScaffold.kt"
        const val DOOR = "app/src/main/java/com/daymark/app/ui/settings/ServerSyncDoor.kt"
        const val FOSS_DOORS = "app/src/foss/java/com/daymark/app/flavor/FlavorDoors.kt"
        const val SYNC_DOORS = "app/src/sync/java/com/daymark/app/flavor/FlavorDoors.kt"
        const val MAIN_MANIFEST = "app/src/main/AndroidManifest.xml"
        const val SYNC_MANIFEST = "app/src/sync/AndroidManifest.xml"
        const val DOOR_BLOCK = "FlavorDoors.serverSync?.let {"

        /** What reaches a network, or the code that does, by import or by full name in code. */
        val NETWORK = Regex(
            """\b(java\.net\.|javax\.net\.|okhttp3\.|HttpURLConnection|HttpsURLConnection|com\.daymark\.synccrypto\.|com\.goterl\.|com\.daymark\.app\.sync\.)""",
        )

        /** A Material icon that draws a tick, in any style, as `TickAndGreenSourceTest` lists them. */
        val TICK_ICON = Regex("""\bIcons\.(?:\w+\.)*(?:Check|CheckCircle|CheckCircleOutline|Done|DoneAll|DoneOutline|TaskAlt|CheckBox|Verified)\b""")

        /** A colour written into a screen rather than taken from the theme's roles. */
        val RAW_COLOUR = Regex("""\bColor\s*\(|\bColor\.(?:Green|GREEN|Red|RED|Blue|White|Black)\b""")
    }

    /** `app/src`, found from a file that must be there. */
    private val src: File = File(repoFile(SETTINGS).absolutePath.substringBefore("/app/src/") + "/app/src")

    private fun kotlinUnder(set: String): List<File> =
        File(src, set).walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun networkIn(file: File): List<String> {
        val code = codeOnly(file.readText())
        return NETWORK.findAll(code).map { "${file.relativeTo(src)}: ${it.value}" }.toList()
    }

    /** Every `{ … }` block that opens with [opener] in [code], as index ranges; braces in literals are already blank. */
    private fun blocks(code: String, opener: String): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var at = code.indexOf(opener)
        while (at >= 0) {
            var depth = 0
            var index = at + opener.length - 1
            while (index < code.length) {
                when (code[index]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) {
                        ranges += at..index
                        break
                    }
                }
                index++
            }
            at = code.indexOf(opener, at + opener.length)
        }
        return ranges
    }

    private fun occurrences(code: String, needle: String): List<Int> {
        val found = ArrayList<Int>()
        var at = code.indexOf(needle)
        while (at >= 0) {
            found += at
            at = code.indexOf(needle, at + needle.length)
        }
        return found
    }

    /** Whether every [needle] in [code] lies inside a door block, and there is at least one. */
    private fun behindTheDoor(code: String, needle: String): Boolean {
        val doors = blocks(code, DOOR_BLOCK)
        val at = occurrences(code, needle)
        return at.isNotEmpty() && at.all { index -> doors.any { index in it } }
    }

    @Test
    fun `the scanners see what they look for, in code and not in comments or strings`() {
        val planted = File.createTempFile("planted", ".kt").apply {
            writeText(
                "package x\n// java.net.URL in a comment\nimport java.net.URL\nval s = \"com.daymark.synccrypto.PhoneSync\"\n" +
                    "fun f() = com.daymark.app.sync.ServerSyncEntry\n",
            )
            deleteOnExit()
        }
        val code = codeOnly(planted.readText())
        assertEquals(listOf("java.net.", "com.daymark.app.sync."), NETWORK.findAll(code).map { it.value }.toList())
        assertTrue(TICK_ICON.containsMatchIn("Icon(Icons.Filled.Check, null)"))
        assertTrue(TICK_ICON.containsMatchIn("Icons.AutoMirrored.Outlined.TaskAlt"))
        assertFalse(TICK_ICON.containsMatchIn("Icons.AutoMirrored.Filled.ArrowBack"))
        assertTrue(RAW_COLOUR.containsMatchIn("color = Color(0xFF2E7D32)"))
        assertTrue(RAW_COLOUR.containsMatchIn("Color.Green"))
        assertFalse(RAW_COLOUR.containsMatchIn("color = MaterialTheme.colorScheme.onSurface"))
    }

    @Test
    fun `nothing the offline build compiles names a network, sync-crypto or the sync flavour`() {
        val main = kotlinUnder("main")
        val foss = kotlinUnder("foss")
        assertTrue("the walk of main found implausibly few files: ${main.size}", main.size > 150)
        assertTrue("the walk missed the door", main.any { it.path.endsWith(DOOR) })
        assertTrue("the walk missed the foss door", foss.any { it.path.endsWith(FOSS_DOORS) })
        val found = (main + foss).flatMap { networkIn(it) }
        assertEquals("the offline build names something that reaches a network", emptyList<String>(), found)

        // The control: the sync flavour's own files are found, and do name them.
        val sync = kotlinUnder("sync")
        assertTrue("the walk missed the sync door", sync.any { it.path.endsWith(SYNC_DOORS) })
        assertTrue("the scanner is blind to the sync flavour's imports", sync.flatMap { networkIn(it) }.size >= 5)
    }

    @Test
    fun `the offline build's door is null, and the sync flavour's is the screen`() {
        val foss = codeOnly(repoFile(FOSS_DOORS).readText())
        assertTrue(Regex("""val serverSync: ServerSyncDoor\? = null\s*}""").containsMatchIn(foss))
        val sync = codeOnly(repoFile(SYNC_DOORS).readText())
        assertTrue(Regex("""val serverSync: ServerSyncDoor\? = ServerSyncEntry\b""").containsMatchIn(sync))
        // Both flavours' objects are the one name main compiles against.
        for (doors in listOf(foss, sync)) {
            assertTrue(doors.contains("package com.daymark.app.flavor"))
            assertTrue(doors.contains("object FlavorDoors"))
        }
    }

    @Test
    fun `settings and the navigation graph reach the screen only through the door`() {
        val settings = codeOnly(repoFile(SETTINGS).readText())
        val scaffold = codeOnly(repoFile(SCAFFOLD).readText())
        assertTrue("the Settings row is not behind the door", behindTheDoor(settings, ".SettingsRow("))
        assertTrue("the route is not behind the door", behindTheDoor(scaffold, "composable(Routes.SERVER_SYNC"))
        assertTrue("the screen is not behind the door", behindTheDoor(scaffold, ".Screen("))
        assertEquals("the route is registered more than once", 1, occurrences(scaffold, "composable(Routes.SERVER_SYNC").size)

        // The control: without the door's `?.let`, the same row and route read as outside it.
        val open = scaffold.replace(DOOR_BLOCK, "run {")
        assertTrue("the mutation did not land", open != scaffold)
        assertFalse("the detector cannot see a route outside the door", behindTheDoor(open, "composable(Routes.SERVER_SYNC"))
        assertFalse(behindTheDoor(settings.replace(DOOR_BLOCK, "run {"), ".SettingsRow("))
    }

    @Test
    fun `internet is asked for by the sync flavour alone`() {
        fun permissionsIn(rel: String) =
            repoFile(rel).readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        assertFalse("main's manifest asks for INTERNET", permissionsIn(MAIN_MANIFEST).contains("android.permission.INTERNET"))
        assertTrue("the control: the sync manifest's request was not seen", permissionsIn(SYNC_MANIFEST).contains("android.permission.INTERNET"))
        // The foss flavour has no manifest of its own; if it ever gains one, it asks for no network either.
        val fossManifest = File(src, "foss/AndroidManifest.xml")
        assertFalse("the foss manifest asks for INTERNET", fossManifest.isFile && fossManifest.readText().contains("android.permission.INTERNET"))
    }

    @Test
    fun `the sync flavour's screens draw no tick and no colour of their own`() {
        val sync = kotlinUnder("sync")
        assertTrue(sync.any { it.name == "ServerSyncScreen.kt" })
        val found = sync.flatMap { file ->
            val code = codeOnly(file.readText())
            (TICK_ICON.findAll(code) + RAW_COLOUR.findAll(code)).map { "${file.name}: ${it.value}" }.toList()
        }
        assertEquals(emptyList<String>(), found)
    }
}
