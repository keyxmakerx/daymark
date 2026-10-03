package com.daymark.synccrypto

import com.daymark.synccrypto.PairingVerdict.Decline
import com.daymark.synccrypto.PairingVerdict.Reason
import com.daymark.synccrypto.PairingVerdict.Redeem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.URLClassLoader

/**
 * The https verdict (#189, #432): the QR code's text, or a typed address and code, in; [Redeem] or
 * [Decline] out, before anything is sent. The phone is the one party the network cannot rewrite, so it
 * is the one that refuses an address that is not https.
 */
class PairingVerdictTest {

    private val example = "daymark-pair:v1?server=https%3A%2F%2Fdaymark.example.org&code=K7M4RD96QA"
    private val address = "https://daymark.example.org"
    private val code = "K7M4RD96QA"

    @Test
    fun theExampleIsRedeemed() {
        assertEquals(Redeem(address, PairingCode.parse(code)), PairingVerdict.ofScan(example))
    }

    @Test
    fun anHttpAddressIsDeclined_theMutantDerivedByReplacingTheScheme() {
        val server = example.substringAfter("server=").substringBefore('&')
        val scanned = PairingVerdict.decodeURIComponent(server)!!
        assertEquals("control: the example carries the address", address, scanned)
        val http = scanned.replaceFirst(Regex("^https:"), "http:")
        assertNotEquals("the mutant must really change the scheme", scanned, http)
        val mutant = example.replace("server=$server", "server=" + PairingVerdict.encodeURIComponent(http))
        assertNotEquals("the mutant must really change the text", example, mutant)
        assertEquals(Decline(Reason.NOT_HTTPS), PairingVerdict.ofScan(mutant))
        assertEquals(Decline(Reason.NOT_HTTPS), PairingVerdict.ofTyped(http, code))
        // Nor any other scheme, or none, or one only a Unicode case rule would fold into https.
        for (other in listOf("HTTP://daymark.example.org", "ftp://daymark.example.org", "wss://daymark.example.org", "daymark.example.org", "https:/daymark.example.org", "https//daymark.example.org", "//daymark.example.org", "http\u017F://daymark.example.org", "")) {
            assertEquals(other, Decline(Reason.NOT_HTTPS), PairingVerdict.ofTyped(other, code))
        }
    }

    /**
     * A scan is a typing of the two values the QR carries (#189): over every kind of address and code,
     * the QR the console would draw for them gets exactly the verdict typing them gets.
     */
    @Test
    fun typedInputGetsTheSameVerdictAsAScan() {
        val addresses = listOf(
            address, "HTTPS://Daymark.Example.org/", "https://example.org:8443/daymark", "https://[::1]:8443", " https://192.168.1.20 ",
            "http://daymark.example.org", "https://user@daymark.example.org", "https://", "https://daymark.example.org?x=1",
            "https://daymark.example.org#x", "https://daymark .example.org", "https://daymark.example.org\\@evil.example", "https://caf\u00E9.example",
        )
        val codes = listOf(code, "k7m4r-d96qa", "K7M4R D96QA", "", "K7M4R0D96QA", "K7M4R#D96QA", "K7M4RD96Q", "K7M4RD96QAA", "K7M4RD96QB")
        val seen = HashSet<PairingVerdict>()
        for (a in addresses) {
            for (c in codes) {
                val typed = PairingVerdict.ofTyped(a, c)
                val serverFirst = "daymark-pair:v1?server=${PairingVerdict.encodeURIComponent(a)}&code=${PairingVerdict.encodeURIComponent(c)}"
                val codeFirst = "daymark-pair:v1?code=${PairingVerdict.encodeURIComponent(c)}&server=${PairingVerdict.encodeURIComponent(a)}"
                assertEquals("'$a' and '$c'", typed, PairingVerdict.ofScan(serverFirst))
                assertEquals("'$a' and '$c', code first", typed, PairingVerdict.ofScan(codeFirst))
                seen += typed
            }
        }
        // The table reaches every address and code verdict, so the equality above is not about one of them.
        val reached = seen.map { if (it is Decline) it.reason.name else "REDEEM" }.toSet()
        val expected = setOf(
            "REDEEM", "NOT_HTTPS", "USER_NAME", "NO_HOST", "QUERY", "FRAGMENT", "NOT_AN_ADDRESS",
            "CODE_EMPTY", "CODE_CONFUSABLE", "CODE_NOT_IN_ALPHABET", "CODE_TOO_SHORT", "CODE_TOO_LONG", "CODE_CHECK_SYMBOL",
        )
        assertEquals(expected, reached)
        assertEquals(
            "and it redeems at four places, so a scan that lost or bent the address would show",
            setOf(address, "https://example.org:8443/daymark", "https://[::1]:8443", "https://192.168.1.20"),
            seen.filterIsInstance<Redeem>().map { it.address }.toSet(),
        )
    }

    @Test
    fun theTextIsExactlyTheConsolesForm() {
        val server = "server=https%3A%2F%2Fdaymark.example.org"
        val codeParameter = "code=K7M4RD96QA"
        val declined = mapOf(
            Reason.NOT_A_PAIRING_QR to listOf(
                "DAYMARK-PAIR:v1?$server&$codeParameter", "daymark-pairing:v1?$server&$codeParameter", "https://daymark.example.org",
                " daymark-pair:v1?$server&$codeParameter", "",
            ),
            Reason.UNKNOWN_VERSION to listOf(
                "daymark-pair:v2?$server&$codeParameter", "daymark-pair:V1?$server&$codeParameter", "daymark-pair:v1.1?$server&$codeParameter",
                "daymark-pair:?$server&$codeParameter", "daymark-pair:$server&$codeParameter",
            ),
            Reason.EXTRA_PARAMETER to listOf(
                "daymark-pair:v1?$server&$codeParameter&x=1", "daymark-pair:v1?$server&$codeParameter&", "daymark-pair:v1?&$server&$codeParameter",
                "daymark-pair:v1?$server&&$codeParameter", "daymark-pair:v1?Server=https%3A%2F%2Fdaymark.example.org&$codeParameter",
                "daymark-pair:v1?$server&code",
            ),
            Reason.DUPLICATE_PARAMETER to listOf(
                "daymark-pair:v1?$server&$server&$codeParameter", "daymark-pair:v1?$server&$codeParameter&$codeParameter", "daymark-pair:v1?$server&$server",
            ),
            Reason.MISSING_PARAMETER to listOf(
                "daymark-pair:v1", "daymark-pair:v1?", "daymark-pair:v1?$server", "daymark-pair:v1?$codeParameter",
                // Only & separates parameters: this is one server value, and no code.
                "daymark-pair:v1?$server;$codeParameter",
            ),
            Reason.NOT_ENCODED to listOf(
                "daymark-pair:v1?server=https%3a%2f%2fdaymark.example.org&$codeParameter",
                "daymark-pair:v1?server=https://daymark.example.org&$codeParameter",
                "daymark-pair:v1?server=https%3A%2F%2Fdaymark.example.org%&$codeParameter",
                "daymark-pair:v1?server=https%3A%2F%2Fdaymark.example.org%2&$codeParameter",
                "daymark-pair:v1?server=https%3A%2F%2Fdaymark%GG.example.org&$codeParameter",
                "daymark-pair:v1?server=https%3A%2F%2Fcaf%FF.example&$codeParameter",
                "daymark-pair:v1?server=https%3A%2F%2F%C0%AE.example&$codeParameter",
                "daymark-pair:v1?server=https%3A%2F%2Fcaf\u00E9.example&$codeParameter",
                "daymark-pair:v1?$server&code=K7M4R D96QA",
                "daymark-pair:v1?$server&code=K7M4R+D96QA",
                "daymark-pair:v1?$server&$codeParameter\n",
                "daymark-pair:v1?$server&$codeParameter#",
            ),
        )
        for ((reason, texts) in declined) {
            for (text in texts) assertEquals(text, Decline(reason), PairingVerdict.ofScan(text))
        }
        // Controls: either order, and the code as the console shows it, encoded as the console encodes it.
        val redeem = Redeem(address, PairingCode.parse(code))
        assertEquals(redeem, PairingVerdict.ofScan("daymark-pair:v1?$codeParameter&$server"))
        assertEquals(redeem, PairingVerdict.ofScan("daymark-pair:v1?$server&code=K7M4R-D96QA"))
        assertEquals(redeem, PairingVerdict.ofScan("daymark-pair:v1?$server&code=K7M4R%20D96QA"))
    }

    @Test
    fun theAddressIsHttpsAHostAndNothingTwoParsersCouldReadTwoWays() {
        val redeemedAt = mapOf(
            address to address,
            "https://daymark.example.org/" to address,
            "HTTPS://Daymark.Example.ORG" to address,
            "Https://daymark.example.org" to address,
            " \u00A0https://daymark.example.org\n" to address,
            "https://daymark.example.org:8443" to "https://daymark.example.org:8443",
            "https://example.org/daymark" to "https://example.org/daymark",
            "https://example.org/Daymark//" to "https://example.org/Daymark",
            "https://example.org/my%20daymark" to "https://example.org/my%20daymark",
            "https://192.168.1.20:8443" to "https://192.168.1.20:8443",
            "https://[::1]:8443" to "https://[::1]:8443",
            "https://[FE80::1]" to "https://[fe80::1]",
            "https://daymark.example.org:65535" to "https://daymark.example.org:65535",
            "https://daymark.example.org:1" to "https://daymark.example.org:1",
        )
        for ((typed, at) in redeemedAt) {
            assertEquals(typed, Redeem(at, PairingCode.parse(code)), PairingVerdict.ofTyped(typed, code))
        }
        val declined = mapOf(
            Reason.USER_NAME to listOf(
                "https://user@daymark.example.org", "https://user:secret@daymark.example.org", "https://daymark.example.org@evil.example",
                "https://@daymark.example.org", "https://evil.example\\@daymark.example.org", "https://daymark.example.org:443@evil.example",
            ),
            Reason.NO_HOST to listOf("https://", "https:///daymark", "https://:443", "https://?x", "https://#x"),
            Reason.QUERY to listOf("https://daymark.example.org?x=1", "https://daymark.example.org/?x", "https://daymark.example.org/daymark?"),
            Reason.FRAGMENT to listOf("https://daymark.example.org#x", "https://daymark.example.org/#", "https://daymark.example.org/daymark#?x"),
            Reason.NOT_AN_ADDRESS to listOf(
                "https://daymark.example.org:", "https://daymark.example.org:0", "https://daymark.example.org:65536",
                "https://daymark.example.org:08443", "https://daymark.example.org:84a3", "https://daymark.example.org:8443:1",
                "https://daymark .example.org", "https://daymark.example.org\\evil", "https://daymark.example.org/a b",
                "https://daym%61rk.example.org", "https://.example.org", "https://example..org", "https://example.org.",
                "https://caf\u00E9.example", "https://daymark.exa\u200Bmple.org", "https://daymark.example.org/a\tb",
                "https://[::1", "https://[]", "https://[::1]x", "https://[g::1]", "https://[127.0.0.1]",
                "https://daymark.example.org/%zz", "https://daymark.example.org/%4", "https://daymark.example.org/{x}",
            ),
        )
        for ((reason, typed) in declined) {
            for (t in typed) assertEquals(t, Decline(reason), PairingVerdict.ofTyped(t, code))
        }
    }

    /** Vectors made by Node 22's own `encodeURIComponent`, and its URIError for an unpaired surrogate. */
    @Test
    fun theEncodingIsJavaScriptsEncodeURIComponent() {
        val vectors = mapOf(
            "https://daymark.example.org" to "https%3A%2F%2Fdaymark.example.org",
            "https://daymark.example.org:8443/my-daymark" to "https%3A%2F%2Fdaymark.example.org%3A8443%2Fmy-daymark",
            "https://[::1]:8443" to "https%3A%2F%2F%5B%3A%3A1%5D%3A8443",
            (' '..'~').joinToString("") to
                "%20!%22%23%24%25%26'()*%2B%2C-.%2F0123456789%3A%3B%3C%3D%3E%3F%40ABCDEFGHIJKLMNOPQRSTUVWXYZ%5B%5C%5D%5E_%60abcdefghijklmnopqrstuvwxyz%7B%7C%7D~",
            "\u00E9\u20AC\uD83D\uDE00" to "%C3%A9%E2%82%AC%F0%9F%98%80",
        )
        for ((text, encoded) in vectors) {
            assertEquals(encoded, PairingVerdict.encodeURIComponent(text))
            assertEquals(text, PairingVerdict.decodeURIComponent(encoded))
        }
        assertNull(PairingVerdict.encodeURIComponent("\uD800"))
        assertNull(PairingVerdict.encodeURIComponent("a\uDC00"))
    }

    @Test
    fun aDeclineCarriesOnlyItsReason_andARedeemNeverShowsItsCode() {
        val redeem = PairingVerdict.ofScan(example)
        assertFalse(redeem.toString().contains(code))
        val declined = PairingVerdict.ofTyped("https://user:secret@daymark.example.org", code)
        assertEquals("Decline(reason=USER_NAME)", declined.toString())
    }

    /**
     * The verdict runs where libsodium is not even on the class path: loaded with only this module's
     * classes and the Kotlin standard library, under the platform's own class loader, it gives the same
     * verdicts. Control: that same loader finds neither lazysodium nor JNA, and cannot resolve SyncCrypto,
     * which is made with a LazySodium, so it is not quietly finding libsodium somewhere.
     */
    @Test
    fun theVerdictRunsWithNoNativeLibrary() {
        val ownClasses = File(PairingVerdict::class.java.protectionDomain.codeSource.location.toURI())
        val kotlinStdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
        URLClassLoader(arrayOf(ownClasses.toURI().toURL(), kotlinStdlib.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { bare ->
            for (unreachable in listOf("com.goterl.lazysodium.LazySodium", "com.sun.jna.Native")) {
                try {
                    bare.loadClass(unreachable)
                    fail("the bare loader reaches $unreachable")
                } catch (_: ClassNotFoundException) {
                    // expected
                }
            }
            try {
                bare.loadClass(SyncCrypto::class.java.name).declaredConstructors
                fail("the bare loader resolved SyncCrypto's constructor, so it is not bare")
            } catch (_: NoClassDefFoundError) {
                // expected: SyncCrypto is made with a LazySodium
            }
            val verdicts = bare.loadClass(PairingVerdict::class.java.name + "\$Companion")
            val companion = bare.loadClass(PairingVerdict::class.java.name).getField("Companion").get(null)
            val ofScan = verdicts.getMethod("ofScan", String::class.java)
            val ofTyped = verdicts.getMethod("ofTyped", String::class.java, String::class.java)
            assertEquals("Redeem(address=$address, code=PairingCode(not shown))", ofScan.invoke(companion, example).toString())
            assertEquals("Decline(reason=NOT_HTTPS)", ofTyped.invoke(companion, "http://daymark.example.org", code).toString())
            assertEquals("Decline(reason=CODE_CHECK_SYMBOL)", ofTyped.invoke(companion, address, "K7M4RD96QB").toString())
        }
    }
}
