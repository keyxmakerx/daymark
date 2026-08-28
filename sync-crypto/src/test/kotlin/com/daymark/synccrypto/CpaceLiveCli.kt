package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava

/**
 * The JVM half of a live CPace interop exchange — the twin of
 * `companion/web/e2e/cpace-live.mts`, whose header explains the harness (and why secret
 * scalars on argv are acceptable there and nowhere else). Same subcommands, same hex-JSON
 * contract, so either side can play either role against the other:
 *
 *   ./gradlew -q ... start <prsHex> <ciHex>
 *   ./gradlew -q ... respond <prsHex> <ciHex> <sidHex> <msgAHex>
 *   ./gradlew -q ... finish <prsHex> <ciHex> <sidHex> <yaHex> <msgAHex> <msgBHex>
 *
 * Lives in the TEST source set on purpose: CI compiles it on every push, so it cannot rot,
 * while nothing ships it. The always-on cross-implementation guarantee is the shared CFRG
 * vector pin in CpaceCryptoTest and cpace.test.ts; this harness is for watching the two
 * implementations agree under fresh randomness, run by a person on a host with both stacks.
 */
object CpaceLiveCli {

    private fun hex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @JvmStatic
    fun main(args: Array<String>) {
        val sodium = LazySodiumJava(SodiumJava())
        val cpace = CpaceCrypto(sodium)
        val ada = "ADa".toByteArray(Charsets.US_ASCII)
        val adb = "ADb".toByteArray(Charsets.US_ASCII)
        when (args.getOrNull(0)) {
            "start" -> {
                val (prs, ci) = listOf(hex(args[1]), hex(args[2]))
                val sid = sodium.randomBytesBuf(CpaceCrypto.SID_BYTES)
                val a = cpace.start(prs, ci, sid, ada)
                println("""{"sid":"${toHex(sid)}","ya":"${toHex(a.ya)}","msgA":"${toHex(a.msgA)}"}""")
            }
            "respond" -> {
                val b = cpace.respond(hex(args[1]), hex(args[2]), hex(args[3]), hex(args[4]), adb)
                println("""{"msgB":"${toHex(b.msgB)}","isk":"${toHex(b.isk)}"}""")
            }
            "finish" -> {
                val start = CpaceCrypto.StartResult(ya = hex(args[4]), msgA = hex(args[5]))
                val isk = cpace.finish(hex(args[3]), start, hex(args[6]))
                println("""{"isk":"${toHex(isk)}"}""")
            }
            else -> {
                System.err.println("usage: CpaceLiveCli start|respond|finish <hex args>")
                kotlin.system.exitProcess(2)
            }
        }
    }
}
