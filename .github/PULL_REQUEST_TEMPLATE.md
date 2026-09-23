## Summary

<!-- What does this change, and why? Name the issue it closes: "Closes #…". -->

## Checklist

- [ ] The checks for what changed pass: `pnpm test && pnpm check && pnpm build` in `companion/web`,
      `./gradlew test` in `companion/server`, `tools/jvm-tests.sh <package>` for `sky`/`stats`, and
      the Android build in CI for anything under `app/`
- [ ] UI changes follow the design rules (docs/DESIGN.md for the app, docs/COMPANION_DESIGN_SYSTEM.md
      for the consoles) and include screenshots
- [ ] No code, strings, icons or assets copied from Daylio or any other closed app (clean-room)
- [ ] No proprietary or tracker dependencies added
- [ ] `CHANGELOG.md` updated under *Unreleased*
- [ ] Anything deferred or found along the way is a GitHub issue, not a note in a document or a comment
- [ ] Commits are signed off (DCO: `git commit -s`)
