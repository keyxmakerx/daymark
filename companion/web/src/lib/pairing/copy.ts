/*
 * Everything the pairing screens say, in one place.
 *
 * WHY A COPY MODULE. Two reasons, both learned here. The first is that these sentences are load-
 * bearing: "wrong code is not an error" is a design rule (plan §3.7), and a screen that phrases a
 * mismatch as a failure has broken it more thoroughly than a bug would. Keeping them as named
 * constants means a test can read them and a reviewer can read them without opening a component.
 * The second is that the same words appear on two screens and in two states, and a sentence
 * duplicated in markup drifts.
 *
 * THE REGISTER. Calm, plain, and never a verdict. No exclamation marks, no congratulation, no
 * green: what "done" looks like in this product is solid ink and a sentence in the past tense
 * (COMPANION_DESIGN_SYSTEM §2.3). A refusal names what happens next, never a cause the product
 * cannot know — "the reply didn't match this code" is honest; "someone is attacking you" is a
 * guess the code cannot support, and "you typed it wrong" is a guess that blames the wrong person.
 */

export const OWNER_COPY = {
  title: 'Invite someone',

  /**
   * The rule the whole design rests on, at the point of the click rather than in a footnote
   * (plan §3.7.4: the Companion must not offer to email the code, and the reason is written at
   * the call site).
   */
  twoChannels:
    'Send the link however you like. Say the code some other way — out loud, by text, in the ' +
    'room. Keeping them apart is what makes this work: anyone who ends up with only the link ' +
    'cannot get in.',
  codeLabel: 'Say this code to them',
  codeHint: 'Eight characters. Case does not matter, and the dash is only there to read by.',

  /** The lower-assurance line, from COMPANION_UX R5. Applied here rather than restated. */
  browserCaveat:
    'This page is served to your browser by the server, so it is the convenient way to do this ' +
    'rather than the strongest one.',

  waiting: 'Waiting for them to type it. You can leave this page open, or come back to it.',
  waitingReload:
    'This pairing is still open. The link and the code were not kept when the page reloaded, so ' +
    'neither can be shown again. If you already sent the link, give them a new code — it works ' +
    'with the link they have. If you had not sent the link yet, stop this invitation and send a ' +
    'fresh one.',

  /**
   * The mismatch. A question, not a verdict: a typo and a stranger holding the link produce the
   * same silence, and the person in front of the screen is the only one who can tell them apart.
   */
  mismatchTitle: 'That reply did not match this code',
  mismatchBody:
    'Someone answered, and what came back does not open with the code you gave. If it was your ' +
    'therapist mistyping, give them a new code and try again. If you were not expecting anyone ' +
    'to answer yet, stop this invitation instead.',

  answeredTitle: 'They typed the code',
  answeredBody:
    'This opened with your code, so it came from the person you gave it to. Check the name reads ' +
    'as you expect, then approve.',
  nameLabel: 'The name they entered',
  fingerprintsLabel: 'Their key fingerprints',
  fingerprintsHint:
    'Recorded now. You do not need to read these out to anyone: the code already did that job.',

  approvedTitle: 'Approved',
  approvedBody:
    'They can finish setting up now. Nothing else is needed from you. If they never do, take the ' +
    'approval back and start again with a new code.',

  /** The attempts line. A count, never a warning: the owner decides what a number means. */
  attemptsLeft: (n: number): string => `${n} of 8 tries left on this invitation.`,
  wrongLinkTried: (n: number): string =>
    n === 1
      ? 'One wrong invitation link has been tried against this invitation.'
      : `${n} wrong invitation links have been tried against this invitation.`,
  wrongLinkAdvice: 'If that was not your therapist, you can stop this invitation.',

  /** Ending an invitation: what it does, and the two things it does not do. */
  stopLabel: 'Stop this invitation',
  stopBody:
    'Sent it to the wrong person, lost the link, or no longer want it used? Ending it stops the ' +
    'link working. It changes nothing already shared, and the other person is not told. You can ' +
    'send a fresh one afterwards.',
  stoppedBody:
    'This invitation has ended. The old link no longer works. Nothing already shared has ' +
    'changed, and nobody has been told. A fresh invitation comes with a new link and a new code.',

  newCodeLabel: 'New code',
  /** The one way to a new link: every fresh invitation starts from a stopped or spent one. */
  freshLabel: 'Send a fresh invitation',
  newCodeHint:
    'Same link, different code. Ends this attempt and starts another. If you no longer have the ' +
    'link, stop this invitation and send a fresh one.',
  cappedTitle: 'This invitation has been tried eight times',
  cappedBody:
    'The old link cannot be used again. A fresh invitation comes with a new link and a new code.',
  endedCancelled: 'You stopped this pairing. Start another whenever you are ready.',
  endedSuperseded: 'A newer code was made for this invitation, so this attempt is closed.',
} as const

export const THERAPIST_COPY = {
  title: 'Accept an invitation',
  lede:
    'The person inviting you will have said a short code out loud, or sent it some way other ' +
    'than the link that brought you here. Type it below, with a passphrase you choose.',

  codeLabel: 'The code they gave you',
  codeHint: 'Eight characters, like K7M4-RD96. Case does not matter.',
  nameLabel: 'Your name, as they should see it',
  nameHint: 'They will see this beside your key fingerprints when they approve.',
  passphraseLabel: 'A reading passphrase',
  passphraseHint:
    'This browser keeps your keys wrapped under it, and asks for it every time you sign in. It ' +
    'is not stored anywhere and cannot be reset, so write it somewhere safe.',

  waitingTitle: 'Waiting for them to approve',
  waitingBody:
    'They can see that someone typed the code and will be asked to approve it. This can take as ' +
    'long as it takes. Keep this tab open; reloading it is fine.',
  waitingSlow: 'Still waiting. Nothing is wrong; nothing here gives up on its own.',

  goneTitle: 'That code is no longer open',
  goneBody:
    'It may have been a different code from the one they meant, or they may have started again. ' +
    'Ask them for a new code, then try once more from this page.',

  /** The demoted read-aloud. The fingerprints stay; the instruction to phone becomes an offer. */
  checkTitle: 'Your key fingerprints',
  checkLede:
    'These are the fingerprints of the two keys this browser just made for you — the encryption ' +
    'key, which is what they seal everything they send you to, and the signing key, which is ' +
    'what proves an assignment or a game plan came from you.',
  checkWhy:
    'They have already recorded both of these: the code you typed is what proved the keys were ' +
    'yours. You do not need to read them out. They are here so you can compare them with what ' +
    'their console shows if either of you ever wants to.',
} as const
