/*
 * THE INVITE LINK — the two values in `{base}/portal/invite#id=<inviteId>&s=<secret>`, read out of
 * the URL FRAGMENT and out of nothing else.
 *
 * WHY THIS IS A MODULE AND NOT THREE LINES IN A COMPONENT. The link a clinician is emailed is the
 * only thing standing between a stranger and the pairing half of another person's mental-health
 * record, and the rules about where its two values may be read from are security properties rather
 * than parsing conveniences. Rules kept in a component are rules that get relaxed by whoever is
 * next in a hurry; rules kept here are rules a test can hold.
 *
 * WHY THE FRAGMENT, AND WHY THAT MUST NOT REGRESS. Everything after `#` is stripped by the browser
 * before the request line is built. It therefore never reaches an access log, a reverse proxy, an
 * error page, a `Referer` header or anything downstream of any of those. The identical two values
 * carried as `?id=…&s=…` would be written to disk by every hop between the clinician and the
 * server — which is why `TherapistAuthRoutes.kt` builds the link with a `#`, why the redeem route
 * answers with `Referrer-Policy: no-referrer`, and why COMPANION_OBSERVABILITY.md §3.5 states the
 * property in as many words. Nothing here may quietly widen that: `parseInviteLink` reads `hash`
 * and refuses to look at `search`, even when the values are sitting right there and would "work".
 * A link that arrives with the secret in its query string is a link whose secret has already been
 * logged somewhere, and treating it as usable would be treating a leak as a convenience.
 *
 * WHY NOTHING HERE EVER PUTS A VALUE IN A MESSAGE. Every string in INVITE_FAULT_TEXT describes the
 * SHAPE of what went wrong and never echoes what was read. A fault message is the one piece of this
 * flow most likely to be screenshotted into a support thread, pasted into a chat, or read aloud
 * over a phone by somebody trying to get help — and half of what it would be echoing is a live
 * credential. The person already has the link; the message does not need to show it back to them.
 *
 * NO DOM, NO CLOCK, NO NETWORK. Everything here is a pure function of a string, which is what makes
 * the fragment rule testable at all — see inviteLink.test.ts.
 */

/** Why a link could not be read as an invitation. One value per distinguishable shape. */
export type InviteFault =
  /** No fragment at all — the `#…` half is missing or empty. */
  | 'noFragment'
  /** A fragment that is present but names neither of the two parameters. */
  | 'notAnInvite'
  | 'missingId'
  | 'missingSecret'
  | 'malformedId'
  | 'malformedSecret'

/** The two values the link carries. Both are opaque server-minted tokens; neither is displayed. */
export interface InviteCredential {
  inviteId: string
  secret: string
}

export type InviteParse = { ok: true; invite: InviteCredential } | { ok: false; fault: InviteFault }

/**
 * The shape `Secrets.newToken()` produces: base64url of 32 random bytes, unpadded, so 43
 * characters of `[A-Za-z0-9_-]`. The bounds are deliberately wider than that on both sides. Pinning
 * 43 exactly would make this module fail closed on a server that changed its token length — a
 * change with no security meaning at all — and the check is not what protects the invite anyway:
 * the server verifies the secret against an Argon2id hash under a capped backoff. What this rejects
 * is the class of input that cannot be a token at all: an empty value, a sentence, a whole URL
 * pasted into the wrong half of a link, a value carrying spaces or path separators.
 */
const TOKEN = /^[A-Za-z0-9_-]{16,128}$/

/**
 * The fragment's parameters, or null when there is no fragment.
 *
 * `URLSearchParams` rather than a hand-rolled split because it already handles percent-decoding,
 * repeated keys and empty values the way the URL standard says to, and because the alternative —
 * a regex per parameter — is how `s=` in the middle of a value ends up being read as a parameter.
 * The `+`-becomes-space quirk of that class cannot bite here: base64url's alphabet is `A-Za-z0-9_-`
 * and contains no `+`, so no value this link legitimately carries can be altered by it.
 */
function fragmentParams(hash: string | null | undefined): URLSearchParams | null {
  const raw = (hash ?? '').trim()
  const body = raw.startsWith('#') ? raw.slice(1) : raw
  if (body === '') return null
  return new URLSearchParams(body)
}

/**
 * Read `id` and `s` out of a URL fragment.
 *
 * Order-independent, and tolerant of extra parameters: a mail client that appends its own tracking
 * key to the fragment must not break an invitation, and an extra parameter cannot change what these
 * two mean. What it is not tolerant of is a missing or unusable value, because the alternative is a
 * request to the server carrying an empty secret, which spends one of the source's small number of
 * pairing attempts to learn nothing.
 */
export function parseInviteFragment(hash: string | null | undefined): InviteParse {
  const params = fragmentParams(hash)
  if (params === null) return { ok: false, fault: 'noFragment' }

  const rawId = params.get('id')
  const rawSecret = params.get('s')
  if (rawId === null && rawSecret === null) return { ok: false, fault: 'notAnInvite' }
  if (rawId === null) return { ok: false, fault: 'missingId' }
  if (rawSecret === null) return { ok: false, fault: 'missingSecret' }

  const inviteId = rawId.trim()
  const secret = rawSecret.trim()
  if (inviteId === '') return { ok: false, fault: 'missingId' }
  if (secret === '') return { ok: false, fault: 'missingSecret' }
  if (!TOKEN.test(inviteId)) return { ok: false, fault: 'malformedId' }
  if (!TOKEN.test(secret)) return { ok: false, fault: 'malformedSecret' }

  return { ok: true, invite: { inviteId, secret } }
}

/**
 * Read an invitation out of a whole link — the fallback for someone whose mail client mangled the
 * click, who is moving the link to another browser, or who has the URL in a message rather than in
 * their address bar.
 *
 * READS THE FRAGMENT ONLY. If the link carries `id` and `s` as query parameters this returns
 * `noFragment`, which looks unhelpful and is the entire point: a link in that shape has already had
 * its secret handed to every log between the sender and here, and the honest answer is that it is
 * not the link the server sent. Accepting it "because the values are right there" would make the
 * fragment rule advisory, and an advisory rule about where a credential may appear is not a rule.
 *
 * A bare fragment (`#id=…&s=…` with no scheme or host) is accepted too, since that is what someone
 * copying the tail of a link out of an email will paste.
 */
export function parseInviteLink(raw: string | null | undefined): InviteParse {
  const text = (raw ?? '').trim()
  if (text === '') return { ok: false, fault: 'noFragment' }
  const cut = text.indexOf('#')
  if (cut < 0) return { ok: false, fault: 'noFragment' }
  return parseInviteFragment(text.slice(cut))
}

/**
 * Does this fragment claim to be an invitation?
 *
 * Deliberately weaker than `parseInviteFragment`: it asks whether the person arrived on an invite
 * link, not whether the link is usable. The router wants the weak question — someone who followed a
 * truncated or mangled invitation should land on the page that can explain what happened to it,
 * not on a sign-in form asking for nine values they have never heard of. The strong question is
 * asked once they are there.
 */
export function looksLikeInvite(hash: string | null | undefined): boolean {
  const params = fragmentParams(hash)
  return params !== null && (params.has('id') || params.has('s'))
}

/** The path `buildInviteLink` points at. Kept next to the parser so the two cannot drift apart. */
export const INVITE_PATH = '/portal/invite'

/**
 * Is this the invite-acceptance path, whatever the deployment's base path is?
 *
 * The other half of the router's question. Someone who lands on `/portal/invite` with the fragment
 * missing entirely — a link that was truncated by a chat client, or one they retyped from a
 * printout — has still plainly come here to accept an invitation, and belongs on the page that says
 * what is wrong with the link.
 */
export function isInvitePath(pathname: string | null | undefined): boolean {
  const path = (pathname ?? '').replace(/\/+$/, '')
  return path === INVITE_PATH || path.endsWith(INVITE_PATH)
}

/**
 * The document paths that serve an SPA entry rather than a directory. Stripping one of these off
 * the current path leaves the deployment's base — the prefix every `/v1/…` call has to carry.
 *
 * Ordered longest-first so `/portal/invite` is not half-matched by something shorter.
 */
const ENTRY_PATHS = ['/portal/invite', '/therapist.html', '/index.html', '/therapist']

/**
 * The API base for the deployment serving this page.
 *
 * WHY THIS IS DERIVED AND NOT TYPED. "Server URL" is the first of LoginGate's nine fields, and it
 * is the one field the page can always answer for itself: this document was served by the server it
 * is about to talk to. Asking a clinician to retype the address they are already looking at buys
 * nothing and costs a typo — and a typo here is a redeem sent to whatever host they actually typed,
 * carrying a live invite secret.
 *
 * WHY IT IS NOT JUST `origin`. `Application.kt` mounts every route under a configurable
 * `basePath`, so on a deployment hosting Daymark at `/dm` this page is `/dm/portal/invite` and the
 * API is `/dm/v1/…`. Taking the origin alone would send every call to `/v1/…` and 404 the whole
 * flow — the kind of break that only ever appears on somebody else's deployment, which is the kind
 * worth a pure function and a test.
 */
export function apiBaseFrom(origin: string, pathname: string): string {
  let path = pathname || ''
  for (const entry of ENTRY_PATHS) {
    if (path.endsWith(entry)) {
      path = path.slice(0, -entry.length)
      break
    }
  }
  return origin.replace(/\/+$/, '') + path.replace(/\/+$/, '')
}

/**
 * The same URL with its fragment removed, for putting back in the address bar once the secret in it
 * has been spent.
 *
 * WHY BOTHER, GIVEN THE SECRET IS SINGLE-USE. Three reasons that outlive the secret's usefulness:
 * the address bar is visible to whoever is standing behind a clinician in a shared room; the URL
 * with its fragment is what gets copied when someone shares "the page I'm on"; and the browser's
 * own history and session restore keep it long after the invitation is dead. None of those is
 * catastrophic once the invite is consumed — but the window between arriving and consuming it is
 * exactly when it is not yet consumed, which is why the caller does this immediately after the
 * redeem succeeds rather than on arrival.
 */
export function withoutFragment(href: string): string {
  const cut = href.indexOf('#')
  return cut < 0 ? href : href.slice(0, cut)
}

/**
 * Why a link could not be read, in words a clinician can act on. Premade, one per fault; the screen
 * picks one, it never composes.
 *
 * None of them accuse the reader of anything. A mangled link is something that happened to them in
 * transit — mail clients wrap long URLs, chat clients cut them at a space, and a printed link is
 * retyped by hand — and the useful next step in every case is the same: ask for the link again, or
 * paste the whole thing. None of them echoes what was read, for the reason in this file's header.
 */
export const INVITE_FAULT_TEXT: Record<InviteFault, string> = {
  noFragment:
    'This address has no invitation in it. The part of the link after the # is what carries the ' +
    'invitation, and some mail and chat clients cut a long link short. Paste the whole link below, ' +
    'or ask the person who invited you to send it again.',
  notAnInvite:
    'This address ends in something other than an invitation, so there is nothing here to accept.',
  missingId:
    'This link is missing the half that says which invitation it is. It has probably been cut ' +
    'short in transit — paste the whole link below, or ask for it again.',
  missingSecret:
    'This link is missing the half that proves it is yours. It has probably been cut short in ' +
    'transit — paste the whole link below, or ask for it again.',
  malformedId:
    'The invitation in this link is not in a shape this page can read, which usually means it was ' +
    'altered or joined up wrongly on its way here. Ask for a fresh link rather than editing this one.',
  malformedSecret:
    'The second half of this link is not in a shape this page can read, which usually means it was ' +
    'altered or joined up wrongly on its way here. Ask for a fresh link rather than editing this one.',
}
