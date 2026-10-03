# Daymark — tool provenance and clinical labelling

Every tool a person can be handed — a questionnaire, a task, an exercise, a guided flow, an
assignment — declares what it clinically is, and the label, not the author, carries the trust. This
page is the rule. The code: `companion/web/src/lib/instruments/types.ts` (the schema), `validate.ts`
(the honesty gate that enforces it), `provenance.ts` (the badge text and disclaimers) and
`ui/ProvenanceBadge.svelte` in the Companion; `ui/components/ProvenanceBadge.kt` in the phone app.

## Why provenance

Daymark should be clinically useful without pretending to be a clinician. The answer is neither "make
everything non-diagnostic" nor "let anyone publish anything": a credentialed clinician can use a
validated instrument, anyone can write their own exercise, and the person always sees, up front,
which one they are using. That is the concrete answer to "someone like me shouldn't be handing out
clinical screeners": you can only publish **Custom**, and Custom says so plainly.

## The three tiers

| Tier | Badge | Meaning | Example |
|---|---|---|---|
| **Validated** | the word alone | A published instrument used faithfully — exact wording, scoring and banding — with a citation and a licence that permits the use. | PHQ-9, GAD-7, WHO-5 in the phone app |
| **Adapted** | ◐ | Built on an evidence-based method but changed (shortened, reworded, recombined). Names the method it draws from. | the phone app's safety plan ([INSTRUMENTS.md](INSTRUMENTS.md)) |
| **Custom** | ✎ | Self-authored. Not validated, not clinical. Always shows the disclaimer. | every tool in the Companion catalogue today |

Validated carries no mark, on the web or the phone. The marks on the other tiers say how a tool
departs from a published instrument (◐ part of one, ✎ none); a validated tool departs from nothing,
and any mark there would read as a verdict, a tick as a pass, which this product never draws
(`instruments/provenance.ts`, `ui/components/ProvenanceBadge.kt`; #278). Custom is the one tier with a filled, amber badge, because
it is the one real caveat ([COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md) §2.3.4). The phone
app calls the third tier **Original** and prints the Custom disclaimer beside it in the PDF report.

## The disclaimer

Every **Custom** tool opens with, verbatim:

> **Custom-made — a personal reflection tool, not a validated or clinical instrument. Not for
> diagnosis.**

**Adapted** tools show *"Adapted from &lt;method&gt; — not the original validated instrument."*
**Validated** tools show their source instead of a warning. The strings are constants in
`provenance.ts` (and, for the phone's PDF report, `PdfReportGenerator.kt`), pinned verbatim by
`components/invariants.tree.test.ts`.

## Rules (enforced)

The honesty gate (`validate.ts`) runs when a definition loads and in CI (`instruments.test.ts`); a
definition that fails any check does not load.

1. **Provenance is required.** A definition without a valid tier is refused.
2. **Custom tools cannot pose as clinical.** Every tool is non-diagnostic by construction —
   `nonDiagnostic` and `noScreeningFlag` must be literally true, no band label may use verdict
   language ("screened positive", "meets criteria"), and every scale's framing must say it is not a
   diagnosis — and a Custom tool's introduction must carry the non-diagnostic disclaimer. The runner
   shows the Custom disclaimer above the first item.
3. **Validated means faithful.** A Validated tool must name its source and an `INSTRUMENTS.md` ledger
   entry (`ledgerRef`); an Adapted tool must name its method and a ledger entry. Not built: checking a
   Validated tool's items, scoring and bands against the registered instrument, and downgrading drift
   to Adapted — no Validated tool exists in the Companion yet: #255.
4. **No self-harm item slot** in any tier's shareable output. The gate refuses any item whose id,
   prompt, body or option labels refer to self-harm or suicide.
5. **The label is fixed per version.** Changing a Validated or Adapted tool's wording or scoring
   forces a re-classification. Not built: nothing publishes a changed tool yet, so nothing checks
   this: #255.

## The provenance field (schema)

As built in `types.ts`:

```ts
provenance: {
  tier: 'validated' | 'adapted' | 'custom'  // required
  source?: string      // required for validated: the published instrument it reproduces
  basedOn?: string     // required for adapted: the method it draws from
  authorRole?: string  // informational: the publisher's role
}
// On the definition beside it: license (required for every tool) and ledgerRef
// (an INSTRUMENTS.md anchor, required for validated and adapted).
```

## How it renders

- **The person using the tool:** the badge in the tool's header, and the tier's disclaimer or source
  above the first item.
- **The clinician:** the same badge in the assignment composer and beside every instrument score in
  Today, the calendar and the client record (a row that did not come from an instrument says where
  it came from instead), so they know what produced a score before acting on it.
- **Shares and exports:** no score is shown without its label. The clinician's views look each shared
  result's tier up in the catalogue, and mark a result from an instrument they do not know as unknown
  rather than guess; the phone's PDF report lists every tool with its tier and prints the Custom
  disclaimer. The phone's three check-ins (PHQ-9, GAD-7, WHO-5) are known by name for display only,
  never run, assigned or published in the Companion (#264). Not built: #340; until then a shared
  result from one of them is marked unknown.

## Who may publish what

Validated and Adapted tools may be published only by credentialed clinical roles, and only against the
vetted catalogue; Custom tools by anyone, always labelled and disclaimed
([COMPANION_ACCESS_CONTROL.md](COMPANION_ACCESS_CONTROL.md)). Not built: the Companion's tool builder
lets an author pick any tier and runs the same gate live, but its **Publish…** button only downloads
the definition as JSON — nothing publishes to a catalogue, assigns a built tool, or checks a role:
#255.

## Relationship to the honesty gate and the ledger

- The **honesty gate** (non-diagnostic wording, no self-harm slot, no cut-offs, licence-clean) is the
  enforcement engine for the rules above.
- The **ledger** — [INSTRUMENTS.md](INSTRUMENTS.md) for the project, `companion/INSTRUMENTS.md` for
  what the Companion ships — is the source of truth for what may be tagged Validated, and under what
  licence.
- The **tool builder** writes the `provenance` field at authoring time, so the gate checks it before
  anything leaves the page.
