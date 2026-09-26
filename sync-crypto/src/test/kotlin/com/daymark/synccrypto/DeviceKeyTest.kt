package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [DeviceKey] and [DeviceSignature] beyond the vector (#186, #432): a key is fresh, a nonce is never
 * reused, what is signed is what the server rebuilds from the request, and a request the server would
 * read differently is not signed at all, because every refusal counts toward the lockout of the phone's
 * network address. Every refusal below is paired with the nearest request that is signed.
 */
class DeviceKeyTest {

    private val sodium = LazySodiumJava(SodiumJava())
    private val key = DeviceKey.generate(sodium)
    private val now = 1_790_000_000L
    private val target = "/v1/snapshots/devA/7"
    private val body = """{"hello":"daymark"}""".toByteArray(Charsets.UTF_8)

    @Test
    fun everyKeyIsFresh_andItsIdIsTheServersSpelling() {
        val other = DeviceKey.generate(sodium)
        assertFalse(key.publicKey.contentEquals(other.publicKey))
        assertNotEquals(key.keyId, other.keyId)
        assertEquals(22, key.keyId.length)
        assertEquals(DeviceSignature.KEY_ID_BYTES, SyncCrypto.fromBase64(key.keyId).size)
        assertEquals(43, key.publicKeyB64.length)
        assertArrayEquals(key.publicKey, SyncCrypto.fromBase64(key.publicKeyB64))
        // The public key handed out is a copy: changing it changes nothing the key signs or shows.
        val copy = key.publicKey
        copy[0] = (copy[0].toInt() xor 0xFF).toByte()
        assertFalse(copy.contentEquals(key.publicKey))
        assertEquals(key.keyId, DeviceKey.keyIdOf(sodium, key.publicKey))
    }

    @Test
    fun everySignatureHasANewNonce_aRetryIncluded() {
        val first = key.signRequest("PUT", target, body, now)
        val retry = key.signRequest("PUT", target, body, now)
        assertNotEquals(first.getValue(DeviceSignature.NONCE_HEADER), retry.getValue(DeviceSignature.NONCE_HEADER))
        assertNotEquals(first.getValue(DeviceSignature.SIGNATURE_HEADER), retry.getValue(DeviceSignature.SIGNATURE_HEADER))
        val nonces = (1..1000).map { key.signRequest("GET", "/v1/devices/registration", ByteArray(0), now).getValue(DeviceSignature.NONCE_HEADER) }
        assertEquals("a nonce was used twice", nonces.size, nonces.toSet().size)
        for (nonce in nonces) {
            assertEquals(22, nonce.length)
            assertEquals(DeviceSignature.NONCE_BYTES, SyncCrypto.fromBase64(nonce).size)
        }
    }

    @Test
    fun whatIsSignedIsTheMessageTheServerRebuildsFromTheRequest() {
        val etag = "\"v7\""
        val headers = key.signRequest("PUT", "$target?replace=1", body, now, listOf("if-match" to etag))
        assertEquals(DeviceSignature.HEADERS + "If-Match", headers.keys.toList())
        assertEquals(key.keyId, headers[DeviceSignature.KEY_HEADER])
        assertEquals("1790000000", headers[DeviceSignature.TIME_HEADER])
        // The server's side, from what the request carries: its headers, its target and its body.
        val rebuilt = DeviceSignature.requestMessage(
            "PUT",
            "$target?replace=1",
            DeviceKey.bodyHash(sodium, body),
            headers.getValue(DeviceSignature.TIME_HEADER),
            headers.getValue(DeviceSignature.NONCE_HEADER),
            DeviceSignature.SIGNED_HEADERS.map { headers[it] },
        )
        val signature = SyncCrypto.fromBase64(headers.getValue(DeviceSignature.SIGNATURE_HEADER))
        assertEquals(DeviceSignature.SIGNATURE_BYTES, signature.size)
        assertTrue(sodium.cryptoSignVerifyDetached(signature, rebuilt, rebuilt.size, key.publicKey))
        // Control: the same signature does not hold for a different body.
        val otherBody = DeviceSignature.requestMessage(
            "PUT",
            "$target?replace=1",
            DeviceKey.bodyHash(sodium, body + byteArrayOf(0x20)),
            headers.getValue(DeviceSignature.TIME_HEADER),
            headers.getValue(DeviceSignature.NONCE_HEADER),
            DeviceSignature.SIGNED_HEADERS.map { headers[it] },
        )
        assertFalse(sodium.cryptoSignVerifyDetached(signature, otherBody, otherBody.size, key.publicKey))
    }

    @Test
    fun aSignedHeaderGivenTwiceOrEmptyIsNotSigned() {
        for (name in DeviceSignature.SIGNED_HEADERS) {
            val given = listOf(name to "a")
            assertEquals("control: $name once is signed", "a", key.signRequest("PUT", target, body, now, given)[name])
            assertRefused(DeviceSignatureException.Reason.HEADER_TWICE, name) { key.signRequest("PUT", target, body, now, given + (name to "a")) }
            assertRefused(DeviceSignatureException.Reason.HEADER_TWICE, name) { key.signRequest("PUT", target, body, now, given + (name.uppercase() to "b")) }
            assertRefused(DeviceSignatureException.Reason.HEADER_TWICE, name) { key.signRequest("PUT", target, body, now, given + (name.lowercase() to "")) }
            assertRefused(DeviceSignatureException.Reason.HEADER_EMPTY, name) { key.signRequest("PUT", target, body, now, listOf(name to "")) }
        }
    }

    @Test
    fun onlyTheSignedHeadersAreTaken() {
        for (name in DeviceSignature.SIGNED_HEADERS + DeviceSignature.SIGNED_HEADERS.map { it.lowercase() } + DeviceSignature.SIGNED_HEADERS.map { it.uppercase() }) {
            assertTrue("control: $name", key.signRequest("PUT", target, body, now, listOf(name to "a")).containsValue("a"))
        }
        // U+017F folds to S, and U+212A to k, under Unicode's case rules; no header name is spelled with either.
        val lookalikes = listOf("X-ſhare-Meta", "X-Setting-Key")
        for (name in listOf("Content-Type", "Authorization", DeviceSignature.NONCE_HEADER, "If-Match ", " If-Match", "IfMatch") + lookalikes) {
            assertRefused(DeviceSignatureException.Reason.NOT_A_SIGNED_HEADER, null) { key.signRequest("PUT", target, body, now, listOf(name to "a")) }
        }
    }

    @Test
    fun aValueTheServerWouldReadDifferentlyIsNotSigned() {
        val name = DeviceSignature.SIGNED_HEADERS.first()
        for (value in listOf("a", "\"etag\"", "W/\"etag\"", "a b", "a\tb", "*", (' '..'~').joinToString("").trim())) {
            assertEquals("control: '$value'", value, key.signRequest("PUT", target, body, now, listOf(name to value))[name])
        }
        for (value in listOf(" a", "a ", "\ta", "a\t", " ", "a\nb", "a\rb", "a\u0000b", "a\u007Fb", "café", "a b")) {
            assertRefused(DeviceSignatureException.Reason.HEADER_VALUE, name) { key.signRequest("PUT", target, body, now, listOf(name to value)) }
        }
    }

    @Test
    fun theMethodTargetAndTimeAreOnlyOnesTheServerReadsBack() {
        for (method in listOf("GET", "PUT", "POST", "DELETE")) {
            assertEquals("control: $method", 4, key.signRequest(method, target, body, now).size)
        }
        for (method in listOf("", "put", "Get", "GET ", "GE T", "PUT\n", "PÉT")) {
            assertRefused(DeviceSignatureException.Reason.METHOD, null) { key.signRequest(method, target, body, now) }
        }
        val targets = listOf(
            target, "/v1/devices/registration", "/v1/owner/audit?before=5&limit=50", "/v1/x%2Fy", "/v1/a:b@c;d=e,f+g!h\$i'j(k)l*m~n",
        )
        for (t in targets) assertEquals("control: $t", 4, key.signRequest("GET", t, ByteArray(0), now).size)
        val refusedTargets = listOf(
            "", "/v1", "v1/x", "/v2/x", "/prefix/v1/x", "https://daymark.example.org/v1/x", "/v1/x y", "/v1/x\n", "/v1/x#f",
            "/v1/%zz", "/v1/%4", "/v1/x%", "/v1/café", "/v1/a\\b", "/v1/{x}",
        )
        for (t in refusedTargets) assertRefused(DeviceSignatureException.Reason.TARGET, null) { key.signRequest("GET", t, ByteArray(0), now) }
        assertEquals("0", key.signRequest("GET", target, ByteArray(0), 0L)[DeviceSignature.TIME_HEADER])
        assertEquals("999999999999999", key.signRequest("GET", target, ByteArray(0), DeviceSignature.LATEST_TIME)[DeviceSignature.TIME_HEADER])
        for (time in listOf(-1L, DeviceSignature.LATEST_TIME + 1, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertRefused(DeviceSignatureException.Reason.TIME, null) { key.signRequest("GET", target, ByteArray(0), time) }
        }
    }

    @Test
    fun noRefusalRepeatsAValue_andTheKeyShowsOnlyItsId() {
        val token = "inbox-token-that-is-a-credential"
        for (given in listOf(listOf("X-Rel-Token" to " $token"), listOf("X-Rel-Token" to token, "x-rel-token" to token))) {
            try {
                key.signRequest("PUT", target, body, now, given)
                fail("expected a refusal")
            } catch (e: DeviceSignatureException) {
                assertFalse(e.message!!.contains(token))
                assertEquals("X-Rel-Token", e.header)
                assertNull(e.cause)
            }
        }
        assertEquals("DeviceKey(${key.keyId})", key.toString())
    }

    private fun assertRefused(reason: DeviceSignatureException.Reason, header: String?, sign: () -> Unit) {
        try {
            sign()
            fail("expected $reason")
        } catch (e: DeviceSignatureException) {
            assertEquals(reason, e.reason)
            assertEquals(header, e.header)
        }
    }
}
