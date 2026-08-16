<script lang="ts">
  /*
   * The therapist pin record, and the two things a person can do to it: forget it, or rotate one
   * entry after checking the new key out of band.
   *
   * WHY THIS SCREEN EXISTS. therapist/pinStore.ts writes the first persistent client-side storage
   * in this app. It holds fingerprints and dates — no keys, no writing, no names — but the record
   * itself says this browser profile is a Daymark owner console with N therapist relationships,
   * first recorded on these dates. On a device that gets shared, borrowed or looked through, the
   * count of clinicians someone sees and when that started is information about their care. Until
   * this screen, the only way to remove it was clearing site data, which is a thing a person has
   * to already know to do, and which also silently un-pins every therapist. Storage a person
   * cannot see and cannot erase is not something this product gets to leave lying around.
   *
   * WHY ROTATION COSTS SOMETHING. The pin buys exactly one property: a therapist's encryption key
   * that changed behind the owner's back is refused instead of being sealed to. A one-click "trust
   * the new key" button would sell that property back for a click — the same hostile server that
   * can substitute the key also serves this page, and can make the accepting button the obvious
   * one. So rotation asks for the SAS words, which are the part a served page cannot supply
   * because the owner gets them from their therapist on a channel this app is not on. The button
   * is inert until they match, and rotatePin() refuses independently of the button, so a UI bug
   * cannot become a trust bug.
   *
   * WHY THE OLD SIDE SHOWS A FINGERPRINT AND THE NEW SIDE SHOWS WORDS. The words are derived from
   * the key material, and the record deliberately never stored key material — so the old key's
   * words are not recoverable, and claiming otherwise would mean storing more about the person's
   * clinicians than the pin needs. The two fingerprints side by side are what shows that something
   * changed; the new key's words are what the owner actually verifies with a human.
   *
   * WHY NEITHER ACTION IS ONE CLICK FROM THE TAB. Forgetting is behind a closed disclosure and
   * then a second, differently-worded button; rotation is behind opening the row and typing the
   * words. Nothing here should be reachable by a mis-click, in either direction: erasing the
   * record loses key-substitution cover, and accepting a key loses it faster.
   */
  import { PairingError, type PinnedIdentity } from '../../share/pairing'
  import {
    loadPins,
    savePins,
    listPins,
    forgetAllPins,
    forgetPeer,
    pendingRotation,
    rotatePin,
    sasWordsMatch,
    type PinRotation,
  } from '../../therapist/pinStore'
  import { Card, Callout } from '../ui'
  import type { OwnerSession, PinnedTherapist } from './session'

  let { session }: { session: OwnerSession } = $props()

  let record = $state<PinnedIdentity[]>([])
  let rotations = $state<Record<string, PinRotation>>({})
  let loadError = $state('')
  let status = $state('')
  let actionError = $state('')

  /** Which row has its rotation panel open, and which row's forget is awaiting the second click. */
  let rotatingFp = $state<string | null>(null)
  let typed = $state('')
  let confirmingForget = $state<string | null>(null)
  let confirmingForgetAll = $state(false)

  interface Row {
    pin: PinnedIdentity
    /** Null when the record holds a therapist whose keys were not entered in this session. */
    therapist: PinnedTherapist | null
    rotation: PinRotation | null
  }

  const rows = $derived<Row[]>(
    record.map((pin) => ({
      pin,
      therapist: session.pinned.find((t) => t.id === pin.ed25519Fp) ?? null,
      rotation: rotations[pin.ed25519Fp] ?? null,
    })),
  )

  /** Therapists in this session with no entry yet: trust-on-first-use has not happened. */
  const notYetRecorded = $derived(session.pinned.filter((t) => !record.some((p) => p.ed25519Fp === t.id)))

  /*
   * Read once at mount, and again after each action. Not an $effect: the record only changes when
   * this screen changes it or when a share is sealed, and re-reading storage on every keystroke in
   * the confirmation field would be a way to lose what someone had typed.
   */
  function refresh() {
    try {
      const pins = loadPins()
      record = listPins(pins)
      const found: Record<string, PinRotation> = {}
      for (const t of session.pinned) {
        const r = pendingRotation(pins, { x25519Pub: t.boxPub, ed25519Pub: t.signPub })
        if (r) found[t.id] = r
      }
      rotations = found
      loadError = ''
    } catch (e) {
      // The record is unreadable, which is the one case where forgetting is also the repair — so
      // the forget section stays rendered below rather than being hidden behind a working read.
      record = []
      rotations = {}
      loadError = e instanceof PairingError ? e.message : 'Could not read the stored pins.'
    }
  }
  refresh()

  function clearMessages() {
    status = ''
    actionError = ''
  }

  function openRotation(fp: string) {
    clearMessages()
    rotatingFp = rotatingFp === fp ? null : fp
    typed = ''
  }

  /*
   * rotatePin's own predicate, not a second one written here: a button that lit up on a phrase
   * rotatePin would reject would teach an owner that the words are decoration. The button is the
   * courtesy; rotatePin is the gate, and it refuses whatever this returns.
   */
  const matches = $derived.by(() => {
    const t = session.pinned.find((x) => x.id === rotatingFp)
    return t ? sasWordsMatch(t.fingerprintWords, typed) : false
  })

  function rotate(t: PinnedTherapist) {
    clearMessages()
    try {
      const pins = loadPins()
      rotatePin(
        pins,
        { x25519Pub: t.boxPub, ed25519Pub: t.signPub },
        { expectedWords: t.fingerprintWords, typedWords: typed },
      )
      savePins(pins)
      status = `Recorded the new key for ${t.displayName}. Shares you seal from now on go to it, and the key that was on file before will be refused.`
      rotatingFp = null
      typed = ''
      refresh()
    } catch (e) {
      actionError = e instanceof Error ? e.message : 'Could not rotate the pin.'
    }
  }

  function forgetOne(row: Row) {
    clearMessages()
    try {
      savePins(forgetPeer(loadPins(), row.pin.ed25519Fp))
      status = `Forgotten on this browser. The next share sealed to ${row.therapist?.displayName ?? 'them'} records whatever key it is given.`
      confirmingForget = null
      refresh()
    } catch (e) {
      actionError = e instanceof Error ? e.message : 'Could not forget this pin.'
    }
  }

  function forgetEverything() {
    clearMessages()
    try {
      forgetAllPins()
      status = 'This browser no longer holds a record of any therapist keys.'
      confirmingForgetAll = false
      confirmingForget = null
      rotatingFp = null
      refresh()
    } catch (e) {
      actionError = e instanceof Error ? e.message : 'Could not clear the record.'
    }
  }

  /*
   * A pin written before this format carried timestamps loads with pinnedAt 0. Saying so beats
   * printing 1 January 1970, which reads as a real date someone might try to remember doing.
   */
  const recordedOn = (ms: number) =>
    ms ? `first recorded ${new Date(ms).toLocaleDateString()}` : 'no date recorded'
  const onFileSince = (ms: number) =>
    ms ? `On file since ${new Date(ms).toLocaleDateString()}` : 'On file'
</script>

<div class="pins">
  <Card>
    <div class="stack">
      <h4>Pinned keys</h4>
      <p class="hint">
        The first time you seal a share to a therapist, this browser writes down the fingerprints of
        the two public keys you used. Every later share is checked against that note, so a key that
        changes without you knowing is refused rather than sealed to. The note lives in this browser
        only — it holds fingerprints and dates, never keys, names or anything you have written.
      </p>

      {#if loadError}
        <Callout tone="critical" title="The stored record could not be read">{loadError}</Callout>
      {:else if rows.length === 0}
        <p class="empty faint">
          Nothing recorded yet. The first share you seal to a therapist writes their fingerprints here.
        </p>
      {/if}

      {#each rows as row (row.pin.ed25519Fp)}
        <div class="row">
          <p class="who">
            <strong>{row.therapist?.displayName ?? 'A therapist not entered in this session'}</strong>
            <span class="faint">· {recordedOn(row.pin.pinnedAt)}</span>
          </p>
          <dl class="fps">
            <dt>Signing key</dt>
            <dd><code>{row.pin.ed25519Fp}</code></dd>
            <dt>Encryption key on file</dt>
            <dd><code>{row.pin.x25519Fp}</code></dd>
          </dl>

          {#if row.rotation && row.therapist}
            <Callout tone="warn" title="This is not the key on file">
              The console is holding a different encryption key for {row.therapist.displayName} than
              the one recorded here. A therapist who changed their keys looks exactly like someone
              substituting their own, and this browser cannot tell the two apart. Nothing has been
              sealed to the new key, and nothing will be until you accept it.
            </Callout>
            <button class="open" onclick={() => openRotation(row.pin.ed25519Fp)}>
              {rotatingFp === row.pin.ed25519Fp ? 'Close' : 'Compare the two keys'}
            </button>
          {/if}

          {#if row.rotation && row.therapist && rotatingFp === row.pin.ed25519Fp}
            {@const t = row.therapist}
            <div class="rotate">
              <div class="side-by-side">
                <div class="side">
                  <p class="label">{onFileSince(row.rotation.pinnedAt)}</p>
                  <code>{row.rotation.pinnedX25519Fp}</code>
                  <p class="faint note">
                    The words for this key cannot be shown: they are worked out from the key itself,
                    and the key was never stored here — only its fingerprint.
                  </p>
                </div>
                <div class="side">
                  <p class="label">Offered now</p>
                  <code>{row.rotation.offeredX25519Fp}</code>
                  <p class="words">{t.fingerprintWords}</p>
                </div>
              </div>

              <p class="ask">
                Ask {t.displayName}, on a channel you already trust — in the room, or a phone number
                you had before this — to read out the words their own console shows for you. Those
                words are worked out from both of your keys, so they match only if the key you have
                been offered is the key they are holding. If they read out something else, do not
                accept it: someone is standing between you, and refusing costs you nothing but a
                delayed share.
              </p>

              <label class="confirm">
                <span>Type the words {t.displayName} read back to you</span>
                <input type="text" bind:value={typed} autocomplete="off" spellcheck="false" />
              </label>

              <button class="primary" onclick={() => rotate(t)} disabled={!matches}>
                They match — record this key instead
              </button>
            </div>
          {/if}

          {#if confirmingForget === row.pin.ed25519Fp}
            <div class="confirm-line">
              <span
                >Forget this one? The next share sealed to
                {row.therapist?.displayName ?? 'them'} records whatever key it is given, unchecked.</span
              >
              <button class="danger" onclick={() => forgetOne(row)}>Yes, forget it</button>
              <button onclick={() => (confirmingForget = null)}>Keep it</button>
            </div>
          {:else}
            <button
              class="quiet"
              onclick={() => {
                clearMessages()
                confirmingForget = row.pin.ed25519Fp
              }}>Forget this one…</button
            >
          {/if}
        </div>
      {/each}

      {#if notYetRecorded.length > 0}
        <p class="hint">
          Not recorded yet: {notYetRecorded.map((t) => t.displayName).join(', ')}. The first share you
          seal to them writes their fingerprints down, and there is nothing to compare that first one
          against — check the words with them before you send it.
        </p>
      {/if}

      <details class="forget">
        <summary>Forget the record on this browser</summary>
        <div class="forget-body">
          <p>
            This erases the list above from this browser: the fingerprints, and the dates they were
            first written down. It does not reach the server, which has never held this record. It
            does not reach your therapists, and it does not unsend anything you have already shared.
          </p>
          <p>
            It also gives up what the record was for. With nothing on file, the next share you seal
            to a therapist trusts whatever key it is handed, the way the first one did — so if a key
            has been substituted in the meantime, this console will not notice.
          </p>
          {#if confirmingForgetAll}
            <div class="confirm-line">
              <span>Erase the whole record?</span>
              <button class="danger" onclick={forgetEverything}>Yes, forget them all</button>
              <button onclick={() => (confirmingForgetAll = false)}>Keep the record</button>
            </div>
          {:else}
            <button
              class="quiet"
              onclick={() => {
                clearMessages()
                confirmingForgetAll = true
              }}>Forget every pinned key…</button
            >
          {/if}
        </div>
      </details>

      {#if status}<p class="status" role="status">{status}</p>{/if}
      {#if actionError}<Callout tone="critical">{actionError}</Callout>{/if}
    </div>
  </Card>
</div>

<style>
  .pins { max-width: 44rem; }
  .stack { display: flex; flex-direction: column; gap: var(--space-3); }
  .hint { margin: 0; font-size: 0.85rem; color: var(--ink-soft); }
  .empty { margin: 0; }
  .faint { color: var(--text-subtle); }

  .row { display: flex; flex-direction: column; gap: var(--space-2); border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3); }
  .who { margin: 0; font-size: 0.9rem; }
  .fps { display: grid; grid-template-columns: auto 1fr; gap: var(--space-1) var(--space-3); margin: 0; font-size: 0.8rem; }
  .fps dt { color: var(--ink-soft); }
  .fps dd { margin: 0; }
  code { font-family: var(--font-mono); font-size: 0.75rem; word-break: break-all; }

  .rotate { display: flex; flex-direction: column; gap: var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); padding: var(--space-3); }
  /* Side by side where there is room, stacked where there is not — the comparison is the point,
     so the two keys must never end up on different screens of a phone scroll. */
  .side-by-side { display: grid; grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr)); gap: var(--space-3); }
  .side { display: flex; flex-direction: column; gap: var(--space-1); }
  .label { margin: 0; font-size: 0.8rem; color: var(--ink-soft); }
  .note { margin: 0; font-size: 0.75rem; }
  /* The words are the thing being read aloud, so they get size and the mono face — misreading a
     word here is the failure the whole exchange exists to prevent. */
  .words { margin: 0; font-family: var(--font-mono); font-size: 0.9rem; color: var(--ink-text); word-spacing: 0.2em; }
  .ask { margin: 0; font-size: 0.85rem; color: var(--ink-soft); line-height: 1.55; }
  .confirm { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .confirm span { color: var(--ink-soft); }
  input { font: inherit; font-family: var(--font-mono); padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }

  .confirm-line { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; font-size: 0.85rem; color: var(--ink-soft); }
  /* Clay is the one alarm hue in this system, and erasing is where it belongs: this is the button
     that cannot be undone. It is never the default-looking one on the row. */
  .danger { color: var(--clay); border-color: var(--clay); }
  .quiet { font-size: 0.8rem; color: var(--ink-soft); }
  .open { align-self: flex-start; font-size: 0.85rem; }

  .forget { border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); }
  .forget summary { cursor: pointer; font-size: 0.9rem; color: var(--ink-soft); }
  .forget-body { display: flex; flex-direction: column; gap: var(--space-2); margin-top: var(--space-3); font-size: 0.85rem; color: var(--ink-soft); line-height: 1.55; }
  .forget-body p { margin: 0; }

  /* Confirmation is solid ink, never a coloured tick — a tick would claim something checked out,
     and what happened here is only that a record changed. */
  .status { margin: 0; font-size: 0.85rem; color: var(--ink-text); }
</style>
