package com.daymark.app.ui.sky

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background screen (#534), read as source. Space only is handed the seed and nothing about a
 * memory; the picture with stars waits for the one-time note; the picture leaves only through a
 * provider that is not exported and serves one cache folder; and nothing here touches the network
 * or shared storage. Runs through `tools/jvm-tests.sh ui/sky sky`.
 */
class SkyWallpaperSourceTest {

    private val screen = repoFile("app/src/main/java/com/daymark/app/ui/sky/SkyWallpaperScreen.kt").readText()
    private val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
    private val paths = repoFile("app/src/main/res/xml/background_paths.xml").readText()

    private fun spaceOnlyParams(src: String) = src.substringAfter("private fun SkySpaceOnly(").substringBefore(")")
    private fun spaceOnlyCall(src: String) = src.substringAfter("SkyWallpaper.Mode.SPACE_ONLY -> SkySpaceOnly(").substringBefore(")")

    @Test
    fun `space only is handed the seed and nothing about a memory`() {
        // `look.nebulae` is the sky's own colours, not its weeks of writing.
        val memory = Regex("""layout|positions|(?<!look\.)nebulae|todayEpochDay|SkyLayout""")
        assertTrue(spaceOnlyParams(screen).contains("seed: Long"))
        assertFalse(memory.containsMatchIn(spaceOnlyParams(screen)))
        assertFalse(memory.containsMatchIn(spaceOnlyCall(screen)))
        val body = screen.substringAfter("private fun SkySpaceOnly(").substringBefore("\nprivate fun writePicture")
        assertFalse("space only reads a memory", memory.containsMatchIn(body))
        // The control: the same check sees a planted layout in the call.
        val planted = screen.replace("SkySpaceOnly(look = look,", "SkySpaceOnly(layout = layout, look = look,")
        assertNotEquals("the mutation did not land", screen, planted)
        assertTrue(memory.containsMatchIn(spaceOnlyCall(planted)))
    }

    @Test
    fun `a picture with stars waits for the note the first time`() {
        val onSet = screen.substringAfter("Button(\n                        onClick = {").substringBefore("},")
        assertTrue(onSet, onSet.contains("mode == SkyWallpaper.Mode.WITH_STARS && !noteSeen) noting = true else setIt()"))
        assertTrue(screen.contains("text = { Text(SkyWallpaper.STARS_NOTE) }"))
        // The note's own confirm records it as read, and only then sets the picture.
        val confirm = screen.substringAfter("text = { Text(SkyWallpaper.STARS_NOTE) }").substringBefore("dismissButton")
        assertTrue(confirm.indexOf("onNoteSeen()") in 0 until confirm.indexOf("setIt()"))
    }

    /** A lock screen is seen by others: no constellation name, card or selection reaches it. */
    @Test
    fun `with my stars draws no names, cards or selection`() {
        val call = screen.substringAfter("SkySurface(").substringBefore("modifier = Modifier.fillMaxSize(),\n                    )")
        assertTrue(call.contains("constellations = emptyList()"))
        assertTrue(call.contains("selectedStar = NO_STAR"))
        assertTrue(call.contains("planets = emptyList()"))
        assertTrue(call.contains("motionEnabled = false"))
    }

    @Test
    fun `the picture leaves only through a provider for one cache folder`() {
        val provider = manifest.substringAfter("androidx.core.content.FileProvider").substringBefore("</provider>")
        assertTrue(provider.contains("android:exported=\"false\""))
        assertTrue(provider.contains("android:grantUriPermissions=\"true\""))
        assertTrue(provider.contains("\${applicationId}.background"))
        assertTrue(screen.contains("WALLPAPER_AUTHORITY_SUFFIX = \".background\""))
        assertTrue(paths.contains("<cache-path name=\"background\" path=\"background/\" />"))
        assertTrue(screen.contains("WALLPAPER_FOLDER = \"background\""))
        assertFalse(paths.contains("external") || paths.contains("files-path") || paths.contains("root-path"))
        val shared = Regex("""MediaStore|getExternal|Environment\.|java\.net|HttpURLConnection|okhttp""")
        assertFalse(shared.containsMatchIn(screen))
        assertTrue(shared.containsMatchIn("$screen\nval u = MediaStore.Images.Media.EXTERNAL_CONTENT_URI"))
    }
}
