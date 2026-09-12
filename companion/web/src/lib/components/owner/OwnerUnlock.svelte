<script lang="ts">
  /*
   * THE OWNER CONSOLE'S FRONT DOOR.
   *
   * It used to have a button called "Generate owner keys". Pressing it minted an X25519 + Ed25519
   * pair that lived until the tab closed, which meant the owner's signing identity was a different
   * identity every session and a clinician who had pinned it correctly could verify nothing the
   * owner signed afterwards (issue #121). The generate path is DELETED rather than hidden: there is
   * no route to a usable key that does not go through the owner's own key, because a usable key
   * from nowhere is the defect.
   *
   * What it asks for instead is the wrapped-key file the Recovery code screen saves, and one of the
   * two secrets that open it. From there owner/unlock.ts derives the identity and wipes the master.
   *
   * WHY THE FILE, EVERY VISIT. There is nowhere to keep it. components/recovery/session.ts argues
   * at length against browser storage for key material on a page the server serves, and no endpoint
   * holds the blob. So the door holds nothing between visits, and says so rather than letting
   * someone discover it by being asked again and reading that as a fault. This console already asks
   * for a bearer token by hand every visit; this is the same posture, not a new one.
   */
  import { initAssignmentCrypto, fingerprint } from '../../assignments/crypto'
  import { sasWords, publicOf, type Identity } from '../../share/pairing'
  import { emptyGrant } from '../../assignments/grant'
  import { fromBase64, toBase64 } from '../../share/sharecrypto'
  import LowerAssuranceBanner from './LowerAssuranceBanner.svelte'
  import GroupEntry from '../recovery/GroupEntry.svelte'
  import { emptyGroups, firstGroupProblem, groupsToTyped, type GroupProblem } from '../recovery/groups'
  import { HOW_ENTRY_WORKS } from '../recovery/copy'
  import {
    UNLOCK_LEDE,
    NOTHING_IS_KEPT,
    KEY_FILE_LABEL,
    KEY_FILE_HINT,
    NO_KEY_FILE_YET,
    USE_CODE_INSTEAD,
    USE_PASSPHRASE_INSTEAD,
    CODE_OPENS_THIS_SESSION,
    UNLOCK_ACTION,
    UNLOCK_BUSY,
    FINGERPRINT_IS_STABLE,
    ADDING_MINTS_A_TOKEN,
    INBOX_TOKEN_LABEL,
    INBOX_TOKEN_TWO_CHANNELS,
    INBOX_TOKEN_SHOWN_ONCE,
  } from '../../owner/unlockCopy'
  import { mintInboxToken } from '../../owner/inboxToken'
  import { Card, Callout } from '../ui'
  import type { OwnerSession, PinnedTherapist } from './session'

  let { onunlock }: { onunlock: (session: OwnerSession) => void } = $props()

  let busy = $state(false)
  let error = $state('')

  /*
   * The owner's identity for this session. Derived, never generated; null until they unlock. The
   * master it came from is wiped inside unlock.ts and never reaches this component.
   */
  let ownerIdentity = $state<Identity | null>(null)
  let ownerFp = $state('')

  /** The chosen key file, as text. Read on selection so the unlock itself has nothing to await. */
  let keyFileText = $state('')
  let keyFileName = $state('')

  /** Which secret is being offered. The passphrase is the everyday one; the code is on paper. */
  let secretMode = $state<'passphrase' | 'recovery'>('passphrase')
  let passphrase = $state('')
  let groups = $state(emptyGroups())
  let problem = $state<GroupProblem | null>(null)

  const messageId = $props.id()
  /** Derived from the one id this component may ask for — `$props.id()` is once per component. */
  const mintedLabelId = `${messageId}-token`

  // Add-a-pinned-therapist form (keys pasted after OOB verification).
  let tName = $state('')
  let tSignPubB64 = $state('')
  let tBoxPubB64 = $state('')
  const pinned: PinnedTherapist[] = []
  let pinnedView = $state<PinnedTherapist[]>([])

  /**
   * The token just minted, and who it is for. Shown once, here, and by nothing else in the console.
   *
   * It is NOT an input's value. Same reasoning as the pairing code in PairingPanel.svelte: a secret
   * inside a form control is a secret a browser offers to save, a password manager offers to fill
   * and an autofill carries into the next form that looks similar. It is rendered into an <output>
   * for a person to read off the screen and hand over by some other channel.
   */
  let mintedToken = $state('')
  let mintedFor = $state('')

  /**
   * What names a pending entry before its keys exist.
   *
   * A COUNTER, AND DELIBERATELY NOT THE TOKEN. This used to be `pending:${token.slice(0,8)}:${n}`,
   * which put eight characters of a journal credential into the entry's id — and therefore into
   * `emptyGrant(id).therapistFingerprint`, which encodeSignedGrant emits as plain JSON: signed, not
   * encrypted, stored under that same token's digest. Nothing published it, because the Grants tab
   * is replaced by a message while an entry is pending, so the leak was one tab-visibility
   * condition away from being real (issue #126). An id has no need of the token, so it no longer
   * has any of it.
   */
  let pendingSeq = 0

  async function chooseFile(event: Event) {
    error = ''
    const input = event.currentTarget as HTMLInputElement
    const file = input.files?.[0]
    if (!file) {
      keyFileText = ''
      keyFileName = ''
      return
    }
    keyFileName = file.name
    try {
      keyFileText = await file.text()
    } catch {
      keyFileText = ''
      error = 'That file could not be read from disk. Nothing has been unlocked.'
    }
  }

  function switchSecret(mode: 'passphrase' | 'recovery') {
    secretMode = mode
    error = ''
    problem = null
    // Whichever field is being left goes with it. Half a secret left in a field is a secret sitting
    // in a form for no reason.
    passphrase = ''
    groups = emptyGroups()
  }

  function updateGroups(next: string[]) {
    groups = next
    problem = null
    error = ''
  }

  async function unlock() {
    error = ''
    problem = null

    /*
     * The check symbol runs here, before anything expensive: a single mistyped character comes back
     * positioned and instantly, rather than as a decrypt failure seconds of Argon2id later that
     * could not tell a typo from the wrong code. Same order as UseCodeFlow.
     */
    if (secretMode === 'recovery') {
      const found = firstGroupProblem(groups)
      if (found) {
        problem = found
        return
      }
    }

    busy = true
    try {
      await initAssignmentCrypto()
      const { unlockOwnerIdentity, UNLOCK_FAULT_TEXT } = await import('../../owner/unlock')
      const secret = secretMode === 'passphrase' ? passphrase : groupsToTyped(groups)
      const out = await unlockOwnerIdentity(keyFileText, secret, secretMode)
      if (!out.ok) {
        error = UNLOCK_FAULT_TEXT[out.fault]
        return
      }
      ownerIdentity = out.identity
      ownerFp = fingerprint(out.identity.ed25519.publicKey)
      // Neither secret is needed again, and neither should outlive the click that used it.
      passphrase = ''
      groups = emptyGroups()
    } catch (e) {
      error = e instanceof Error ? e.message : 'That key could not be opened.'
    } finally {
      busy = false
    }
  }

  /**
   * Add a clinician, and mint the token that routes their requests.
   *
   * THERE IS NO FIELD FOR THE TOKEN, and that is the fix rather than a side effect of it. It used
   * to be typed — an "Inbox token (OOB)" box whose entire validation was "not empty", so the secret
   * standing between a stolen database and a person's journal was whatever the owner thought of,
   * while the security document described it as a 256-bit CSPRNG (issue #126). owner/inboxToken.ts
   * now produces it and this screen shows it once; a token from a keyboard is the defect, so there
   * is nowhere left to type one.
   *
   * NOTHING CHECKS FOR A DUPLICATE, because a duplicate can no longer be made. Two clinicians on
   * one token share a relationship reference and can read each other's material, and the old typed
   * path made that a plausible accident — one owner, one remembered string, two clinicians. Off a
   * 256-bit CSPRNG it is not a case to defend against.
   */
  async function addTherapist() {
    error = ''
    if (!tName.trim()) {
      error = 'Enter a name for this clinician. Nothing has been added.'
      return
    }

    /*
     * The keys are OPTIONAL now, because at the moment the owner sets a relationship up the
     * clinician's keys DO NOT EXIST — they are generated in the clinician's browser during
     * acceptance, after the invitation this console is about to mint. Requiring them here was the
     * circle that made the whole pairing undrivable: paste keys to reach the screen that fetches
     * keys. An entry added without them is marked pending, can mint the invitation, and receives
     * its keys through the Published-keys tab once the clinician has accepted — checked against
     * what they read aloud, exactly as a hand-pasted pair would have been.
     */
    const noKeys = !tSignPubB64.trim() && !tBoxPubB64.trim()
    if (!noKeys && (!tSignPubB64.trim() || !tBoxPubB64.trim())) {
      error = 'Enter both keys, or neither — half a keypair cannot be checked against anything.'
      return
    }

    /*
     * The keys are read BEFORE the token is made, so an unreadable paste costs nothing. A token
     * minted and then thrown away is harmless, but it would also be a token this screen had shown
     * nobody, and an entry half-added is the state this form must never leave behind.
     */
    let id: string
    let signPub: Uint8Array = new Uint8Array(0)
    let boxPub: Uint8Array = new Uint8Array(0)
    let words = ''
    if (noKeys) {
      id = `pending:${++pendingSeq}`
    } else {
      try {
        signPub = fromBase64(tSignPubB64.trim())
        boxPub = fromBase64(tBoxPubB64.trim())
        words = sasWords(publicOf(ownerIdentity!), { x25519Pub: boxPub, ed25519Pub: signPub }).join(' ')
        id = fingerprint(signPub)
      } catch {
        error = 'Could not parse the public keys (expect URL-safe base64, no padding).'
        return
      }
    }

    let token: string
    try {
      token = await mintInboxToken()
    } catch {
      // A consequence, not a cause: this screen cannot know why libsodium did not answer.
      error = 'No token could be made, so this clinician has not been added.'
      return
    }

    const t: PinnedTherapist = {
      id,
      displayName: tName.trim(),
      signPub,
      boxPub,
      grant: emptyGrant(id),
      inboxToken: token,
      fingerprintWords: words,
      pinnedAt: Date.now(),
      keysPending: noKeys,
    }
    pinned.push(t)
    pinnedView = [...pinned]
    mintedFor = t.displayName
    mintedToken = token
    tName = ''; tSignPubB64 = ''; tBoxPubB64 = ''
  }

  function enter() {
    if (!ownerIdentity) return
    onunlock({ ownerBox: ownerIdentity.x25519, ownerSign: ownerIdentity.ed25519, pinned: [...pinned] })
  }
</script>

<div class="unlock">
  <Card title="Owner console">
    <div class="stack">
      <LowerAssuranceBanner />

      {#if !ownerIdentity}
        <p class="hint">{UNLOCK_LEDE}</p>
        <p class="hint">{NOTHING_IS_KEPT}</p>

        <fieldset class="add">
          <legend>Your key</legend>

          <label>
            <span>{KEY_FILE_LABEL}</span>
            <input type="file" accept="application/json,.json" onchange={chooseFile} disabled={busy} />
          </label>
          {#if keyFileName}<p class="hint">Chosen: {keyFileName}</p>{/if}
          <p class="hint">{KEY_FILE_HINT}</p>
          <p class="hint">{NO_KEY_FILE_YET}</p>

          {#if secretMode === 'passphrase'}
            <label>
              <span>Passphrase</span>
              <input type="password" bind:value={passphrase} autocomplete="current-password" disabled={busy} />
            </label>
            <button type="button" class="swap" onclick={() => switchSecret('recovery')} disabled={busy}>
              {USE_CODE_INSTEAD}
            </button>
          {:else}
            <GroupEntry
              {groups}
              onchange={updateGroups}
              problemGroup={problem?.group ?? null}
              describedBy={messageId}
              disabled={busy}
              legend="The six groups of your recovery code"
            />
            <div class="message" id={messageId} role="status" aria-live="polite">
              {#if problem}{problem.message}{/if}
            </div>
            <p class="hint">{HOW_ENTRY_WORKS}</p>
            <p class="hint">{CODE_OPENS_THIS_SESSION}</p>
            <button type="button" class="swap" onclick={() => switchSecret('passphrase')} disabled={busy}>
              {USE_PASSPHRASE_INSTEAD}
            </button>
          {/if}
        </fieldset>

        <button class="primary" onclick={unlock} disabled={busy}>{busy ? UNLOCK_BUSY : UNLOCK_ACTION}</button>
      {:else}
        <p class="fp">Your owner fingerprint: <code>{ownerFp}</code></p>
        <p class="hint">{FINGERPRINT_IS_STABLE}</p>
        <p class="pub faint">Share your public keys with therapists out-of-band to pin:
          sign <code>{toBase64(ownerIdentity.ed25519.publicKey)}</code>,
          box <code>{toBase64(ownerIdentity.x25519.publicKey)}</code>
        </p>

        <fieldset class="add">
          <legend>Add a clinician</legend>
          <p class="hint">{ADDING_MINTS_A_TOKEN}</p>
          <p class="hint">
            Their keys are created in their browser when they accept your invitation, and arrive on
            the Published keys tab. Paste keys here only if you already hold them from an earlier
            exchange.
          </p>
          <label><span>Display name</span><input type="text" bind:value={tName} autocomplete="off" /></label>
          <label><span>Ed25519 public key <em>(optional — arrives when they accept)</em></span><input type="text" bind:value={tSignPubB64} autocomplete="off" /></label>
          <label><span>X25519 public key <em>(optional — arrives when they accept)</em></span><input type="text" bind:value={tBoxPubB64} autocomplete="off" /></label>
          <button onclick={() => void addTherapist()}>Add clinician</button>
        </fieldset>

        {#if mintedToken}
          <!--
            THE ONE SIGHTING OF THE TOKEN.

            An <output> with aria-live, not an input and not a link — the same choice PairingPanel
            makes for the pairing code, for the same reason: a form control is something a browser
            saves, a password manager offers and an autofill carries into the next form. There is no
            copy button either, deliberately. The clipboard is a shared surface this screen cannot
            see the other end of, and the one secret it holds is the one the owner is about to send
            by a channel of their own choosing.
          -->
          <div class="minted">
            <span class="token-label" id={mintedLabelId}>{INBOX_TOKEN_LABEL} — {mintedFor}</span>
            <output class="token" aria-labelledby={mintedLabelId} aria-live="polite">{mintedToken}</output>
            <p class="hint">{INBOX_TOKEN_TWO_CHANNELS}</p>
            <p class="hint">{INBOX_TOKEN_SHOWN_ONCE}</p>
          </div>
        {/if}

        {#if pinnedView.length > 0}
          <ul class="pinned">
            {#each pinnedView as t (t.id)}
              <li><strong>{t.displayName}</strong> · <span class="sas">{t.fingerprintWords}</span></li>
            {/each}
          </ul>
        {/if}

        <button class="primary" onclick={enter}>Enter console</button>
      {/if}

      {#if error}<Callout tone="critical">{error}</Callout>{/if}
    </div>
  </Card>
</div>

<style>
  .unlock { max-width: 40rem; }
  .stack { display: flex; flex-direction: column; gap: var(--space-3); }
  .hint { margin: 0; color: var(--ink-soft); font-size: 0.9rem; }
  .fp code, .pub code { font-family: var(--font-mono); font-size: 0.75rem; word-break: break-all; }
  .pub { margin: 0; font-size: 0.8rem; }
  .add { display: flex; flex-direction: column; gap: var(--space-2); border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3); }
  .add legend { padding: 0 var(--space-2); color: var(--ink-soft); font-size: 0.85rem; }
  .add label { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .add label span { color: var(--ink-soft); }
  .swap { align-self: flex-start; background: none; border: none; padding: 0; color: var(--ink-text); font: inherit; font-size: 0.85rem; text-decoration: underline; cursor: pointer; }
  .message { min-height: 1.25rem; font-size: 0.85rem; color: var(--ink-soft); }
  input { font: inherit; padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  .pinned { margin: 0; padding-left: var(--space-4); font-size: 0.9rem; }
  .sas { font-family: var(--font-mono); font-size: 0.75rem; }

  /*
   * The minted token, drawn as PairingPanel draws the pairing code — same box, same `user-select:
   * all` so one click takes the whole value, and no colour that reads as a verdict. Forty-three
   * characters rather than eight, so it wraps instead of setting at 2rem.
   */
  .minted { display: flex; flex-direction: column; gap: var(--space-1); }
  .token-label { font-size: 0.85rem; color: var(--ink-soft); }
  .token {
    font-family: var(--font-mono);
    font-size: 1rem;
    line-height: 1.5;
    color: var(--ink-text);
    background: var(--paper-bg);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-3);
    word-break: break-all;
    user-select: all;
  }
</style>
