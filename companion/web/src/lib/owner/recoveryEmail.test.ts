import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import * as copy from './recoveryEmail'
import {
  BEFORE_YOU_NEED_IT,
  EMAIL_LABEL,
  EMAIL_MISSING,
  LOAD_FAILED,
  LOST_TOKEN_HEADING,
  LOST_TOKEN_LEDE,
  NEEDS_TOKEN,
  OWNER_CONSOLE_POINTER,
  REGISTER,
  REGISTER_LEDE,
  REMOVE,
  SAVE_FAILED,
  SENDING_NEEDS_EMAIL,
  SUMMARY_REGISTERED,
  SUMMARY_UNREGISTERED,
  recoverCardView,
  registerRecoveryEmail,
  registeredStatement,
  type NotificationPort,
  type RecoverCardView,
} from './recoveryEmail'
import { offersRoute } from '../onboarding/audience'
import { PortalClient, type NotificationSettings } from '../sync/portal'
import type { ShapeId } from '../setup/shape'

/*
 * THE "RECOVER ACCESS" CARD (#330)
 *
 * On a server whose published shape withholds the owner console — solo — this card is the one
 * place to register the address a recovery link is sent to. Everywhere else the owner console's
 * Notifications tab holds it, and the card points there. Five states, each a node test over the
 * module and a structural test over the component: solo without a token, solo with one, solo with
 * an address registered, paired, and no published shape.
 */

const read = (rel: string) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
/** What ships: markup and script with the commentary removed. */
const codeOf = (rel: string) =>
  read(rel)
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(?<!:)\/\/[^\n]*/g, ' ')

const CARD = codeOf('../components/owner/RecoverAccess.svelte')
const APP = codeOf('../../App.svelte')
const SYNC = codeOf('../components/SyncPanel.svelte')
const NOTIFY = codeOf('../components/owner/NotificationSettings.svelte')

const EMAIL = 'someone@example.org'

/** The card for one published shape, with the owner-console rule the page itself uses. */
const cardFor = (published: ShapeId | null, connected: boolean, registered: string | null = null) =>
  recoverCardView({ ownerConsoleOffered: offersRoute('owner', published), connected, registered })

/** Every sentence and label a view puts on the card. */
function wordsOf(view: RecoverCardView): string[] {
  const setup = view.setup
  const setupWords =
    setup.kind === 'register'
      ? [setup.lede, EMAIL_LABEL, REGISTER, ...(setup.registered ? [registeredStatement(setup.registered)] : [])]
      : [setup.text, setup.link.label]
  return [...(view.summary ? [view.summary] : []), BEFORE_YOU_NEED_IT, ...setupWords, view.lostHeading, ...view.lostLede]
}

describe('the words, as the copy pass settled them and as the server makes them true', () => {
  it('pins every sentence and label on the card', () => {
    expect(SUMMARY_UNREGISTERED).toBe(
      'Set this up before you need it. If you lose your access token, a one-time link can be sent to ' +
        'an email address you register here.',
    )
    expect(SUMMARY_REGISTERED).toBe(
      'A recovery email is registered. If you lose your access token, a one-time link can be sent to it.',
    )
    expect(BEFORE_YOU_NEED_IT).toBe('Before you need it')
    expect(REGISTER_LEDE).toBe('Register an email address for recovery. Nothing else is sent to it on this server.')
    expect(EMAIL_LABEL).toBe('Recovery email')
    expect(REGISTER).toBe('Register')
    expect(REMOVE).toBe('Remove')
    expect(registeredStatement(EMAIL)).toBe(
      'Recovery email registered: someone@example.org. You can change or remove it here.',
    )
    expect(NEEDS_TOKEN).toBe(
      'Registering an email needs your access token. Connect to your sync server first, then come back here.',
    )
    expect(OWNER_CONSOLE_POINTER).toBe('Register or change the email in the owner console, under Notifications.')
    expect(LOST_TOKEN_HEADING).toBe('If you have lost your access token')
    expect(LOST_TOKEN_LEDE).toBe('If you registered a recovery email, enter it and a one-time link will be sent to it.')
    expect(SENDING_NEEDS_EMAIL).toBe('That needs this server to be set up to send email.')
    expect(EMAIL_MISSING).toBe('Enter the recovery email you registered.')
    expect(LOAD_FAILED).toBe('This page could not read the recovery email registered on your server.')
    expect(SAVE_FAILED).toBe('That change to the recovery email could not be saved.')
  })

  it('the link is one-time, and is sent only by a server with email: the server says so', () => {
    const SERVER = '../../../../server/src/main/kotlin/com/daymark/companion/'
    const store = codeOf(`${SERVER}mail/OwnerAccountStore.kt`)
    const routes = codeOf(`${SERVER}routes/RecoveryRoutes.kt`)
    const mailer = codeOf(`${SERVER}mail/Mailer.kt`)
    // One-time: the confirmation flips to CONSUMED and an unused one expires.
    expect(store).toContain("UPDATE reissue_confirm SET status='CONSUMED' WHERE token_hash=?")
    expect(store).toContain('if (status != "PENDING" || now >= expiry) return ReissueConfirmOutcome.Gone')
    // Taken from the address typed, and minted only when it matches the registered one.
    expect(routes).toContain('accountStore.requestReissue(req.email.trim(), confirmTtlSeconds)')
    expect(store).toContain('val registered = registeredEmail() ?: return null')
    // Sent through a mailer that sends nothing when the server has no outbound email.
    expect(mailer).toMatch(/if \(!enabled\) \{/)
    // So the promise of a link stands beside the condition that keeps it true.
    expect(LOST_TOKEN_LEDE).toMatch(/will be sent/)
    expect(cardFor('solo', false).lostLede).toEqual([LOST_TOKEN_LEDE, SENDING_NEEDS_EMAIL])
  })

  it('on a solo server nothing but recovery mail reaches the address: every other owner mail comes from clinician routes', () => {
    const SERVER = '../../../../server/src/main/kotlin/com/daymark/companion/'
    const notifier = codeOf(`${SERVER}mail/OwnerNotifier.kt`)
    expect(notifier).toContain('mailer.send(MailMessage.ReviewNotification(email, portalUrl, event))')
    // Every file that notifies the owner, found by walking the server's source rather than listed:
    // the two clinician route files a solo shape switches off. Recovery itself sends the link and
    // the notice that the link re-issued a token, and nothing else.
    const root = fileURLToPath(new URL(SERVER, import.meta.url))
    const notifying = (readdirSync(root, { recursive: true }) as string[])
      .filter((f) => f.endsWith('.kt') && /notifier\.notify\(/.test(codeOf(`${SERVER}${f}`)))
      .sort()
    expect(notifying).toEqual(['routes/RelationRoutes.kt', 'routes/TherapistAuthRoutes.kt'])
    const routes = codeOf(`${SERVER}routes/RecoveryRoutes.kt`)
    expect(routes).toContain('MailMessage.AccessRecovery(')
    expect(routes).toContain('MailMessage.SecurityNotice(addr, MailMessage.SecurityEvent.TOKEN_REISSUED)')
    expect(routes.match(/mailer\.send\(/g)).toHaveLength(2)
  })

  it('the lines they replace are gone from the card', () => {
    const RETIRED = /If you registered a notification email|registered for notifications|Lost your owner access token\?/
    expect(
      'Lost your owner access token? If you registered a notification email, you can request a new one here.',
    ).toMatch(RETIRED)
    expect("error = 'Enter the email you registered for notifications.'").toMatch(RETIRED)
    expect(CARD).not.toMatch(RETIRED)
    expect(Object.values(copy).filter((v) => typeof v === 'string' && RETIRED.test(v))).toEqual([])
  })
})

describe('the card, state by state', () => {
  it('solo, with no token in this page: the lead, and a link to the sync card in place of the field', () => {
    const view = cardFor('solo', false)
    expect(view.summary).toBe(SUMMARY_UNREGISTERED)
    expect(view.setup).toEqual({
      kind: 'needs-token',
      text: NEEDS_TOKEN,
      link: { route: 'sync', label: 'Connect to your sync server' },
    })
  })

  it('solo, with a token: the field, and nothing to remove yet', () => {
    const view = cardFor('solo', true)
    expect(view.summary).toBe(SUMMARY_UNREGISTERED)
    expect(view.setup).toEqual({ kind: 'register', lede: REGISTER_LEDE, registered: null, canRemove: false })
  })

  it('solo, with an address registered: the other lead, the address, and a way to remove it', () => {
    const view = cardFor('solo', true, EMAIL)
    expect(view.summary).toBe(SUMMARY_REGISTERED)
    expect(view.setup).toEqual({ kind: 'register', lede: REGISTER_LEDE, registered: EMAIL, canRemove: true })
  })

  it('paired, practice and no published shape: no lead, and a pointer to the owner console', () => {
    for (const published of ['paired', 'practice', null] as const) {
      for (const connected of [false, true]) {
        const view = cardFor(published, connected, connected ? EMAIL : null)
        expect(view.summary, String(published)).toBeNull()
        expect(view.setup, String(published)).toEqual({
          kind: 'owner-console',
          text: OWNER_CONSOLE_POINTER,
          link: { route: 'owner', label: 'Owner console — clinicians and shares' },
        })
      }
    }
  })

  it('every state keeps the lost-token part, with its condition', () => {
    for (const published of ['solo', 'paired', 'practice', null] as const) {
      for (const connected of [false, true]) {
        const view = cardFor(published, connected)
        expect(view.lostHeading).toBe(LOST_TOKEN_HEADING)
        expect(view.lostLede).toEqual([LOST_TOKEN_LEDE, SENDING_NEEDS_EMAIL])
      }
    }
  })

  it('on a solo server the card never points at the owner console or its Notifications tab', () => {
    const POINTS_AT_THE_CONSOLE = /\bowner console\b|\bnotifications?\b/i
    // Control: the paired card does, in its pointer and its link, so the detector sees it.
    expect(wordsOf(cardFor('paired', false)).filter((w) => POINTS_AT_THE_CONSOLE.test(w)).length).toBeGreaterThanOrEqual(2)
    for (const [connected, registered] of [[false, null], [true, null], [true, EMAIL]] as const) {
      const view = cardFor('solo', connected, registered)
      expect(view.setup.kind).not.toBe('owner-console')
      expect(wordsOf(view).filter((w) => POINTS_AT_THE_CONSOLE.test(w))).toEqual([])
    }
  })
})

describe('registering goes through the owner route the Notifications tab uses', () => {
  /** A port that records what it was asked, over a server holding [initial]. */
  function recordingPort(initial: NotificationSettings) {
    let held = { ...initial }
    const calls: string[] = []
    const port: NotificationPort = {
      async getNotificationSettings() {
        calls.push('get')
        return { ...held }
      },
      async setNotificationSettings(email, events) {
        calls.push(`set ${String(email)} [${events.join(',')}]`)
        held = { email: email === null ? null : email.trim().toLowerCase(), events }
      },
    }
    return { port, calls }
  }

  it('reads the chosen notifications first and writes them back unchanged, then reads the address back', async () => {
    const { port, calls } = recordingPort({ email: null, events: ['NEW_ASSIGNMENT'] })
    expect(await registerRecoveryEmail(port, ' Someone@Example.org ')).toBe('someone@example.org')
    expect(calls).toEqual(['get', 'set  Someone@Example.org  [NEW_ASSIGNMENT]', 'get'])
  })

  it('removes the address with null, keeping the chosen notifications', async () => {
    const { port, calls } = recordingPort({ email: EMAIL, events: ['THERAPIST_ENROLLED'] })
    expect(await registerRecoveryEmail(port, null)).toBeNull()
    expect(calls).toEqual(['get', 'set null [THERAPIST_ENROLLED]', 'get'])
  })

  it('reaches GET and PUT /v1/owner/notifications with the access token, through the client the tab uses', async () => {
    const seen: { method: string; url: string; auth: string | null; body: string | null }[] = []
    let stored: NotificationSettings = { email: null, events: ['NEW_GAMEPLAN'] }
    const fakeFetch = (async (url: string, init: RequestInit = {}) => {
      const headers = (init.headers ?? {}) as Record<string, string>
      const method = init.method ?? 'GET'
      seen.push({ method, url, auth: headers.Authorization ?? null, body: (init.body as string) ?? null })
      if (method === 'PUT') {
        const body = JSON.parse(init.body as string) as NotificationSettings
        stored = { email: body.email?.toLowerCase() ?? null, events: body.events }
        return new Response(null, { status: 204 })
      }
      return new Response(JSON.stringify(stored), { status: 200 })
    }) as typeof fetch
    const client = new PortalClient('https://daymark.example.org/', 'the-token', fakeFetch)
    expect(await registerRecoveryEmail(client, EMAIL)).toBe(EMAIL)
    expect(seen.map((r) => `${r.method} ${r.url}`)).toEqual([
      'GET https://daymark.example.org/v1/owner/notifications',
      'PUT https://daymark.example.org/v1/owner/notifications',
      'GET https://daymark.example.org/v1/owner/notifications',
    ])
    expect(seen.every((r) => r.auth === 'Bearer the-token')).toBe(true)
    expect(JSON.parse(seen[1]!.body!)).toEqual({ email: EMAIL, events: ['NEW_GAMEPLAN'] })
  })

  it('the Notifications tab calls the same two client methods, so the two places set one address', () => {
    expect(NOTIFY).toContain('forClient.getNotificationSettings()')
    expect(NOTIFY).toContain('client.setNotificationSettings(')
    expect(CARD).toContain('registerRecoveryEmail(new PortalClient(connection.serverUrl, connection.token), address)')
    expect(CARD).toContain('new PortalClient(c.serverUrl, c.token).getNotificationSettings()')
  })
})

describe('RecoverAccess.svelte renders the view, and App.svelte hands it what it needs', () => {
  it('renders the lead only when there is one, and the heading of each part', () => {
    expect(CARD).toContain('const view = $derived(recoverCardView({ ownerConsoleOffered, connected: connection !== null, registered }))')
    expect(CARD).toContain('{#if view.summary}<p class="hint">{view.summary}</p>{/if}')
    expect(CARD).toContain('{BEFORE_YOU_NEED_IT}</h3>')
    expect(CARD).toContain('{view.lostHeading}</h3>')
    expect(CARD).toContain('{#each view.lostLede as line (line)}<p class="para">{line}</p>{/each}')
  })

  it('renders the registration section in the register branch: lede, address, field, Register and Remove', () => {
    const open = CARD.indexOf("{#if view.setup.kind === 'register'}")
    expect(open, 'no register branch').toBeGreaterThan(-1)
    const branch = CARD.slice(open, CARD.indexOf('{:else}', open))
    expect(branch).toContain('{view.setup.lede}')
    expect(branch).toContain('{registeredStatement(view.setup.registered)}')
    expect(branch).toContain('<span>{EMAIL_LABEL}</span>')
    expect(branch).toContain('onclick={() => save(draft.trim())}')
    expect(branch).toContain('{REGISTER}</button>')
    expect(branch).toMatch(/\{#if view\.setup\.canRemove\}\s*<button type="button" onclick=\{\(\) => save\(null\)\}[^>]*>\{REMOVE\}<\/button>/)
  })

  it('renders the pointer and its link in every other branch, from the view', () => {
    const open = CARD.indexOf('{:else}', CARD.indexOf("{#if view.setup.kind === 'register'}"))
    const branch = CARD.slice(open, CARD.indexOf('</section>', open))
    expect(branch).toContain('{view.setup.text}')
    expect(branch).toContain('{@const link = view.setup.link}')
    expect(branch).toContain('onclick={() => onopen?.(link.route)}>{link.label}</button>')
    // No pointer words of its own: a hand-written "owner console" would bypass the solo rule.
    const markup = CARD.replace(/<script[\s\S]*?<\/script>/, ' ')
    expect('<p>Open the owner console</p>').toMatch(/owner console/i) // the detector detects
    expect(markup).not.toMatch(/owner console/i)
  })

  it('names the address the lost-token flow takes when it is left empty', () => {
    expect(CARD).toContain('error = EMAIL_MISSING')
  })

  it('App.svelte gives the card the owner-console rule, the token a sync pull proved, and a way to open the other cards', () => {
    expect(APP).toContain('let syncConnection = $state<OwnerConnection | null>(null)')
    expect(APP).toContain(
      '<RecoverAccess {ownerConsoleOffered} connection={syncConnection} onopen={(id) => (source = id)} />',
    )
    expect(APP).toContain('onconnected={(c) => (syncConnection = c)}')
    // Control: a card handed a fixed rule is caught.
    const FIXED = /<RecoverAccess\b[^/]*ownerConsoleOffered=\{(true|false)\}/
    expect('<RecoverAccess ownerConsoleOffered={true} />').toMatch(FIXED)
    expect(APP).not.toMatch(FIXED)
  })

  it('the sync card hands the token up only after the server accepted it, and before the card closes', () => {
    const pull = SYNC.indexOf('await client.pullLatest(lineage, passphrase)')
    const handUp = SYNC.indexOf('onconnected?.({ serverUrl, token })')
    const load = SYNC.indexOf('onload(data,')
    expect(pull, 'no pull').toBeGreaterThan(-1)
    expect(handUp, 'no hand-up').toBeGreaterThan(pull)
    expect(load).toBeGreaterThan(handUp)
    expect(SYNC.match(/onconnected\?\.\(/g)).toHaveLength(1)
  })

  it('keeps the token in memory only: nothing on these three writes it to browser storage', () => {
    const STORAGE = /localStorage|sessionStorage|indexedDB/
    expect('globalThis.localStorage.setItem("t", token)').toMatch(STORAGE) // the detector detects
    for (const [name, code] of [['RecoverAccess', CARD], ['App', APP], ['SyncPanel', SYNC]] as const) {
      expect(code, name).not.toMatch(STORAGE)
    }
  })
})
