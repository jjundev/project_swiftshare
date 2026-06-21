# Aether Design System

Aether is an **original, fictional consumer-technology brand** and the design system that dresses it. It is built in the spirit of premium, restrained product design — generous whitespace, sentence-case voice, near-monochrome surfaces and a single confident accent — but every mark, colour, font and screen here is original and free to use.

> **Why "Aether" and not a real brand?** This project was seeded from an extraction of a real company's website. To respect intellectual property, none of that brand's logos, proprietary fonts (e.g. SF Pro) or pixel-exact screens are reproduced. Instead we built an independent system that captures the same *principles* — clarity, calm, craft — under our own name.

---

## The brand at a glance

- **Name:** Aether — "Considered technology for everyday life."
- **Personality:** calm, precise, quietly confident. Benefit before spec. Never shouts.
- **Logo:** a "halo" mark — an ink ring with a Signal-Coral core — locked up with the lowercase `aether` wordmark.
- **Products (fictional):** Slate (laptops), Pulse (watch), Halo (audio), Aura (home).
- **Accent:** Signal Coral `#ff5a36` — used sparingly, never as a field of colour.
- **Type:** Hanken Grotesk (display + UI + body) and JetBrains Mono (specs, prices, code).

---

## Sources & provenance

This system was created in a project that mounted two read-only reference folders describing a real site's *structure* (used only to understand generic, non-proprietary patterns — spacing rhythm, pill controls, soft elevation, calm motion):

- `apple-design-extract-output/` — a `designlang`-generated extraction (DESIGN.md, tokens JSON, motion/gradient/voice descriptors) of `https://www.apple.com/kr/mac/`, captured 2026-06-06.
- `apple-com-design-system/` — a packaged token/component export (tokens JSON, storybook scaffold, voice/prompt packs).

**No proprietary assets, marks or fonts from those sources are included here.** Where this system needed a concrete value (a font, a logo, an accent), an original or open-source choice was made and is documented below.

---

## Content fundamentals

How Aether writes. The voice is **calm, declarative and human** — it sells the feeling, then backs it with a number.

- **Casing:** Sentence case everywhere — headlines, buttons, nav. Never Title Case, never ALL CAPS except tiny uppercase eyebrows/labels (with wide tracking).
- **Person:** Third-person about the product, second-person to the reader where it warms things up ("so *you* charge less and live more"). Avoid "I/we" except in support/account contexts.
- **Sentence shape:** Short. One idea per line. Headlines often break into two calm lines ("Power, quietly / delivered.").
- **Benefit before spec:** Lead with what it does for a person, let the spec follow ("Two-day battery" → then "48 hr").
- **Punctuation:** A single full stop closes most headlines. No exclamation marks. Em dashes for considered asides.
- **Numbers:** Real, specific, monospaced (`$1,599`, `22 hr`, `14.2″`). Prices show a financing line beneath.
- **No hype words:** avoid "revolutionary, insanely, ultimate, game-changing." Confidence is shown, not claimed.
- **Emoji:** none. Ever. The system uses line icons, not emoji.
- **CTA verbs:** "Buy", "Pre-order", "Add to bag", "Learn more", "Compare". Plain and direct.

**Examples**
- ✅ "Power, quietly delivered." / "A brighter every day." / "Sound, all around."
- ✅ "In stock. Order today, delivered by Thursday."
- ❌ "The Most POWERFUL Laptop EVER!!!" / "Revolutionary new experience"

---

## Visual foundations

**Colour.** A warm-neutral **ink ramp** (`--ink-900 … --ink-50`, near-black `#15161a` to hairline `#f4f4f6`) on **warm paper whites** (`--surface #fff`, `--surface-muted #f5f5f3`, `--surface-sunken #ececea`). One brand accent — **Signal Coral `#ff5a36`** — appears only on high-emphasis CTAs, the logo core, focus rings and the occasional badge. Semantic colours (positive/warning/critical/info) signal state only, never decorate. Imagery (when supplied) should be warm-neutral, evenly lit, with lots of negative space; this kit ships art-directed icon placeholders rather than stock photos.

**Type.** A single grotesque (Hanken Grotesk) carries the whole system; hierarchy comes from **weight and tight negative tracking**, not many families. Display is 64–80px / 700 / −0.03em / line-height 1.05. Headlines 24–40px / 600 / −0.02em. Body is a comfortable 17px / 1.5. Specs, prices and code use JetBrains Mono with tabular figures. Headlines `text-wrap: balance`; body `text-wrap: pretty`.

**Spacing & layout.** 4px base unit; the scale jumps generously (4, 8, 12, 16, 24, 32, 48, 64, 80…). Sections breathe — 64–80px vertical padding is normal. Content max-width 1280px (marketing), 720px (reading measure), 22px side gutters.

**Shape & radii.** Soft, never sharp. Controls and chips are **full pills** (`--radius-pill: 980px`). Cards use 20px (`lg`) or 28px (`xl`) corners. Inputs 14px (`md`).

**Elevation.** Shadows are **soft and ink-tinted, never black** — a quiet `sm` for raised cards, `md`/`lg` for floating surfaces and the bag drawer, `xl` for modals. Many surfaces use only a 1px hairline (`--ring-hairline`) instead of a shadow. Cards lift `translateY(-3px)` on hover.

**Borders.** 1px hairlines in `--border-subtle`/`--border-default`; `--border-strong` on hover of inputs. No heavy outlines.

**Transparency & blur.** Reserved for the sticky global nav: `rgba(255,255,255,0.8)` + `backdrop-filter: saturate(180%) blur(20px)`. Overlays use a calm `rgba(20,22,26,0.32)` scrim. No frosted glass elsewhere.

**Motion.** Calm and confident, **never bouncy**. Standard easing `cubic-bezier(0.4,0,0.6,1)`; entrances ease-out `cubic-bezier(0,0,0.3,1)`. Durations 80ms (press) → 160ms (hover) → 240ms (most) → 360ms (overlays) → 600ms (hero). Hovers are colour/lift; **press shrinks to 0.97 scale**; focus shows a coral ring with a white inset gap. All motion collapses under `prefers-reduced-motion`.

**Hover / press states.** Buttons darken their fill on hover and scale to 0.97 on press. Nav items shift ink-700 → ink-900. Cards lift. Swatches gain a coral focus ring. Nothing changes hue dramatically.

**Cards.** White (or muted) surface, 20–28px radius, hairline ring or soft `sm` shadow, optional 16:10 media frame at top, then a padded body of eyebrow → title → description → footer.

---

## Iconography

- **System:** [**Lucide**](https://lucide.dev) — open-source, MIT-licensed, 1.5–2px stroked line icons. This is a **substitution**: the reference site used a proprietary SF-based icon font (`SF Pro Icons` / `Apple Icons`), which we do not ship. Lucide's even, geometric line style matches Aether's calm tone. **Flagged for review** — swap for a bespoke set later if desired.
- **Delivery:** loaded from CDN (`unpkg.com/lucide`) in cards and UI kits via `<i data-lucide="name">` + `lucide.createIcons()`. For production, install the `lucide-react` package.
- **Stroke & size:** default 18–22px in UI, 1.25–2px stroke; large product-placeholder glyphs use 1.25 stroke for an airy feel. Icons inherit `currentColor`.
- **Emoji:** never used as iconography. **Unicode** is used only for the chevron "›" in "Learn more ›" links.
- **Logo / brand marks** (original SVGs in `assets/`): `aether-mark.svg` (ink ring + coral core, on light), `aether-mark-inverse.svg` (white ring, on dark), `aether-mark-mono.svg` (all-ink). The wordmark is set live in Hanken Grotesk, not outlined.

---

## What's in here (manifest)

**Root**
- `styles.css` — the single entry point consumers link. `@import` manifest only.
- `tokens/` — `fonts.css`, `colors.css`, `typography.css`, `spacing.css`, `elevation.css`, `motion.css`, `base.css`.
- `assets/` — original Aether logo marks (`aether-mark*.svg`).
- `SKILL.md` — Agent-Skill front-matter so this system can be used inside Claude Code.

**Components** (`components/<group>/` — each has `.jsx` + `.d.ts` + `.prompt.md` + a `@dsCard` HTML)
- `core/` — **Button**, **Badge**, **Card**
- `forms/` — **Input**, **Switch**
- `navigation/` — **SegmentedControl**
- `feedback/` — **Banner**

Use components at runtime via `const { Button } = window.AetherDesignSystem_7ccd51` after loading `_ds_bundle.js`.

**Foundation cards** (`guidelines/*.card.html`) — colour ramps, type specimens, spacing/radii/elevation, logo & voice. These populate the Design System tab.

**UI kit** (`ui_kits/storefront/`) — an interactive **Aether storefront**: sticky global nav, marketing home (hero, lineup grid, dark feature band, footer), a product **configurator** (size / finish swatches / storage, live price, specs, delivery banner) and a slide-in **bag drawer**. Built from the DS components. `index.html` is the click-through entry point.

---

## Known caveats / substitutions
- **Fonts** are loaded from Google Fonts (Hanken Grotesk + JetBrains Mono), not self-hosted `@font-face` binaries — so the compiler reports 0 shipped fonts. To self-host, drop `.woff2` files in `assets/fonts/` and replace the `@import` in `tokens/fonts.css` with local `@font-face` rules.
- **Icons** are Lucide via CDN — a stand-in for a bespoke icon set.
- **Product imagery** is art-directed icon placeholders, not photography.
