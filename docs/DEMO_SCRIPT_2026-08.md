# Showing Daymark to a therapist — the ten-minute walkthrough

Runs on any machine with Docker. It does not need the home server, a reverse proxy, or a domain.

## Start it (two minutes, one command)

```bash
docker run --rm -p 8080:8080 \
  -e DAYMARK_AUTH_TOKEN=demo-owner-token-change-me \
  -e DAYMARK_THERAPIST_AUTH=1 \
  -e DAYMARK_PUBLIC_BASE_URL=http://localhost:8080 \
  -e DAYMARK_COOKIE_SECURE=0 \
  -v daymark-demo:/data \
  ghcr.io/keyxmakerx/daymark-companion:SEE_CHAT_FOR_TAG
```

`DAYMARK_COOKIE_SECURE=0` is for plain-http localhost only — never a real deployment.
Open http://localhost:8080.

## The story to tell, in the order the screens tell it

1. **The first-run screen.** One question — what is this machine for — with Solo marked as what
   most people want and Practice marked as more work. Choose **Paired**. (This screen replaced ten
   buttons nobody could explain.)
2. **The phone app** (if the APK is on your phone: journal, moods, sleep, goals, self-checks, the
   safety plan, the Sky). Everything lives on the phone; the server only ever holds ciphertext.
3. **Owner console.** Generate owner keys → add the clinician with just a **name and an inbox
   token** (make one up — e.g. `inbox_demo_anything`). Note what you did NOT need: none of their
   keys exist yet.
4. **Connect** (the token from the command above) → **Share** tab → **Create invite link**.
   Point out the copy: the link is single-use, expiring, and carries no secret that can
   impersonate anyone.
5. **Open the link in a private window** — this is the therapist's side. One passphrase, one
   authenticator code (any TOTP app — scan the shown secret). That is ALL a clinician ever types.
6. **The finish screen** shows two key fingerprints and says to read them aloud — and explains
   why aloud: both pages are drawn by the same server, so matching screens prove nothing; a voice
   is the one channel the server cannot redraw.
7. **Back in the owner console → Published keys → Read the published keys.** Type the two
   fingerprints "as read aloud" → **record**. The Share tab now offers the real thing: choose
   exactly what to share (scores and bands only, free text stripped by default), an expiry, and
   **Seal & publish share** — sealed to the clinician's now-pinned key, signed by yours.
8. **The honesty tour**, if there is time: every screen states what the server can and cannot
   see; nothing shows a green tick or a score; revoking says plainly what it does and does not
   un-send; the admin console can audit everything and read nothing.

## If something breaks

The fallback demo needs no server at all: open the page, choose Solo, and open an exported
backup file from the app — the whole viewer runs in the browser and sends nothing.

## What to say when asked "is this ready?"

Honest answer: the personal app is real and daily-usable; the pairing ceremony works end to end
as of this week and has been driven by an automated browser, not yet by two humans; the practice
tier has a working control plane and a console, with the clinical workflows still to come.
