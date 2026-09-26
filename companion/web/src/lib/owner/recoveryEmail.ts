/*
 * THE "RECOVER ACCESS" CARD: WHERE THE RECOVERY EMAIL IS REGISTERED, AND WHAT THE CARD SAYS (#330).
 *
 * Access-token recovery sends a one-time link to the email address registered on the server
 * (routes/RecoveryRoutes.kt: the link's token is single-use and expires). That address is set by
 * PUT /v1/owner/notifications, which every shape serves. Where the owner console is offered, its
 * Notifications tab is where it is set, and this card points there. On a server whose published
 * shape withholds the owner console — solo — this card is the one place to set it, because the
 * route it calls is still served there.
 *
 * WHAT THE CARD'S SENTENCES CLAIM, CHECKED AGAINST THE SERVER.
 *
 *   A one-time link: the confirmation token is flipped to CONSUMED on use and has a TTL
 *   (OwnerAccountStore.confirmReissue).
 *
 *   It is sent only when the address entered matches the registered one, and only when the server
 *   has outbound email: a server without it registers the address and sends nothing, which is
 *   why [SENDING_NEEDS_EMAIL] stands beside the promise in [LOST_TOKEN_LEDE]. With email on, the
 *   server refuses to start without the address the link is built from (Config.buildsLinks).
 *
 *   Nothing else is sent to it on a solo server: every notification the owner can choose comes
 *   from the clinician routes, which that shape switches off. What the recovery itself sends is
 *   the link, and a notice when the link re-issued the token.
 *
 * PURE. No fetch, no DOM, no Svelte. The one server call is [registerRecoveryEmail], and it takes
 * its client as an argument so a test can see exactly which route it reaches.
 */
import type { NotificationSettings } from '../sync/portal'
import { OWNER_ROUTES, type OwnerRouteId } from '../onboarding/audience'

/** Where the page reaches the server with the owner's access token, once a fetch has proved it. */
export interface OwnerConnection {
  /** As the person typed it on the sync card; blank means this page's own server. */
  serverUrl: string
  token: string
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
  'Set this up before you need it. If you lose your access token, a one-time link can be sent to ' +
  'an email address you register here.'

/** The same lead once the server has said an address is registered. */
export const SUMMARY_REGISTERED =
  'A recovery email is registered. If you lose your access token, a one-time link can be sent to ' +
  'it.'

export const BEFORE_YOU_NEED_IT = 'Before you need it'

export const REGISTER_LEDE =
  'Register an email address for recovery. Nothing else is sent to it on this server.'

export const EMAIL_LABEL = 'Recovery email'
/**
 * Beside the field, the same words the owner console's Notifications tab puts beside its own: the
 * address is kept in the clear on the server (COMPANION_SECURITY.md), and a person typing it here
 * should know that as much as a person typing it there.
 */
export const EMAIL_STORED_NOTE = 'stored in plaintext on the server; see COMPANION_SECURITY.md'

export const REGISTER = 'Register'

/**
 * Not in the copy pass's words, and needed by them: "You can change or remove it here" is true
 * only if something on the card removes it.
 */
export const REMOVE = 'Remove'

/** Shown once the server says an address is registered. The address is the person's own. */
export function registeredStatement(email: string): string {
  return `Recovery email registered: ${email}. You can change or remove it here.`
}

/**
 * In place of the field, when this page holds no access token for the server. The token is the
 * one the sync card proved by fetching with it (see App.svelte); nothing here asks for it again.
 */
export const NEEDS_TOKEN =
  'Registering an email needs your access token. Connect to your sync server first, then come ' +
  'back here.'

/** Where the owner console is offered, the address is set there and this card says so. */
export const OWNER_CONSOLE_POINTER =
  'Register or change the email in the owner console, under Notifications.'

export const LOST_TOKEN_HEADING = 'If you have lost your access token'

export const LOST_TOKEN_LEDE =
  'If you registered a recovery email, enter it and a one-time link will be sent to it.'

/**
 * The condition on the sentence above, said beside it: a server without outbound email registers
 * the address and sends nothing to it.
 */
export const SENDING_NEEDS_EMAIL = 'That needs this server to be set up to send email.'

/** The request with an empty address. Names the address the flow takes. */
export const EMAIL_MISSING = 'Enter the recovery email you registered.'

export const LOAD_FAILED = 'This page could not read the recovery email registered on your server.'

export const SAVE_FAILED = 'That change to the recovery email could not be saved.'

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
  | { kind: 'register'; lede: string; registered: string | null; canRemove: boolean }

export interface RecoverCardView {
  /** The card's lead, or null where the owner console holds the setting. */
  summary: string | null
  setup: RecoverSetup
  lostHeading: string
  /** The lost-token part's sentences, in order. */
  lostLede: readonly string[]
}

export interface RecoverCardInputs {
  /** Whether the owner's page offers the owner console (`offersRoute`, onboarding/audience.ts). */
  ownerConsoleOffered: boolean
  /** Whether this page holds an access token the server has accepted. */
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
  const lost = { lostHeading: LOST_TOKEN_HEADING, lostLede: [LOST_TOKEN_LEDE, SENDING_NEEDS_EMAIL] }
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
    setup: { kind: 'register', lede: REGISTER_LEDE, registered, canRemove: registered !== null },
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
