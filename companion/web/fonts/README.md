# Vendored fonts

The design system specifies **self-hosted** variable fonts — **Fraunces** (display) and
**Inter** (text) — served from this origin only. **No Google Fonts, no CDN, no runtime
third-party fetch** (that is the whole point: it keeps `font-src 'self'` and the strict
CSP honest).

This scaffold ships **without** the binary font files so the repo stays text-only and the
build needs no network for assets. Until they are added, `src/app.css` falls back to a
high-quality system font stack, so the UI looks clean out of the box.

## To vendor the real fonts

1. Obtain the SIL Open Font License variable woff2 builds of Fraunces and Inter from their
   official upstreams (both are OFL — redistributable; keep the `OFL.txt` next to them).
2. Subset to the glyphs actually used (Latin + the handful of symbols we draw) to keep the
   bundle small, e.g. with `fonttools`:
   ```
   pyftsubset Inter.var.woff2 --unicodes=U+0000-00FF,U+2010-2027 \
     --flavor=woff2 --output-file=Inter-variable.subset.woff2
   ```
3. Drop the `.woff2` files in this directory and uncomment the `@font-face` blocks at the
   top of `src/app.css`.

The files are served `'self'` by Vite/Ktor; nothing else changes.
