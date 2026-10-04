package com.daymark.app.sync

import android.os.SystemClock
import com.daymark.synccrypto.ClinicianCode
import com.daymark.synccrypto.ClinicianInvites
import com.daymark.synccrypto.ClinicianPairing
import com.daymark.synccrypto.HttpsTransport
import com.daymark.synccrypto.PhoneClock
import com.daymark.synccrypto.PhonePairing
import com.daymark.synccrypto.PhoneSync
import com.daymark.synccrypto.Transport
import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the `sync` flavour's server screen is built from (#432): libsodium through its Android binding,
 * and the phone's side of the protocol over [HttpsTransport], all from `:sync-crypto`, where it is tested
 * on a plain JVM against the real server. One libsodium binding for the process, since [SodiumAndroid]
 * loads the native library when it is made.
 *
 * WHY ONE CLASS OF THE APP'S OWN, AND NOT A HILT MODULE. Hilt writes Java for every type an injected
 * constructor or a provider names, and the app compiles that Java for Java 17. `:sync-crypto` and
 * lazysodium-android are compiled for Java 21, whose class files javac 17 cannot read. So no
 * injected constructor and no provider may name one of their types: they are built here, behind this
 * class, which the app's own Kotlin compiles for Java 17. `ServerSyncSeamSourceTest` holds that line.
 */
@Singleton
class ServerSyncParts @Inject constructor() {

    /** libsodium, loaded once for the process, the first time anything here needs it. */
    val sodium: LazySodium by lazy { LazySodiumAndroid(SodiumAndroid()) }

    private val transport: Transport by lazy { HttpsTransport() }

    /** The pairing ceremony, over the one transport and the phone's clocks. */
    val pairing: PhonePairing by lazy { PhonePairing(sodium, transport, PhoneClocks) }

    /** Unlocking the sync key and sending a copy, over the same transport and clocks. */
    val phoneSync: PhoneSync by lazy { PhoneSync(sodium, transport, PhoneClocks) }

    /** The owner's invitations to a clinician (#174), signed by the phone's key like every request here. */
    val clinicianInvites: ClinicianInvites by lazy { ClinicianInvites(sodium, transport, PhoneClocks) }

    /** The owner's side of pairing with a clinician (#174). */
    val clinicianPairing: ClinicianPairing by lazy { ClinicianPairing(sodium, transport, PhoneClocks) }

    /** Wall time, for when a clinician's keys were recorded. */
    val clock: PhoneClock get() = PhoneClocks

    /** A fresh code for one pairing run, drawn so no symbol is favoured. */
    fun drawCode(): ClinicianCode = ClinicianCode.draw(sodium)

    /**
     * The phone's clocks. The poll's pace and its deadline run on [SystemClock.elapsedRealtime], which
     * keeps counting while the phone sleeps, so a poll that slept through the time to confirm stops
     * without asking, rather than asking once more and being refused.
     */
    private object PhoneClocks : PhoneClock {
        override fun nowMillis(): Long = System.currentTimeMillis()

        override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

        override fun pause(millis: Long) = Thread.sleep(millis)
    }
}
