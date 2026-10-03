/*
 * THE WORDS FOR THE WEB CONSOLE'S LANE (#345). Fixed sentences, each naming what did not happen
 * and the one fact this console knows about why, never a cause it cannot know. No libsodium, so
 * the browser tests can import them.
 */

/** Why an addition to the lane was not made, or not confirmed. */
export type LaneFault = 'keyChanged' | 'refused' | 'ownLaneUnreadable' | 'unconfirmed' | 'notConnected'

export const LANE_FAULT_TEXT: Readonly<Record<LaneFault, string>> = {
  keyChanged:
    'Nothing was saved. The key this server holds has changed since this console was opened. Lock the console, open it again, and try again.',
  refused: 'Nothing was saved. Try again.',
  ownLaneUnreadable:
    'Nothing was saved. What this browser saved on this server before could not be opened, so nothing was added after it.',
  unconfirmed: 'This may not have been saved. Refresh shows whether it was.',
  notConnected: 'Nothing was saved. This console is not connected to a server.',
}

/** The inbox could not read the decisions kept on the server at all. */
export const DECISIONS_UNREAD = 'The decisions saved on this server could not be read, so none are shown. Refresh to try again.'

/** One or more lanes did not open; the decisions in the others are shown. */
export const SOME_DECISIONS_UNOPENED = 'Some decisions saved on this server could not be opened, so they are not shown.'
