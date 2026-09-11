---
name: next
description: Take the next item of work off the backlog and do it alone — pick a ready issue, carry it out, check it, push it to a branch, and report back in plain English. Use when asked what is next, told to get on with it, or woken on a schedule with nobody watching.
argument-hint: [optional issue number]
---

You are working unattended. The person who owns this project is not a programmer and is probably
not reading this as it happens. Everything below assumes that.

## The one rule that matters

**One issue per session. Then stop.**

Not one issue and then a quick look at something else. Not "while I was in there I also fixed…".
Every extra turn re-sends this whole conversation to the model, so a session that wanders costs
many times what a session that finishes costs. Finishing and stopping is the single largest thing
you control about this project's running cost.

## Pick the work

If the invocation named an issue number, use it. Otherwise list open issues in `keyxmakerx/daymark`
labelled `claude-ready`, and take the **lowest-numbered** one that has no branch already pushed for
it. If two look equally ready, the smaller one wins — a finished small thing beats a stalled large
one.

Nothing labelled `claude-ready`? Then the backlog is empty. Say so in one line and stop. Do not
invent work. Do not go looking for something to improve. An empty queue is a correct outcome.

## Carry it out

Read the issue completely, including the "Watch out" section — it exists because someone already
knew where this goes wrong.

Work on a branch named `claude/issue-<number>-<short-slug>`. Create it from the current `main`:

```
git fetch origin main && git checkout -B claude/issue-<n>-<slug> origin/main
```

Delegate the noisy parts rather than doing them in this conversation — that is what they are for,
and their output would otherwise fill this session's context and end it early:

- `/ux` when the issue involves what a screen looks like or says.
- `/challenge` when the issue asks you to establish something, especially about security.
- `/walkthrough` when a person would have to *look* at the result to know it worked.
- `/tests` or the `verifier` agent for the suites. Never run them inline.

## Check it before you believe it

Run `/tests`. A failure stops the work — fix it, or push nothing and report what broke.

The house rule applies to your own work too: a passing test proves nothing unless it would fail
when the thing it tests is broken. If you wrote a test for something load-bearing, break the thing
on purpose and confirm that exact test reddens, then restore it.

## Push, and stop there

```
git push -u origin claude/issue-<n>-<slug>
```

**Never push to `main`. Never open a pull request. Never merge anything.** The maintainer merges
their own work; that is deliberate and is not a step you were forgotten from.

## Report

Comment on the issue with:

- What now works that did not before, in plain words. No file paths, no commit hashes, no jargon —
  write it for someone who has not opened the repository.
- The branch name, so they can find it.
- What you did not finish, and why.
- Anything you decided that they might have decided differently. Be specific enough to overrule you.

Then **remove the `claude-ready` label** from the issue, and leave the issue open. The label coming
off is how the queue drains: it marks the item as handled and stops the next session picking it up
again. The maintainer closes the issue once they have looked, or puts the label back if they want
more work on it.

Remove the label whether you finished or stopped early. An item you are blocked on belongs to them
now, and spinning on it would be the one way this loop wastes real money.

## Then hand off, or go quiet

Count the open issues still labelled `claude-ready`.

**If any remain**, start a fresh session to take the next one. Use `create_trigger` with
`run_once_at` about five minutes out and `create_new_session_on_fire: true`, and give it a prompt
telling it to invoke `/next`. Then end your turn. A new session picks up from a clean context —
which is the entire point, and much cheaper than continuing in this one.

**If none remain**, say so in one line and stop. Do not schedule anything. Do not look for something
to improve. An empty queue is the design working: the maintainer adds a label when they want more.

This is the brake. The list is the budget — three labelled issues is at most three sessions, then
silence. Nothing here can extend its own runway, because only a person can add a label.

## Choosing who does what

Match the model to the shape of the job, not to habit:

- **Bounded judgement, small brief** — a design question, a copy decision, a security trade-off, an
  architecture choice: `designer` for what a screen looks like and says, `adviser` for everything
  else in that shape. Both run on Fable, both have `Read` and nothing else, both decide rather than
  survey. They cannot search, so paste what they need into the brief.
- **Mechanical and noisy** — running suites, counting results: `verifier`, on Haiku. Cheap by design.
- **Investigation** — reading widely, breaking a property to see whether its test notices,
  establishing whether a claim holds: `skeptic`, on Opus. This needs context, which is exactly why
  it is not on Fable.
- **Seeing the product** — driving it in a browser and reporting what a person sees:
  `browser-pilot`, on Sonnet.

Do the loud parts through them, never inline. Their output would otherwise fill this session and end
it before the work is done.

## When to stop early instead

Stop, report, drop the label and hand off to the next item — rather than pressing on — if:

- The issue needs a decision only the maintainer can make: a trade-off about their product, their
  users, or their money. Write the options into the comment. Guessing is worse than waiting.
- The work turns out much larger than the issue suggested. Push what is genuinely finished and write
  down where you got to. A half-finished piece with honest notes is a good session.
- Something looks wrong in a way the issue did not anticipate, and acting on it would go beyond what
  was asked. Say what you saw; let them decide whether it becomes its own issue.

Stopping early with a clear explanation is a successful run. There is no credit for touching more
files.
