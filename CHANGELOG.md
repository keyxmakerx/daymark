# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Removed
- **"Review my year" ends on two plain facts, and no longer offers to save a picture of your
  year.** The last page used to show three numbers: your average mood, your brightest month, and
  your longest streak. Each was a way of marking a year of your life out of something — an average
  of a mood scale is a grade, and naming your brightest month tells anyone reading it that one of
  the others was your darkest. They are replaced by two things that rank nothing: **Most often**,
  the mood you chose on more days than any other, in your own word for it, and **First entry**, the
  day you started. The heading is now just the year. The same two claims appeared as a small note
  on the quarter pages, and that note has gone with them.
  **"Save keepsake" is removed.** It wrote your year — every mood, and the timing of everything you
  logged — into a single image in your gallery, which is the folder most likely to be quietly
  backed up somewhere by software you have forgotten about; and it made the unlogged stretches
  permanent. In its place the last page says: *This stays on your phone. You can come back to it
  any time.* Nothing you have already saved is affected.
- **Streaks are gone, and the Achievements screen with them.** A streak is a number you can lose:
  miss one day and it goes back to nothing, which means it is at its lowest exactly when someone
  has been away and has just come back. Every continuity number in Daymark is now the same one —
  **days with an entry, "12 of the last 30"** — counted without needing the days to be in a row, so
  a missed day costs one day and nothing else. It reads the same on your phone and in the companion
  web console. On **Stats** it is a single card labelled *Days with an entry*; on **Home** there is
  now nothing of the sort at all, because Home is the screen you land on after time away and a
  count there is a mark on you however gently it is phrased. If there are no entries in the last
  thirty days the card is simply not drawn — an empty stretch is not a nought to be shown.
  The **Achievements** screen is deleted: the screen, the nine badges, the badge art, the stored
  record of what you had earned, and the "Streaks and milestones" switch in Settings that used to
  turn the notices off. The app says nothing about this to anyone who had earned badges — the only
  sentence available would be one asking you to feel something about a thing we decided was bad for
  you, which is the same move again. Nothing else in your data is touched, and an older backup that
  still contains unlock times restores normally; the badges in it are just not brought back.

### Added
- **An entry can say who you were with, and everyone you name gets a page of their own.** There is
  a new **People and communities** screen in More. A community counts: a church, a fandom, a team, a
  support group — anything you would say you are part of. Each name you add sits in Friends, Family,
  Partners, Communities or Other, and each has a page with two things on it: a line answering **"who
  (or what) is this to you?"**, in your own words, and a set of dated notes you add whenever you
  like. The page also lists the entries that named them, so you can see when you last wrote about
  somebody without the app counting it for you. In the entry editor there is now an optional
  **"with"** row — a picker over the same list and nothing more. Leaving it empty is not a gap.

  **What the app deliberately does not do with any of this.** Nobody's name is ever an input to
  anything that reads your mood. Daymark will not tell you your mood is lower with a particular
  person, will not rank the people in your life, and will never say "you haven't written about X in
  a while" — the sentence everybody reaches for first, and the one that turns a place to keep things
  into a thing that wants something from you. Archiving somebody takes them out of the picker and
  leaves everything you wrote about them exactly where it is.

  **Sharing is off for everything until you switch it on.** One screen, reached from the people
  list: a default for each group and an override for each person, all starting off. Nothing about
  anybody in here reaches a clinician unless you put it there yourself.
- **Tapping a past entry now opens it to read, not to edit.** Before, tapping an entry anywhere in
  the app dropped you into the editor with a delete button in the corner, which is the wrong first
  thing to meet when you open the record of a hard day. The new page shows the mood word you chose
  in your own colour, your activities, who you were with, your note and your photo; the editor is
  one deliberate tap further on. The page says what you wrote and never interprets it. There is no
  line anywhere on it that begins "you seem".
- **Daymark now keeps track of when in the week it asked you things, and whether anything came
  back.** By hour and by day of the week, for sixty days. An hour with no answer in it is recorded
  as exactly that and nothing more — asleep, busy and a hard week look identical from here, so no
  reason is ever stored. The ledger is never shared with a clinician and appears in no backup,
  export or report.

  **What it does not do yet, said plainly.** The rule that would move asking toward the hours you
  reply in is built and you can read it on the debug screen, but it does not currently decide
  anything, because neither thing the app says today is the kind of thing it should decide.
  A **reminder** is at a time you chose, and quieting something you explicitly scheduled is
  overriding you rather than being considerate — an earlier version did ration them, and two
  unanswered firings could collapse a three-a-day schedule to one a week with no setting anywhere to
  turn it back up. The **support offer** appears while you are already in the app writing, so where
  it falls in the week says nothing about whether you are there to hear it. Choosing an hour is for
  something the app starts on its own, and there is no such thing yet. The ledger is the part that
  had to come first, because a rule about when you answer is worth nothing until there is a record
  of when you answered.
- **A "Why it asks" screen, in development builds only.** It lists every rule that can decide to
  speak, what each would do at this moment and why not if it would not, the hours it is willing to
  use, and the full list of what it reads. It is reachable only from a Settings row that exists only
  in a debug build, so it is not in the app you install. It exists so the rules can be checked by
  looking at them rather than by trusting a description of them.
- **Your journal is now encrypted on your phone.** Daymark does this for everybody, from the first
  time you open it — you don't switch it on and there is nothing to remember. The key is made on
  your phone and kept in the phone's own secure hardware, where it can't be copied off, so someone
  who took a copy of Daymark's storage would get a file they can't read. If you already have
  entries, Daymark converts them once the next time you open it: it copies them into a new
  encrypted file, checks that every single one arrived and that the file is sound, and only then
  removes the old one. If any part of that doesn't work, the old file is left exactly as it was and
  Daymark tries again next time. Nothing is ever deleted on a failure.

  Two things this does **not** cover, said here rather than left for you to find out: **photos** you
  attach to entries, which stay ordinary picture files; and **backups, CSV files and PDF reports you
  make yourself**, which are plain files you asked for and put where you chose.

  In the rare case where a phone loses the key — it happens after some firmware updates and security
  resets — Daymark tells you the entries can't be opened, offers to leave them alone, and removes
  them only if you choose that.
- **The app-lock setting now says what the PIN actually guards, and what it doesn't cost you.** The
  row used to say "On". It now says the PIN guards the screen and is not what your entries are
  encrypted with — which means **forgetting your PIN does not lose your entries**. A person reading
  the words "app lock" on a journal infers something stronger than the lock was ever making, and
  the difference had never been written anywhere you would look.
- **A new PIN is six to twelve digits.** Four was reasonable while the PIN only guarded a screen
  behind a wait-after-wrong-guesses lockout. A PIN you already set keeps working at whatever length
  it is — Daymark will not make you change it or interrupt you about it.
- **Companion — a clinician who has lost their keys can be invited back.** If a clinician lost the
  browser or the passphrase holding their keys, there was no way back: their passphrase cannot be
  reset, and the console refused to approve anyone whose keys were not the ones it already had. You
  were left sending things to a key nobody could open. Now you send a fresh invitation and say a new
  code, exactly as the first time, and approving the reply replaces the keys on file. Before you do,
  the screen tells you what that reaches and what it does not: it changes what is sent from now on,
  and it does not reach anything already sent — whoever has the old device can still open every
  share you sent them before today. It also says that if the person did not ask for this, do not
  approve. The choice is "Replace and approve" or "Not now", and "Not now" cancels nothing. The one
  case still refused is keys already recorded for a different person, because then nothing can tell
  which of the two a message was meant for.
- **Companion — a key that changes is written down beside the old one instead of over it.** The
  console's record of a clinician's keys used to be overwritten when a key changed, so the one
  question anybody asks afterwards — what was on file before, and until when? — could no longer be
  answered. The record now keeps both, and the newest is the one everything is sent to. Forgetting a
  clinician still erases the whole of their record at once.
- **Companion — a clinician can now end their own access, and it really ends it.** Until now only
  the person sharing could end a connection; a clinician who left a practice, retired, or simply
  should not be holding it any longer had no way to put it down. There is now a plain "Leave this
  relationship" at the foot of their console. It closes their sign-in for that person on every
  device, not just the browser they clicked it in, and there is no way back without a fresh
  invitation.

  What it does not do is the point of it. It touches nothing belonging to the person who invited
  them: no entry is deleted, nothing is un-shared, and the record of what was shared is exactly as
  it was. It also cannot reach copies — anything the clinician already opened, printed or saved is
  still wherever they put it, and the screen says so rather than implying otherwise. Nothing about
  the act reads as failure or punishment; putting down access is an ordinary professional thing to
  do.

  Under the surface, ending it writes one permanent line rather than deleting anything. The
  clinician's sign-in credential is never removed, because removing it would let anyone still
  holding the old invitation link set up a brand-new one against a connection somebody had just
  ended.

- **Companion — you find out when a clinician ends their access, without being messaged about it.**
  Three places, all of them somewhere you were already looking. The strip at the top of every screen
  stops saying somebody is reading your entries and says they ended their access, with the date. The
  next time you try to send them something, it is refused before anything is sealed, and says why
  and what you can do — take back what is still published to them, or invite them again. And the
  access log for that connection carries a line.

  **No email is sent.** An email saying a therapy connection has ended could land in an inbox
  somebody else reads. #216 has since decided an opt-in email, off by default, that never says what
  happened; it is not built yet (#329).

- **Companion — the practice console now says what removing somebody does, and what it does not.**
  Removing a member ends their standing in the practice. It does not end any patient's relationship
  with them: the relationship belongs to the patient, was made by the patient's invitation, and only
  the patient can end it — or the clinician themselves, now that they can. An administrator letting
  somebody go will assume the button did more than it did, so the confirmation says so at the moment
  they are about to press it. No new power was given to a practice over a patient's connection, and
  none should be.
- **Companion — your identity is now yours, instead of a new one every visit.** The owner console
  used to make a fresh pair of keys each time you opened it, which meant a clinician who carefully
  wrote down your fingerprint could not verify anything you sent them afterwards. It looked like
  nothing was wrong: a new key does everything a key should do, and it fails only against somebody
  who checked. Your identity is now worked out from the key that opens your own data, so it is the
  same identity every time, on every device. Opening the console is now an unlock rather than a
  button: your key file and either your passphrase or your recovery code. Nothing is kept between
  visits, and the screen says so rather than letting you find out by being asked again. The cost,
  written down because it is real: whoever holds your recovery code can now send things to your
  clinician as you, not only read your journal.
- **Companion — sending your key to a clinician, and the sentence that says it is permanent.** The
  server has always had a place for your public key and nothing ever put one there. Now the console
  does, and it tells you first: once a key is sent it cannot be replaced, so if you lose both your
  passphrase and your recovery code that connection ends and you would have to invite them again.
  If a different key is already on file the console says so plainly instead of showing an error
  code, because that means this clinician can no longer verify you and no button can fix it.

- **Companion — your clinician now learns your keys from the code you spoke, and stops typing them
  in by hand.** The short code you read out to a clinician has always proved *their* keys to *you*:
  what they send back can only be opened by someone who heard it, so nobody who merely got hold of
  the invitation link can be approved. The other direction was not like that. Your keys reached them
  as a long line of characters they pasted into a form, and what that proved depended entirely on
  how the characters got to them — an email anyone could have sent, a message anyone could have
  changed. That was the direction your journal travels along.
  <br><br>
  When you approve a clinician now, your console seals your own two keys with the same code and
  sends them back through the same exchange. Their browser opens them and writes them down before it
  finishes setting them up, so a clinician is never signed up unable to check that what arrives is
  really from you. The two fields where they used to paste your keys are gone from the sign-in
  screen, and there is no longer any way to type one in.
  <br><br>
  The copy of your key that sits on the server has not gone away and still does a job: when your
  clinician signs in, it is compared with the one the code proved. If the two disagree the sign-in
  stops and says so — it does not choose one. If you have not sent your key to the server at all,
  nothing is missing; the screen says there was nothing to compare and carries on with the one the
  code proved. A clinician who was set up before today has no proved copy, signs in on the server's,
  and is told plainly that nothing proved those keys to them and that a fresh invitation from you
  fixes it. Nothing about this asks anything new of you: it is the same code, spoken once, doing
  both jobs.

### Changed
- **Companion — the server logs at `info` as shipped.** The image, the compose file and `.env.example`
  set `DAYMARK_LOG_LEVEL` to `warn`, which hid the three lines that say how the server is set up and
  whether it recovered: the startup settings line, the email-enabled line and "readiness restored",
  without which a cleared storage outage looks permanent in the log. All three now say `info`, the
  code's own default. There is no per-request logging, so this is a handful of lines per start. (#367)
- **A share lasts 14 days unless you choose otherwise, and never more than 90.** The share builder
  used to start at 30 days and allow a year. Beside the number you now read the date the share ends:
  *Ends on {date}. The server then deletes its copy. Anything read before then has already been
  seen.* (#228, #339)
- **The assignment inbox no longer fails as a whole when one item has ended.** The server keeps what
  a clinician sends for 90 days. An item it no longer keeps now shows as one line, *Sent by {name}
  on {date}. The server keeps items for 90 days.*, and everything else still loads. (#339)
- **Planning moved to GitHub, and the documents describe only what exists.** Work to do, bugs and
  open decisions had been spread across plans, session logs, dated audits and to-do comments, and
  several of those had gone stale in ways that told a reader something false. They are now GitHub
  issues, organised under a [roadmap](https://github.com/keyxmakerx/daymark/issues/132); a choice
  only the maintainer can make carries the `needs-decision` label. Twenty-seven retired documents
  were deleted (git history and the issues keep their text), their reference content was merged into
  the documents that remain, and [docs/DECISIONS.md](docs/DECISIONS.md) replaces the dated decision
  log. Code comments cite issues and reference documents rather than plans, and a test now fails if
  code, configuration or an agent instruction names a document that does not exist. Where each kind
  of information lives is set out in [CONTRIBUTING.md](CONTRIBUTING.md).
- **Pinching the sky now magnifies the place you are pinching, and there is a way back out.** A
  pinch used to scale the field about its own corner, so whatever you had your fingers on slid away
  from between them — and this surface has no labels and no landmarks, so what you were looking at
  was simply gone. It holds still now. And once you have zoomed in, a quiet **Fit the whole sky**
  appears in the corner; at rest there is nothing there, because there is nothing to come back from.
  It is not a double-tap, which would have made every tap on a star wait to find out whether a
  second one was coming.
- **The sky is a sky now, not a calendar.** Stars used to be laid out in rows, one row per month
  with the days running across — a chart wearing a starfield's clothes. They are scattered now:
  where a star sits comes from a hash of its own identity and nothing else, warped so the field
  clumps and thins the way a real one does. The consequences are deliberate. No part of the surface
  is a date, so no part of it is labelled with one, and the month headings live in the list
  underneath, which is now the way to reach a particular day. Stars can overlap and are left to. Two
  stars close together mean nothing at all.
- **Colour in the sky is age, not mood.** A star's tint used to come from the mood you recorded, so
  the warm end of the range was the hard end and a bad month was a red month — a verdict drawn in
  colour on the most screenshot-able surface in the app. Colour now says one thing only: how long
  ago it was. Blue-white when it is recent, through white and gold and amber to a deep red after
  about five and a half years. Every star reddens, all at the same rate, so nobody's worst week is
  their reddest. Brightness follows the same clock — older stars recede toward a floor they never
  fall below, so an old year reads as far sky and **nothing is ever dropped from the surface**.
  Somebody who logged for a year, stopped, and came back after five finds all of it still there.
  Mood keeps one job and it is a quiet one: it changes how a star's light is *spread* — wider and
  softer after a hard day, gathered tighter after a good one — and it is **the same amount of light
  either way**. Nothing adds light to a good day or takes it from a hard one; the light is only
  arranged differently. The marks you place yourself are exempt from both curves. A life event does
  not fade and does not redden, which is what leaves it the brightest thing up there.
- **The sky is drawn as light now, on a darker night.** A star used to be a flat translucent disc
  with a dot on it. It is now a hard white point, a tight bright glow right against it, and a soft
  outer glow that fades away to nothing — and the glows add up where stars overlap, the way light
  does. The ground under them went from a warm near-black to a cooler, deeper one, so the glow has
  somewhere to fade *to* instead of stopping on a smudge, and so the older, redder stars read as
  warm against it. Because the ground got darker, every mood colour on this screen was re-measured
  and re-pinned: they are drawn at a higher contrast than before and come out **22% brighter**, not
  dimmer. The mood word you tapped a star to see now has your own colour beside it.
- **Stars have their own beat.** Every star breathes slowly in brightness, each to its own rhythm,
  the same rhythm forever; about one in five carries a brief red-and-blue sparkle a couple of times
  a minute, as does every mark you placed yourself. None of it is a reading of you — the rhythm
  comes from the star's identity, never from your mood, the kind of thing it was, or how much you
  logged — and **all of it stops** when the Motion switch is off, which follows your phone's own
  "remove animations" setting. The animation also stops entirely whenever the sky is not the thing
  on screen, so it costs nothing in the background.
- **A star is just a star until you come close.** The small marks that said what kind of thing a
  star was — the ring, the cross, the underline — used to appear as soon as you zoomed in a little.
  They now wait until you are looking at a single day. At any wider view the sky is stars and
  nothing else. The list still names the kind of every entry, as it always did.
- **"Review my year" opens on a count, not a compliment.** The first page used to read "N days you
  showed up for yourself", which says what your year meant rather than what it held, and decides on
  your behalf why you wrote. It now reads "N days with an entry" — the same phrase the rest of the
  app settled on — and the invitation that follows is unchanged.
- **Companion — a reply that will not open now asks you a question instead of guessing.** When
  somebody answers your invitation and what comes back does not open with your code, the screen used
  to offer a new code and a paragraph speculating about whether it was a typo. It now says "A reply
  did not open with your code" and asks the only question that can be answered: keep this invitation
  open and ask them whether they answered, or stop it and send a new link? The count of tries left
  is untouched — a reply that did not open costs you nothing — and "Keep it open" ends nothing and
  tells nobody. There is no pop-up notification, because this half of the product runs in a browser
  tab and a closed tab cannot raise one reliably; the phone version, when it arrives, will raise one
  quiet notice that names nobody and counts nothing.

### Fixed
- **Restoring a backup no longer empties the journal when it fails part-way.** "Replace all
  current data" deleted thirteen tables and then wrote the backup back, and the two halves were not
  tied together. If anything threw between them — an older file whose activity links name something
  it no longer carries, or the app being killed during a long restore — every deletion stood and
  nothing took its place. The person was left with an empty journal, an "Import failed" message,
  and their entries only in the file that had just failed to load. The deletions and the writes are
  now one operation: either the backup is in place, or nothing was touched.
- **"Backup exported" is no longer shown when nothing was written.** If the file could not be
  opened, the failure was swallowed and the app reported success over a zero-byte file. It now says
  the export failed, which is the whole point of the one file people are told to make before
  installing a new version. The PDF export already did this correctly; the backup and the
  spreadsheet did not.
- **A PIN longer than eight digits can be typed back in.** Settings and the first-run setup both
  offer a PIN of six to twelve digits, and the note under the field says a longer one takes longer
  for someone else to guess. The unlock screen, meanwhile, stopped accepting keystrokes at the
  eighth digit. Anyone who took that advice could set a PIN, close the app, and then never get back
  in: the screen said "Incorrect PIN", cleared the field, and after five tries started a cool-down
  of up to five minutes, with nothing to suggest that the length was the problem. The entries were
  never damaged and are not encrypted under the PIN, but the only screen that can read them sits
  behind that lock, so the realistic way out was clearing the app's data, which deletes the journal.
  The unlock screen now asks the same single place that decides the rule, and the test written to
  catch exactly this drift was looking at the two screens that SET a PIN and not at the one that
  takes it back; it now covers all three.
- **Companion — the secret that guards your journal is now made by the console, not typed into a
  box.** Every request a clinician makes for your material carries a token, and the token is what
  makes a copy of the server's database useless to whoever took it. Nothing in Daymark made one.
  The console asked you to type it, accepted anything at all, and a single letter worked — while
  the security document described it as 256 random bits. The console now makes a real one when you
  add a clinician and shows it once, with no box to type your own into. Two things follow. Nobody
  can hand two clinicians the same token by accident any more, which used to put them in each
  other's material with nothing on screen to show it. And the help text on the clinician's side has
  stopped telling them to look in the invitation for it: the token has never been in the
  invitation, cannot be put there, and has to reach them another way — said out loud, sent by text,
  handed over. Losing it is not a dead end: adding that clinician again makes a new one, and what
  was already shared stays as it was.
- **Companion — a clinician no longer retypes forty-three characters at every single visit.** The
  sign-in screen was meant to remember that token after the first time, and the code that
  remembered it failed every time it ran, silently, from the day it was written. Nobody was told
  and nothing looked broken — it just asked again, forever. It is remembered now, for as long as
  that tab stays open, and deliberately not for longer: it is not kept next to their stored keys,
  because a copy of their browser would then carry both halves of what the server asks for. Closing
  the tab means being asked once more, and the screen says so where the question used to be.
- **Companion — waiting for you to approve a pairing no longer uses up the clinician's allowance.**
  The server gave each internet connection twelve pairing requests every five minutes, and counted
  the "has she approved it yet?" checks against them — so a clinician who simply waited for you
  spent most of their own allowance doing nothing, and two clinicians in the same practice, who
  share one connection, spent each other's. What ran out first was the one thing that most needs to
  work: **telling us an invitation was not expected**. That is now outside the allowance
  altogether. The checks while waiting are counted per pairing instead of per connection, so nobody
  waits on anybody else, and the requests that actually carry the ceremony get twenty per five
  minutes rather than twelve. If a connection is genuinely busy, the clinician is told it is
  **paused until** a time, and that their invitation is unchanged and will still open then —
  because the server knows the connection was busy, not that they did anything wrong.
- **Companion — "this wasn't me" is now accepted even while somebody is guessing at the
  invitation.** If wrong codes were being tried against an invitation, it locked, and the lock
  refused the invited person's report along with the guesses — so the invitation most likely to be
  in the wrong hands was the one its real holder could not close. A report proving the right secret
  is now honoured whether or not the invitation is locked. Reporting also gives the same answer to
  everyone now, whatever the truth of it, so the button cannot be turned round and used to find out
  which invitations exist; and reporting twice does the same thing once.
- **Companion — a clinician's hand-checked copy of your key is no longer silently replaced by the
  server's.** Signing in used to take whatever the server said your keys were and write it over the
  one the clinician had verified with you, without comparing them or mentioning it. It had never
  done any harm only because nothing had ever published a key. Now the one they checked is the one
  they keep, the server's copy is only a cross-check, and a disagreement stops the sign-in and says
  so — naming no cause, because a typo, a changed key and a server handing over a different one
  cannot be told apart from there.
- **A recovery-code test that failed about one run in thirty-one, against code that was correct.**
  It checked that a tampered code is refused, and tampered with it by replacing a whole group of
  five characters. A check character is an error detector, not a signature: it catches every
  single-character mistake and every swap, and a five-character change slips past it once in
  thirty-one — measured at 97 in 3000. The test now changes one character, which is the error the
  scheme actually guarantees to catch. Nothing was skipped, loosened or retried.

- **Companion — the pairing key now carries something: the therapist's offer, and the owner's
  approval of it.** When a therapist answers a pairing code, their reply now travels with their
  public keys, a name, and an enrolment ticket they chose, sealed under the key that only the
  right code produces. The owner's side opens it, or cannot, which is the first and only place a
  wrong code shows itself: as a reply that did not match, never as an error and never as a
  verdict about who sent it. Approving hands that ticket to the server, which is how enrolment
  becomes possible at all; a link-holder who answers a run first has produced something the
  owner cannot open, and gets nothing. The ticket lives as long as the invitation rather than
  ten minutes, because the clock now starts at the owner's approval and a stranger's wrong
  guesses can lock the therapist's own status poll for an hour. An approval nobody finished can
  be taken back, which puts the invitation back where it was. The owner can also list their
  invitations and see where each stands, including how many wrong secrets have been tried
  against it, as a count and nothing more. The old secret-only redeem route is still present
  until the screens move over; the next change removes it.
- **Companion — the pairing code exists, and the ceremony refuses a code that has not been read
  properly first.** Eight characters in two groups of four, `K7M4-RD96`: seven carry the secret
  and the eighth checks the other seven, so a mistyped or swapped character is caught on the
  therapist's screen, with its position, before anything is sent. The code is generated on the
  owner's device and has no field anywhere it could be stored: the owner's half of an open
  pairing now survives a page reload in the tab's own storage, and a test greps that record for
  the code in every encoding. The relay takes the canonical form only and checks it again at the
  door, because a trailing space or a lower-case letter used to make a silently different key.
  The owner console also gained the one button that ends an invitation, with copy that says what
  that does and does not do. No screen runs the ceremony yet; that is the next two changes.
- **Companion — the pairing ceremony is complete, end to end.** A clinician can now be invited,
  accept, and end up with keys the owner has actually confirmed — no step of it hand-carried:
  the invitation link opens an acceptance page (one code, one passphrase); the therapist's public
  keys travel to the owner over a new route, and the owner's travel back over its mirror (the
  auth deliberately reversed on each); and both fingerprints are read aloud between two humans,
  because the server relays keys and vouches for none of them. The whole ceremony has been driven
  in a real browser, both sides, with real TOTP codes computed from the enrolment screen.
- **Companion — sign-in asks for two things instead of nine.** Seven of the nine fields were
  bytes the product had no way to move, so a person carried them. Now the browser that accepted
  the invitation keeps a record, the owner publishes their keys, and the origin answers the
  server-address question. A returning clinician types the code from their authenticator and
  their reading passphrase. The nine survive as a folded-away fallback for a browser with no
  record, running the same unlock so it cannot drift into a second, less careful sign-in.
- **Companion — recovery codes (crypto complete; storage honest about not existing yet).** The
  data key is now random and wrapped once per secret: the passphrase or a written-down recovery
  code opens the same key, and a server holding both wrapped slots and neither secret recovers
  nothing — that sentence is an adversary test, not a hope. The code is 29 symbols over a
  31-symbol alphabet with both halves of every handwriting collision removed and a check symbol
  that provably catches every substitution and transposition. The panel says plainly that no
  transport or storage for the wrapped key exists yet, on screen, not in a comment.
- **Companion — a practice gets a console, and the three-plane rule gets teeth.** Orgs, members,
  roles, and an org admin scoped to their own practice — plus the invariant that makes the shape
  safe to offer at all, as a test: an admin can revoke anyone and see who accessed what, and
  cannot read a single clinical note. The test parses the role table out of the specification at
  run time and iterates the whole catalog, so code and document cannot drift apart silently.
- **Companion — first-run finally asks what this machine is for.** Solo (one person, one
  machine), Paired (you and a clinician), or Practice — sixty-nine words before the choices,
  storage that fails open to a repeated question rather than a wrong screen, and no sentence
  anywhere claiming the copy is safe or survives a lost phone, enforced by a copy test.
- **Companion — the audit chain can now check itself, and hands you the value worth keeping.**
  The server recomputes a relationship's hash chain oldest-to-newest and reports the extent, the
  first internal break if any, and the head hash as stored — behind the owner's token, appending
  nothing (a check that extended the chain it was checking would move the head on every look).
  The admin console renders the head with a real SHA-256 and says, on every view, what a clean
  verdict is not: a server that quietly declines to append, or truncates its tail, verifies
  perfectly. The head's real value is being written down where the server cannot reach.
- **CPace, on both sides of the pair.** The password-authenticated key exchange the remote
  pairing design rests on now exists twice — once for the browser, once for the JVM the phone
  will run — each written directly against the IETF draft and pinned byte-for-byte to the CFRG
  working group's published test vectors, so the two agree through the standard rather than
  through each other. A live exchange was run between them in both directions with fresh
  randomness: equal keys on a matching code, silently diverging keys on a wrong one — the
  property the invite burn rule leans on.
- **The Sky.** Everything you have ever logged, drawn as one field of stars — a check-in, a journal
  entry, a practice you worked through, a step you finished, a goal you reached, a life event you
  marked. One star per act, placed by date. It is reachable from **More** and deliberately **not a
  tab**: it is the surface that says the most about you to anyone holding your phone, so getting
  there is something you choose rather than something you walk past.
  - **It never counts, ranks, compares, or congratulates.** There is no streak, no "best month", no
    total, and no comparison between one stretch of your life and another. A gap draws nothing —
    not a grey placeholder, not a dotted line, not an apology. A period of not coping is not a
    thing the software gets to draw a shape around, and an empty stretch of sky simply reads as
    sky. Deleting something leaves no mark that it was ever there.
  - **The Sky never loads a word you wrote.** Not the journal title, not the body, not a goal's
    name, not a life event's label. Every one of the six kinds is read through its own query that
    selects an id and a date and *cannot return text* — the property lives in the database
    signature rather than in anyone remembering to be careful at the other end, so someone
    standing behind you learns *that* you wrote something and never *what*. A test asserts all six,
    and it fails the build if a seventh kind is added without one.
  - The background starfield is **seeded once, from your first record, and never re-derived** — a
    place whose walls move is not a place, and it would otherwise shift every time you deleted
    something, which is a shape that deletion left behind.
  - Your own mood palette is used, passed through a contrast equalisation so a custom palette
    cannot produce a star that is unreadable against the night. The Sky hardcodes no colour.
- **Life events**: a short title and a date, for the things that happened *to* you rather than the
  things you did — a bereavement, a move, a diagnosis, a beginning. **Never inferred, never
  suggested, never prompted for.** The app does not notice that your entries changed and ask
  whether something happened, because detecting a discontinuity and asking you to explain it is
  inference with a question mark on the end, and the thing it would most reliably detect is a
  period of not coping. There are no categories to pick from — a taxonomy is software deciding
  what counts as a life — and no mood or valence, because a life event is not good or bad and the
  app never asks how it felt.
- **Goals can be marked reached**, by you and only ever by you. It is a switch you set and can
  unset, never something derived from progress: a habit that hit its weekly target is not "reached"
  and a project with every step done is not "reached", because a threshold that flips this field is
  the software deciding something about your life and then acting on it. Archiving a goal you
  reached keeps the star — tidying your list is not un-doing the thing — and abandoning one is
  never recorded as a failure.
- **A pinned clinician key can now be forgotten or rotated individually**, instead of the previous
  all-or-nothing wipe. Rotation is gated on typing the short verification words that match the new
  key, and the check that lets the button light up is the *same* predicate that performs the
  rotation — a button that brightened on a phrase the rotation would then reject would teach an
  owner that the words are decoration. Neither action is one click away: forgetting sits behind a
  closed disclosure and a second confirmation, because a key you forget by accident is a key you
  re-trust blind the next time it appears.
- **The PDF report is now four sides, and you decide what's on each.** *The glance* (the shape of
  the range), *the detail* (per-instrument series), *in their own words* (your journal writing), and
  *for the conversation* (questions to bring). Side three **only exists if you switched it on** and
  there is writing in range — an entry falling between two dates is not consent to print it, and the
  report says which of the two things you did in so many words ("you turned it on for the whole
  range" reads differently from "you ticked these"), because on the one side where the strength of
  the consent is the point, the software must not describe a deliberation that never happened.
  - The last side prints **questions, never recommendations** — "ask about the three harder days in
    week two", not "the patient should". They can be switched off entirely. What it will and will
    not say is a bright line in the code, not a tone of voice.
  - The whole page geometry moved into `ReportLayout.kt`, which imports nothing from Android. That
    sounds like housekeeping and is not: layout arithmetic was previously only observable by looking
    at a rendered PDF on a device, so a column that overflowed by two points was invisible to CI.
    It now has 980 lines of tests that run anywhere.
- **Guided exercises sit beside questionnaires** in the clinician tool: a hard-moment exercise and a
  values exercise, neither of which is scored, because a compassion practice has no score and giving
  it one would invent a number a clinician might reasonably act on. The assignment list labels each
  by what it actually is, rather than calling everything a "Questionnaire" — the previous behaviour
  told a clinician they were assigning a measure when they were assigning an exercise.
- **My safety plan**: short lists you write **while things are steady**, so a harder moment doesn't
  have to start from a blank page — *warning signs I notice*, *things that help*, *people I can
  reach*, plus an optional *reasons I want to stay* that is **offered rather than assumed** (it's
  the heaviest thing on the screen to write, and an empty one sitting there permanently would read
  as a reproach on a bad day). Reading and editing are the same screen, because in a hard moment
  nobody should have to hunt for an edit button. The crisis row shows **your own** saved resource —
  it defaults to 988 and is editable, so it's a real number wherever you are — and it hands off to
  the crisis screen rather than dialing, since Daymark isn't a crisis service and never places a
  call for you. The footer says the limit out loud: **a plan is not a person — reaching one is the
  point.** Reachable from **More**, and as a quiet row in "Take a moment" **only once a plan
  exists** (a row leading to a blank page in a hard moment is exactly what the plan is meant to
  prevent). Deliberately **never** a suggestion card: nothing infers from your mood that you need
  your safety plan. Everything is local, offline, and included in backups.
  - Written in **our own words** and labelled **Adapted**, not the Stanley-Brown Safety Planning
    Intervention — that form requires written permission to program into an electronic record.
    There is deliberately **no means-restriction prompt**, which is also a house rule under
    `PROVENANCE.md`. You can write anything you like; the app just never asks.
- **Home — the daily loop**: Home is no longer the whole archive. It now opens with a greeting
  and the date, a **one-tap check-in row** ("How are you, right now?" — tap a face and the entry
  editor opens with that mood already picked), a small **glance** (current streak, plus the last
  seven days as seven bars where an unlogged day is a faint stub), at most **one** suggestion
  card, and **today's** entries. Two quiet links lead onward: **More for you** and **All
  entries**. Follows the locked concept in
  [docs/design/app-01-home-daily-loop.png](docs/design/app-01-home-daily-loop.png).
- **All entries**: the full day-grouped timeline that used to live on Home, now its own screen
  linked from Home — nothing was removed, it just stopped being the first thing you see.
- **For you**: the ranked Signals suggestions Home has no room for, plus the richer "on this day"
  memories card, on one screen ("Gentle suggestions — never nags"). Every card is still a fixed,
  rules-based template and every one can be dismissed.
- **Suggestion controls — opt-out, granular, and remembered**: every suggestion card now carries a
  menu — **not right now** (this visit only, nothing stored), **show less like this**, **remind me
  in a few hours**, **not helpful, hide it**, and **turn this suggestion off** — and every choice
  but the first is remembered across restarts. **Settings →
  Suggestions** lists every kind of suggestion under *On* and *Off*, says when a snoozed one comes
  back ("Snoozed · back in 2h") and offers to bring it back now. Turning one back on clears
  everything holding it back, so it can't be on and still invisible. Nothing is learned from what
  you tap: "show less" subtracts a fixed amount from that suggestion's rank and snoozes it for
  three days, and the rules are unit-tested. Two things are deliberately **not** controllable:
  Home's check-in row (that's how you log, not a nudge) and the "what might help" support menu (you
  open it on purpose). Crisis resources stay reachable whatever you set.

- **Companion — tool provenance labeling**: every questionnaire/tool now declares whether it is
  **Validated**, **Adapted**, or **Custom**, enforced by the instrument honesty gate (validated
  requires a source; adapted names the method it draws from; custom must open with a
  non-diagnostic disclaimer). The questionnaire runner shows the badge and, for a custom tool, its
  "not a validated or clinical instrument" disclaimer up front. The tier is also surfaced in the
  self-check list and the therapist's assign surface. See [docs/PROVENANCE.md](docs/PROVENANCE.md).
- **Companion — no-code tool builder**: author a questionnaire/reflection tool (items, provenance
  tier, descriptive bands) with a live honesty-gate panel and a live preview; reachable from the
  owner app's "Build a tool" tab, which exports the compiled tool as a validated instrument
  definition. Publishing to the catalog/assignment channel is a later slice.
- **Insights — what affects your mood**: on-device mood↔factor correlations for activities and
  numeric trackers, ranked "lifts you up / weighs you down" (with a minimum-sample gate);
  by-day-of-week and by-time-of-day mood patterns; and a this-period-vs-last comparison that
  follows the Week / Month / Year toggle. Every surface is labeled **association, not cause**, and
  it all computes locally with no schema change.
- **Insights — "In review" + consistency heatmap**: a short, rules-based recap (entries, average,
  best/worst weekday, top mood-lifting factor, current streak), also rendered in the PDF report;
  and a GitHub-style entries-per-day **logging-consistency heatmap**.
- **Check-ins (PHQ-9 / GAD-7 / WHO-5)**: three free, widely-used wellbeing self-checks with score
  history and a trend chart (**More → Check-ins**). **Strictly non-diagnostic** — only the score
  and band are stored, never the individual item answers (the PHQ-9 self-harm item never
  persists). If that item is non-zero, PHQ-9 surfaces the offline crisis flow — never a risk
  verdict. WHO-5 is shown as a 0–100 percentage. PHQ-9 and GAD-7 are **free to reproduce
  (Pfizer)**; WHO-5 is **© WHO, free for non-commercial use** (cited in-app). See
  [docs/INSTRUMENTS.md](docs/INSTRUMENTS.md).
- **Achievements**: gentle milestones for showing up — first entry, entry counts, longest streaks,
  activity variety, first check-in (**More → Achievements**), with original hand-drawn badge art.
  No streak-shaming; earned badges are sticky.
- **Breathing presets**: pick a pacer cadence — **slow ~6/min** (gentle default), **box 4·4·4·4**,
  or **4·7·8** — with proper hold phases and the existing in/out haptics. Described generically.
- **Journal writing templates**: starters on a fresh entry — **Three Good Things** (gratitude), a
  timed **Expressive Writing** prompt (with a gentle "this may surface hard feelings" note and a
  link to support), and a reflect-on-the-day prompt.
- **Do one thing (behavioral activation)**: plan a small pleasure/mastery activity, optionally set
  a reminder, then rate **enjoyment** and **mastery**, which log to auto-created 0–10 trackers so
  they show up against mood in Insights. Reuses trackers + reminders; framed as a skill, not
  treatment.
- **Implementation intentions**: goals can carry an optional "when X, I will Y" plan. Existing
  goals are unaffected.
- **Thought records (CBT)**: a guided record (**More → Thought records**) — situation → automatic
  thought → optional thinking-trap tags → evidence for/against → balanced thought, with mood
  before/after. The cognitive-distortion list is **self-authored** (our own names/definitions).
  Framed as reflection, not a verdict or diagnosis.
- **Move**: gentle yoga/stretch and bodyweight interval routines (**More → Move**) with
  **original hand-drawn pose figures** (drawn with the same Canvas primitives as the mood faces,
  zero image assets) and a haptic-cued timer that works eyes-closed. Sequences are described in
  our own words, with no branded programs; each session logs to an auto-created "Movement minutes"
  tracker so it shows up against mood in Insights. No video, no network.
- **Photo attachments**: optionally attach a photo to a mood entry via the Android Photo Picker
  (no storage permission). Photos are downscaled and stored app-private, shown as thumbnails on the
  Home timeline and Day Detail, and embedded (base64) in JSON backups so a backup stays a single
  portable file.
- **Swipe-to-delete with undo**: swipe a Home-timeline entry to delete it, with a 5-second **Undo**
  snackbar that restores the entry (and its activity links).
- **Multiple reminders**: replace the single daily reminder with a list, each reminder having its
  own time, on/off toggle, and optional label, managed under **Settings → Reminders**.
- **Notification quick-log**: tapping a reminder notification — or its **Log now** action — opens a
  fresh entry straight away.
- **Auto-lock timeout**: when the PIN lock is on, choose to re-lock immediately (default) or after
  1 / 5 / 15 minutes in the background.
- **Customize moods**: rename and recolor any of the five mood levels (**Settings → Customize
  moods**). The 1–5 level stays the stable key, so existing entries keep their place on the scale;
  custom names/colors flow through the timeline, calendar, insights, widget, and CSV export, and
  ride along in JSON backups.
- **Journal**: a separate free-form diary, distinct from per-entry mood notes.
- **Global note search**: search across mood notes and journal entries from one place.
- **Activity library**: browse 100+ ready-made activities by category and add the ones you use.
- **Insights tab**: a single screen merging the former Stats, Calendar, and Year-in-Pixels views,
  with a **Week / Month / Year** scale toggle. (Year in Pixels shows the whole year as a grid of
  mood-colored squares.)
- **Tap a day to view/edit** its entries from the calendar.
- **"On this day" memories**: gently resurface what you logged on this date in past months/years.
- **Goals**: weekly habit goals with progress, optionally linked to an activity.
- **Custom trackers**: track anything alongside mood as a scale, a number (with a unit), or a
  simple yes / no, with history.
- **Sleep suite (non-diagnostic)**: a manual sleep diary with derived metrics (time in bed,
  total sleep, sleep efficiency); sleep-setup calibration; license-clean, original self-checks
  for apnea-style signs, restless legs, and insomnia signs; treatments before/after comparison;
  a descriptive (never causal) sleep ↔ mood insight; and an **experimental** on-body breathing
  check that uses the accelerometer (no audio recorded, only the result shown).
- **Gentle support ("Take a moment")**: an opt-in, validate-first flow with a breathing pacer and
  offline crisis resources; nothing is sent anywhere.
- **CSV export** of mood entries; **merge** option when restoring a JSON backup.
- **Home-screen widget** (Glance) to quick-log a mood.
- **Export PDF report** — a printable report with a SHA-256 + QR authenticity stamp.
- **First-run onboarding wizard** (skippable): daily-reminder setup, optional PIN lock.

### Changed
- **"Update" is now a real word — 0.2.0.** Every CI build used to sign with a throwaway key, so
  every install was an uninstall-and-reinstall on an app where uninstalling deletes the data. A
  committed testing keystore (its password beside it in the clear, because this key is a channel,
  not an identity) makes every artifact from here on update in place. One last migration is
  required and stated honestly: export a backup, uninstall, install 0.2.0, import. Real releases
  keep signing with the secrets-based key that never enters the repo.
- **`DAYMARK_THERAPIST_AUTH` is now in the deployment files it always needed to be in.** It gates
  the entire relationship surface and defaulted off — right for Solo, fatal for the other two
  shapes — while existing only in `Config.kt`, so operators met it as a 503 at the last step of a
  ceremony that had appeared to work. It is in `docker-compose.yml` and `.env.example` with the
  reasoning at the call site.
- **One place now decides whether the app may interrupt you.** Each feature used to ration its own
  interruptions, which meant nothing could see the total: three features each politely limiting
  themselves to "twice a week" is six. The interruption budget is asked a single question —
  *may I interrupt, right now* — and it is deliberately **not told what about**. It has no access to
  your mood, your writing, or which feature is asking, so it cannot become a thing that infers a
  clinical state from your behaviour and then acts on it. It answers from how previous offers landed
  and nothing else.
  - **Reminders are not rationed by it**, and that is a reversal of the first implementation. A
    reminder at 9am is one you asked for at 9am; an alarm clock that decides you have been ignoring
    it and drops to weekly is broken, not considerate. The first cut let two unanswered firings
    silently collapse a three-a-day schedule to one a week, with no setting anywhere to put it back.
    Reminders now record how they landed — the budget learns from them — and fire exactly when you
    said.
  - Outcomes live in a new local `offer_record` table (schema **v13 → v14**, migration included).
    It stores that the app asked and how that went. It does not store what was asked about.
- **Companion web visuals rebuilt on a two-tier token layer.** Raw values are declared once and
  referenced by role, so the three theme states (light, `prefers-color-scheme`, explicit toggle)
  cannot silently disagree — a colour defined only inside a media query was previously possible and
  is now a test failure. Four tokens that failed WCAG AA contrast were found this way and corrected,
  including the amber used on the badge that appears on every row of the client list (3.53:1).
  - **The mood ramp is data and only data.** Those five colours mean "this is what the person
    logged". Using one for a button, a chart series, or a status pill makes the interface look like
    it is agreeing or disagreeing with them, so the ramp is now unavailable outside the places that
    render mood, enforced tree-wide rather than by convention.
- **Dependabot now knows which upgrades this project cannot take**, so it stops reopening
  un-mergeable PRs every week. Five of them were failing CI on repeat: Hilt 2.60.1 (Dagger ≥2.59
  hard-requires AGP 9), Kotlin 2.4.0 (Kotlin and KSP are version-locked and bumped in separate PRs;
  plus a KSP defect around `internal` Hilt providers), lazysodium 5.2.0 (JVM-21-only, which
  `libs.versions.toml` had already documented in detail), and a grouped androidx PR carrying Compose
  BOM 2024.12.01 → 2026.06.01 past the AGP 8.8.2 lint floor. Every ignore rule carries its reason
  and its unblock condition inline, and the full constraint graph is written up in
  [docs/TOOLCHAIN.md](docs/TOOLCHAIN.md). GitHub Actions updates are left unconstrained — those
  aren't failing.
- **Room schemas are now exported through Room's own Gradle plugin** (`room { schemaDirectory(…) }`)
  instead of the raw `ksp { arg("room.schemaLocation", …) }`, so the directory is a declared Gradle
  task input/output rather than a path the processor writes to blind. CI additionally fails if the
  exported schemas drift from what is committed — Room's own equality check compares only entities
  and views, so a wrong `identityHash` in a committed schema would otherwise go unnoticed. No
  dependency versions changed, and the existing flat schema layout is unchanged. Build-only; no
  behaviour change in the app.
- **Deleting an entry now asks first.** The swipe itself never deletes — a confirmation dialog
  does, with the 5-second **Undo** snackbar still behind it. A steady drag has to cross most of the
  row before the background turns from "Keep swiping" to "Release to delete", so a stray thumb
  costs a tap on "Keep it" rather than a day's log. The entry editor's delete button also confirms
  now (and says plainly that there's no undo behind that one).
- The **"on this day" memories** card and the extra suggestion cards moved off Home to the new
  **For you** screen, and the day-grouped timeline moved to **All entries** — Home was doing too
  many jobs at once.
- Renamed the app from "Daylie" to **Daymark** (package `com.daymark.app`).
- Adopted the "modern paper" design system: paper palette, serif/sans type, and original
  hand-drawn mood + activity icons (replacing emoji and Material icons).
- Consolidated navigation around the unified Insights tab.
- Snappier, directional navigation transitions; larger mood-picker tap targets.
- Database schema is now **v12** with Room migrations, adding `assessment_results` (v10, check-in
  scores), `cue`/`routine` on goals (v11, implementation intentions), and `thought_records` (v12,
  CBT). The backup format is now **v12**: check-in score history, achievement unlock times,
  goal cue/routine, and thought records all round-trip (replace **and** merge). Older backups
  still import, and an existing single reminder is migrated automatically on upgrade.
- The bundled wellbeing check-ins use the **exact wording** of PHQ-9, GAD-7 (free to reproduce,
  Pfizer) and WHO-5 (© WHO, free for non-commercial use, attributed in-app). No licensed
  instruments are bundled — see [docs/INSTRUMENTS.md](docs/INSTRUMENTS.md) for the full ledger.
- **No new permission** was added for any of these features — the app still has no `INTERNET`
  permission and makes no network connections.

### Fixed
- **Companion — withdrawing a share now says when a copy could not be removed.** The store used
  to mark the rows and swallow every failure to delete the ciphertext files, so "withdrawn" read
  as complete while the bytes stayed on the volume. The count of copies that would not delete now
  comes back with the result, and a test makes one refuse.
- **Companion — the pairing relay was audited before anything was built on it, and three things
  moved.** A re-open now retires the run it replaces: before, the abandoned exchange stayed
  answerable and, once the newer one was answered or cancelled, came back as "newest" to the next
  fetch — a reply into it would have made a key nobody could ever hold, with no signal on either
  side. The channel identifier now carries the invitation id, the one value each party has without
  asking the server (the relationship reference the therapist used before arrived from the server
  itself, so it bound nothing on their side despite the comment). And the owner's side pins the
  reply that produced its key and refuses a different one on a later read, so "one guess per run"
  is enforced by the code rather than by how often a console polls. Each fix has a test that was
  red on the code before it; the bound that survived attack, and what the screens must hold for
  it to mean anything, are written where the screens will be built (plan §4.0a).
- **Every invitation link this server ever sent rendered a blank page.** `/portal/invite`
  returned 200, served the right markup, had a passing test — and displayed nothing, because the
  bundle's relative asset URLs resolved against the URL's directory and every script came back as
  HTML. It is now a redirect that carries the fragment (the secret never reaches the server
  either way), and the test asserts the redirect, since the old 200-assertion was itself the bug.
- **Every server call from a real browser threw "Illegal invocation".** Six call sites stored a
  bare `fetch` as a default parameter, which arrives with the wrong `this` in Chromium. Every
  test passed throughout, because tests inject their own fetch and a default nobody evaluates is
  a default nobody notices. Found the only way it could be: driving the built pages in a real
  browser, which is now part of how this repository verifies itself.

### Security
- **Companion — the server will not start the clinician portal or email without its public
  address.** Invitation and notification links fell back to whatever address the visitor's request
  named when `DAYMARK_PUBLIC_BASE_URL` was unset, and an invitation link carries its secret. With the
  clinician portal (`DAYMARK_THERAPIST_AUTH`) or outbound email (`DAYMARK_SMTP_HOST`) on, the server
  now refuses to start without the address, or with one that is not an absolute `http` or `https`
  address, and says so in one log line that names the setting and gives an example; it exits with
  status 78. The shipped compose file always sets the address from `DAYMARK_DOMAIN`, so a standard
  install is unaffected, and a server that only syncs needs none. (#180)
- **Companion — the plain-http testing switch is refused on a server reached over https.**
  `DAYMARK_COOKIE_INSECURE` lets the clinician session cookie travel over plain `http`, for local
  testing. Left on where the public address is `https`, it was one step from session cookies crossing
  the network in the clear, and nothing said so. The server now refuses to start with it there,
  naming both settings. (#181)
- **A share's signature now covers everything in it, so nobody holding a share can change what it
  says.** The key that opens a share is sealed to the clinician with a sealed box, which anyone who
  knows the clinician's public key can make, and the owner's signature covered only the share's
  label: its id, version, recipient, expiry and owner. Whoever held one share, the server that
  stores it included, could keep the signed label and put different contents under it, and the
  clinician's portal would have shown those contents as the owner's. The signature now covers the
  label, the encrypted contents and the sealed key together, and the portal checks it before it
  opens anything. **Shares made before this change no longer open.** The clinician sees *This share
  was sealed in an older format whose contents cannot be checked, so it stays closed. Ask for a
  fresh one.* The version a share is signed as is now the version it is published as, and the portal
  refuses one that says otherwise. Shares are also padded before they are encrypted, so the server
  learns only a rounded size: up to 1 MiB, the next power of two and never less than 4 KiB; above
  that, never more than about 12% larger. Snapshots, game plans and assignments follow (#315).
- **A clinician can no longer use up the space the owner needs.** Everything in a relationship, the
  owner's grants and shares and the clinician's assignments and game plans, drew on one storage
  allowance, so a clinician who wrote enough could leave the owner unable to publish anything,
  including a grant that takes a permission away from that clinician. Each direction now has its own
  allowance: what the clinician writes may use a quarter of `DAYMARK_REL_QUOTA_BYTES` (64 MiB of the
  default 256 MiB), and what the owner writes the rest. The total is unchanged.
- **A clinician's portal trusts only the grant written for them, and the owner's inbox only an
  assignment filed under the label it was signed with.** The owner signs every clinician's grant
  with the same key, so the signature said who wrote a grant but not whom it was for, and a server
  could have shown one clinician the permissions granted to another. The portal now checks the name
  inside as well. Nothing depended on that screen, because the owner's console checks every
  assignment against its own copy of the grant, but a clinician should never be shown permissions
  they do not have. The owner's inbox now refuses an assignment the server files under a different
  lineage or version from the one signed inside it, so an old assignment cannot be shown again as a
  new one. A clinician who re-pairs with new keys keeps what was granted, re-bound to the new key;
  before, every assignment they sent after re-pairing was refused.
- **What you share with a clinician no longer outlives its end.** A share is served until the end
  you chose and never more than 90 days after you publish it (it was a year), and publishing a new
  share ends the ones before it, so narrowing a share really narrows it. Assignments and game plans
  end 90 days after they arrive. Within the hour after anything ends, the server deletes its stored
  copy and keeps only a record that it existed. Before, an expired share was only refused, and its
  bytes stayed on the disk for good. Only what the server still holds counts against a
  relationship's storage now. The limit of 50 versions per item now ends the oldest the same way,
  instead of deleting the record and sometimes leaving the file behind with nothing pointing at it
  (#374). Items already stored follow the same rule from the first start after upgrading; nothing
  had been publicly released, so no one's chosen end date is cut short. (#228, #332, #338)
- **Backups, game plans and assignments are padded before they are encrypted, as shares already
  are.** The server stores each one at a size rounded up to a standard bucket (at least 4 KiB, then
  powers of two up to 1 MiB, then never more than about 12% larger), so it can no longer tell from
  sizes how much you wrote between two backups, or how long a plan or a task was. Padding hides how
  much, never when: the server still sees when each one arrives, and the clinician's sign-in page
  now says so in those words. Everything already stored unpadded still opens. The command-line
  backup writer checks the padded size before it sends anything and says plainly when a backup is
  too large once padded; a server that accepts larger blobs is matched with `--max-blob-bytes`. The
  phone's sync code, not yet switched on (#168), makes and opens the same padded backups byte for
  byte and still opens unpadded ones. (#315, #316)
- **A copy of a share sealed before one the clinician has already opened stays closed.** A server
  restored from a backup, or anything able to change what a server stores, could have handed the
  clinician an older share as the current one. The clinician's browser now remembers when the newest
  share it opened was sealed (the owner's own time, signed into the share) and refuses an older
  copy: *This copy was sealed before one you have already opened, so it stays closed. Ask for a
  fresh one.* It cannot catch the first share a browser opens, or a server that changes the page
  itself.
- **The phone's actual cryptography moves from 2019 to 2024.** The C library doing the encrypting
  on the phone was libsodium 1.0.18, bundled inside a wrapper whose version number said nothing
  about it; 1.0.20 brings five years of hardening (AEAD MAC memory fences, optimizer blockers,
  ed25519 small-order point rejection). The comment that had justified staying put asserted a
  cross-artifact parity invariant that measurement disproved — it is replaced by a test that
  parses both artifacts' bytecode on every run and was itself proven against four deliberately
  broken artifacts before it was allowed to guard anything.
- **A lockout is recorded once, when it is armed — locked doors stop writing on behalf of
  whoever knocks.** Both the invite routes and the TOTP sign-in used to append an audit row for
  every request that bounced off an already-armed lockout: a free-to-the-caller path writing
  attacker-paced volume, permanently, into the one log the owner reads. Each lockout episode is
  now one row, written by the request that paid to arm it, on whichever surface armed it — and a
  second episode still writes a second row, pinned by tests on both surfaces so "once per
  lockout" can never decay into "once ever".
- **Photos keep the picture and nothing else.** A JPEG straight off a phone camera carries GPS
  latitude and longitude to five decimal places, the exact capture time, and the device's make,
  model and serial. Daymark has no location feature, and it would be a strange promise to make on
  the settings screen while filing the coordinates of someone's bedroom in their journal, where a
  backup file carries them onward to whoever ends up with it.
  - Every photo is now decoded to pixels and re-encoded. A bitmap has nowhere to keep a tag, so the
    tags are gone by construction rather than by a filter that has to list them all correctly.
  - **This was already happening, and that was the problem.** It fell out of resizing, nothing said
    so, and the obvious optimisation — "we have the bytes, just copy the file" — is faster, sharper,
    deletes code, and silently puts every tag back. A test now fails that change with an explanation
    instead of letting it through review.
  - **Photos arriving in a backup are re-encoded too.** That path used to write whatever the file
    carried, so a backup made before the strip, or merged from another install, walked its EXIF in
    through the door nobody looks at. It costs a generation of JPEG quality on restore, which is
    better than an import path whose privacy depends on which version wrote the file.
  - **Photos are no longer stored sideways.** Cameras don't rotate pixels; they set an orientation
    tag, and `BitmapFactory` ignores it — so portrait photos were decoded as landscape and re-saved
    with the tag stripped, losing which way was up for good. The rotation is now applied to the
    pixels themselves. All eight EXIF orientations are handled, including the four mirrored ones
    that front cameras produce.
  - **And they're no longer randomly half-resolution.** The downscaler halved until it was under the
    limit, which overshoots: a 3200px photo landed on exactly 1600, a 3300px photo on 825. Two
    near-identical originals, one visibly blurry, no way to tell why.
- **Clinician-authored branching logic runs in a sandbox that fails closed.** A therapist can make a
  question conditional on an earlier answer. That is a small expression language, and a small
  expression language on a server is where the interesting bugs live, so it evaluates a fixed
  structure (`all` / `any` / `ref` / `op` / `value`) and never a string — no `eval`, no `Function`,
  nothing reachable from authored content to the host.
  - **A missing answer was treatable as present.** The guard tested `ref in answers`, and `in` walks
    the JavaScript prototype chain: `constructor`, `toString` and `__proto__` all reported as
    answered questions, so a predicate referencing one would branch on an object property rather
    than fail closed. Now `Object.hasOwn`. Found by auditing my own guard, not by a report.
  - **Nesting is capped at 16.** Unbounded recursion on operator-authored content is a stack
    overflow waiting for a deep enough form.
- **The documentation is now answerable to the tree.** Four times in one working session a document
  asserted something about this repository that was not true — design tokens described as "reserved"
  that existed nowhere, a path naming a file that was never written, two security findings left in
  the present tense as open defects for a day after both were fixed, with a proposed fix describing
  what the code already did. A test now checks the mechanically checkable half: every file path the
  docs name resolves, and every design token they name is either defined or **declared absent with a
  reason**, split into forbidden / superseded / unbuilt.
  - The forbidden list is the load-bearing part. `--success` and the `--trust-*` family must never
    exist: a green "all clear" is a clinical claim this product does not get to make, and the
    deployment guide's own argument is that a served page is lower-assurance no matter what it says
    about itself. Both the absence and the documentation of the absence are now asserted.
  - It immediately found six paths printed as `/home/user/daymark/...` in the deployment hardening
    guide — an address that exists on no reader's machine — and two token sketches presented as
    shipping CSS. Both corrected.
- **Companion server — three CVE bumps, and the Kotlin move they required.** `ktor 3.0.3 → 3.5.2`
  brings Netty `4.1.116.Final → 4.2.16.Final`, above the fixes for CVE-2025-58056 (bare-LF chunk
  terminator), CVE-2025-67735 (CRLF in the request URI) and CVE-2026-42587 (decompression bomb
  bypassing `maxAllocation`). Alongside it, `logback 1.5.12 → 1.5.13` (CVE-2024-12798 Janino EL
  injection → RCE, CVE-2024-12801 SaxEventRecorder SSRF) and `bcprov-jdk18on 1.79 → 1.85`
  (CVE-2026-0636 LDAP injection).
  - The Ktor bump could not be landed on its own: on Kotlin 2.1.0 it crashed the compiler with an
    internal error rather than failing to compile, because Ktor 3.5.2 is built with Kotlin 2.3.21
    and 2.3-era metadata is unreadable to a 2.1 reader. So the server moves to Kotlin 2.3.21 in the
    same change. Dependabot had been retrying this bump and failing since the ecosystem was added.
  - CI now asserts the **resolved** Netty version is at or above 4.2.13, because it arrives
    transitively and nothing in the repo could previously answer "which Netty are we on?" — the
    audit had to record its own premise as unverified. The check fails loudly if Netty is absent
    from the graph, so it cannot pass by finding nothing.
- **Companion deployment — the bundled reverse proxy is gone.** `companion/docker-compose.yml` now
  starts the application and nothing else, published on `127.0.0.1:8080` for whichever proxy you
  already run (Cosmos Cloud, Caddy, Traefik, nginx, a tunnel) to terminate TLS in front of. Bundling
  one meant shipping ACME, a certificate volume, a `:80`/`:443` binding and a privileged-port
  workaround for an audience that already has a proxy. What your proxy must do is now written down
  as a nine-point contract in `docs/COMPANION_DEPLOYMENT.md` §3.1, and worked configs for
  Caddy / nginx / Traefik moved to `docs/alternatives/` where their status as untested references is
  stated rather than implied.
  - `docker-compose.no-egress.yml` is the stronger opt-in topology for a **containerised** proxy: no
    published port, `internal: true` + `gateway_mode_ipv4: isolated`, so the app has no route off its
    network at all. It has to be opt-in because published ports **do not work** on Docker `internal:`
    networks ([moby/moby#36174](https://github.com/moby/moby/issues/36174)) — a host-side proxy could
    not reach the app that way. Both topologies are now booted and probed in CI.
  - `DAYMARK_TRUSTED_PROXIES` defaults to **empty** — trust nothing. Because setting it wrong fails
    *silently* (forwarded headers ignored, every lockout keying on the proxy, so eight bad tokens
    from one attacker lock out everybody), the server now logs one warning naming the address it
    actually saw, the first time a forwarded header arrives while the list is empty.
- **Companion — `/readyz`.** `/healthz` returning 200 never proved the server could accept a write;
  a read-only volume, a volume owned by the wrong UID and a full disk all leave HTTP working
  perfectly. `/readyz` probes the data directory and returns 503 when it cannot, and the container's
  own health check now uses it. Both endpoints stay unauthenticated and content-free — the reason
  for a failure goes to the operator's log, not to an anonymous caller.
- PIN moved to PBKDF2 (210k iterations, random salt) in AES-256 `EncryptedSharedPreferences`,
  with failed-attempt lockout/backoff; transparent upgrade from the old hash.
- Re-lock on background; `FLAG_SECURE` when locked; strong (Class 3) biometrics only.
- Hardened backup import (version gate, malformed-file guard).
- Release build: R8 minification, real release signing config, Gradle wrapper validation and
  Dependabot in CI.

## [0.1.0]

### Added
- Initial release: mood logging (5-level scale, activities, notes), calendar with mood tinting,
  statistics (trend, streaks, distribution, per-activity averages), daily reminder, PIN +
  biometric app lock, and JSON backup/restore.
