package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodium

/**
 * The six words of a device's key (#189, #432): what the phone and the owner's console both show while
 * the person decides whether to confirm this phone. The console computes them from the key the server
 * relays (#431), so if anything between the two substituted a key, the words differ.
 *
 * The words are the first six bytes of BLAKE2b-256 over the UTF-8 text `daymark-device-words-v1`, a line
 * feed, and the public key in base64url; each byte picks one word of [WORDS]. They are for a person to
 * compare, and are never an input to anything.
 *
 * [WORDS] is the web's list, `companion/web/src/lib/share/wordlist.ts`, in its order: 256 words, so one
 * byte picks exactly one. DeviceWordsDriftTest reads that file and fails when the two lists differ.
 */
object DeviceWords {
    /** The first line of what the words are hashed from. */
    const val CONTEXT = "daymark-device-words-v1"

    /** How many words a key has: six bytes, 48 bits. */
    const val COUNT = 6

    val WORDS: List<String> = listOf(
        "able", "acid", "acorn", "actor", "adobe", "agent", "album", "alert",
        "alien", "alley", "amber", "angle", "ankle", "apple", "april", "apron",
        "arena", "armor", "array", "arrow", "atlas", "attic", "audio", "award",
        "badge", "baker", "banjo", "basil", "basin", "beach", "beard", "beast",
        "begin", "berry", "bison", "blade", "blaze", "blend", "block", "bloom",
        "board", "bonus", "boost", "booth", "brain", "brake", "brave", "bread",
        "brick", "brief", "bring", "broom", "brush", "bugle", "bunny", "cabin",
        "cable", "cacao", "camel", "candy", "canoe", "canon", "cargo", "carol",
        "catch", "cedar", "chair", "chalk", "charm", "chart", "chase", "cheer",
        "chess", "chief", "chime", "chunk", "cider", "cliff", "climb", "cloak",
        "clock", "cloud", "clove", "clown", "coach", "coast", "cobra", "cocoa",
        "comet", "coral", "couch", "crane", "crate", "creek", "crest", "crisp",
        "crown", "curve", "daisy", "dance", "delta", "depth", "diary", "diner",
        "ditch", "diver", "dodge", "dough", "draft", "drake", "dream", "dress",
        "drift", "drink", "drone", "eagle", "earth", "easel", "ebony", "elbow",
        "elder", "elite", "ember", "emoji", "empty", "entry", "equal", "exact",
        "fable", "fairy", "fancy", "fault", "feast", "fence", "ferry", "fever",
        "field", "fiber", "final", "flame", "flare", "flash", "fleet", "flint",
        "float", "flock", "flour", "flute", "focus", "foggy", "forge", "found",
        "frost", "fruit", "fudge", "gauge", "ghost", "giant", "glade", "gleam",
        "glide", "globe", "glory", "glove", "grace", "grain", "grape", "grasp",
        "grass", "green", "grove", "guard", "guest", "guide", "habit", "harbor",
        "hasty", "hatch", "haven", "hazel", "heart", "hedge", "hello", "heron",
        "hinge", "hobby", "honey", "horse", "hotel", "house", "human", "humor",
        "ideal", "igloo", "image", "index", "inlet", "input", "ivory", "jelly",
        "jewel", "joker", "jolly", "juice", "kayak", "ketch", "kiosk", "kite",
        "koala", "label", "labor", "lance", "large", "laser", "latch", "layer",
        "leader", "ledge", "lemon", "level", "lever", "light", "lilac", "linen",
        "llama", "lodge", "lotus", "lucky", "lunar", "magic", "mango", "maple",
        "march", "marsh", "medal", "melon", "mercy", "metal", "meter", "mimic",
        "miner", "model", "money", "month", "moral", "motor", "mound", "mouse",
        "nacho", "nerve", "niche", "noble", "north", "novel", "nurse", "oasis",
    )

    /**
     * The six words of the public key [publicKeyB64], which must be the canonical base64url of 32 bytes:
     * a key spelled another way would give other words for the same key.
     */
    internal fun of(sodium: LazySodium, publicKeyB64: String): List<String> {
        val publicKey = try {
            SyncCrypto.fromBase64(publicKeyB64)
        } catch (_: IllegalArgumentException) {
            null
        }
        require(publicKey != null && publicKey.size == DeviceSignature.PUBLIC_KEY_BYTES) { "not a public key in base64url" }
        val hash = DeviceKey.blake2b(sodium, "$CONTEXT\n$publicKeyB64".toByteArray(Charsets.UTF_8), DeviceSignature.HASH_BYTES)
        return (0 until COUNT).map { WORDS[hash[it].toInt() and 0xFF] }
    }
}
