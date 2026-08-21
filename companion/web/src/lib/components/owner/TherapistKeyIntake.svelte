<script lang="ts">
  /*
   * Where a therapist's public keys enter this console — and the human check that stands between
   * arriving and being trusted.
   *
   * WHY THIS SCREEN EXISTS. Everything downstream of it was already built and had nothing to work
   * on. ShareBuilder seals to a therapist's X25519 key; assignments/share.ts refuses to seal to a
   * key that is not pinned; PinnedTherapistPicker lists the pinned therapists and tells the owner to
   * verify a fingerprint out of band first. But nothing anywhere fetched a therapist's keys, so the
   * only route into the console was OwnerUnlock's paste field — 43 characters of base64url that a
   * clinician had to spell down a phone line, twice, without a typo. A ceremony that is that
   * unpleasant is a ceremony people abandon, and the abandonment shows up as an owner who never
   * shares anything, or one who pastes whatever the server offered them without checking it.
   *
   * WHAT THE SERVER DID AND DID NOT DO. It carried the keys. It did not vouch for them and could
   * not: it accepted two strings from whoever held a therapist session for this relationship, and
   * hands the same two strings back here. A hostile operator, a stolen session or a tampered page
   * can put a key of their choosing on this route, and everything downstream — the seal, the
   * fingerprint, this very screen — would look exactly the same. That is not a defect being
   * apologised for; it is why the screen is shaped the way it is.
   *
   * WHY THE CONFIRMATION IS A VOICE AND NEVER TWO SCREENS. The check this screen asks for is: have
   * the clinician READ THE CHARACTERS OUT to you, on a channel this app is not on. It deliberately
   * never says "check it matches what they see on their screen", and no wording here should ever
   * drift into that. The server that could hand this console a substituted key also serves the page
   * the therapist reads their fingerprint from — so two screens agreeing proves only that one
   * machine agrees with itself, and an owner who has done that comparison has done nothing at all
   * while feeling that they have. A voice on a phone number that existed before this relationship,
   * or a person in the room, is the one part of the exchange the server does not carry.
   *
   * WHY THE ACCEPTANCE COSTS A TYPED STRING. A "yes, I checked" button is something a tampered page
   * can style, pre-select or press. The characters the owner heard are the part a served page cannot
   * supply, which is the same argument PinRecord.svelte makes for its rotation ceremony, and the
   * same argument therapist/pinStore.ts makes for taking words rather than a boolean. The button is
   * a courtesy; acceptTherapistKeys() is the gate and refuses independently of it, so a bug in this
   * markup cannot turn into a trust bug.
   *
   * WHY REFUSING IS A BUTTON TOO. "They read out something else" is the outcome this whole exchange
   * exists to produce, and a screen that offers no way to say it teaches people that the only way
   * forward is the accepting one. It records nothing, keeps nothing, and says what to do next.
   *
   * WHY IT ASKS FOR BOTH FINGERPRINTS AND NOT THE INTERESTING ONE. The encryption key is the one
   * shares are sealed to and the obvious thing to check. But both keys arrive in a single JSON body
   * from a machine that composes it freely, so a server that keeps the clinician's real signing key
   * and swaps only the encryption key would sail through an encryption-blind check, and the mirror
   * of that would sail through a signing-blind one. The first hands an attacker every share the
   * owner will ever seal; the second lets them write assignments the owner opens as their
   * therapist's. A pin records the pair, so the reading-aloud covers the pair. It costs a clinician
   * two lines of characters once per relationship, and that is the right price.
   *
   * WHAT IT DOES NOT REACH. This screen pins; it does not change which keys this console seals to,
   * which are the ones typed in at unlock. If the two disagree the screen says so and the seal path
   * refuses — the safe direction, and the owner is told rather than left to discover it.
   */
  import { fingerprint } from '../../assignments/crypto'
  import { relRefOf } from '../../sync/portal'
  import { loadPins, savePins, pendingRotation } from '../../therapist/pinStore'
  import {
    fetchTherapistKeys,
    acceptTherapistKeys,
    confirmationMatches,
    keyFingerprints,
    groupFingerprint,
    peerOf,
    type OwnerEndpoint,
    type TherapistKeyRecord,
  } from '../../owner/therapistKeys'
  import { Card, Callout } from '../ui'
  import type { PinnedTherapist } from './session'

  let {
    therapist,
    endpoint,
  }: {
    therapist: PinnedTherapist
    /** Null until the console has been connected to a server; the read needs the owner token. */
    endpoint: OwnerEndpoint | null
  } = $props()

  let busy = $state(false)
  let record = $state<TherapistKeyRecord | null>(null)
  /** Distinct from `record === null`: this one means the server answered, with nothing on file. */
  let nothingPublished = $state(false)
  let typedBox = $state('')
  let typedSign = $state('')
  let status = $state('')
  let error = $state('')

  const fps = $derived(record ? keyFingerprints(record) : null)
  /** The characters in reading groups, so a person can say them a chunk at a time. */
  const readingGroups = (fp: string) => groupFingerprint(fp).join(' ')
  const typed = $derived({ boxFp: typedBox, signFp: typedSign })

  /*
   * acceptTherapistKeys's own predicate, not a second one written here. A button that lit up on
   * characters the gate would reject teaches an owner that the typing is decoration.
   */
  const matches = $derived(record ? confirmationMatches(record, typed) : false)

  /**
   * Whether this browser already holds a DIFFERENT encryption key for this therapist.
   *
   * Read from storage rather than from the session, and read before anything is offered, so the
   * owner sees "this is not the key on file" while they are still deciding — not as an outcome
   * after they have pressed accept. An unreadable record is NOT treated as "no conflict": loadPins
   * throws in that case and the throw is carried out of here as `unreadable`, because quietly
   * reporting "nothing on file" is exactly the reassurance a record nobody can read cannot support.
   *
   * Both answers come out of one derived rather than one derived and a flag: a derived that writes
   * to state is a Svelte error, and — the reason that rule exists — two values computed from one
   * read of storage cannot disagree with each other, which they could if the flag were set
   * elsewhere. Nothing here depends on `typed`, so this does not re-read storage on every keystroke.
   */
  const pinCheck = $derived.by(() => {
    if (!record) return { rotation: null, unreadable: false }
    try {
      return { rotation: pendingRotation(loadPins(), peerOf(record)), unreadable: false }
    } catch {
      return { rotation: null, unreadable: true }
    }
  })

  /** The keys entered by hand for this therapist at unlock, if they are not the published ones. */
  const disagreesWithSession = $derived.by(() => {
    if (!fps) return false
    return fps.boxFp !== fingerprint(therapist.boxPub) || fps.signFp !== fingerprint(therapist.signPub)
  })

  async function read() {
    if (!endpoint) return
    error = ''
    status = ''
    typedBox = ''
    typedSign = ''
    record = null
    nothingPublished = false
    busy = true
    try {
      // relRef, not the inbox token: the module is never handed a secret it has no request for.
      // Same derivation InvitePanel uses, so both owner calls route by the same value.
      const relRef = await relRefOf(therapist.inboxToken)
      const fetched = await fetchTherapistKeys(endpoint, relRef)
      if (fetched === null) nothingPublished = true
      else record = fetched
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not read the published keys.'
    } finally {
      busy = false
    }
  }

  function accept() {
    if (!record) return
    error = ''
    status = ''
    try {
      const pins = loadPins()
      const outcome = acceptTherapistKeys(pins, record, typed)
      if (outcome === 'pinned-now') {
        savePins(pins)
        status = `Recorded in this browser: both fingerprints, and today's date. Shares you seal to ${therapist.displayName} from here on are checked against that encryption key, and one that changes without you hearing about it is refused rather than sealed to.`
        typedBox = ''
        typedSign = ''
      } else if (outcome === 'already-pinned') {
        status = `These are the keys already on file for ${therapist.displayName}. Nothing changed, and nothing needed to.`
        typedBox = ''
        typedSign = ''
      } else {
        // 'differs-from-pin'. Said plainly rather than as a failure: nothing is wrong with what the
        // owner just did, and the next step is a different screen with a heavier ceremony.
        error = `This console already holds a different encryption key for ${therapist.displayName}, so nothing was recorded and nothing has been sealed to the key above. A therapist who changed their keys and someone substituting their own look identical from here. The Pinned keys tab shows the two side by side and can replace the old one after you have checked the new one with them.`
      }
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not record these keys.'
    }
  }

  function refuse() {
    record = null
    typedBox = ''
    typedSign = ''
    error = ''
    status = `Nothing was recorded and nothing has been sealed to those keys. Reach ${therapist.displayName} another way — a number you had before this relationship, or in the room — before reading them again from here.`
  }

  /** The server's word about when it filed them, labelled as the server's word wherever shown. */
  const filedOn = (ms: number) => (ms ? new Date(ms).toLocaleDateString() : 'a date it did not give')
</script>

<div class="intake">
  <Card>
    <div class="stack">
      <h4>Keys {therapist.displayName} published</h4>
      <p class="hint">
        Your therapist's portal publishes their two public keys to the server, and this reads them
        back. What it shows you are their FINGERPRINTS — a short value worked out from each key,
        short enough that two people can read it to each other and different for every key. The
        server carries the keys and does not vouch for them: it cannot tell your therapist's real
        key from one substituted for it, and neither can this page. What tells the two apart is the
        check below, which happens off this screen entirely.
      </p>

      {#if !endpoint}
        <Callout tone="info" title="Not connected to a server">
          Open the server connection above and enter your owner access token. Reading the published
          keys is an owner-authenticated call; there is nothing to read without it.
        </Callout>
      {:else}
        <div class="row">
          <button class="primary" onclick={read} disabled={busy}>
            {busy ? 'Reading…' : record || nothingPublished ? 'Read again' : 'Read the published keys'}
          </button>
        </div>
      {/if}

      {#if nothingPublished}
        <Callout tone="info" title="Nothing published yet">
          {therapist.displayName} has not published keys for this relationship. That is the ordinary
          state between sending an invite and their finishing enrolment — there is nothing to fix and
          nothing to pin, and this console will not seal anything to a key it has not been given.
          Check again once they tell you they are set up.
        </Callout>
      {/if}

      {#if record && fps}
        <div class="record">
          <dl class="fps">
            <dt>Encryption key fingerprint</dt>
            <dd>
              <code>{readingGroups(fps.boxFp)}</code>
              <span class="what">the key your shares are sealed to</span>
            </dd>
            <dt>Signing key fingerprint</dt>
            <dd>
              <code>{readingGroups(fps.signFp)}</code>
              <span class="what">what assignments and game plans from them are checked against</span>
            </dd>
            <dt>Filed by the server</dt>
            <dd>
              <span class="when">{filedOn(record.registeredAt)}</span>
              <span class="what">the server's own note of when it took them; nothing here checks it</span>
            </dd>
          </dl>

          <p class="ask">
            Ask {therapist.displayName} to read both of these out to you, four characters at a
            time, on a channel this app is not on — in the room, or a phone number you had before
            this relationship. Each fingerprint is worked out from a key their own browser made, so
            what they read from their console and what this console worked out from what the server
            handed it can only agree if the keys came through untouched. Do not compare these
            against anything on a screen: the same server that could hand this console the wrong key
            also serves the page their fingerprints appear on, so two screens agreeing shows only
            that one machine agrees with itself. Their voice is the part of this exchange the server
            does not carry.
          </p>
          <p class="ask">
            Both, not the interesting one. The two keys arrive together in one answer from one
            machine, so checking only the encryption key leaves the signing key free to be swapped —
            and someone holding a signing key you trusted can write assignments you will read as
            theirs. Their own page ends by listing both under these same two names, so that they
            have both to read to you — not so that anyone can hold two screens up against each
            other, which would prove nothing. If they can find only one of the two, stop here: half
            a check is not a check, and nothing is recorded on the strength of it.
          </p>

          {#if pinCheck.rotation}
            <Callout tone="warn" title="This is not the key on file">
              This browser already recorded a different encryption key for {therapist.displayName}
              on {filedOn(pinCheck.rotation.pinnedAt)}. Nothing has been sealed to the key above and
              nothing will be from this screen. If they did change their keys, the Pinned keys tab
              shows the old and the new side by side and can replace one after its own check.
            </Callout>
          {:else if pinCheck.unreadable}
            <Callout tone="warn" title="The stored pins could not be read">
              This browser could not read its record of which keys you have pinned, so it cannot say
              whether the key above is the one it knows for {therapist.displayName}. Sort that out on
              the Pinned keys tab before recording anything here.
            </Callout>
          {/if}

          {#if disagreesWithSession}
            <Callout tone="warn" title="Not the keys entered for them in this console">
              The keys you typed in for {therapist.displayName} when you unlocked this console are
              not these. Sealing uses the ones you typed in, so recording these would leave the
              console holding two different answers and refusing to seal — which is the safe
              outcome, not a bug. Work out which is right with them before going on.
            </Callout>
          {/if}

          <div class="typing">
            <label class="confirm">
              <span>Encryption key fingerprint, as {therapist.displayName} read it out</span>
              <input type="text" bind:value={typedBox} autocomplete="off" spellcheck="false" />
            </label>
            <label class="confirm">
              <span>Signing key fingerprint, as they read it out</span>
              <input type="text" bind:value={typedSign} autocomplete="off" spellcheck="false" />
            </label>
          </div>
          <p class="case faint">
            Capitals count: <code>k</code> and <code>K</code> are different characters here. Spacing
            does not — group it however it was read to you.
          </p>

          <div class="row">
            <button class="primary" onclick={accept} disabled={!matches}>
              They read these out — record them
            </button>
            <button class="quiet" onclick={refuse}>They read out something else</button>
          </div>
        </div>
      {/if}

      {#if status}<p class="status" role="status">{status}</p>{/if}
      {#if error}<Callout tone="critical">{error}</Callout>{/if}
    </div>
  </Card>
</div>

<style>
  .intake { max-width: 44rem; }
  .stack { display: flex; flex-direction: column; gap: var(--space-3); }
  .hint { margin: 0; font-size: 0.85rem; color: var(--ink-soft); line-height: 1.55; }
  .faint { color: var(--text-subtle); }

  .record { display: flex; flex-direction: column; gap: var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); padding: var(--space-3); }
  .fps { display: grid; grid-template-columns: auto 1fr; gap: var(--space-2) var(--space-3); margin: 0; font-size: 0.8rem; }
  .fps dt { color: var(--ink-soft); }
  .fps dd { margin: 0; display: flex; flex-direction: column; gap: 0.15rem; }
  /* The characters are what gets read down a phone line, so they get the mono face and room to
     break — a fingerprint clipped at the edge of a column is a fingerprint someone misreads. */
  code { font-family: var(--font-mono); font-size: 0.8rem; color: var(--ink-text); word-break: break-all; }
  .when { font-size: 0.8rem; color: var(--ink-text); }
  .what { font-size: 0.75rem; color: var(--text-subtle); }

  .ask { margin: 0; font-size: 0.85rem; color: var(--ink-soft); line-height: 1.55; }
  .typing { display: flex; flex-direction: column; gap: var(--space-2); }
  .confirm { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .confirm span { color: var(--ink-soft); }
  input { font: inherit; font-family: var(--font-mono); padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  .case { margin: 0; font-size: 0.75rem; }
  .case code { font-size: 0.75rem; }

  .row { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }
  .quiet { font-size: 0.8rem; color: var(--ink-soft); }

  /* Confirmation is solid ink, never a coloured tick: what happened is that a record changed, not
     that anything checked out — the checking happened on a phone call this page knows nothing
     about, and a reassuring mark here would be this page claiming credit for it. */
  .status { margin: 0; font-size: 0.85rem; color: var(--ink-text); line-height: 1.55; }
</style>
