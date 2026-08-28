/*
 * THE WHOLE PAIRED CEREMONY, DRIVEN IN A REAL BROWSER.
 *
 * Owner: first-run fork -> Paired -> generate owner keys -> add a clinician with a NAME AND AN
 * INBOX TOKEN ONLY (their keys do not exist yet) -> connect with the bearer token -> mint the
 * invitation. Therapist, in a second browser context: open the link -> choose a reading
 * passphrase -> enrol a real TOTP (the code is computed here from the secret the page shows) ->
 * finish, fingerprints on screen. Owner again: read the published keys -> type both fingerprints
 * "as read aloud" -> record -> the Share tab flips from invite-only to the seal surface.
 *
 * WHY THIS FILE EXISTS WHEN 1263 UNIT TESTS ARE GREEN. Every bug this script has caught was
 * invisible to the node suite by construction: the unbound `fetch` that threw "Illegal invocation"
 * on every server call (tests inject their own fetch, so the default was never evaluated); the
 * invite page that served 200 with markup whose relative assets could not load (a test that asks
 * for the API never asks for the page); the pin circle where reaching the fetch-keys screen
 * required already having the keys. A browser is the only oracle for this class.
 *
 * RUN IT BY HAND — it is not wired into CI:
 *   1. build the web bundle, start the server with DAYMARK_THERAPIST_AUTH=1 and a known token
 *   2. npm i playwright (anywhere), CHROMIUM=<path> node e2e/pairing-ceremony.mjs
 * It talks to http://127.0.0.1:8099 and prints PIN RESULT / SHARE TAB / GRANTS TAB verdicts.
 * A fresh inbox token is generated per run because enrolment is insert-only per relationship —
 * re-running against the same relationship correctly refuses, which is the product working.
 */
import { chromium } from 'playwright'
import { createHmac } from 'node:crypto'

const BASE = 'http://127.0.0.1:8099'
const OUT = process.env.CEREMONY_SHOTS ?? '/tmp/ceremony-shots'
const TOKEN = 'smoke-token-0123456789'
const PASS = 'demo-reading-passphrase-01'

// RFC 6238 TOTP, SHA1, 30s, 6 digits — matches the server's Totp.kt.
function b32decode(s) {
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = 0, val = 0, out = []
  for (const c of s.replace(/=+$/,'').toUpperCase()) {
    const i = A.indexOf(c); if (i < 0) continue
    val = (val << 5) | i; bits += 5
    if (bits >= 8) { out.push((val >>> (bits - 8)) & 0xff); bits -= 8 }
  }
  return Buffer.from(out)
}
function totp(secretB32, t = Date.now()) {
  const key = b32decode(secretB32)
  const step = Math.floor(t / 1000 / 30)
  const msg = Buffer.alloc(8); msg.writeBigUInt64BE(BigInt(step))
  const h = createHmac('sha1', key).update(msg).digest()
  const o = h[h.length - 1] & 0xf
  const code = ((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).toString().padStart(6, '0')
  return code
}

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM ?? '/opt/pw-browsers/chromium-1194/chrome-linux/chrome', args: ['--no-sandbox'] })
const shot = async (page, name) => page.screenshot({ path: `${OUT}/c-${name}.png`, fullPage: true }).catch(()=>{})
const dump = async (page, label) => {
  const btns = await page.locator('button:visible').allInnerTexts()
  console.log(`[${label}] buttons: ${btns.map(b=>b.trim().replace(/\s+/g,' ')).filter(Boolean).slice(0,14).join(' | ')}`)
}
const errsOf = (page, tag, sink) => {
  page.on('pageerror', e => sink.push(`${tag} PAGEERROR ${String(e).slice(0,150)}`))
  page.on('console', m => { if (m.type()==='error') sink.push(`${tag} CONSOLE ${m.text().slice(0,150)}`) })
}
const errs = []

// ---------- OWNER ----------
const owner = await browser.newContext({ viewport: { width: 1360, height: 950 } })
const op = await owner.newPage(); errsOf(op, 'owner', errs)
await op.goto(BASE + '/', { waitUntil: 'networkidle' })
await shot(op, '01-first-run')

// First-run: pick Paired if the fork is showing.
const paired = op.getByRole('button', { name: /one clinician/i }).first()
if (await paired.isVisible().catch(()=>false)) { await paired.click(); await op.waitForTimeout(600) }
await shot(op, '02-after-shape')
await dump(op, 'owner after shape')

// Owner console unlock
await op.getByRole('button', { name: /generate owner keys/i }).click()
await op.waitForTimeout(400)
await op.locator('label:has-text("Display name") input').fill('Dr. Demo')
const INBOX = 'inbox_demo_' + Math.random().toString(36).slice(2, 14)
console.log('INBOX TOKEN:', INBOX)
await op.locator('label:has-text("Inbox token") input').fill(INBOX)
await op.getByRole('button', { name: /add clinician/i }).click()
await op.waitForTimeout(200)
await op.getByRole('button', { name: /enter console/i }).click()
await op.waitForTimeout(400)
await shot(op, '03-console')
await dump(op, 'console')

// Connect with the owner token
await op.locator('summary:has-text("Server connection")').click()
await op.locator('label:has-text("Owner access token") input').fill(TOKEN)
await op.getByRole('button', { name: /^connect$/i }).click()
await op.waitForTimeout(600)

// Share tab -> invite panel (pending clinician => invite only)
await op.getByRole('button', { name: /^share$/i }).click()
await op.waitForTimeout(400)
await shot(op, '04-share-pending')
await op.getByRole('button', { name: /create invite link/i }).click()
await op.waitForTimeout(2000)
await shot(op, '05a-after-mint-click')
const linkBox = op.locator('input[aria-label="Invite link"]')
if (!(await linkBox.isVisible().catch(()=>false))) {
  const t = await op.locator('body').innerText()
  console.log('MINT DID NOT RENDER A LINK. Tail of page:\n' + t.slice(-900))
  console.log('ERRORS SO FAR:', errs.join(' :: ') || 'none')
  await browser.close(); process.exit(2)
}
const link = await linkBox.inputValue()
console.log('INVITE LINK:', link)
await shot(op, '05-invite-minted')

// ---------- THERAPIST ----------
const ther = await browser.newContext({ viewport: { width: 1360, height: 950 } })
const tp = await ther.newPage(); errsOf(tp, 'ther', errs)
await tp.goto(link, { waitUntil: 'networkidle' })
await tp.waitForTimeout(600)
await shot(tp, '06-acceptance')
await dump(tp, 'acceptance')
const bodyText = () => tp.locator('body').innerText()

// Walk the ceremony generically: fill passphrase fields when they appear, read the TOTP secret,
// type the code, press the forward button. Loop up to 8 steps, logging each.
for (let step = 0; step < 8; step++) {
  const text = await bodyText()
  if (/signed in|portal|allowed|fingerprint/i.test(text) && /read.*aloud|fingerprint/i.test(text) && step > 2) break

  const passFields = tp.locator('input[type="password"]:visible')
  const nPass = await passFields.count()
  for (let i = 0; i < nPass; i++) {
    const cur = await passFields.nth(i).inputValue()
    if (!cur) await passFields.nth(i).fill(PASS)
  }

  // TOTP secret shown? The otpauth link's href carries it unspaced; the visible text is grouped.
  const otp = tp.locator('a[href^="otpauth:"]').first()
  if (await otp.isVisible().catch(()=>false)) {
    const href = await otp.getAttribute('href')
    const secret = (href.match(/secret=([A-Z2-7]+)/i) || [])[1]
    const codeInput = tp.locator('input:visible:not([type="password"])').last()
    if (secret && await codeInput.isVisible().catch(()=>false)) {
      await codeInput.fill(totp(secret))
      console.log(`[step ${step}] filled TOTP (secret ${secret.length} chars)`)
    }
  }

  // Press the most forward-looking visible button.
  const fwd = tp.locator('button:visible').filter({ hasText: /continue|accept|next|enrol|finish|confirm|use this link|create|begin|start/i }).first()
  if (await fwd.isVisible().catch(()=>false)) {
    const label = (await fwd.innerText()).trim()
    await fwd.click(); console.log(`[step ${step}] clicked "${label}"`)
    await tp.waitForTimeout(1500)
  } else {
    console.log(`[step ${step}] no forward button; stopping walk`)
    break
  }
  await shot(tp, `07-accept-step${step}`)
  await dump(tp, `accept step ${step}`)
}
await shot(tp, '08-accept-final')
// The two fingerprints, from the readonly boxes under their labels.
const fpBoxes = tp.locator('input[readonly], code, .fp')
const finalText = await bodyText()
const grab = async (label) => {
  const el = tp.locator(`text=${label}`).locator('xpath=following::input[1] | following::code[1] | following::div[1]').first()
  return null
}
// Simpler: the two highlighted value boxes are inputs or divs right after the label text lines.
// The fingerprints render as <p class="fingerprint"> chunk spans; the owner's check strips
// spacing, so the raw concatenated innerText is fine to type back.
const fpEls = tp.locator('p.fingerprint')
const encFp = (await fpEls.count()) > 0 ? (await fpEls.nth(0).innerText()).trim() : null
const signFp = (await fpEls.count()) > 1 ? (await fpEls.nth(1).innerText()).trim() : null
console.log('THERAPIST FPS:', JSON.stringify({ encFp, signFp }))
if (!encFp || !signFp) { console.log('FINAL TEXT:\n'+finalText.slice(0,1500)); await browser.close(); process.exit(3) }

// ---------- OWNER: published keys -> confirm -> pin ----------
await op.getByRole('button', { name: /published keys/i }).click()
await op.waitForTimeout(400)
await op.getByRole('button', { name: /read the published keys/i }).click()
await op.waitForTimeout(1200)
await shot(op, '09-fetched')

// Type both fingerprints exactly as "read aloud" and record them.
await op.locator('label:has-text("Encryption key fingerprint, as") input').fill(encFp)
await op.locator('label:has-text("Signing key fingerprint, as") input').fill(signFp)
await op.waitForTimeout(300)
await shot(op, '10-typed')
await op.getByRole('button', { name: /they read these out/i }).click()
await op.waitForTimeout(600)
await shot(op, '11-pinned')
const intakeText = await op.locator('body').innerText()
console.log('PIN RESULT:', intakeText.includes('Recorded in this browser') ? 'RECORDED ✔' : '(no record message — tail below)')
if (!intakeText.includes('Recorded in this browser')) console.log(intakeText.slice(-700))

// The payoff: the Share tab must now show the SEAL surface, not just the invite panel.
await op.getByRole('button', { name: /^share$/i }).click()
await op.waitForTimeout(700)
await shot(op, '12-share-unlocked')
const shareText = await op.locator('body').innerText()
const sealNow = /seal|bundle|share builder|select|what to share/i.test(shareText.split('Lock console').pop() || shareText)
console.log('SHARE TAB AFTER PIN:', sealNow ? 'SEAL SURFACE PRESENT ✔' : 'still invite-only — tail:\n' + shareText.slice(-600))
// Grants must also be reachable now.
await op.getByRole('button', { name: /^grants$/i }).click()
await op.waitForTimeout(500)
await shot(op, '13-grants')
const grantsText = await op.locator('body').innerText()
console.log('GRANTS TAB:', /has not published keys yet/i.test(grantsText) ? 'STILL GATED ✗' : 'OPEN ✔')

console.log('ERRORS:', errs.length ? '\n  '+errs.join('\n  ') : 'none')
await browser.close()
