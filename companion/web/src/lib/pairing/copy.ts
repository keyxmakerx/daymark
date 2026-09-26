/*
 * Everything the pairing screens say, in one place.
 *
 * WHY A COPY MODULE. Two reasons, both learned here. The first is that these sentences are load-
 * bearing: "wrong code is not an error" is a design rule (COMPANION_PAIRING.md §6), and a screen
 * that phrases a mismatch as a failure has broken it more thoroughly than a bug would. Keeping
 * them as named constants means a test can read them and a reviewer can read them without opening
 * a component. The second is that the same words appear on two screens and in two states, and a
 * sentence duplicated in markup drifts.
 *
 * THE REGISTER. Calm, plain, and never a verdict. No exclamation marks, no congratulation, no
 * green: what "done" looks like in this product is solid ink and a sentence in the past tense
 * (COMPANION_DESIGN_SYSTEM §2.3). A refusal names what happens next, never a cause the product
 * cannot know — "the reply didn't match this code" is honest; "someone is attacking you" is a
 * guess the code cannot support, and "you typed it wrong" is a guess that blames the wrong person.
 */

/**
 * The sentence CLAUDE.md §4 requires verbatim at every point where a person revokes, forgets, or
 * turns off access.
 *
 * A CONSTANT, NOT A PARAPHRASE. Three call sites each wrote their own version of this one fact
 * before there was anywhere to import it from — GrantManager.svelte, PinRecord.svelte and
 * pinStore.ts each phrased it differently, none of them in these exact words. The rule says
 * verbatim so a person meets the same sentence everywhere and learns it once; without a shared
 * constant to import, that drift was not a risk, it was what already happened.
 */
export const REVOKE_CAVEAT = 'Revoking does not un-send what was already read.'

export const OWNER_COPY = {
  title: 'Invite someone',

  /**
   * The rule the whole design rests on, at the point of the click rather than in a footnote
   * (COMPANION_PAIRING.md §2, "Two channels": the Companion must not offer to email the code, and
   * the reason is written at the call site).
   */
  twoChannels:
    'Send the link however you like. Say the code some other way — out loud, by text, in the ' +
    'room. Keeping them apart is what makes this work: anyone who ends up with only the link ' +
    'cannot get in.',
  codeLabel: 'Say this code to them',
  codeHint: 'Eight characters. Case does not matter, and the dash is only there to read by.',

  /** The lower-assurance line, from COMPANION_SECURITY.md R5. Applied here rather than restated. */
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
   * The mismatch (issue #112). A question, not a verdict: a typo and a stranger holding the link
   * produce the same silence, and the person in front of the screen is the only one who can tell
   * them apart. So the screen asks them to go and find out — from the person, on the channel the
   * code went down — rather than offering a diagnosis it does not have.
   *
   * ONE NOTICE, ON THIS SCREEN, AND NO NOTIFICATION. The owner's half runs in a browser tab today,
   * and a closed tab cannot raise a notification honestly: it would arrive late, or not at all, and
   * either way the person would learn to treat it as unreliable. The phone half (not built: #174)
   * can do it properly and the contract for that is written down in COMPANION_PAIRING.md §14
   * rather than improvised here.
   *
   * The count stays where it was, below this, and stays a count. Nothing about a reply that did not
   * open changes how often anything is asked of the server — see ownerCeremony.ts, checkForReply.
   */
  mismatchTitle: 'A reply did not open with your code',
  mismatchBody: (name: string): string =>
    `Keep this invitation open and ask ${name} whether they answered, or stop it and send a new ` +
    `link?`,
  /** The dismissal. It ends nothing, spends nothing, and asks the server for nothing. */
  keepOpenLabel: 'Keep it open',

  /*
   * REPLACING KEYS THIS CONSOLE ALREADY HOLDS (issue #111).
   *
   * The decision these sentences carry: a matching code on a FRESH invitation is authority enough
   * to replace a pinned key, because the pin was recorded on that same proof and nothing weaker.
   * Demanding more to replace a pin than to create one buys nothing — someone holding a code can
   * already pair fresh and be sent shares — so the screen's job is not to obstruct, it is to make
   * sure nobody walks through this without being told the two things they cannot find out later:
   * what it does NOT reach, and that they should stop if the person did not ask for it.
   */
  replaceAtMint: (name: string): string =>
    `This console already holds keys for ${name}. Approving whoever opens this invitation with ` +
    `the code will replace them. Until then, nothing changes.`,

  replaceTitle: (name: string): string => `Replace the keys held for ${name}`,

  /**
   * One paragraph per element, in order. The third is the standing fact that
   * [REVOKE_CAVEAT] states everywhere else a person takes something away: replacing a key changes
   * what is sealed from now on and reaches nothing that was already read.
   */
  replaceBody: (name: string): readonly string[] => [
    `The offer opened under the code you gave ${name}. Its keys are not the ones this console ` +
      `holds for them.`,
    'Approving records the new keys. Nothing further is sealed to the old ones.',
    `This does not reach what was already sealed to the old keys. Anyone holding the device that ` +
      `carried them can still open every share sent to ${name} before now.`,
    'You do not need to read these out: the code already did that job.',
    `If ${name} did not ask for this, do not approve.`,
  ],
  replaceApproveLabel: 'Replace and approve',
  /** A dismissal, not a burn: it ends nothing, reports nothing and spends nothing. */
  replaceDeclineLabel: 'Not now',
  replacedBody:
    'The new keys are recorded. Nothing further is sealed to the old ones, and what was already ' +
    'sealed to them is unchanged.',

  /**
   * The one refusal kept on this route. Names the consequence rather than a verdict about anyone:
   * the console cannot tell which of two people a share would be for, so it seals to neither.
   */
  sameKeysAsOther: (name: string, other: string): string =>
    `Those are the keys this console has recorded for ${other}. Nothing was approved and nothing ` +
    `was recorded: if they were also recorded for ${name}, a share meant for one of them could be ` +
    `opened by the other. Check with ${name} on a channel that is not this server before going ` +
    `further.`,

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
  wrongLinkAdvice: 'If that was not your clinician, you can stop this invitation.',

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
  /**
   * Explains the "New code" button wherever it is offered. Rendered on the waiting screens; it used
   * to appear on the mismatch screen too, until that screen became a question with two answers
   * (#112) and a third piece of advice under it would have been a third answer.
   */
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
