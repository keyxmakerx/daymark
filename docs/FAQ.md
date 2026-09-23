# Daymark FAQ

Short, plain answers to the questions people ask most. For step-by-step help, see the
[User Guide](USER_GUIDE.md). The full privacy statement is [PRIVACY.md](../PRIVACY.md).

---

## Is my data private?

Yes. Daymark has **no accounts, no servers, no analytics, no crash reporting, no ads and no
trackers**. The released app doesn't even have internet permission, so it can't send your data
anywhere. Everything you log stays on your device unless you export it yourself.

---

## Where is my data stored?

In Daymark's **private storage** on your phone, which Android keeps apart from other apps.

The database that holds your entries, notes, journal, goals, people and the rest is **encrypted by
Daymark, for everyone, from the first time you open it**. A journal from an older version is
converted once, automatically. The key is kept in your phone's secure hardware, so a copy of
Daymark's storage taken off the phone can't be read. Settings → *Your entries on this device* shows
whether this is true on your phone.

Not encrypted by the app: photos you attach, a few settings kept outside the database (your custom
mood names and colours, your crisis line, your sleep setup answers and latest sleep self-check
results), and any file you export.

If you set a **PIN**, it's never stored as it is, only as a one-way hash inside an encrypted store.

---

## Why do I get an "unknown app" warning when I install it?

Daymark isn't on the Google Play Store, so you install it by "sideloading" the APK from the
project's releases. Android shows an "unknown app" or Play Protect warning for **any** app installed
outside the Play Store. It's a normal precaution, not a sign that something is wrong, and you can
allow the install to continue.

Publishing a checksum with each release, so you can verify a download, is tracked in #{R4}.

---

## Will Daymark be on the Play Store or F-Droid?

**F-Droid** is the goal. Getting there needs a reproducible build and store metadata (#{R5}). For
now, the official way to get Daymark is the project's
[GitHub Releases](https://github.com/keyxmakerx/daymark/releases).

---

## How do I move my data to a new phone?

Use a backup:

1. On your **old** phone: **Settings → Export backup**, and save the file somewhere you can reach
   from the new phone (a USB transfer, an SD card, or a private file location).
2. Install Daymark on the **new** phone.
3. On the **new** phone: **Settings → Restore backup**, pick the file, and choose **Replace all**.

If you've already started logging on the new phone and want to keep those entries too, choose
**Merge** instead.

A backup doesn't carry your settings, suggestion choices, crisis line or sleep setup, so set those
again on the new phone.

---

## Are backups encrypted?

**No. Backups, CSV files and PDF reports are plain files, not encrypted.** A backup contains all
your entries in readable form, and once it leaves Daymark the app can't protect it. Please keep
backups somewhere private, and avoid shared or cloud folders unless that's a deliberate choice.

Encrypted backups and exports are tracked in #{R7}.

---

## What if I forget my PIN?

The PIN guards the screen. It isn't the key your journal is encrypted with, so forgetting it
doesn't damage your entries.

But there's **no reset and no backdoor**, and the lock screen offers no way past a forgotten PIN
except biometric unlock. If you can't unlock with biometrics either, the way back today is to
**reinstall Daymark**, which erases its storage, and then **restore your most recent backup**.
Anything you logged after that backup is lost.

That's why **regular backups matter**. A written-down recovery code, which would let you set a new
PIN, is designed and built but not switched on yet (#109).

---

## Is Daymark medical or clinical software?

**No.** Daymark is a personal **self-reflection and journaling tool**, not a medical device, and
nothing in it is medical advice, diagnosis or treatment. Its self-checks, sleep and breathing
features never tell you that you're fine or that you have a condition. It's meant to help you notice
your own patterns over time. If you're struggling with your mental health, please reach out to a
qualified professional or a local support service.

---

## How can I contribute?

Contributions are welcome. Daymark is open source (GPL-3.0); see
[CONTRIBUTING.md](../CONTRIBUTING.md) and the [Code of Conduct](../CODE_OF_CONDUCT.md). You can help
by reporting bugs, suggesting features, improving documentation or sending code. Daymark is an
independent project, so please read the clean-room rule in the contribution guide before you
submit: nothing is copied from another app.
