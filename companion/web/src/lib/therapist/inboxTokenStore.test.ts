/*
 * REMEMBERING THE INBOX TOKEN FOR ONE TAB.
 *
 * The behaviour, in the node environment the suite runs in — the port interface exists so this file
 * can pass a plain object where a browser would pass sessionStorage.
 *
 * The property that matters most here is the one the code this replaces got wrong: FAILING OPEN.
 * LoginGate's old write threw on every sign-in and the throw was swallowed by a catch meant for a
 * different failure, so the feature was dead and nothing said so (issue #125). Every case below
 * that hands this module a hostile storage asserts it returns rather than throws, because a
 * clinician's completed sign-in must never be undone by a browser refusing to remember something.
 */
import { describe, it, expect } from 'vitest'
import {
  INBOX_TOKEN_STORAGE_KEY,
  defaultInboxTokenStorage,
  recallInboxToken,
  rememberInboxToken,
  type InboxTokenStorage,
} from './inboxTokenStore'

const TOKEN_A = 'kQ7bXm2pR9tYw4vZ1nL6sH8jF3dG5aC0eB7uI9oP2xM'
const TOKEN_B = 'Zc4Nq8Lp1Ws6Yt3Bv0Hm9Kj5Rd2Fg7Xa4Ue1Io8Pl6'

/** A store that behaves like the browser's, with its raw contents readable for assertions. */
function memory(seed: string | null = null): InboxTokenStorage & { raw(): string | null } {
  let value = seed
  return {
    getItem: () => value,
    setItem: (_k, v) => {
      value = v
    },
    raw: () => value,
  }
}

/** A store that refuses, the way a private window or blocked site data does. */
function hostile(): InboxTokenStorage {
  return {
    getItem: () => {
      throw new Error('storage is blocked')
    },
    setItem: () => {
      throw new Error('storage is blocked')
    },
  }
}

describe('what one tab remembers', () => {
  it('gives back the token it was handed, for that relationship', () => {
    const storage = memory()
    rememberInboxToken(storage, 'rel-1', TOKEN_A)
    expect(recallInboxToken(storage, 'rel-1')).toBe(TOKEN_A)
  })

  it('keeps several relationships apart', () => {
    // A clinician can hold more than one, and the sign-in screen lets them choose. Handing back the
    // wrong relationship's token would route a request at somebody else's material.
    const storage = memory()
    rememberInboxToken(storage, 'rel-1', TOKEN_A)
    rememberInboxToken(storage, 'rel-2', TOKEN_B)
    expect(recallInboxToken(storage, 'rel-1')).toBe(TOKEN_A)
    expect(recallInboxToken(storage, 'rel-2')).toBe(TOKEN_B)
  })

  it('has nothing for a relationship it was never told about', () => {
    const storage = memory()
    rememberInboxToken(storage, 'rel-1', TOKEN_A)
    // Null is what makes the screen ask. Absence is the harmless direction and must not be a throw.
    expect(recallInboxToken(storage, 'rel-2')).toBeNull()
  })

  it('writes under one versioned key, and writes the token nowhere else', () => {
    const storage = memory()
    rememberInboxToken(storage, 'rel-1', TOKEN_A)
    expect(INBOX_TOKEN_STORAGE_KEY).toBe('daymark.therapist.inbox-token.v1')
    expect(storage.raw()).toContain(TOKEN_A) // the detector sees the token when it IS there
    expect(JSON.parse(storage.raw() ?? '{}')).toEqual({ 'rel-1': TOKEN_A })
  })
})

describe('it fails open, every way a browser can refuse', () => {
  it('remembers nothing and throws nothing when there is no storage at all', () => {
    expect(() => rememberInboxToken(null, 'rel-1', TOKEN_A)).not.toThrow()
    expect(recallInboxToken(null, 'rel-1')).toBeNull()
  })

  it('throws nothing when the store itself refuses', () => {
    const storage = hostile()
    expect(() => rememberInboxToken(storage, 'rel-1', TOKEN_A)).not.toThrow()
    expect(recallInboxToken(storage, 'rel-1')).toBeNull()
  })

  it('treats junk in the slot as an empty tab rather than repairing it', () => {
    for (const junk of ['not json at all', '[]', 'null', '"a string"', '{"rel-1":42}', '{"rel-1":""}']) {
      const storage = memory(junk)
      expect(recallInboxToken(storage, 'rel-1'), junk).toBeNull()
      // And it can still be written to afterwards: a bad slot must not wedge the tab.
      expect(() => rememberInboxToken(storage, 'rel-1', TOKEN_A)).not.toThrow()
      expect(recallInboxToken(storage, 'rel-1'), junk).toBe(TOKEN_A)
    }
  })

  it('refuses to remember half an answer', () => {
    const storage = memory()
    rememberInboxToken(storage, '', TOKEN_A)
    rememberInboxToken(storage, 'rel-1', '')
    // An empty token stored against a relationship would read as "do not ask", and then route a
    // request with nothing in the header.
    expect(recallInboxToken(storage, 'rel-1')).toBeNull()
    expect(storage.raw()).toBeNull()
  })
})

describe('where it puts things', () => {
  it('reaches for sessionStorage, never localStorage', () => {
    /*
     * The decision the issue asked to be made deliberately, pinned as code rather than as a comment.
     * localStorage would put a bare journal credential beside the wrapped keys, permanently, where
     * the wrapping passphrase does not protect it.
     */
    const seen: string[] = []
    const stub = { getItem: () => null, setItem: () => {} }
    const g = globalThis as unknown as Record<string, unknown>
    const hadSession = 'sessionStorage' in g
    const hadLocal = 'localStorage' in g
    const priorSession = g.sessionStorage
    const priorLocal = g.localStorage
    try {
      Object.defineProperty(g, 'sessionStorage', {
        configurable: true,
        get: () => {
          seen.push('sessionStorage')
          return stub
        },
      })
      Object.defineProperty(g, 'localStorage', {
        configurable: true,
        get: () => {
          seen.push('localStorage')
          return stub
        },
      })
      const port = defaultInboxTokenStorage()
      expect(port).toBe(stub)
      // The detector is live: it recorded the access it did make, so "no localStorage" below is a
      // fact about the call rather than a probe that never fired.
      expect(seen).toContain('sessionStorage')
      expect(seen).not.toContain('localStorage')
    } finally {
      if (hadSession) Object.defineProperty(g, 'sessionStorage', { configurable: true, value: priorSession })
      else delete g.sessionStorage
      if (hadLocal) Object.defineProperty(g, 'localStorage', { configurable: true, value: priorLocal })
      else delete g.localStorage
    }
  })
})
