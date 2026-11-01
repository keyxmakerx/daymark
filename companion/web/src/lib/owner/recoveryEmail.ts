/*
 * THE "RECOVER ACCESS" CARD: WHERE THE NOTICE EMAIL IS REGISTERED, AND WHAT THE CARD SAYS (#330, #325).
 *
 * The card has two parts. "Before you need it" registers an email address. "If you have lost your
 * sign-in" gets the owner back in with their recovery code (owner/recoverSignIn.ts); no email is
 * involved in that, because a mailbox is not proof of who holds the journal (#325).
 *
 * The address is set by PUT /v1/owner/notifications, which every shape serves. Where the owner
 * console is offered, its Notifications tab is where it is set, and this card points there. On a
 * server whose published shape withholds the owner console — solo — this card is the one place to
 * set it, because the route it calls is still served there.
 *
 * WHAT THE CARD'S SENTENCES CLAIM, CHECKED AGAINST THE SERVER.
 *
 *   A notice is sent when someone gets back in with the recovery code: OwnerRecoveryRoutes.kt sends
 *   SecurityNotice(SIGN_IN_RECOVERED) to the registered address, when there is one, and only when
 *   the server has outbound email, which is why [SENDING_NEEDS_EMAIL] stands beside the promise.
 *
 *   Nothing else is sent to it on a solo server: every other notification the owner can choose
 *   comes from the clinician routes, which that shape switches off.
 *
 *   The code stays on the page: only a signature made with the key it opens is sent.
 *
 * PURE. No fetch, no DOM, no Svelte. The one server call is [registerRecoveryEmail], and it takes
 * its client as an argument so a test can see exactly which route it reaches.
 */
import type { NotificationSettings } from '../sync/portal'
import { OWNER_ROUTES, type OwnerRouteId } from '../onboarding/audience'
import type { OwnerCredential } from './account'

/** Where the page reaches the server with the owner's access token, once a fetch has proved it. */
export interface OwnerConnection {
  /** As the person typed it on the sync card; blank means this page's own server. */
  serverUrl: string
  /** The shared token while it lasts, or the owner's signed-in session (account.ts, #324). */
  token: OwnerCredential
}

/** A link on this card to another of the owner's entry points, named as its route card names it. */
export interface RouteLink {
  route: OwnerRouteId
  label: string
}

function linkTo(route: OwnerRouteId): RouteLink {
  const found = OWNER_ROUTES.find((r) => r.id === route)
  /* Unreachable through the type, and a thrown error beats a link with no words on it. */
  if (!found) throw new Error(`unknown owner route: ${route}`)
  return { route, label: found.label }
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. The words.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** The card's lead on a solo server, while no registered address is known to this page. */
export const SUMMARY_UNREGISTERED =
  'Set this up before you need it. Register an email address here, and if anyone gets back into ' +
  'your server with your recovery code, a notice is sent to it.'

/** The same lead once the server has said an address is registered. */
export const SUMMARY_REGISTERED =
  'An email for notices is registered. If anyone gets back into your server with your recovery ' +
  'code, a notice is sent to it.'

export const BEFORE_YOU_NEED_IT = 'Before you need it'

export const REGISTER_LEDE =
  'Register an email address for security notices. Nothing else is sent to it on this server.'

export const EMAIL_LABEL = 'Email for notices'
/**
 * Beside the field, the same words the owner console's Notifications tab puts beside its own: the
 * address is kept in the clear on the server (COMPANION_SECURITY.md), and a person typing it here
 * should know that as much as a person typing it there.
 */
export const EMAIL_STORED_NOTE = 'stored in plaintext on the server; see COMPANION_SECURITY.md'

export const REGISTER = 'Register'

/** "You can change or remove it here" is true only if something on the card removes it. */
export const REMOVE = 'Remove'

/** Shown once the server says an address is registered. The address is the person's own. */
export function registeredStatement(email: string): string {
  return `Email for notices registered: ${email}. You can change or remove it here.`
}

/**
 * In place of the field, when this page holds no connection to the server. The connection is the
 * one the sync card proved by fetching with it (see App.svelte); nothing here asks for it again.
 */
export const NEEDS_TOKEN =
  'Registering an email needs a connection to your server. Connect to your sync server first, ' +
  'then come back here.'

/** Where the owner console is offered, the address is set there and this card says so. */
export const OWNER_CONSOLE_POINTER =
  'Register or change the email in the owner console, under Notifications.'

export const LOST_HEADING = 'If you have lost your sign-in'

export const LOST_LEDE =
  'Your recovery code gets you back in. Type it here with a new name and a new authenticator. ' +
  'Every earlier sign-in is signed out, and your phones and clinicians stay as they are.'

/** What leaves the page, said where the code is typed (recoverSignIn.ts keeps it true). */
export const CODE_STAYS_HERE = 'The code stays on this page. Only a proof made with it is sent.'

/**
 * The limit, said plainly. The server is never handed the passphrase lock, so the recovery code is
 * the only thing this page can prove the owner with.
 */
export const CODE_IS_THE_ONLY_WAY =
  'Getting back in this way needs your recovery code. Without it, nothing on this page can make ' +
  'you a new sign-in.'

/**
 * The condition on the notice: a server without outbound email registers the address and sends
 * nothing to it.
 */
export const SENDING_NEEDS_EMAIL = 'A notice needs this server to be set up to send email.'

export const NEW_NAME_LABEL = 'A name, as you will type it to sign in'
export const NEW_AUTHENTICATOR = 'A new authenticator'
export const NEW_AUTHENTICATOR_LEDE =
  'Add this key to an authenticator app, by scanning or typing the groups below. The old one stops ' +
  'working once you are back in.'
export const DIGITS_LABEL = 'The six digits your authenticator shows now'
export const GET_BACK_IN = 'Get back in'
export const GETTING_BACK_IN = 'Checking…'

/** After it worked. Stated in words, in ink: there is no success hue. */
export const BACK_IN =
  'You are signed in with your new sign-in, and every earlier sign-in is signed out. Unlock as ' +
  'usual to carry on.'

export const LOAD_FAILED = 'This page could not read the email registered on your server.'

export const SAVE_FAILED = 'That change to the email could not be saved.'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. What the card shows.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** The "Before you need it" part, in its three forms. */
export type RecoverSetup =
  /** The owner console is offered, and holds the setting: a pointer, and a link to it. */
  | { kind: 'owner-console'; text: string; link: RouteLink }
  /** Solo, and this page holds no access token to register with: a link to the sync card. */
  | { kind: 'needs-token'; text: string; link: RouteLink }
  /** Solo, with a token: the field, and the registered address when the server has said one. */
  | { kind: 'register'; lede: string; note: string; registered: string | null; canRemove: boolean }

export interface RecoverCardView {
  /** The card's lead, or null where the owner console holds the setting. */
  summary: string | null
  setup: RecoverSetup
  lostHeading: string
  /** The lost-sign-in part's sentences, in order. */
  lostLede: readonly string[]
  /** Where to go once back in: the owner console where it is offered, otherwise the sync card. */
  afterRecovery: RouteLink
}

export interface RecoverCardInputs {
  /** Whether the owner's page offers the owner console (`offersRoute`, onboarding/audience.ts). */
  ownerConsoleOffered: boolean
  /** Whether this page holds a connection the server has accepted. */
  connected: boolean
  /** The address the server says is registered, or null when none is, or none has been read. */
  registered: string | null
}

/**
 * The card, for one state of the page.
 *
 * The owner console decides it: where the console is offered, the setting is there and the card
 * points at it; where it is withheld, the card holds the setting itself. Nothing the card shows on
 * a solo server names the console or its tabs.
 */
export function recoverCardView({
  ownerConsoleOffered,
  connected,
  registered,
}: RecoverCardInputs): RecoverCardView {
  const lost = {
    lostHeading: LOST_HEADING,
    lostLede: [LOST_LEDE, CODE_IS_THE_ONLY_WAY],
    afterRecovery: linkTo(ownerConsoleOffered ? 'owner' : 'sync'),
  }
  if (ownerConsoleOffered) {
    return {
      summary: null,
      setup: { kind: 'owner-console', text: OWNER_CONSOLE_POINTER, link: linkTo('owner') },
      ...lost,
    }
  }
  if (!connected) {
    return {
      summary: SUMMARY_UNREGISTERED,
      setup: { kind: 'needs-token', text: NEEDS_TOKEN, link: linkTo('sync') },
      ...lost,
    }
  }
  return {
    summary: registered === null ? SUMMARY_UNREGISTERED : SUMMARY_REGISTERED,
    setup: { kind: 'register', lede: REGISTER_LEDE, note: SENDING_NEEDS_EMAIL, registered, canRemove: registered !== null },
    ...lost,
  }
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   3. The one call.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** The slice of PortalClient this needs: the owner routes the Notifications tab uses. */
export interface NotificationPort {
  getNotificationSettings(): Promise<NotificationSettings>
  setNotificationSettings(email: string | null, events: string[]): Promise<void>
}

/**
 * Register [email], or remove the address with null, and return what the server then holds.
 *
 * Through the route the owner console's Notifications tab uses, PUT /v1/owner/notifications,
 * which replaces the address AND the chosen notifications together. So the chosen notifications
 * are read first and written back unchanged: setting the address from this card must not clear
 * choices made in the console on a server that was once paired. The address is read back rather
 * than echoed, because the server trims and lowercases what it stores.
 */
export async function registerRecoveryEmail(
  port: NotificationPort,
  email: string | null,
): Promise<string | null> {
  const current = await port.getNotificationSettings()
  await port.setNotificationSettings(email, current.events)
  return (await port.getNotificationSettings()).email
}
