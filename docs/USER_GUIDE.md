# Daymark User Guide

Welcome to **Daymark** — a free, private mood tracker and journal for Android.

Daymark helps you notice how your days feel. You log a mood, tag what you did,
write a little about *why*, and over time watch patterns appear in the calendar
and stats. Everything stays **on your device** — there are no accounts, no
servers, and no tracking. Nothing leaves your phone unless *you* choose to
export it.

This guide walks through everything the app can do, one task at a time.

---

## Getting started

1. Install Daymark (see the FAQ if you get an "unknown app" warning).
2. Open the app — you'll land on the **Home** timeline, which starts empty.
3. Tap the **+** button to log your first mood.

That's it. There's no sign-up and nothing to configure before you begin. You can
explore the bottom navigation any time: **Home**, **Journal**, **Calendar**,
**Stats**, and **Settings**.

---

## Logging a mood

Your mood is the heart of Daymark. You can log as many entries per day as you
like.

**To log a mood:**

1. From **Home**, tap **+** to open the entry editor.
2. Pick how you feel from the 5-level scale: **Awful, Bad, Meh, Good, Rad**.
3. (Optional) Adjust the **date and time** if you're logging for an earlier moment.
4. (Optional) Tag any **activities** you did (see below).
5. (Optional) Write a short **note** under *"Why do you feel this way?"*.
6. (Optional) **Add a photo** (see below).
7. Tap **Save**.

Your entry appears on the Home timeline, newest first.

### Adding a photo

You can attach one photo to a mood entry — a sunset, a meal, whatever marked the
moment.

1. In the entry editor, tap **Add photo**.
2. Pick an image with the **Android Photo Picker**. Daymark doesn't need any
   storage or media permission for this — the picker hands over just the one
   image you choose.
3. A thumbnail appears with a remove button; tap it to drop the photo.

The photo is shrunk down and copied into Daymark's private storage, so it stays
on your device. Thumbnails show on the Home timeline and on a day's detail view,
and photos are included when you export a JSON backup.

### Deleting an entry from the timeline

To remove an entry quickly, **swipe it left** on the Home timeline. A 5-second
**Undo** snackbar appears — tap **Undo** to bring the entry back (with its
activities intact). If you don't, the entry, and any photo on it, is removed.

### Mood note vs. Journal — what's the difference?

This trips people up, so here's the simple version:

- A **mood note** is the *"why"* attached to a single mood entry. It answers
  *"Why do I feel this way right now?"* — a sentence or two of context for that
  moment. It lives with the mood and shows up in your CSV export alongside the
  mood and activities.
- The **Journal** is a *separate*, free-form diary. Journal entries have a
  **title** and a longer **body**, and they are **not** tied to a mood. Use the
  journal for longer reflections, gratitude lists, to-dos, or anything that
  doesn't fit a single mood note.

Think of it this way: the **mood note** is a caption; the **Journal** is the
diary.

---

## Using activities

Activities are tags for what you were doing or what was going on — like
*Exercise*, *Work*, *Friends*, or *Sleep*. Over time they power your stats (so
you can see, for example, which activities tend to go with your better days).

**To tag activities on an entry:**

1. In the entry editor, find **"What have you been up to?"**.
2. Tap any activity chips that apply — tap again to unselect.
3. Save the entry as usual.

**To manage your activities:**

1. Go to **Settings → Manage activities**.
2. Add, rename, reorder, or archive activities here.

Archived activities stay attached to your past entries but won't clutter the
list when you log something new.

---

## The Journal

The journal is your free-form diary, kept separate from mood notes.

**To write a journal entry:**

1. Open the **Journal** tab.
2. Tap **+** to start a new entry.
3. Add a **title** and write your **body** text.
4. Save.

**To find an old entry:**

- Use the **search** on the Journal screen to look through your journal entries
  by their text.

---

## Calendar & Year in Pixels

The **Calendar** shows your moods at a glance.

- **Month view:** each day is tinted with the color of its mood, so a glance
  shows you how the month is going. Warmer reddish tones lean toward tougher
  days; greener tones lean toward better ones.
- **Year in Pixels:** from the calendar, jump to the year view to see all 365
  days as a grid of colored squares — a beautiful, big-picture look at your year.

Days with no entry simply stay blank.

---

## Reading your Stats

The **Stats** tab turns your entries into a few friendly summaries:

- **Average mood** — your overall mood score.
- **Current streak** and **Longest streak** — how many days in a row you've
  logged.
- **Mood over the last 30 days** — a trend line of how things have been going.
- **Mood distribution** — how often each mood level shows up.
- **Average mood by activity** — which activities tend to accompany your better
  (or harder) days.

You'll need a few entries logged before the stats have much to show.

---

## Setting Goals

Goals are simple weekly habit targets — for example, *"Exercise 5× a week."*

**To create a goal:**

1. Go to **Settings → Goals** (or the **Goals** screen).
2. Tap **+** to add a goal.
3. Give it a **title**, optionally link it to an **activity**, and set a
   **target per week**.
4. Save.

If you link a goal to an activity, Daymark counts how many times you've tagged
that activity this week and shows your **progress** toward the target. Goals you
no longer want can be archived.

---

## Reminders

Gentle, optional notifications can nudge you to check in. You can set up **as
many reminders as you like** — for example a morning and an evening nudge — each
with its own time and label.

**To add a reminder:**

1. Go to **Settings → Reminders**.
2. Tap **Add reminder** (on Android 13+ you'll be asked to allow notifications
   the first time).
3. Pick a **time**, and optionally give it a **label** (e.g. "Morning check-in").

Each reminder has its own **on/off toggle**, and you can edit its time or label
or **delete** it at any time. Daymark aims to deliver each one at the exact time
you picked, and re-schedules them all automatically after you restart your phone.

**Quick-log from a notification:**

- Tapping a reminder notification — or its **Log now** action — opens a fresh
  mood entry straight away, so you can check in without hunting for the app.

> If you used a single daily reminder in an older version, it's moved into this
> list automatically when you upgrade.

---

## App lock (PIN + biometrics)

If you'd like to keep your entries private from anyone else who picks up your
phone, you can lock the app.

**To set a PIN:**

1. Go to **Settings → App lock (PIN)** and switch it on.
2. Enter a **4–8 digit PIN** and confirm it.

**To also unlock with biometrics:**

- With a PIN set, turn on **Unlock with biometrics**. Daymark will offer your
  fingerprint or face unlock when you open the app, and fall back to the PIN if
  that doesn't work.

**Auto-lock timeout:**

- With the PIN lock on, an **Auto-lock** option appears in Settings. By default
  Daymark re-locks **immediately** every time it goes to the background. If you'd
  rather not re-enter your PIN after briefly switching apps, choose a grace
  period of **1, 5, or 15 minutes** — Daymark only re-locks once that much time
  has passed in the background.

**How the lock behaves:**

- When locked, you'll see a *"Daymark is locked"* screen and must enter your PIN
  (or use biometrics) to get in.
- **Lockout after wrong guesses:** you get **5 free attempts**. After that, the
  app makes you wait before trying again, and the wait grows each time you keep
  missing — up to **5 minutes**. The screen shows a countdown so you know when
  you can try again. A correct unlock resets the counter.
- **Hidden from recents:** while the app is locked, its contents are hidden from
  the app-switcher / recent-apps thumbnail (a screen flag called
  `FLAG_SECURE`), so a quick peek can't reveal your data.

> **Important:** there is no "forgot PIN" reset and no backdoor — that's the
> point of a private, offline app. See the FAQ for what to do if you forget it.

---

## Backing up & restoring

Because Daymark stores everything locally and uses no cloud, **backups are how
you keep your data safe** and how you move to a new phone. You decide where each
backup file goes using your phone's file picker.

### Export a backup (JSON)

1. Go to **Settings → Export backup**.
2. Choose where to save the file.

This writes a single JSON file containing **everything** — moods, notes,
activities, journal entries, goals, your reminders, and any **photos** you've
attached (embedded in the file, so it stays one portable backup).

### Restore a backup (Replace vs. Merge)

1. Go to **Settings → Restore backup**.
2. Pick a Daymark JSON file.
3. Choose how to bring it in:
   - **Replace all** — wipes your current data first, then loads the backup.
     Use this when restoring onto a fresh install or a new phone.
   - **Merge** — keeps what you already have and **adds** the backup's entries
     alongside it. Use this to combine data from two devices without losing
     anything. Merged entries are given fresh internal IDs so nothing collides.

> A backup made by a **newer** version of Daymark can't be restored into an
> older one — update the app first.

### Export as CSV

1. Go to **Settings → Export as CSV**.
2. Choose where to save the file.

The CSV is a spreadsheet-friendly list of every mood entry with its **date,
time, mood, activities, and note** — handy for opening in a spreadsheet app.
(CSV is for viewing/analysis; use the **JSON backup** to actually restore your
data.)

> **Please keep backups safe.** Backup and CSV files are **plaintext** — they
> are not encrypted. Once a file leaves Daymark it's no longer protected by the
> app, so store it somewhere private. See the FAQ for more.

---

## The home-screen widget

Daymark includes a quick-log widget so you can record a mood without even
opening the app.

**To add it:**

1. Long-press an empty spot on your home screen.
2. Choose **Widgets**, find **Daymark**, and drag the widget out.

**To use it:**

- The widget asks *"How are you?"* and shows the five moods. **Tap a mood** and
  Daymark opens a new entry with that mood already selected — just add any
  details and save.

---

## Light & dark theme

Daymark's warm "modern paper" look comes in both **light** and **dark**, and
follows your **system** light/dark setting automatically — so it matches the
rest of your phone.

- On **Android 12+**, you can turn on **Dynamic color** in **Settings** to tint
  the app with colors drawn from your wallpaper.

---

## A note on privacy

Daymark is **offline and private by design**. Your moods, notes, journal,
activities, and goals live only in the app's private storage on your device. The
app has no internet permission in its core and makes no network connections.
The **only** way your data leaves your phone is if **you** export a backup.

For the full details, see the project's privacy statement.
