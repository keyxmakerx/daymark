package com.daymark.app.sync

import android.os.SystemClock
import com.daymark.synccrypto.HttpsTransport
import com.daymark.synccrypto.PhoneClock
import com.daymark.synccrypto.PhonePairing
import com.daymark.synccrypto.PhoneSync
import com.daymark.synccrypto.Transport
import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * What the `sync` flavour's server screen is built from (#432): libsodium through its Android binding,
 * and the phone's side of the protocol over [HttpsTransport], all from `:sync-crypto`, where it is tested
 * on a plain JVM against the real server. One libsodium binding for the process, since
 * [SodiumAndroid] loads the native library when it is made.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServerSyncModule {

    @Provides
    @Singleton
    fun provideSodium(): LazySodium = LazySodiumAndroid(SodiumAndroid())

    @Provides
    @Singleton
    fun provideTransport(): Transport = HttpsTransport()

    @Provides
    fun provideClock(): PhoneClock = PhoneClocks

    @Provides
    fun providePairing(sodium: LazySodium, transport: Transport, clock: PhoneClock): PhonePairing =
        PhonePairing(sodium, transport, clock)

    @Provides
    fun providePhoneSync(sodium: LazySodium, transport: Transport, clock: PhoneClock): PhoneSync =
        PhoneSync(sodium, transport, clock)

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
