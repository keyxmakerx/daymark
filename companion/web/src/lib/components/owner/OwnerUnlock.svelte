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
   * WHERE THE KEY COMES FROM (#258). The server keeps the owner's master locked under the passphrase
   * and under the recovery code (docs/SYNC_PROTOCOL.md §1.2), and this door reads what it holds with
   * the owner's access token. There are three answers, each with its own form below:
   *
   *   nothing          a first run makes the key, here, and stores only its two locks;
   *   key parameters   an enrolment locks the key the passphrase already derives, after proving the
   *                    passphrase on the newest stored snapshot;
   *   a locked key     an unlock, with the passphrase or with the recovery code.
   *
   * The order inside each — what is proved before anything is written, what a create names, what is
   * read back before an identity exists — is recovery/serverKey.ts's, where it is a node test, and the
   * set-up form is the Recovery code screen's own (recovery/KeySetup.svelte). This component holds
   * only what is on screen. A new recovery code is shown once and checked as written down before the
   * console opens, as the Recovery code screen does.
   *
   * Nothing is kept between visits: not the key, not the identity, not the token. The address and
   * token the key was read with are handed to the console with the session, so it does not ask for
   * them twice.
   */
  import { initAssignmentCrypto, fingerprint } from '../../assignments/crypto'
  import { sasWords, publicOf, type Identity } from '../../share/pairing'
  import { emptyGrant } from '../../assignments/grant'
  import { fromBase64, toBase64 } from '../../share/sharecrypto'
  import LowerAssuranceBanner from './LowerAssuranceBanner.svelte'
  import GroupEntry from '../recovery/GroupEntry.svelte'
  import CodeSheet from '../recovery/CodeSheet.svelte'
  import WriteDownCheck from '../recovery/WriteDownCheck.svelte'
  import KeySetup from '../recovery/KeySetup.svelte'
  import { emptyGroups, firstGroupProblem, groupsToTyped, type GroupProblem } from '../recovery/groups'
  import {
    HOW_ENTRY_WORKS,
    KEY_CHANGED_ON_SERVER,
    READ_ACTION,
    READ_BACK_DID_NOT_MATCH,
    READ_BACK_FAILED,
    READ_BUSY,
    READS_AGAIN,
    WRITE_IT_ON_PAPER,
  } from '../recovery/copy'
  import {
    UNLOCK_LEDE,
    NOTHING_IS_KEPT,
    KEY_IS_ON_THE_SERVER,
    CONNECT_ACTION,
    CONNECT_BUSY,
    CONNECT_NO_TOKEN,
    CONNECT_REFUSED,
    CONNECT_FAILED,
    HOLDS_A_LOCKED_KEY,
    KEY_STORED_WITH_THIS_CODE,
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
  import type { KeyDocument } from '../../sync/client'
  import type { EnrolmentCheck, ServerKeyPorts } from '../../recovery/serverKey'
  import type { RecoverableDataKey } from '../../recovery/dataKey'
  import type { RecoveryCode } from '../../recovery/recoveryCode'
  import type { OwnerConnection } from '../../owner/recoveryEmail'
  import type { LaneKey } from '../../lane/lane'

  let { onunlock }: { onunlock: (session: OwnerSession, connection: OwnerConnection) => void } = $props()

  let busy = $state(false)
  let error = $state('')
  /**
   * How the message is drawn: READ_BACK_FAILED is a warning about something not yet checked, with the
   * code already handed over; everything else here is a refusal.
   */
  const errorTone = $derived(error === READ_BACK_FAILED ? 'warn' : 'critical')
  /**
   * Whether the message tells the person to read what this server holds. Taken from the message
   * itself (READS_AGAIN), so the button that does it is under every such message and under no other.
   */
  const asksForARead = $derived(READS_AGAIN.has(error))
  /** A statement about what happened that is not a refusal: the server's key changed under a set-up. */
  let notice = $state('')

  /* ── The server, and what it holds ──────────────────────────────────────────────────────── */

  let serverUrl = $state('')
  let token = $state('')
  /**
   * What the server held when last read with the address and token above; null until then. Raw,
   * because it is replaced whole and never edited, and the locked key in it is handed to the crypto
   * as the server sent it.
   */
  let held = $state.raw<KeyDocument | null>(null)
  /** For key parameters: whether the passphrase is proved on a snapshot or typed twice. */
  let check = $state<EnrolmentCheck | null>(null)
  /** The reads and writes, bound to the address and token the key was read with. */
  let ports = $state.raw<ServerKeyPorts | null>(null)
  /** That address and token, captured at the read, for the console to connect with. */
  let readWith: OwnerConnection | null = null

  /* ── A set-up: nothing held, or key parameters only (recovery/KeySetup.svelte) ───────────── */

  /** A new recovery code, from the moment the server took the key until it is confirmed written down. */
  let newCode = $state<RecoveryCode | null>(null)
  let codeStep = $state<'showing' | 'confirm'>('showing')
  /**
   * The identity a set-up derived, held until the new code is confirmed written down. The master
   * it came from was wiped inside serverKey.ts and never reaches this component.
   */
  let setUpIdentity: Identity | null = null
  /** The sync key of the same set-up's master, with its read-back's ETag, held beside it (#345). */
  let setUpLane: LaneKey | null = null
  /**
   * A create the server took (201) whose read-back could not be read: what was sent, kept until a
   * later read finds the server holding exactly it. While it is set, no identity exists, and the
   * code is on screen with READ_BACK_FAILED rather than the statement that the key is stored.
   */
  let unreadSent = $state.raw<RecoverableDataKey | null>(null)
  let checking = $state(false)

  /* ── An unlock: a locked key held ───────────────────────────────────────────────────────── */

  /** Which secret is being offered. The passphrase is the everyday one; the code is on paper. */
  let secretMode = $state<'passphrase' | 'recovery'>('passphrase')
  let passphrase = $state('')
  let groups = $state(emptyGroups())
  let problem = $state<GroupProblem | null>(null)

  /*
   * The owner's identity for this session. Derived, never generated; null until they unlock. The
   * master it came from is wiped inside unlock.ts or serverKey.ts and never reaches this component.
   */
  let ownerIdentity = $state<Identity | null>(null)
  let ownerFp = $state('')
  /**
   * The sync key of the same master, with the ETag of the key document it was opened from: what the
   * console reads and adds to the owner's lane with (lane/lane.ts, #345). Set beside the identity by
   * the same unlock or set-up, never on its own, and handed to the console with it.
   */
  let laneKey: LaneKey | null = null

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

  /** Editing where the key is read from makes what was read from there stale. */
  function connectionEdited() {
    held = null
    check = null
    ports = null
    readWith = null
    notice = ''
    error = ''
  }

  /** Read what the server holds. Nothing is written here. */
  async function connect() {
    error = ''
    notice = ''
    if (!token) {
      error = CONNECT_NO_TOKEN
      return
    }
    busy = true
    try {
      const { SyncClient, SyncError } = await import('../../sync/client')
      const { serverKeyPorts, enrolmentCheck } = await import('../../recovery/serverKey')
      const reading = serverKeyPorts(new SyncClient(serverUrl, token))
      try {
        const doc = await reading.read()
        check = doc.kind === 'keyparams' ? await enrolmentCheck(reading) : null
        held = doc
        ports = reading
        readWith = { serverUrl, token }
      } catch (e) {
        connectionEdited()
        error = e instanceof SyncError && e.status === 401 ? CONNECT_REFUSED : CONNECT_FAILED
      }
    } catch {
      connectionEdited()
      error = CONNECT_FAILED
    } finally {
      busy = false
    }
  }

  /**
   * The server took the key, read it back and opened it. The code is shown at once, before anything
   * else can fail: the server holds the lock it opens, and a code that never reached the screen
   * would be a recovery slot nobody holds. The identity waits until the code is confirmed written
   * down.
   */
  function keyStored(stored: { recoveryCode: RecoveryCode; identity: Identity; lane: LaneKey }) {
    error = ''
    notice = ''
    setUpIdentity = stored.identity
    setUpLane = stored.lane
    newCode = stored.recoveryCode
    codeStep = 'showing'
  }

  /**
   * The server took the key and it could not be read back. The code is shown all the same — the
   * lock it opens is on the server — with READ_BACK_FAILED in place of the statement that the key
   * is stored, and no identity until a read finds the server holding exactly what was sent.
   */
  function keyUnread(stored: { recoveryCode: RecoveryCode; sent: RecoverableDataKey }) {
    error = ''
    notice = ''
    setUpIdentity = null
    unreadSent = stored.sent
    newCode = stored.recoveryCode
    codeStep = 'showing'
  }

  /**
   * READ_BACK_FAILED's button: read the key document again and compare it with what was sent. It
   * never takes the code off the screen by itself — only a server that answers with something else
   * does, because then the code opens nothing it serves. When the server holds exactly what was
   * sent, the identity is derived by opening that read-back with the code on screen, so it still
   * comes from what the server handed back.
   */
  async function checkReadBack() {
    if (!ports || !unreadSent || !newCode) return
    checking = true
    try {
      const { confirmStored } = await import('../../recovery/serverKey')
      const out = await confirmStored(ports, unreadSent)
      if (out.kind === 'unread') return
      if (out.kind === 'held') {
        await initAssignmentCrypto()
        const { unlockFromBlob } = await import('../../owner/unlock')
        const back = await unlockFromBlob(out.wrapped, newCode.canonical, 'recovery')
        if (back.ok) {
          setUpIdentity = back.identity
          setUpLane = { syncKey: back.syncKey, keyDocumentEtag: out.etag }
          unreadSent = null
          return
        }
      }
      newCode = null
      unreadSent = null
      keyLost(READ_BACK_DID_NOT_MATCH)
    } finally {
      checking = false
    }
  }

  /** The server's key changed between the read and the create: show what it holds now. */
  function keyMoved(now: KeyDocument, nowCheck: EnrolmentCheck | null) {
    error = ''
    held = now
    check = nowCheck
    notice = KEY_CHANGED_ON_SERVER
  }

  /** Where things stand is not known here any more: read the server again, from the start. */
  function keyLost(message: string) {
    connectionEdited()
    error = message
  }

  /**
   * The new code is on paper: drop it, and open the console with the identity the set-up derived.
   * With the read-back still unread there is no identity, so nothing opens: the person is asked to
   * read what the server holds, with the button under the words, and unlocks from there.
   */
  async function codeWrittenDown() {
    newCode = null
    if (!setUpIdentity) {
      unreadSent = null
      keyLost(READ_BACK_FAILED)
      return
    }
    // The fingerprint needs the assignment crypto ready, which an unlock readies too.
    await initAssignmentCrypto()
    opened(setUpIdentity)
    laneKey = setUpLane
    setUpIdentity = null
    setUpLane = null
  }

  /** The one place the session's identity is set: from an unlock, or from a set-up's read-back. */
  function opened(identity: Identity) {
    ownerIdentity = identity
    ownerFp = fingerprint(identity.ed25519.publicKey)
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

  /** A locked key held: open it with the passphrase or the recovery code. */
  async function unlock() {
    error = ''
    problem = null
    if (!held || held.kind !== 'wrapped') return
    // The document this unlock opens, named by its ETag: the lane sends nothing once the server
    // serves another (lane/lane.ts).
    const keyDocumentEtag = held.etag

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
      const { unlockFromBlob, UNLOCK_FAULT_TEXT } = await import('../../owner/unlock')
      const secret = secretMode === 'passphrase' ? passphrase : groupsToTyped(groups)
      const out = await unlockFromBlob(held.wrapped, secret, secretMode)
      if (!out.ok) {
        error = UNLOCK_FAULT_TEXT[out.fault]
        return
      }
      opened(out.identity)
      laneKey = { syncKey: out.syncKey, keyDocumentEtag }
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
    if (!ownerIdentity || !readWith || !laneKey) return
    onunlock({ ownerBox: ownerIdentity.x25519, ownerSign: ownerIdentity.ed25519, pinned: [...pinned], lane: laneKey }, readWith)
  }
</script>

<div class="unlock">
  <Card title="Owner console">
    <div class="stack">
      <LowerAssuranceBanner />

      {#if !ownerIdentity}
        <p class="hint">{UNLOCK_LEDE}</p>
        <p class="hint">{NOTHING_IS_KEPT}</p>

        {#if newCode}
          <!--
            THE NEW CODE, ONCE. The server has taken the key, read it back and opened it; the code
            is on screen until it is confirmed written down, and then it is gone. The same two
            components the Recovery code screen uses, so the code looks and is checked the same
            wherever it is made.
          -->
          <fieldset class="add">
            <legend>Your recovery code</legend>
            {#if codeStep === 'showing'}
              <!-- Nothing between the heading and the sheet: its one line is the only one above the
                   code (CodeSheet.svelte), and what this door says about the code comes after it. -->
              <CodeSheet display={newCode.display} />
              {#if unreadSent}
                <!-- The server took the key and could not be read back: the code stays, and so does
                     the one way to check it, directly under the words that name it. -->
                <Callout tone="warn"><p class="para">{READ_BACK_FAILED}</p></Callout>
                <button type="button" onclick={checkReadBack} disabled={checking}>{checking ? READ_BUSY : READ_ACTION}</button>
              {:else}
                <p class="hint">{KEY_STORED_WITH_THIS_CODE}</p>
              {/if}
              <p class="hint">{WRITE_IT_ON_PAPER}</p>
              <button class="primary" type="button" onclick={() => (codeStep = 'confirm')}>I have written it down</button>
            {:else}
              <WriteDownCheck
                canonical={newCode.canonical}
                onconfirmed={codeWrittenDown}
                onshowagain={() => (codeStep = 'showing')}
              />
            {/if}
          </fieldset>
        {:else}
          <fieldset class="add">
            <legend>Your server</legend>
            <p class="hint">{KEY_IS_ON_THE_SERVER}</p>
            <label>
              <span>Server URL <em>(blank = this server)</em></span>
              <input type="url" bind:value={serverUrl} oninput={connectionEdited} placeholder="https://daymark.example.com" autocomplete="off" disabled={busy} />
            </label>
            <label>
              <span>Owner access token</span>
              <input type="password" bind:value={token} oninput={connectionEdited} autocomplete="off" disabled={busy} />
            </label>
            {#if !held}
              <button type="button" onclick={connect} disabled={busy}>{busy ? CONNECT_BUSY : CONNECT_ACTION}</button>
            {/if}
          </fieldset>

          {#if notice}<p class="hint notice" role="status">{notice}</p>{/if}

          {#if held?.kind === 'wrapped'}
            <fieldset class="add">
              <legend>Your key</legend>
              <p class="hint">{HOLDS_A_LOCKED_KEY}</p>

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
          {:else if held && ports}
            <KeySetup
              {ports}
              {held}
              {check}
              onstored={keyStored}
              onunread={keyUnread}
              onmoved={keyMoved}
              onlost={keyLost}
              onbusy={(b) => (busy = b)}
            />
          {/if}
        {/if}
      {:else}
        <p class="fp">Your owner fingerprint: <code>{ownerFp}</code></p>
        <p class="hint">{FINGERPRINT_IS_STABLE}</p>
        <p class="pub faint">Share your public keys with clinicians out-of-band to pin:
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

      {#if error}
        <Callout tone={errorTone}><p class="para">{error}</p></Callout>
        {#if asksForARead}
          <button type="button" class="again" onclick={connect} disabled={busy}>{busy ? READ_BUSY : READ_ACTION}</button>
        {/if}
      {/if}
    </div>
  </Card>
</div>

<style>
  .unlock { max-width: 40rem; }
  .stack { display: flex; flex-direction: column; gap: var(--space-3); }
  .hint { margin: 0; color: var(--ink-soft); font-size: 0.9rem; }
  .para { margin: 0; font-size: 0.9rem; line-height: 1.55; }
  .notice { color: var(--ink-text); }
  .again { align-self: flex-start; }
  .fp code, .pub code { font-family: var(--font-mono); font-size: 0.75rem; word-break: break-all; }
  .pub { margin: 0; font-size: 0.8rem; }
  .add { display: flex; flex-direction: column; gap: var(--space-2); border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3); }
  .add legend { padding: 0 var(--space-2); color: var(--ink-soft); font-size: 0.85rem; }
  .add label { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .add label span { color: var(--ink-soft); }
  .add label em { color: var(--text-subtle); font-style: normal; }
  .add > button { align-self: flex-start; }
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
