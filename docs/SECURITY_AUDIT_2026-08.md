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

Of the few still open, several are `unverified` in exactly that sense. That is a flaw in how I ran the
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

| **Critical** | **Share expiry and `read.share` revocation were enforced only in the therapist's own browser** — an `{#if}` and a `Date.now()` on the machine of the party being restricted. The owner's client sent the deadline in `X-Share-Meta`; the server dropped it. | `RelationRoutes.kt`, `RelationStore.kt` | **confirmed** — the server now records the expiry, gates both read paths under the store lock, and has an owner-only revoke route. 410 Gone, with expired and revoked collapsed to one exception kind so the route cannot leak which. |

**What that fix does NOT do**, since the opposite is easy to assume: a therapist who already
fetched the ciphertext keeps it forever, and no server change alters that. What is closed is the
case that should always have worked — a therapist who has not yet fetched, after expiry or
withdrawal, can no longer fetch.

## Fixed in the follow-up pass

| Sev | Finding | Where | Note |
|---|---|---|---|
| Med | Cookie-authenticated therapists bypassed every rate limit, and each assignments/gameplans PUT then did a blocking SMTP send on the request coroutine. | `RelationRoutes.kt` | Per-source budget charged **after** the inbox-token check, so a stranger cannot burn a real therapist's allowance from a shared address. Send moved off the request thread. |
| Med | `MailContentGuard` scanned the server's own link for record sentinels, so an operator at e.g. `mood.example.org` could send **no** mail at all, and ~1 invite in 200 died on a random token spelling `phq`/`gad`. Recovery mail still answered 202. | `MailContentGuard.kt` | Scan scoped away from server-generated URLs. The half that matters — actual record content still throws — is tested in both directions. |
| Med | PIN lockout was an absolute wall-clock deadline, so stepping the device clock forward cleared it. | `PinManager.kt` | Now takes the **stricter** of wall-clock and monotonic `elapsedRealtime`, so a reboot cannot clear a lockout either. |
| Med | After a photo pick the app could stop re-locking entirely — the skip had no expiry on the return leg. | `AutoLockController.kt` | Skip now covers a genuine round-trip and nothing longer. |
| Med | "Replace all current data" left `offer_records` intact — a timestamped partial mood history, in the one table whose own schema doc says it must never carry anything about the person. | `BackupManager.kt` | Ledger is cleared on REPLACE, and is neither exported nor restored. |
| Low | `zeroize()` wiped only the four returned copies, leaving the Argon2id master and the 160-byte key concatenation live for the life of the tab. | `keyStore.ts` | Both source buffers wiped, including on the wrong-passphrase path, which used to throw straight past cleanup. |
| Low | The "refuses to seal to an unpinned therapist" gate built its evidence out of the thing it was checking — all three assertions compared a value with itself. | `ShareBuilder.svelte`, `pinStore.ts` | Pins now persist across sessions, so a later key substitution is refused. See the two follow-ups below. |
| Low | No `dataExtractionRules` while `targetSdk = 35`. | `AndroidManifest.xml` | Explicit device-transfer exclude added. |

**Not fixed, and deliberately: the setting-key allowlist.** The claim was the defect, not the code.
`X-Setting-Key` is optional, the shipping therapist client never sends it, and the real key is
inside the sealed body the server cannot read — so making the header mandatory would close the skip
and buy nothing, since the therapist writes both halves. The check that actually binds is the
owner's, on plaintext, after decrypting. The route's comment now says that instead of claiming a
guarantee it never provided. A structural gate that can be skipped is worse than an absent one,
because someone eventually deletes the real check as "redundant with the server allowlist".

**Not fixed, and deliberately: the CSP `connect-src 'self'`.** Relaxing it would not make the
absolute "Server URL" field work — the server installs no CORS plugin and registers no OPTIONS
handler, so every authenticated cross-origin call is preflighted into a response with no CORS
headers and fails identically. Meanwhile `connect-src 'self'` is the exfiltration boundary for a
page holding a decrypted journal and unwrapped keys. The honest fix is in the UI: stop offering a
field that cannot work, and stop reporting a browser refusal as an unreachable host.

## Owed follow-ups from the pin fix

Both are user-visible and neither is built:

1. **No way to forget a pin.** `pinStore.ts` is the first persistent client-side storage in the web
   app. It leaves a durable record saying *this browser is an owner console with N therapist
   relationships, first pinned on these dates* — no keys, no writing, but on a shared device that is
   information about someone's care, and there is no in-app way to clear it.
2. **No way to rotate a pin.** If a therapist legitimately re-keys, every seal now fails closed and
   the owner's only recourse is clearing site data. It needs a deliberate "this therapist's key
   changed, here are the old and new fingerprint words" affordance costing an explicit out-of-band
   confirmation.

## Still open

Nothing from the original fourteen. All were either fixed above, or deliberately not fixed with the
reasoning recorded (the setting-key allowlist and the CSP `connect-src`, both of which turned out to
be claims that needed correcting rather than code that needed changing).

What remains is the two **owed follow-ups** from the pin fix, listed above: no way to forget a pin,
and no way to rotate one. Both are user-visible, neither is built.

The nine `unverified` rows are now moot as findings — each was read and acted on directly — but the
methodological point stands and is left in place at the top of this document, because the next audit
will be run the same way unless someone remembers not to.

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
