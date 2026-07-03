package com.daymark.app.sync

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.daymark.synccrypto.SyncCrypto

/**
 * The one place the `sync` flavor decides which libsodium binding backs [SyncCrypto]. Kept
 * deliberately tiny: everything that matters (the actual protocol/algorithm logic) lives in
 * the Android-free `:sync-crypto` module, which is unit-tested for real on the plain JVM via
 * lazysodium-java (see docs/COMPANION_PHONE_2B.md). This factory just swaps in
 * lazysodium-android for on-device use — no Context or other Android dependency needed.
 *
 * Nothing calls this yet (no networking/UI has landed — see the build order in
 * docs/COMPANION_PHONE_2B.md §6). [SodiumAndroid] loads the native libsodium binding at
 * construction time, so once a real caller exists it should hold a single [SyncCrypto]
 * instance (e.g. a Hilt `@Singleton`) rather than calling this repeatedly.
 */
fun createSyncCrypto(): SyncCrypto = SyncCrypto(LazySodiumAndroid(SodiumAndroid()))
