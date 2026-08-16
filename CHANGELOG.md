# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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

### Security
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
  as a nine-point contract in `docs/COMPANION_DEPLOYMENT_HARDENING.md` §3, and worked configs for
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
