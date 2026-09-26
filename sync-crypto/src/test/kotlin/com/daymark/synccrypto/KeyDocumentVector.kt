package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong

/**
 * The #403 vector: the constants of `companion/web/src/lib/recovery/dataKeyVector.test.ts`, which
 * made them with the web's own writer. A change to any value here is a change to the wire format,
 * never a refactor, and the web test must change with it.
 *
 * The two documents are an enrolment: the wrapped key locks the master that the key parameters
 * derive from the passphrase.
 */
internal object KeyDocumentVector {
    /** Two-, three- and four-byte UTF-8 sequences, so the vector pins how a passphrase becomes bytes. */
    const val PASSPHRASE = "wrapped-key vector: caf\u00E9, \u65E5\u8A18, \uD83C\uDF3F"
    const val PASSPHRASE_UTF8 = "777261707065642d6b657920766563746f723a20636166c3a92c20e697a5e8a8982c20f09f8cbf"

    const val PAYLOAD = "K7M2QXR9CT4HWAZP3NE8GUV6DYJF5"
    const val CODE = "K7M2QXR9CT4HWAZP3NE8GUV6DYJF59"

    /** The code as a person might type it back: lower case, an en dash, a no-break space, a spaced em dash, a tab, a newline. */
    const val TYPED_CODE = " k7m2q\u2013xr9ct\u00A04hwaz \u2014 p3ne8\tguv6d\nyjf59 "

    const val KEY_PARAMS =
        """{"v":1,"alg":"xchacha20poly1305","kdf":{"alg":"argon2id","memMiB":256,"ops":3},"saltB64":"EBESExQVFhcYGRobHB0eHw"}"""
    val KEY_PARAMS_SALT = ByteArray(16) { (0x10 + it).toByte() }

    val PASSPHRASE_SALT = ByteArray(16) { (0x20 + it).toByte() }
    val PASSPHRASE_NONCE = ByteArray(24) { (0x30 + it).toByte() }
    val RECOVERY_SALT = ByteArray(16) { (0x50 + it).toByte() }
    val RECOVERY_NONCE = ByteArray(24) { (0x60 + it).toByte() }

    const val PASSPHRASE_SALT_B64 = "ICEiIyQlJicoKSorLC0uLw"
    const val PASSPHRASE_NONCE_B64 = "MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZH"
    const val PASSPHRASE_CT_B64 = "RATPiu0OXZXeHyv_qf9sywj4klVNv2V0bfJatAFESi0ULES6wAX9gxbYETbgJ_hV"
    const val RECOVERY_SALT_B64 = "UFFSU1RVVldYWVpbXF1eXw"
    const val RECOVERY_NONCE_B64 = "YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3"
    const val RECOVERY_CT_B64 = "EKWynx8Jr3QkiB8YRyYoeVzI6reqpmygeRV0lO9MPU-wYBq9zdqyKCgf-wkR-nC9"

    const val WRAPPED = "{\"v\":1,\"slots\":[" +
        "{\"kind\":\"passphrase\",\"kdf\":{\"alg\":\"argon2id\",\"memMiB\":256,\"ops\":3},\"saltB64\":\"$PASSPHRASE_SALT_B64\"," +
        "\"nonceB64\":\"$PASSPHRASE_NONCE_B64\",\"ctB64\":\"$PASSPHRASE_CT_B64\"}," +
        "{\"kind\":\"recovery\",\"kdf\":{\"alg\":\"argon2id\",\"memMiB\":256,\"ops\":3},\"saltB64\":\"$RECOVERY_SALT_B64\"," +
        "\"nonceB64\":\"$RECOVERY_NONCE_B64\",\"ctB64\":\"$RECOVERY_CT_B64\"}]}"
    const val WRAPPED_SHA256 = "8f47f8f8f875deabc23a45c54fda979a1faf4d5a1a2fdebdb688a08df6f3b2c1"

    const val MASTER = "fd4b977bf6032cbf030a5339c2525bc63d64a49bfb647534e64e462b6e129a14"
    const val SUBKEY_1 = "c6c95a6ff06a5386d366c4fd16746850927c9115408ac3ca7ca72868a7125e63" // SYNC_KEY
    const val SUBKEY_2 = "cb7c3ed93bb15409a1250c5e8c7b2b245026a90e06d8cab6e79cd80424a972e6" // MANIFEST_SEED
    const val SUBKEY_3 = "ad6e05995dd77043b6f12335dd56e1765bcc1ca96d4dfec4fcf2facd9a81d907" // the owner's X25519 seed
    const val SUBKEY_4 = "6f706ed43396fcb040d1a78898e61eb8f35da3a3141e976146cce7e793efbf1f" // the owner's Ed25519 seed

    /**
     * The wrapped key as a tree a test can change one field of, before [json] writes it back. It is
     * built here, not read by [KeyDocument], so a test's input never depends on the reader it tests;
     * `json(wrappedTree())` is [WRAPPED], which KeyDocumentVectorTest checks.
     */
    fun wrappedTree(): MutableMap<String, Any?> = linkedMapOf(
        "v" to Raw("1"),
        "slots" to mutableListOf<Any?>(
            slotTree("passphrase", PASSPHRASE_SALT_B64, PASSPHRASE_NONCE_B64, PASSPHRASE_CT_B64),
            slotTree("recovery", RECOVERY_SALT_B64, RECOVERY_NONCE_B64, RECOVERY_CT_B64),
        ),
    )

    fun keyParamsTree(): MutableMap<String, Any?> = linkedMapOf(
        "v" to Raw("1"),
        "alg" to "xchacha20poly1305",
        "kdf" to kdfTree(),
        "saltB64" to "EBESExQVFhcYGRobHB0eHw",
    )

    fun kdfTree(): MutableMap<String, Any?> = linkedMapOf("alg" to "argon2id", "memMiB" to Raw("256"), "ops" to Raw("3"))

    private fun slotTree(kind: String, salt: String, nonce: String, ct: String): MutableMap<String, Any?> = linkedMapOf(
        "kind" to kind,
        "kdf" to kdfTree(),
        "saltB64" to salt,
        "nonceB64" to nonce,
        "ctB64" to ct,
    )

    @Suppress("UNCHECKED_CAST")
    fun MutableMap<String, Any?>.slots(): MutableList<Any?> = this["slots"] as MutableList<Any?>

    @Suppress("UNCHECKED_CAST")
    fun MutableMap<String, Any?>.slot(i: Int): MutableMap<String, Any?> = slots()[i] as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun MutableMap<String, Any?>.kdf(): MutableMap<String, Any?> = this["kdf"] as MutableMap<String, Any?>

    /** JSON text written as it is, for a number or anything else a test spells out itself. */
    class Raw(val text: String)

    /** No spaces and no escaping: every string in these trees is a plain word or base64url. */
    fun json(value: Any?): String = when (value) {
        null -> "null"
        is Raw -> value.text
        is String -> "\"$value\""
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "\"${it.key}\":${json(it.value)}" }
        is List<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> error("no JSON form for ${value::class.simpleName}")
    }

    /** The base64url alphabet in value order, so a test can change one symbol's bits exactly. */
    const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** The same text with the lowest bit of its last symbol flipped: bits after the last byte. */
    fun strayBits(s: String): String = s.dropLast(1) + B64[B64.indexOf(s.last()) xor 1]

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/** Real libsodium that counts its Argon2id runs, so a test can say a refusal came before any. */
internal class CountingSodium : LazySodiumJava(SodiumJava()) {
    var argon2idRuns = 0
        private set

    override fun cryptoPwHash(
        outputHash: ByteArray,
        outputHashLen: Int,
        password: ByteArray,
        passwordLen: Int,
        salt: ByteArray,
        opsLimit: Long,
        memLimit: NativeLong,
        alg: PwHash.Alg,
    ): Boolean {
        argon2idRuns++
        return super.cryptoPwHash(outputHash, outputHashLen, password, passwordLen, salt, opsLimit, memLimit, alg)
    }
}
