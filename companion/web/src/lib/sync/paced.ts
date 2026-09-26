/*
 * ASKING THE OWNER'S SERVER AGAIN, A LITTLE LATER, WHEN THE ANSWER WAS NOT AN ANSWER.
 *
 * The server's default allowance is five requests a second from one address, and a console that
 * reads one thing per lineage (recovery/serverKey.ts finding the newest snapshot, lane/lane.ts
 * reading every lane) can meet it on its own. So a read that failed for a transient reason is
 * asked again, a few times, with a growing pause. Used by both.
 */
import { SyncError } from './client'

/** How long to wait, in milliseconds. Tests pass one that records and does not wait. */
export type Wait = (ms: number) => Promise<void>

export const pause: Wait = (ms) => new Promise<void>((done) => setTimeout(done, ms))

/**
 * Whether a failed request is worth asking again: the server's 429, a 5xx (from the server or a
 * proxy in front of it), or a request that got no answer at all. A 401, a 404 or an answer this
 * client could not read is the answer, and asking again would only repeat it.
 */
export function transient(e: unknown): boolean {
  if (e instanceof SyncError) return e.status === 429 || (e.status !== undefined && e.status >= 500)
  return e instanceof TypeError
}

/** A read, asked again after a transient failure, at most five times in all. */
export async function paced<T>(read: () => Promise<T>, wait: Wait): Promise<T> {
  for (let attempt = 1; ; attempt++) {
    try {
      return await read()
    } catch (e) {
      if (!transient(e) || attempt >= 5) throw e
      await wait(300 * attempt)
    }
  }
}
