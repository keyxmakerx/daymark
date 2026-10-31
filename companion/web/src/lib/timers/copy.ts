/*
 * The Timers screen's words, in one place so the copy rules (docs/COMPANION_UX.md) are checked
 * against one file. Each alert style says what the person will actually notice and who else may
 * notice it, because the room the clinician is sitting in is the reason the choice exists.
 *
 * "Tap on my watch" is honest about its mechanism: a browser cannot reach a watch. It shows a
 * notification from this browser, and a watch paired to the device shows that notification as a
 * tap if it mirrors notifications. The words say so rather than promise a tap the page cannot make.
 */
import type { AlertStyle } from './timers'

export const ALERT_COPY: Record<AlertStyle, { label: string; detail: string; fired: string }> = {
  sound: {
    label: 'Sound',
    detail: 'A soft chime from this device. The room may hear it.',
    fired: 'Chimed on this device',
  },
  vibrate: {
    label: 'Vibrate',
    detail: 'A short buzz, on a phone that can vibrate. A computer shows the banner instead.',
    fired: 'Buzzed this phone',
  },
  watch: {
    label: 'Tap on my watch',
    detail:
      'A notification from this browser. A watch that mirrors this device’s notifications shows it as a tap. Nobody else notices.',
    fired: 'Sent as a notification',
  },
  screen: {
    label: 'Screen only',
    detail: 'A quiet banner here. No sound, no buzz.',
    fired: 'Shown here only',
  },
}

/** When the device cannot do what was chosen, the banner says what it did instead. */
export const FELL_BACK: Record<'vibrate' | 'watch' | 'sound', string> = {
  vibrate: 'This device can’t vibrate, so it is shown here instead.',
  watch: 'Notifications are off for this page, so it is shown here instead.',
  sound: 'This browser couldn’t play a sound, so it is shown here instead.',
}

export const STAYS_HERE =
  'Timers stay in this browser. They aren’t sent to the server, shared with anyone, or added to any client’s record.'
