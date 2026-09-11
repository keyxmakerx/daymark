/*
 * THE PAIRING CEREMONY, DRIVEN IN TWO REAL BROWSERS against a real server.
 *
 * WHY THIS EXISTS. Every property of the pairing screens is covered by node tests over the
 * modules beneath them, and none of those tests renders a component. This repo's recurring
 * lesson is that a green check on a screen nobody has run is a claim rather than a check — so
 * this drives the whole thing: the owner mints an invitation, makes a code, and reads it off
 * the screen; a second browser context opens the link, types that code IN LOWER CASE (which is
 * the canonicalisation working, end to end), sends; the owner collects, sees a name that could
 * only have come out of an envelope sealed under the code, and approves; the therapist's poll
 * lands and the authenticator step appears.
 *
 * It found two things the suites could not: a page lede still promising a read-aloud the
 * ceremony no longer performs, and a heading printed twice.
 *
 * HOW TO RUN IT.
 *   cd companion/server && ./gradlew shadowJar
 *   cd companion/web && pnpm build
 *   DAYMARK_BIND_ADDR=127.0.0.1 DAYMARK_PORT=8101 DAYMARK_DATA_DIR=<empty dir> \
 *     DAYMARK_WEB_DIR=companion/web/dist DAYMARK_AUTH_TOKEN=owner-token-e2e \
 *     DAYMARK_THERAPIST_AUTH=1 DAYMARK_COOKIE_SECURE=false \
 *     java -jar companion/server/build/libs/daymark-companion.jar
 *   BASE=http://127.0.0.1:8101 SHOTS=<dir> node companion/web/e2e/pairing-live.mjs
 *
 * The data directory must be EMPTY: a live invitation from an earlier run is restored by the
 * screen (correctly), and the script then has no link to hand the therapist.
 *
 * TWO THINGS THAT COST THIS SCRIPT A RUN EACH, written down so they do not cost another:
 *  - The orientation's route cards stay on the page above the console, and one of them contains
 *    the word "Connect". An unscoped search for a button clicks the CARD and navigates away, so
 *    every console interaction here is scoped to `section.console`.
 *  - `document.body.innerText` from the top is mostly furniture. Read the screen under test.
 */
import { chromium } from 'playwright-core'
const BASE = process.env.BASE || 'http://127.0.0.1:8100'
const SHOTS = process.env.SHOTS
const log = (...a) => console.log(...a)

/*
 * Scoped by default to the console section once it exists. The orientation's route cards stay on
 * the page above the console, and one of them is "Connect to your sync server" — so an unscoped
 * search for a button containing "Connect" finds the CARD, clicks it, and navigates away from the
 * screen under test. That is a property of the page, not of a selector, and it cost this script a
 * run to notice.
 */
const byText = async (page, text, tag = 'button', scope = null) => {
  const el = await page.evaluateHandle(([t, g, sc]) => {
    const root = sc ? document.querySelector(sc) : document
    if (!root) return null
    const nodes = [...root.querySelectorAll(g)]
    return nodes.find((n) => (n.textContent || '').trim().includes(t)) || null
  }, [text, tag, scope])
  const e = el.asElement()
  if (!e) throw new Error(`no ${tag} containing "${text}"${scope ? ` inside ${scope}` : ''}`)
  return e
}
const click = async (page, text, tag = 'button', scope = null) => { const e = await byText(page, text, tag, scope); await e.click(); await page.waitForTimeout(250) }
const fill = async (page, labelText, value, scope = null) => {
  const ok = await page.evaluate(([t, v, sc]) => {
    const root = sc ? document.querySelector(sc) : document
    if (!root) return false
    const labels = [...root.querySelectorAll('label')]
    const lab = labels.find((l) => (l.textContent || '').includes(t))
    let input = lab?.querySelector('input')
    if (!input && lab?.htmlFor) input = document.getElementById(lab.htmlFor)
    if (!input) return false
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
    setter.call(input, v)
    input.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  }, [labelText, value, scope])
  if (!ok) throw new Error(`no input for label "${labelText}"`)
  await page.waitForTimeout(120)
}
/*
 * The SCREEN under test, not the top of the document. The orientation's route cards and the
 * lower-assurance banner sit above both screens, so reading body.innerText from the top and
 * truncating shows the furniture and misses the thing being asserted — which cost this script a
 * run and produced a failure that read like a product bug.
 */
const visible = (page, scope = null) =>
  page.evaluate((sc) => {
    const el = (sc && document.querySelector(sc)) || document.querySelector('section.console .pairing') || document.body
    return el.innerText.replace(/\n{2,}/g, '\n').slice(0, 1600)
  }, scope)
const therapistView = (page) =>
  page.evaluate(() => {
    const cards = [...document.querySelectorAll('article, section, .card, main')]
    const el = cards.find((c) => /code they gave you|Waiting for them|authenticator|no longer open/i.test(c.innerText)) || document.body
    return el.innerText.replace(/\n{2,}/g, '\n').slice(0, 1200)
  })

const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome', args: ['--no-sandbox'] })
const ctxOwner = await browser.newContext({ viewport: { width: 1280, height: 900 } })
const ctxTher = await browser.newContext({ viewport: { width: 1280, height: 900 } })
// Answer the first-run question and the orientation the way a returning owner already has;
// this run is about the pairing screens, not about first-run.
for (const c of [ctxOwner]) {
  await c.addInitScript(() => {
    try {
      localStorage.setItem('daymark.setup.shape.v1', '1:paired')
      localStorage.setItem('daymark.orientation.dismissed.v1', '1')
    } catch {}
  })
}
const owner = await ctxOwner.newPage()
const ther = await ctxTher.newPage()
const errors = []
for (const [name, p] of [['owner', owner], ['therapist', ther]]) {
  p.on('pageerror', (e) => errors.push(`${name} pageerror: ${e.message}`))
  p.on('console', (m) => { if (m.type() === 'error') errors.push(`${name} console: ${m.text().slice(0, 200)}`) })
}

try {
  // ---- owner: reach the console -----------------------------------------------------------
  await owner.goto(BASE + '/', { waitUntil: 'networkidle' })
  await owner.waitForTimeout(600)
  log('STEP landing:', (await visible(owner)).slice(0, 200).replace(/\n/g, ' | '))
  await click(owner, 'Owner console', 'button, a[href], [role=button]')
  await owner.waitForTimeout(600)
  await click(owner, 'Generate owner keys')
  await fill(owner, 'Display name', 'Dr Example')
  await fill(owner, 'Inbox token', 'inbox-token-for-the-end-to-end-run-0001')
  await click(owner, 'Add clinician')
  await click(owner, 'Enter console')
  await owner.waitForTimeout(400)
  log('STEP console:', (await visible(owner)).slice(0, 220).replace(/\n/g, ' | '))

  // connect
  const details = await owner.$('details.conn')
  if (details) await owner.evaluate(() => { document.querySelector('details.conn').open = true })
  await fill(owner, 'Owner access token', 'owner-token-e2e', 'section.console')
  await click(owner, 'Connect', 'button', 'section.console')
  await owner.waitForTimeout(600)

  // ---- owner: the pairing panel -------------------------------------------------------------
  await click(owner, 'Share', 'button', 'section.console')
  await owner.waitForTimeout(300)
  if (SHOTS) await owner.screenshot({ path: `${SHOTS}/01-owner-share.png`, fullPage: true })
  await click(owner, 'Set up an invitation', 'button', 'section.console')
  await owner.waitForTimeout(800)
  // `restore` may already have found a live invitation from an earlier run on this server, in
  // which case the screen opens at "Make a code" and there is nothing to create. That is the
  // product behaving correctly, so the script follows it rather than insisting.
  try {
    await click(owner, 'Create an invitation', 'button', 'section.console')
    await owner.waitForTimeout(900)
  } catch {
    log('STEP: an invitation was already live for this relationship')
  }
  console.log('PANEL:', (await owner.evaluate(() => document.querySelector('section.console .pairing')?.innerText ?? '(no panel)')).replace(/\n/g, ' | ').slice(0, 700))
  const link = await owner.evaluate(() => document.querySelector('input[aria-label="Invitation link"]')?.value ?? '')
  log('STEP link:', link ? link.replace(/s=[^&]+/, 's=REDACTED') : '(none)')
  await click(owner, 'Make a code', 'button', 'section.console')
  await owner.waitForTimeout(900)
  const code = await owner.evaluate(() => document.querySelector('output.code')?.textContent?.trim() ?? '')
  log('STEP code shown:', code)
  if (SHOTS) await owner.screenshot({ path: `${SHOTS}/02-owner-code.png`, fullPage: true })
  if (!/^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$/.test(code)) {
    throw new Error(`the code on screen is not a pairing code: ${JSON.stringify(code)}`)
  }
  if (!link) throw new Error('no invitation link rendered')

  // ---- therapist: type the code -------------------------------------------------------------
  await ther.goto(link, { waitUntil: 'networkidle' })
  await ther.waitForTimeout(500)
  log('STEP therapist page:', (await therapistView(ther)).slice(0, 300).replace(/\n/g, ' | '))
  if (SHOTS) await ther.screenshot({ path: `${SHOTS}/03-therapist-form.png`, fullPage: true })
  await fill(ther, 'The code they gave you', code.toLowerCase())
  await fill(ther, 'Your name, as they should see it', 'Sam Rivera')
  await fill(ther, 'Reading passphrase', 'seven brass lanterns humming')
  await fill(ther, 'Type it again', 'seven brass lanterns humming')
  await click(ther, 'Send this to them')
  await ther.waitForTimeout(3500)
  const waitingText = await therapistView(ther)
  log('STEP therapist after send:', waitingText.slice(0, 300).replace(/\n/g, ' | '))
  if (SHOTS) await ther.screenshot({ path: `${SHOTS}/04-therapist-waiting.png`, fullPage: true })

  // ---- owner: collect, see the offer, approve -----------------------------------------------
  await click(owner, 'Check for a reply', 'button', 'section.console')
  await owner.waitForTimeout(1500)
  const answered = await visible(owner)
  log('STEP owner after check:', answered.slice(0, 500).replace(/\n/g, ' | '))
  if (SHOTS) await owner.screenshot({ path: `${SHOTS}/05-owner-answered.png`, fullPage: true })
  if (!answered.includes('Sam Rivera')) throw new Error('the owner did not see the therapist name from the offer')
  await click(owner, 'Approve', 'button', 'section.console')
  await owner.waitForTimeout(1500)
  const approved = await visible(owner)
  log('STEP owner after approve:', approved.slice(0, 320).replace(/\n/g, ' | '))
  if (SHOTS) await owner.screenshot({ path: `${SHOTS}/06-owner-approved.png`, fullPage: true })

  // ---- therapist: the poll lands and the authenticator appears -------------------------------
  await ther.waitForTimeout(48000)
  const done = await therapistView(ther)
  log('STEP therapist after approval:', done.slice(0, 400).replace(/\n/g, ' | '))
  if (SHOTS) await ther.screenshot({ path: `${SHOTS}/07-therapist-enrolled.png`, fullPage: true })
  console.log(done.includes('authenticator') ? 'RESULT: ceremony reached the authenticator step' : 'RESULT: did NOT reach the authenticator step')
} catch (e) {
  console.log('FAILED:', e.message)
  if (SHOTS) { await owner.screenshot({ path: `${SHOTS}/err-owner.png`, fullPage: true }).catch(() => {}); await ther.screenshot({ path: `${SHOTS}/err-therapist.png`, fullPage: true }).catch(() => {}) }
  console.log('owner sees:', (await visible(owner)).slice(0, 700))
  console.log('therapist sees:', (await therapistView(ther)).slice(0, 400))
  console.log('owner clickables:', JSON.stringify(await owner.evaluate(() => [...document.querySelectorAll('button,a,[role=button],summary')].map((n) => (n.textContent||'').trim().replace(/\s+/g,' ').slice(0,44)).filter(Boolean).slice(0, 30))))
} finally {
  if (errors.length) console.log('PAGE ERRORS:\n' + errors.slice(0, 10).join('\n'))
  else console.log('no page errors')
  await browser.close()
}
