# Daymark FAQ

Short, plain answers to the questions people ask most. For step-by-step help,
see the [User Guide](USER_GUIDE.md).

---

## Is my data private?

Yes. Daymark has **no accounts, no servers, no analytics, no crash reporting, no
ads, and no trackers**. The core app doesn't even have internet permission, so
it can't send your data anywhere. Everything you log stays on your device.

---

## Where is my data stored?

Inside the app's **private storage** on your phone, in a local database that
Android keeps isolated from other apps. Your moods, mood notes, activities,
journal entries, goals, and settings all live there.

If you set a **PIN**, it's never stored as plain text — only as a securely
hashed value (PBKDF2) inside an AES-256 encrypted preferences store.

---

## Why do I get an "unknown app" warning when I install it?

Because Daymark isn't on the Google Play Store (yet), you install it by
"sideloading" the APK from the project's releases. Android shows an "unknown
app" or Play Protect warning for **any** app installed outside the Play Store —
it's a normal precaution, not a sign that something is wrong. You can allow the
install to continue.

To make this smoother over time, the project plans to publish each release's
**SHA-256 checksum** so you can verify a download, and to use a stable signing
identity.

---

## Will Daymark be on the Play Store or F-Droid?

**F-Droid** is the goal — getting there involves verifying that all
dependencies are free and open-source, providing a reproducible build, and
adding store metadata. It's on the roadmap. For now, the official way to get
Daymark is the project's **GitHub Releases**.

---

## How do I move my data to a new phone?

Use a backup:

1. On your **old** phone: **Settings → Export backup**, and save the JSON file
   somewhere you can reach from the new phone (e.g. a USB transfer, SD card, or
   a private file location).
2. Install Daymark on the **new** phone.
3. On the **new** phone: **Settings → Restore backup**, pick the JSON file, and
   choose **Replace all**.

If you've already started logging on the new phone and want to keep those
entries too, choose **Merge** instead of Replace.

---

## Are backups encrypted?

**No — backups and CSV exports are plaintext, not encrypted.** We want to be
honest about this. A backup file contains all your entries in readable form, and
once it leaves Daymark it's no longer protected by the app. **Please store
backups somewhere safe and treat them as sensitive** (avoid leaving them in
shared or cloud folders unless that's a deliberate choice).

Encrypted backups are on the roadmap.

---

## What if I forget my PIN?

There is **no PIN reset and no backdoor** — that's a deliberate part of being a
private, offline app with no account to recover through. If you forget your PIN
and can't unlock with biometrics, the only option is to **reinstall the app**,
which clears its data and starts fresh.

This is exactly why **regular backups matter**: if you have a recent JSON
backup, you can reinstall and **Restore** it to get your entries back. Keep one
somewhere safe.

---

## Is Daymark medical or clinical software?

**No.** Daymark is a personal **self-reflection and journaling tool**, not a
medical device, and nothing in it is medical advice, diagnosis, or treatment.
It's meant to help you notice your own patterns over time. If you're struggling
with your mental health, please reach out to a qualified professional or a local
support service.

---

## How can I contribute?

Contributions are welcome. The project is open source (GPL-3.0) and lives on its
GitHub repository — see its `CONTRIBUTING.md` and Code of Conduct to get
started. You can help by reporting bugs, suggesting features, improving
documentation, or sending code. Because Daymark is an independent, clean-room
project, please read its contribution rules (especially around UI and assets)
before submitting.
