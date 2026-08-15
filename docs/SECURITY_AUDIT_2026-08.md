# Security audit — August 2026

An adversarial static audit of the branch, run as six independent lenses (untrusted input, authz,
crypto, the Android surface, privacy/photo pipeline, web XSS/CSP), each finding then attacked by a
separate skeptic instructed to refute it. 22 raw findings, 4 killed on refutation, 18 surviving,
**14 distinct after deduplication** — three lenses independently found the same path traversal, and
two independently found the same portal lock failure, which is the corroboration you want.

## How to read the Verified column

This matters more than the severities, so it goes first.

| Value | Meaning |
|---|---|
| **confirmed** | A skeptic read the code and could not break the claim, *and* I checked it myself. |
| **refuted-pass** | A skeptic attacked it and it survived. Not independently re-checked by me. |
| **unverified** | **The skeptic returned no verdict for this row.** My workflow counted a missing verdict as survival, which is the wrong default — absence of refutation is not evidence. Treat these as *reported, not established*. |

Of the eleven still open, several are `unverified` in exactly that sense. That is a flaw in how I ran the
audit, not a property of the findings, and it is written down here rather than quietly smoothed
over. **A finding below being listed is not a claim that it is real.**

## Fixed in this branch

| Sev | Finding | Where | Verified |
|---|---|---|---|
| **High** | **A hostile backup could delete the journal database.** `PhotoStore`'s `isSafeName` guard was applied to `readBytes`/`writeBytes`/`exists` but not to `delete` or either `fileFor`. A backup naming a photo it does not contain (`photoPath: "../../databases/daymark.db"`, empty `photos` map) never reached the one guarded door, was written verbatim onto the entry, and was resolved by the next ordinary swipe-delete. Silent (`runCatching`), unrecoverable (`allowBackup="false"`). | `PhotoStore.kt`, `BackupManager.kt:280,332` | **confirmed** |
| **High** | **The therapist portal never locked.** The guard was `$derived(ctx ? isLive(ctx.session) : false)`; `isLive` reads `Date.now()` internally, so the only tracked dependency was `ctx` — written twice, at unlock and logout. Evaluated once, when true by construction. Unwrapped reading keys and the decrypted share stayed in memory until the tab closed. | `TherapistPortal.svelte:71` | **confirmed** |
| **High** | **Auth lockout ratcheted and leaked.** `count` was cleared only by a successful auth, so one wrong request per lockout window held a source out forever — and where `sourceId` is shared, that locks out everyone. Separately the eviction predicate required `count < lockoutThreshold`, which a locked-out source can never satisfy again. | `AuthGuard.kt:76,114` | **confirmed** |

Each was traced by hand before being touched, and each fix is mutation-tested — the mutation
confirmed to land, the guard confirmed to catch it, the tree restored.

**Two of the three had a second bug underneath that made the obvious fix wrong on its own**, which
is worth more than the findings themselves:

- `PhotoStore` — a test asserting "delete is guarded" would go green the day someone adds a seventh
  method, so `PhotoStoreSourceTest` counts the *doors*. Writing that test took three attempts: it
  passed a mutated `fileFor` first because the body slicer cut at a literal that does not match
  `private fun`, then again because the two overloads collided under a name key and the guarded one
  masked the unguarded one. A test for a partial guard that was itself partial, twice.
- `TherapistPortal` — `touch()`, which refreshes the idle deadline on activity, had **no production
  caller anywhere**. Fixing the clock alone would have logged a therapist out fifteen minutes after
  unlock while they were actively reading: not "the guard works now" but a different broken
  behaviour, and one that would have been blamed on the lock.

## Open — ranked by what it costs a real person

### 1. Share expiry and revocation are enforced only in the therapist's browser — **critical**, still open

`companion/server/.../routes/RelationRoutes.kt:89`. Verified: **unverified by the skeptic, but I
checked the load-bearing parts myself and they hold.**

The `shares` read path authorizes on two things: you hold the relationship's inbox token, and a live
session bound to it. It never checks expiry, never checks a revocation flag, never reads the
owner-signed grant. I confirmed directly that:

- no `revoked`/`expiry` column or route exists for shares anywhere in `companion/server/src/main`
  (every hit is invites and sessions in `AuthStore.kt`);
- `ShareBuilder.svelte:78` **sends** the expiry to the server in `X-Share-Meta`, and `grep` for
  `Share-Meta` across the whole server returns **nothing** — it is handed over and dropped.

So the only expiry check runs on a clock supplied by the party being restricted, and the only
`read.share` check is an `{#if}` in a bundle that party is serving to themselves.

**The honest scope.** This is E2EE: a therapist who already fetched the ciphertext keeps it, and no
server change can revoke that. What is broken is the case that *should* work — a therapist who has
not yet fetched, after the owner revoked or the share expired, can still fetch and decrypt. The
audit log records `SHARE_OPEN`: it records the access it failed to prevent.

**Smallest correct fix.** Persist `expiry` from the `X-Share-Meta` the client already sends, add a
`revoked` column and an owner-authenticated revoke route, and have the `shares` GET consult both
before serving. Not a one-liner — it touches the trust model, so it should be designed, not
patched.

### 2. The rest

| Sev | Finding | Where | Verified |
|---|---|---|---|
| Med | `SETTING_ALLOWLIST` applies only when the caller *chooses* to send `X-Setting-Key`; the header is optional and the real client never sends it on the assignments channel. The gate documented as guaranteeing no PIN/lock/encryption key can transit is opt-in by the party it restricts. | `RelationRoutes.kt:134` | unverified |
| Med | Cookie-authenticated therapists bypass every rate limit (`resolveRole` consults `AuthGuard` only when an `Authorization` header is present), and each assignments/gameplans PUT then does a synchronous network-bound `mailer.send` on the request coroutine. | `RelationRoutes.kt:146` | unverified |
| Med | `MailContentGuard` scans the *rendered* body — which contains the server's own link — for record sentinels, so an operator hosting at e.g. `mood.example.org`, or a random token containing `phq`/`gad`, makes every mail throw. Recovery mail has no fallback and the route still answers 202. | `MailContentGuard.kt:78` | refuted-pass |
| Med | PIN lockout is an absolute wall-clock deadline, so moving the device clock forward clears it. | `PinManager.kt:56` | unverified |
| Med | The picker auto-lock skip has no expiry on the return leg — `onBackgrounded()` sets `backgroundedAtMs = -1L` and `shouldLockOnForeground()` returns false whenever `bg < 0`, so after a photo pick the app may never re-lock regardless of elapsed time. | `AutoLockController.kt:41,46` | refuted-pass (found twice) |
| Med | "Replace all current data" wipes twelve tables but not `offer_records` (`BackupManager` does not inject `OfferRecordDao`). A `kind="support"` row is written only when `moodLevel <= 2`, so a timestamped partial mood history survives the wipe — in the one table whose own schema doc says it must never carry anything about the person. | `BackupManager.kt:262` | refuted-pass |
| Low | `zeroize()` misses the concatenated 160-byte secret plaintext and the Argon2id master key; it wipes only the four returned copies. | `keyStore.ts:88` | refuted-pass |
| Low | `ShareBuilder.seal()` builds the `PinStore` from the very keys it is about to seal to, so all three pin assertions are tautologies; `PinStore.serialize()`/`load()` have no production call sites. | `ShareBuilder.svelte:63` | refuted-pass |
| Low | No `dataExtractionRules` while `targetSdk = 35`, so Android 12+ device-to-device transfer is governed by a default rather than an explicit exclude, against what `PRIVACY.md` claims. | `AndroidManifest.xml:12` | unverified |
| Low | `connect-src 'self'` blocks the operator-editable absolute "Server URL" that four connection surfaces expose, and those paths report the failure as something else. | `SecurityHeaders.kt:30` | unverified |

## What came up clean

Both invariants that matter most held. `app/src/main/AndroidManifest.xml` declares only
`POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `USE_BIOMETRIC`, `SCHEDULE_EXACT_ALARM`, `VIBRATE`;
`INTERNET` appears exactly once, in `app/src/sync/`, and there is no `app/src/foss/` source set, so
the foss variant merges `main` only. **No location API is referenced anywhere** under `app/src/main`
— the only hits are prose saying there is none.

## What this audit did not cover

- **No device and no running server.** Everything is static reading. Nothing was executed, no
  request was sent, no exploit was run. The traversal above is the one case where I verified the
  mechanism (path resolution) independently.
- **Source manifests, not the merged manifest.** Manifest merge from an AAR is the usual way
  `INTERNET` appears without anyone typing it, and that was not checked.
- **No dependency CVE scan**, no fuzzing, no timing measurements — "constant-time" claims were read,
  not measured.
- **Nine rows have no adversarial verdict at all**, per the table at the top.
- An audit that finds fourteen things has not proven there are fourteen.
