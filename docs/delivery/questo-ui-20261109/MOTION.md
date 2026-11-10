# 城瘾 motion system (2026-11-10)

For implementing agents (r2-*, r3-*, and later lanes). Binding rules from BRIEF.md and ROUND2.md still apply: the existing UI is the baseline, no helper captions, player dark / merchant light, `prefers-reduced-motion` and the in-app 减少动态效果 switch are honoured, transitions before keyframes, no shake / explosions / loud colour, never fake data, copy structure from references but keep 城瘾 styling. This file adds the *how* for motion so lanes stop inventing their own timings.

Everything below is written against `origin/staging/questo-review-20261110` (`6d51e844`). Facts about the existing kit were read from that tree and from the u1/u2/u3 lane branches (`claude/qx-u1-20261109`, `-u2-`, `-u3-`, all **draft PRs into staging, not merged**); where a helper lives only on a lane branch it is marked **(u1)** / **(u3)** and the merge order matters.

Package facts that change where things go: the main package is only the five tab pages (`pages/index`, `pages/template`, `pages/talent/list`, `pages/member/index`, `pages/roam`) plus `components/`, `utils/`, `style/`, `behaviors/`. **`pages/play` and `pages/publish` are subpackages** (`subpackagePlay`, `subpackagePublish`), so play-only motion lives in `pages/play/components/` and `pages/play/utils/` and costs the main package nothing. `componentFramework: glass-easel`, no Skyline, `lazyCodeLoading: requiredComponents`.

---

## 0. What already exists — reuse before you build

Do not re-create any of these. Lanes that add a parallel helper will be asked to delete it.

| Area | Where | What it gives you |
|---|---|---|
| Duration tokens | `chengyinhub-xcx/style/tokens.wxss` ≈ L393–442 | `--cy-motion-press 80ms`, `--cy-motion-fast 140ms`, `--cy-motion-standard 220ms`, `--cy-motion-slow 350ms`, `--cy-motion-celebrate 600ms`, `--cy-motion-fade-swap 200ms`, `--cy-motion-spinner-cycle 700ms`, `--cy-motion-sheet-in 300ms`, `--cy-motion-sheet-out 250ms`, `--cy-motion-sheet-peek-in 260ms`, `--cy-motion-sheet-peek-out 200ms`, `--cy-motion-sheet-full-in 340ms`, `--cy-motion-sheet-full-out 280ms`, `--cy-motion-toast-out 150ms`, `--cy-motion-expand 300ms`, `--cy-motion-collapse 200ms`, `--cy-motion-crossfade 150ms`, `--cy-motion-check-fill 120ms`; aliases `--cy-dur-fast`, `--cy-dur`. |
| Easing tokens | same file ≈ L445–463 | `--cy-ease-out cubic-bezier(.22,.61,.36,1)`, `--cy-ease-spring cubic-bezier(.2,1.4,.5,1)`, `--cy-ease-decelerate cubic-bezier(0,0,.2,1)`, `--cy-ease-accelerate cubic-bezier(.4,0,1,1)`, `--cy-ease-emphasized-decelerate cubic-bezier(.05,.7,.1,1)`, `--cy-ease-standard cubic-bezier(.4,0,.2,1)`, `--cy-ease-page-rise cubic-bezier(.2,.82,.2,1)`, alias `--cy-ease`. |
| Z / elevation | same file ≈ L953–963 | `--cy-z-tabbar 80`, `--cy-z-sticky 85`, `--cy-z-nav 90`, `--cy-z-sheet 800`, `--cy-z-popover 850`, `--cy-z-modal 900`, `--cy-z-toast 950`, `--cy-z-celebrate 990`. `--cy-shadow-card` is `none` in the player domain and `0 4rpx 16rpx rgba(0,0,0,.04)` in the merchant light scope. |
| Radius | same file ≈ L934–939 | `--cy-radius-sm 16rpx`, `-md 24rpx`, `-lg 32rpx`, `-xl 40rpx`, `-2xl 44rpx`, `-pill 999rpx`; `--cy-comp-btn-radius` (r2-visual owns the site-wide bottom-button radius change). |
| Reduced motion | `utils/motion-preference.js` (`readReducedMotion`, `subscribeReducedMotion`, `writeReducedMotion`; storage key `play_reduced_motion`, legacy `roam_reduced_motion`), `behaviors/reduced-motion.js` (component data `reducedMotion`), `style/reduced-motion.wxss` (the `@media (prefers-reduced-motion: reduce)` static-fallback list). | Read the switch in JS; add every new animated selector to the static list in `style/reduced-motion.wxss` (the A11Y gate checks it). |
| Page entrance kit | `utils/page-motion.js` → `enter(page, key, {once})`, `countIn(page, key, opts)`, `countMoney(page, key)` (83 files already use it); `utils/wxs/count.wxs` (`f(value, k)`, `money(text, k)`); `style/motion-entry.wxss` (`.cy-rise-in` 12rpx standard/ease-out, `.cy-fly-in` 96rpx slow/emphasized-decelerate with scale overshoot, `.cy-fly-wait`), `style/fade-in.wxss` (`.cy-fade-in`, `.cy-refresh-float`) | The `--pm-x / --pm-x-d / --pm-x-s` container-variable convention: content renders hidden (state 1), rises in staggered (state 2), settles (state 0). WXSS: `filter: opacity(calc(1 - var(--pm-l,0))); translate: 0 calc(var(--pm-l,0)*31rpx); transition: … var(--cy-ease-page-rise)`; stagger `min(var(--pmi),8)*30ms`. Use `translate` + `filter: opacity()` so `transform`/`opacity` stay free for press feedback. `once:true` for first-load only. |
| Motion maths | `utils/motion.js` → `COUNT_UP_MS 220`, `STAMP_MS 600`, `FADE_SWAP_MS 200`, `ROUTE_DRAW_MS 350`, `easeOutCubic`, `easeInOutCubic`, `countUp(from,to,onFrame,opts)→stop`, `countUpFrames`, `stampInPlan()`/`stampIn()` (scale 1.35→1, 600 ms, spring, haptic), `haptic({type,reducedMotion})`, `fadeSwapPlan`/`fadeSwap`, `routeDrawPlan`/`routeDraw(points,onFrame)`; `utils/count-up.js` (roam only) | Every function collapses to one frame with `opts.reducedMotion`. `stampIn` **is** the badge/stamp drop-in (M8 badge); `routeDraw` **is** the route-line draw (4.8); `fadeSwap` **is** skeleton→content (M15). |
| Sheet physics **(u1, draft PR)** | `utils/gesture-physics.js` → `resist(raw, min, reduced)`, `velocity(prev, prevT, cur, curT)`, `detentOffsets(levels, panelH, winH)`, `nearest(list, x)`, `sheetRelease(offset, v, height, detents)`, `swipeRelease(dx, vx, width)`; `utils/wxs/gesture.wxs`; `components/cy/sheet/*`, `components/cy/scene-sheet/*` | 1:1 finger follow in WXS, dismiss at v > 0.5 px/ms or 30 % travel, rubber-band ×0.25 past the top detent, spring back with `--cy-ease-spring`, multi-detent lists via `data-detents`, left-edge (≤ 32 px) swipe-back for pages-in-page (`playkit-album`, `fabu .sp`). **On staging today the sheet grip still drags through JS `setData`** (`DRAG_RATIO 1/3`, `DRAG_VELOCITY .55`, `SNAP_MS 220`) and no WXS file is bound to an event; u1 is the only gesture WXS. The rig needs u1's `rig/rig-wxs-event-shim.diff` to run WXS. |
| Celebration | `components/cy/celebrate` (`eventKey` → 44 confetti pieces, `cy-celebrate-fall`, `--cy-motion-celebrate`, renders nothing under reduced motion; used by play, roam, coinflip, growthcenter); play `.couponburst`, `.woo` + `cy-slide-confirm`, `cy-medal-screen` (`md-rot`/`md-shine`), `cy-chapter-flip` (canvas cube, `mode="gate"|"finale"`), `cy-result-sheet` (`rs-ring/spark`), `cy-fragment` (`fragFly`) | M12 **is** `cy-celebrate`; nobody adds a second confetti. The chapter-number cube (`cy-chapter-flip`, the owner's approved pyramid-flip + pixel-tile number from 2026-10-28) stays; the dissolve (M11) runs *before* it. |
| Tilt / 3D | `components/cy/spin-card` (gyroscope tilt collectible), `subpackageP3/components/pin-spin` (canvas enamel pin, drag with inertia), `xr-badge` (WebGL turntable), `cy-starfield` (CSS stars) | Finger-follow tilt (M16) is the WXS sibling of `spin-card`; keep `spin-card` for gyro. |
| Wizard morph | `utils/wizard-morph.js` → `runIntroMorph(page,{ctaSel,segSel,enterData,afterEnter})`, `DURATION 420`, `style/wizard-intro.wxss` | CTA → progress-segments FLIP (club/apply, club/create, merchant/apply). It has **no reduced-motion check**; whoever touches it next adds `readReducedMotion()`. |
| Success morph **(u3, draft PR)** | `components/cy/btn` props `morph`, `done`; `style/morph-mark.wxss`; `utils/morph-success.js` → `settle(vm, key, then, {with, hold, reduced})`, `cancel(vm)`, `holdFor(opts)`; HOLD_MS 720 | Pill → 88rpx circle + spinner (clip-path inset transition) → check + one pulse ring → page success state → pill regrows with the new label. Never reaches the check on failure/unknown. |
| Stacked sheet depth **(u3)** | `utils/sheet-depth.js` registry, `behaviors/sheet-depth.js` attached to `cy-sheet`, `cy-scene-sheet`, `project-drawer`, `project-layer` | Lower sheet eases to scale .94, translateY −10px, brightness .7 over `--cy-motion-sheet-in`. Hosts no longer pass `stacked`/`under`. |
| FLIP morph entrance | `behaviors/morph-entrance.js` (`anchorSelector`, `shellSelector`, `radius`), `behaviors/exit-motion.js` (`_render`, `_closing`) | A panel grows from the tapped element's rect and shrinks back on close. Used by `cy-sheet` and `cy-publish-sheet`. This is the shared-element primitive; do not write a second FLIP. |
| Page-scroll lock | `behaviors/page-scroll-lock.js` | Lock the page behind full overlays. |
| Haptics | `wx.vibrateShort({type})` in `pages/play/index.js` (arrival = `medium` + `medium` 120 ms later; easter egg `light`; run hold `heavy`), roam, citystamp (`light`), publish/fabu + preset (drag), coupon (`heavy`), `motion.js haptic()` | See M14 for the map that replaces these ad-hoc scales. |
| Gates | `tests/unit/motion-property-ratchet.test.js` (**`KEYFRAMES_CEILING = 146`, asserted as equality; `LAYOUT_ANIMATION_CEILING = 9`; `transition: all` must be 0**), `tests/unit/reduced-motion-coverage-contract.test.js` (every translate/scale/rotate animation needs an `xx--reduced`/`--static` rule or a `wx:if` on `!reducedMotion`), `scripts/ds-hardcode-gate.sh` (no hex/rgba/bare rpx on added lines; `/* ds-ok */` escape), `tests/lint/ui-integrity.js`, `scripts/uiaudit/build-action-ledger.js` | **No new `@keyframes`, anywhere.** Everything in this document is a transition, a WXS-driven inline transform, or canvas. |

The owner's "circular-rotate opening animation" for gameplay templates (ROUND2 b4-21) was **not found on staging** by a full search; the closest existing pieces are `cy-chapter-flip`, roam's `r-opening*` sequence and `npcRingSpin`. r2-create locates it in git history (the 2026-10-22 gameplay-template editor work) before claiming to "keep" it, and names the file in RESULT.

Three lanes have motion work in flight that this system must not collide with: **u1** (gestures, draft; +9.4 KiB main), **u2** (onboarding card stacks on landing/report/ai-npc, draft; 0 main), **u3** (morph + stack depth, draft; +8.2 KiB main). r2-visual owns the surface/contrast/radius tokens; r2-play owns `pages/play/**` except the ending panel; r2-finish owns chapter end, settlement, leaderboards, team rank.

---

## 1. Principles

1. **Game feel through physics, not decoration.** Every motion answers one of: *where did this come from*, *what did my action do*, *what changed*, *how much did I achieve*. Decorative loops (ambient particles, breathing glows) are allowed only on the play page hero layer and the chapter-end / settlement screens, never on lists, forms, merchant pages.
2. **Restraint is the Apple part.** One hero motion per screen; everything else is a 140–220 ms state transition. No two celebrations in a row (a run end plays confetti **or** the dissolve → settlement, not both at full strength; see §4.6). No shake, no screen flash, no colour pulses outside the brand accent.
3. **Transform/opacity first.** Animate only `transform`, `opacity`, `filter: opacity()`, `clip-path`, and (for sheets) `transform` on the panel. Never animate `height`, `top`, `width`, `box-shadow` or `background` on anything larger than a chip. Layout animations are FLIP (`behaviors/morph-entrance.js`) or clip-path.
4. **60 fps on mid Android (X5/XWeb WebView).** Budget per frame: at most 1 animated layer > 50 % of the viewport, at most 12 simultaneously animated elements, no `backdrop-filter` on an element that is moving (blur a static layer, move an unblurred one above it), no `filter: blur()` transitions. Stagger lists cap at 8 items; items past the cap appear with the last item.
5. **WXS for anything the finger drives.** Finger-follow, tilt, drag-reorder, card swipe and the release snap run in `utils/wxs/gesture.wxs` (and its extensions), never through `setData` per frame. Logic-layer code only receives *start / commit / cancel*.
6. **Canvas 2d only where the effect cannot be a transform:** particle dissolve (M11), confetti over 40 pieces (M12), share-card export. Canvas components live in subpackages. One canvas per screen, destroyed on `detached`.
7. **Reduced motion is a first-class state, not a kill switch.** `readReducedMotion()` (in-app switch) **or** `prefers-reduced-motion` ⇒ every primitive has a defined fallback (listed per primitive): instant state change or a 150 ms crossfade (`--cy-motion-crossfade`). Haptics stay. Count-ups show the final number. Nothing is withheld.
8. **Never animate on data that is not there.** A number roll runs only when the server has the number; unknown stays `—`/`未知` with no motion (owner rule). Celebration plays only on a server-confirmed state (`settle()` discipline from u3).
9. **Once.** Entrances play on the first data arrival of a page instance (`enter(page, key, {once:true})`), never on refresh, tab return, pagination or `setData` from polling.
10. **Measure, then ship.** Every lane posts a frame strip (the u3 `rig/frames.mjs` screencast, stamped ms) and a reduced-motion shot. "I added a transition" without frames is not done.

---

## 2. Tokens to add (`style/tokens.wxss`, r2-visual lane, one patch)

Add under the existing motion block. Names are final; lanes reference them from now on even before the token lands (fallback values allowed as `var(--cy-motion-reveal, 450ms)` only until r2-visual merges, then remove fallbacks).

### 2.1 Durations

| Token | Value | Use |
|---|---|---|
| `--cy-motion-micro` | `100ms` | icon swaps, check fill, chip crossfade in lists (between press 80 and fast 140) |
| `--cy-motion-reveal` | `450ms` | hero element reveal: result card, laurel, badge, big number settle |
| `--cy-motion-roll` | `520ms` | digit roll / number roll column travel |
| `--cy-motion-curl` | `420ms` | ticket page-curl / peel |
| `--cy-motion-flip` | `480ms` | ring flip (album), card flip |
| `--cy-motion-dissolve` | `900ms` | particle dissolve of a card (M11), canvas |
| `--cy-motion-stagger` | `40ms` | per-item delay in staggered lists (cap 8 items → 320 ms max) |
| `--cy-motion-stagger-hero` | `90ms` | per-element delay in hero choreography (headline → stats → buttons) |
| `--cy-motion-hold` | `720ms` | hold on a success/check state before the next step (matches `morph-success` HOLD_MS) |
| `--cy-motion-ambient` | `6000ms` | ambient loops (play hero dust); the only loop duration allowed |

Keep using the existing `press 80 / fast 140 / standard 220 / slow 350 / celebrate 600 / sheet-* / expand / collapse / crossfade`.

### 2.2 Easings

| Token | Value | Use |
|---|---|---|
| `--cy-ease-settle` | `cubic-bezier(.16, 1, .3, 1)` | expo-out: big hero elements landing (card, laurel, numbers) |
| `--cy-ease-spring-soft` | `cubic-bezier(.34, 1.3, .64, 1)` | gentle overshoot: list items, chips, avatars stacking |
| `--cy-ease-flip` | `cubic-bezier(.45, 0, .2, 1)` | symmetric 3D flips / curls |
| `--cy-ease-roll` | `cubic-bezier(.2, .9, .2, 1)` | digit roll columns |

Existing `--cy-ease-spring cubic-bezier(.2,1.4,.5,1)` stays the *firm* spring (stamps, badge landing). `--cy-ease-out` stays the default enter, `--cy-ease-accelerate` the default exit, `--cy-ease-page-rise` the page rise.

### 2.3 Distances and scales

| Token | Value | Use |
|---|---|---|
| `--cy-motion-rise-sm` | `16rpx` | row / chip rise-in |
| `--cy-motion-rise-md` | `40rpx` | card rise-in |
| `--cy-motion-rise-lg` | `160rpx` | page body rise (a7 G-11 already uses 160rpx) |
| `--cy-motion-scale-under` | `.94` | sheet / card behind a stacked layer (u3 value) |
| `--cy-motion-shift-under` | `-10px` | same, vertical shift |
| `--cy-motion-scale-lift` | `1.02` | lifted card during drag |
| `--cy-motion-scale-press` | `.97` | press feedback on cards (buttons keep their own) |
| `--cy-motion-tilt-max` | `8deg` | finger-follow tilt (M16) |
| `--cy-motion-stack-rot` | `-6deg` / `4deg` | card stack layer rotations (M8) |

### 2.4 Elevation for lifted / moving layers

| Token | Player (dark) | Merchant (light) |
|---|---|---|
| `--cy-shadow-lift` | `0 24rpx 48rpx rgba(0,0,0,.45)` | `0 16rpx 40rpx rgba(0,0,0,.14)` |
| `--cy-shadow-stack` | `0 12rpx 32rpx rgba(0,0,0,.35)` | `0 8rpx 24rpx rgba(0,0,0,.10)` |

Shadows are set once on the lifted class; they are **not** transitioned (rule 3). The lift *looks* animated because the scale changes under a fixed shadow.

### 2.5 Z-levels (existing, restated so lanes stop hard-coding)

`tabbar 80 < sticky 85 < nav 90 < sheet 800 < popover 850 < modal 900 < toast 950 < celebrate 990`. Full-screen game screens (node gameplay, M3) sit at `--cy-z-modal`; the dissolve canvas and confetti at `--cy-z-celebrate`.

### 2.6 JS spring helper — `utils/motion-spring.js` (new, ≈1.5 KB, main package)

The WebView renderer has no real spring API, so springs are expressed two ways:

```js
// utils/motion-spring.js
// Presets are (stiffness, damping, mass=1). Each has a pre-solved CSS approximation
// so pages can apply a spring as a plain transition (60 fps, no JS per frame).
const PRESETS = {
  snappy: { k: 420, c: 34, dur: 320, ease: 'cubic-bezier(.2,.9,.2,1)' },   // == --cy-ease-roll
  soft:   { k: 300, c: 28, dur: 420, ease: 'cubic-bezier(.34,1.3,.64,1)' }, // == --cy-ease-spring-soft
  firm:   { k: 380, c: 22, dur: 480, ease: 'cubic-bezier(.2,1.4,.5,1)' },   // == --cy-ease-spring
  settle: { k: 260, c: 32, dur: 450, ease: 'cubic-bezier(.16,1,.3,1)' },    // == --cy-ease-settle
}
// curve(name) -> { duration, easing } : for `transition: transform ${duration}ms ${easing}`
// step(state, dt) -> state : semi-implicit Euler for gesture-release loops in WXS/JS
//   state = { x, v, target, k, c }; returns settled=true when |v|<0.01 && |x-target|<0.1
// releaseToTransition(x, v, target, name) -> { duration, easing } : after a drag release,
//   scales duration by clamp(|v|/2, .6, 1.4) so a fast flick lands faster.
module.exports = { PRESETS, curve, step, releaseToTransition }
```

`step()` is the only per-frame function and is used **inside WXS** (`utils/wxs/gesture.wxs` already owns release logic; extend it rather than importing JS). Logic-layer callers use `curve()`/`releaseToTransition()` and let CSS run the frames. Unit-test `step()` for convergence and `releaseToTransition()` bounds (pure functions, `tests/unit/motion-spring.test.js`).

---

## 3. Primitive catalogue

Each primitive: **ID · name** — spec · reduced-motion fallback · haptic · where · package cost. Class names use the existing `cy-` prefix; new components go under `components/cy/` only when they are needed on the main-package pages, otherwise under the subpackage that uses them (see §5.3).

### M1 · Enter / exit (`cy-rise-in`, `page-motion.enter`)
- Enter: `translateY(var(--cy-motion-rise-md)) → 0`, `filter: opacity(0) → opacity(1)`, `--cy-motion-slow` (350) `--cy-ease-page-rise`. Rows use `rise-sm`, page bodies `rise-lg` with `420ms`.
- Exit: `--cy-motion-collapse` (200) `--cy-ease-accelerate`, translate +`rise-sm`, opacity → 0. Exits are always shorter than enters.
- Reduced: instant.
- Where: first data arrival of any page (`enter(page,'pm',{once:true})`), new rows after an action.

### M2 · List stagger
- Each item: `transition-delay: calc(var(--cy-motion-stagger) * var(--i))` with `--i` set inline (`style="--i:{{index < 8 ? index : 7}}"`). Max 8 steps. Items share M1's enter.
- Skeleton → content (refs4 load-tab): skeleton crossfades out (`--cy-motion-crossfade`) while rows stagger in; tab switch slides content `translateX(±24rpx)` + opacity over `--cy-motion-standard`, direction = tab index delta.
- Reduced: all items appear together, no slide on tab switch.
- Where: leaderboards, invite records, memories, catalog, order lists, merchant lists.

### M3 · Page push / pop and pages-in-page
- Native `wx.navigateTo` keeps the platform transition. Do not fake page pushes with sheets.
- **Pages-in-page** (full-screen overlays that must stay inside the play page, e.g. node gameplay, backpack, album): panel `translateX(100%) → 0`, `--cy-motion-slow` `--cy-ease-out`; the page behind scales to `--cy-motion-scale-under` and dims (same values as sheet depth). Close = reverse at `--cy-motion-sheet-out` `--cy-ease-accelerate`, plus u1's left-edge swipe-back.
- Full-screen **game screen** open (node gameplay, ROUND2 a1-v3-21): keep the approved circular-rotate opener for gameplay templates; for a station game card tapped from the story flow, use M5 (the card grows into the screen) and then the game's own opener inside.
- Reduced: crossfade 150.

### M4 · Sheet detents + drag (u1) and stacked depth (u3)
- Peek/half/full ≤ 30/60/80 % height, fixed once content is loaded (owner rule). Drag only from the handle/header when content is scrolled to top. Release rules in `gesture-physics.sheetRelease`.
- Open: `--cy-motion-sheet-in` (300) / peek 260 / full 340, `--cy-ease-standard`. Close: `sheet-out` 250 / 200 / 280, `--cy-ease-accelerate`.
- Stacked: lower sheet → scale .94, −10px, brightness .7.
- Haptic: `light` on detent snap, none on dismiss.
- Reduced: no rubber band, instant snap, dimming without transform.

### M5 · Shared-element morph (FLIP, `behaviors/morph-entrance.js`)
- A panel/screen grows from the tapped element's rect: start `translate3d(dx,dy,0) scale(sx,sy)` (+ radius when the shell carries its own background), end identity, `--cy-motion-slow` `--cy-ease-settle`. Body and footer fade in 80 ms after the shell starts. Close shrinks back over `--cy-motion-sheet-out`.
- Use the same behavior for **card → full screen** (order card → detail sheet only where the destination is already a sheet; gameplay card → game screen; album thumbnail → album viewer).
- Reduced: crossfade 150, no scale.

### M6 · Number roll / count-up
- **Count-up** (existing `countIn` + `count.wxs`): 600 ms, cubic ease, start 150 ms after the card lands. Money 1300 ms ease-in-out. Use for scores, distances, totals.
- **Digit roll** (new `components/cy/digit-roll`, ≈2 KB, needed on main-package HUD and the journey subpackage; put the component in `subpackageJourney/components/digit-roll` first, promote to `components/cy/` only if the play HUD adopts it): each digit is a vertical column of 0–9 (`translateY(calc(-1 * var(--d) * 1em))`), `--cy-motion-roll` (520) `--cy-ease-roll`, columns staggered 30 ms from the least significant digit. Width is fixed per digit (tabular numerals) so the layout never shifts. Property `value`, `digits` (min columns), `animate` (false ⇒ jump).
- Reduced: final value immediately.
- Haptic: none for count-up; `light` once when a digit roll finishes on the rank screen.
- Never roll from 0 when the previous value is known (period switch on the laurel screen rolls old → new).

### M7 · Success check morph (u3 `cy-btn morph/done` + `morph-success.settle`)
- Spec is u3's: pill → circle (clip-path) over `--cy-motion-standard`, spinner, on server success check pops (`--cy-motion-slow`, `--cy-ease-spring`) + pulse ring (`--cy-motion-celebrate`), hold `--cy-motion-hold` 720, then `then()` writes the page's success state and the pill regrows with the new label.
- Haptic: `medium` exactly when the check appears (the lane did not add it; add it in `settle()` guarded by `wx.vibrateShort`), never on the spinner.
- Where: seat claim, gift claim, theme adoption, hint unlock, publish, pass purchase, invite accepted, review submit, join team.
- Reduced: no hold, no check; the page state just appears.

### M8 · Card stack / depth (`cy-card-stack`)
- New presentational component `subpackageJourney/components/card-stack` (u2 already built a 3-layer tilted stack inline on landing/report; extract it): 3 layers, back `rotate(var(--cy-motion-stack-rot, -6deg)) translate(-12rpx, 10rpx) scale(.94)`, mid `rotate(4deg) translate(10rpx, 4rpx) scale(.97)`, front identity; `--cy-shadow-stack` on all; soft radial glow behind (static image/gradient, no animation).
- Enter: layers rise with `--cy-motion-stagger-hero` (back first), front lands with `--cy-ease-settle` over `--cy-motion-reveal`.
- **Advance** (next card): front card exits by M9 (ticket wallet) or M10 (album ring) or M11 (chapter dissolve) depending on the screen; the mid card animates to the front transform over `--cy-motion-slow` `--cy-ease-spring-soft`; a new back layer fades in.
- Reduced: layers appear in place; advance = crossfade.

### M9 · Page-curl / peel (ticket wallet swipe, refs4 ticket-holder)
- Real curl needs canvas; the approved budget is a **3D peel** in WXSS that reads as a page turn at phone size:
  - card `transform-style: preserve-3d; transform-origin: left center;` leaving card: `rotateY(-78deg) translateX(-8%) skewY(-4deg)`, `--cy-motion-curl` (420) `--cy-ease-flip`; a gradient overlay (`linear-gradient(90deg, rgba(0,0,0,0) 40%, rgba(0,0,0,.55))`) on the card fades in with it so the fold darkens; incoming card slides from `translateX(60%) scale(.92)` to identity with `--cy-ease-settle`.
  - During the drag (WXS): progress p = dx / width; leaving card `rotateY(-78deg * p)`, incoming `translateX((1-p)*60%)`. Release uses `swipeRelease` (v > 0.5 px/ms or 30 %).
  - Backdrop: a static blurred copy of the **current** ticket cover (`filter: blur(40px)` on an `image` that is **not** animated; crossfade between two backdrop images over `--cy-motion-slow` when the active ticket changes).
- Haptic: `light` at the commit point of a swipe.
- Reduced: cards crossfade, backdrop crossfades, no 3D.
- Perspective: `perspective: 1200rpx` on the stage, not the card.

### M10 · Ring flip (album entry, refs4 album-ring-stack)
- Stage `perspective: 1400rpx`; page `transform-origin: top center; backface-visibility: hidden;` front face and back face are two `view`s (the back is the next page's colour, rotated `rotateX(180deg)` in rest). Flip = `rotateX(-180deg)` over `--cy-motion-flip` (480) `--cy-ease-flip`; the ring is a static image/SVG layer above the fold line (z above both faces). Pages beneath show a 6rpx offset stack (M8 colours) so the stack looks thick. Dots + round arrow button are static controls.
- Headline per page: big serif (use the approved 龙藏体 / story font only if PR #654's OSS font is live; else the existing display weight) rises with M1 after the flip passes 90°.
- Reduced: crossfade between pages.
- Haptic: `light` when the flip lands.

### M11 · Particle dissolve (chapter end, refs2 chapter-end-dissolve)
- Canvas 2d component `subpackageJourney/components/dissolve` (≈4 KB JS + no assets). Input: the card's cover image URL (already shown), the card's rect. Steps: draw the image into an offscreen canvas at 1/4 scale, sample a grid of `cols × rows` cells (cols = 28, rows = round(28 × h/w), ≈ 1000 particles max), each particle gets the cell's colour, position, a velocity biased up-right (vx ∈ [−0.4, 1.2] px/frame·dpr, vy ∈ [−1.6, −0.2]) with per-particle noise, scale → 0, alpha → 0 over `--cy-motion-dissolve` (900) with ease-out; cells near the top-right start 0–200 ms earlier (wipe direction). Draw with `fillRect` only (no per-particle images). Target ≤ 6 ms per frame on mid Android; if `wx.getDeviceInfo().benchmarkLevel` < 20 (or unknown), cut cols to 18.
- The real card `view` becomes `visibility: hidden` the frame the canvas starts; the next card arrives with M8 advance 300 ms after the dissolve begins (overlap is the point).
- Reduced: crossfade 150, no canvas created.
- Haptic: `medium` at dissolve start.

### M12 · Confetti / celebration (`components/cy/celebrate`, existing)
- Reuse `cy-celebrate` (`eventKey` prop; 44 pieces, `cy-celebrate-fall`, `--cy-motion-celebrate`, `--cy-z-celebrate`). Two additions only, in the same component: a `count` prop (`44` default; `26` for the "60 %" cases below) and a `palette` prop (`brand` = brand accent + white + two muted tones; the current palette stays the default). No canvas branch: 44 CSS pieces is the ceiling. Plays **once per run**: the host guards with the run id (`celebrated_<runId>` in storage).
- Reduced: `cy-celebrate` renders nothing today; keep that, and the host shows one soft glow pulse behind the hero (`filter: opacity`, 450 ms, transition).
- Haptic: `heavy` once at burst.

### M13 · Laurel / rank reveal (refs2 team-rank-laurel)
- Composition: big number (M6 digit roll, 96rpx+ tabular) inside two laurel branches (static SVG/PNG in the journey subpackage, brand mono colour), one line `前 N%` beneath (only if the server gives the percentile; else nothing), period/scope control above.
- Reveal: laurel branches scale from .6 with `--cy-ease-spring` over `--cy-motion-reveal`; number rolls from 0 → rank over `--cy-motion-roll` starting 120 ms after; `前 N%` rises (M1) after the roll; stat cards below stagger (M2).
- Period / scope switch: number rolls old → new (not from 0), cards crossfade.
- Reduced: everything in place; number final.
- Haptic: `light` when the roll lands.

### M14 · Haptic map (named exports added to `utils/motion.js`, ≈0.5 KB, main package)

`utils/motion.js` already has `haptic({type, reducedMotion})`. Add four named helpers next to it so lanes stop choosing intensities per page:

```js
// utils/motion.js (additions)
// hapticTap():     light  — detent snap, drag slot change, flip landed, swipe commit, chip select in games, star picked
// hapticConfirm(): medium — check appears (M7), station arrived, hint unlocked, dissolve start, drop in drag-reorder
// hapticHit():     heavy  — run-complete burst (M12), badge/stamp earned, pass purchase success
// hapticMiss():    wrong answer — one medium, then light 120 ms later (two taps; no shake)
// No-ops when wx.vibrateShort is missing (rig). They do NOT read the reduced-motion preference:
// reduced motion is a visual setting; the existing haptic({reducedMotion}) keeps its old gating for its old callers.
```

Migration: the play page's arrival double-`medium` becomes `hapticConfirm()` (single); coupon `heavy` stays `hapticHit()`; citystamp `light` → `hapticTap()`. Rule: at most one `heavy` per screen, never on open, never in lists.

### M15 · Skeleton → content and tab slide
- Covered by M2 plus the existing `cy-skeleton` (`cy-sk` 1.4 s shimmer, 400 ms delay before it shows) and `motion.fadeSwap()` (200 ms) for the swap. Keep the shimmer on the merchant light side; on the player dark side set the skeleton's shimmer to the static base colour (`--cy-color-skeleton-base #191919`) via the component's existing reduced class, because a shimmer on black reads as a glitch. No new keyframe: `cy-sk` is the only shimmer.

### M16 · Finger-follow tilt (album cards, V3-34 "要") — `utils/wxs/tilt.wxs`
- WXS `touchmove` on the card: `rotateX((cy − y) / h * 8deg)`, `rotateY((x − cx) / w * 8deg)` (the 8deg is `--cy-motion-tilt-max`, passed in via `data-tilt`), `perspective 1200rpx` on the stage, specular highlight = a `::after` radial gradient whose `background-position` is set by `ins.setStyle` from the same coordinates (allowed: it is ≤ one card). Release → identity with `--cy-motion-standard` `--cy-ease-spring-soft`. `cy-spin-card` stays the gyroscope variant; tilt.wxs is for cards the finger is already on (album pages, share cards, ticket deck). The action ledger must accept the `{{module.fn}}` WXS binding (u1 taught `build-action-ledger.js` to treat it as a view-layer handler; r2-visual re-verifies on its base and fixes the tool if the shape differs, never skips the gate).
- Reduced: no tilt (the WXS reads `data-reduced` and returns early).

### M17 · Progress morph pill (HUD and in-sheet progress)
- A control that morphs between states (ref3 morph): pill label → progress state (width stays, label crossfades, a thin progress bar grows inside via `scaleX`) → check (M7). All transitions on `clip-path`/`transform`/`opacity`.
- Where: play HUD "到达 N/M" when a station is reached; ticket wallet "即将开始" filter; share-and-earn "已到账".

### M18 · Press feedback (cards)
- Cards: `scale(var(--cy-motion-scale-press))` over `--cy-motion-press` (80) on `hover-class`, release with `--cy-motion-fast`. Buttons keep `cy-btn`'s own press. Never add shadow changes on press.

---

## 4. Choreography for the key game moments

Times are ms after the trigger. Each row names the primitive; durations use the tokens. "Server" = only after the server fact is in `data`.

### 4.1 Game start (play page, first station, after 开始 / 开启定位)
| t | What |
|---|---|
| 0 | CTA runs M7 to the check (server: run created / resumed). Haptic `confirm` at the check. |
| 0–350 | Location sheet (if not yet granted) rises as a sheet (M4 peek). Asked **once**; the how-to guide opens settings. |
| 720 | Hold ends: the start sheet closes (`sheet-out`), the page behind un-dims. |
| 720–1200 | Play hero: title centred (r2-play a1-v3-13-14) settles with `--cy-ease-settle`; HUD row (HP, backpack, radar) rises as M2 with `stagger-hero`; story text paragraphs rise M1 one after another (`stagger` 40 → cap 8). |
| 1200+ | The existing story-dust canvases (`#stBack` / `#stFront` in `pages/play/index.wxml`, rAF-driven) fade in to their resting opacity over 600 ms. No new particle layer and no new keyframe. |
Reduced: sheet closes instantly; text appears; dust canvases are not mounted (existing `reducedMotion` gate).

### 4.2 Arrive at station (geofence hit or QR / code)
| t | What |
|---|---|
| 0 | Haptic `confirm`. HUD pill (M17) morphs from `第 N 站` to the progress state and the bar grows to the new fraction over `--cy-motion-slow`. |
| 0–450 | Radar/compass ring does one `scale(1→1.12→1)` pulse with `--cy-ease-spring` (transform only). Map zoom-in hides the HUD row (r2-play rule: on `regionchange` scale) with a 220 ms fade. |
| 300 | The place card (M4 sheet, peek → half) rises with its cover; title and address M1; the station game card (cover + title + type, no 「打开」 label) rises last. |
| — | Nothing else moves. No confetti on arrival. |

### 4.3 Unlock hint (提示 sheet, UG-05)
u3 already wired it: row action text fades → spinner → check (M7 in-row), hint content rises (`cy-rise-in`) after the hold. Add: haptic `confirm` at the check; the 剩余免费 counter (M6 count-up old → new, 300 ms); the next locked row's `需先看上一层` label crossfades to its price (`--cy-motion-crossfade`). Reduced: content appears, counter jumps.

### 4.4 Correct / wrong answer (gameplay kits, b2)
| | Correct | Wrong |
|---|---|---|
| 0 | Selected option/answer field: border/background crossfade to the success surface (`--cy-motion-fast`), check glyph pops (`--cy-ease-spring`, 350). Haptic `confirm`. | Field crossfades to the error surface; the error text appears **under the field** (owner rule: red text, no pop-up) with M1 `rise-sm`. Haptic `miss`. No shake, no horizontal nudge. |
| 150 | Score/HP count-up (M6, 600) in the HUD if the server returned a delta. | HP count-down if the server returned a delta; otherwise nothing. |
| 720 | Hold ends: the result panel (正常结案 / 指认失败) comes in as a pages-in-page screen (M3) with the animated success/failure/waiting asset (ROUND2 rule 8, Lottie/APNG in the kits subpackage, ≤ 120 KB each) playing once, headline centred, big type. | Same screen, failure asset. |
Reduced: state swap, asset shows its last frame (APNG) or is replaced by the static poster.

### 4.5 Chapter end (B1 structure + refs2 dissolve; today `cy-chapter-flip mode="gate"` + `.chhold` in `pages/play/index.wxml`)
| t | What |
|---|---|
| 0 | Haptic `confirm`. The last story/station card dissolves (M11, 900) up-and-right. |
| 300 | The chapter card arrives from the stack (M8 advance): `第 N 章完成` headline, then 用时 / 本章得分 rows count up (M6; only server values, else `—` with no motion), then `下一站：…` CTA rises. Stagger `stagger-hero` 90. The approved chapter-number cube (`cy-chapter-flip`, pyramid flip + pixel-tile number) renders **inside** this card as the number; it is not a second screen. |
| 1500 | Everything still. The CTA is a normal `cy-btn`; tapping it uses M3 to go back into play. The `.chhold` waiting state (host not released) keeps its still dots; it is a state of the same card, not a different layout. |
Reduced: crossfade, numbers final, cube static (existing `reduced-motion` prop).

### 4.6 Run complete → settlement (4 steps: 总览 → 排行榜 → 评价 → 分享)
Today's pieces: `openFinish()` → `.finmask`/`.finsheet` (route canvas `#routecv`), `_triggerCelebration('classic-finish'|'free-finish')`, `cy-medal-screen`, `cy-chapter-flip mode="finale"`, `.ending` (fragments staggered `index*120ms`), `.woo` + `cy-slide-confirm`, `.rdrop`, `.banner` — all in `pages/play/index.{wxml,js,wxss}`; the report is `subpackageJourney/pages/report` with the 榜单 half-sheet (`openRank`, scopes 单关/城市/国家/世界 + 累计/本周, to be trimmed per ROUND2) and the growth leaderboard `subpackageP3/pages/growthcenter/leaderboard`. r2-finish replaces the stack of ending layers with **one** choreography:
| t | Step 1 总览 |
|---|---|
| 0 | Last chapter card dissolves (M11). No confetti here. `cy-medal-screen` rays (`md-rot`) are not shown at this step; the medal belongs to the rewards strip at 900. |
| 300 | Result card (M8 stack, u2's layered card) lands with `--cy-ease-settle` 450. `通关` eyebrow + theme title M1. |
| 600 | Rank (`本主题第 N 名`, server) digit-rolls (M6) — if there is no rank, the line is absent, nothing rolls. Score / chapters n/n / 用时 count up with `stagger-hero`. |
| 900 | Rewards strip (勋章 / 积分 +N, only if granted): badge scales in with `--cy-ease-spring` + **the single celebration burst M12** (haptic `hit`). If no reward was granted, the burst plays on the result card instead, at 70 % piece count. Exactly one burst per run. |
| 1400 | Buttons rise: primary `看排行榜`, secondary `完成`. Share icon top-right fades in. |
| Step 2 排行榜 | Push (native). Segmented `队内 | 全体 | 好友` (only tabs with a source). Rows stagger (M2); the player's own row is highlighted with a one-time `scale(1.02)` settle; when off-screen it pins to the bottom with a `translateY(100%) → 0` slide over `--cy-motion-standard`. Tab switch slides content (M2). Team tab shows the laurel (M13) above the list when `/api/orient/team/ranking` returns rank + percentile. |
| Step 3 评价 | Sheet (M4 half). Stars fill left→right with `--cy-motion-micro` each (haptic `tap` per star); after the first star the tag chips rise (M2); 提交 enables with a crossfade. Submit → M7 → toast → auto-advance (hold 720). |
| Step 4 分享 | Share page (r3-share): one bold card per target slides in from the right as a horizontal pager with peek; the active card has M16 tilt; actions row rises last. 下一个主题 card after it uses M8 colours. |
Reduced: crossfades; numbers final; one static glow instead of the burst.

### 4.7 Rank reveal (team / member rank page, refs2 laurel)
M13 as specified; the scope control (`单关 / 城市 / 本周 …`, trimmed per ROUND2) changes roll old → new, stat cards crossfade. The horizontal scope row is a `scroll-view` with `enhanced` and `show-scrollbar=false`; its chips use M18 press.

### 4.8 Badge / stamp earned (album, passport, 通关卡)
| t | What |
|---|---|
| 0 | Haptic `hit`. The badge/stamp drops in with the existing `motion.stampIn()` plan (`scale 1.35 → 1`, 600 ms, `--cy-ease-spring`) plus `rotate(-8deg) → 0`; a 2rpx ring expands from the badge edge and fades (transform only). |
| 200 | M12 burst (`count=26`) if this is the run's first celebration; otherwise no burst. |
| 600 | Name and condition line rise (M1). On the album map variant (refs2 album-map-stamps) the photo stamp pins onto the map at the station's position with the same `stampIn`, and the route line draws to it with the existing `motion.routeDraw(points, onFrame)` (350 ms) on the roam map canvas (`#routecv` / roam `fogcv` family), never a DOM polyline. |
Reduced: badge appears with the ring only (`stampIn` collapses to one frame with `reducedMotion`).

### 4.9 Album open (收集册, refs2 album-map-stamps + refs4 ring stack)
| t | What |
|---|---|
| 0 | From the 收集册 entry card (home 成就 tab), the card grows into the full screen (M5 FLIP from the tapped rect). |
| 350 | The ring stack (M10) renders page 1 (勋章) on top; the page's headline rises; the dots row and round arrow appear. |
| — | Swipe/arrow: ring flip to 邮票 / 藏品. Inside a page, cards tilt (M16). Opening one item → M5 from its thumbnail. |
Reduced: crossfade, no flip.

### 4.10 Ticket wallet (票夹) swipe
M9 as specified. Header filter `即将开始 ▾` is a popover (`--cy-z-popover`) sliding 8rpx with `--cy-motion-fast`. The card's status pill (未开始/进行中/已通关) crossfades. The backdrop blur image is swapped by crossfade only after the swipe commits.

### 4.11 Pass purchase success (主题通票 / 自玩票, refs4 pass-purchase)
| t | What |
|---|---|
| 0 | 支付 CTA runs M7 to the check only on **server-confirmed payment state** (never on SDK success — owner rule). Haptic `hit` at the check. |
| 720 | Post-purchase screen: dark → warm gradient surface crossfades in (`--cy-motion-slow`), our own object (city pass / ticket / seal, a static 3D-rendered PNG set from a free-licensed source, ≤ 3 frames, in the store subpackage) assembles: three pieces translate from `±120rpx` offsets and `rotate(±18deg)` to rest with `--cy-ease-spring` over `--cy-motion-reveal`, staggered `stagger-hero`. |
| 1100 | `+N 积分` chips pop (`--cy-ease-spring`, 350) only if the server credited them; the top-bar points pill digit-rolls old → new (M6). |
| 1400 | Covered-themes carousel rises (M2); 「继续」 white pill rises; 「跳过」 top-right. |
Reduced: static object, numbers final.

### 4.12 Invite accepted / share-and-earn credited (refs4 share-earn-credits)
| t | What |
|---|---|
| 0 | QR/scan card is static. When the server confirms a credit (polled or returned): amount text morphs in — `clip-path: inset(0 100% 0 0) → inset(0)` over `--cy-motion-standard` `--cy-ease-out`. |
| 220 | A record card slides up into the list from `translateY(40rpx)` (M1), the list above shifts with a 200 ms transition (FLIP via `transform` on siblings — only the top 3 rows; the rest re-render). |
| 500 | Green check (M7 check mark only, no button) pops on the card. Haptic `confirm`. |
| 500 | Top-bar points pill digit-rolls old → new (M6, 520). Only if the server returned the new balance. |
| Invite accepted (seat claimed by a friend) | On the 同行席位 page the dashed `+` slot crossfades to the avatar with `scale(.6) → 1` `--cy-ease-spring-soft`; the `N/M` counter rolls. |
Reduced: values final, card appears.

### 4.13 Seat claimed (P5, u3 already shipped the morph)
Keep u3's: 领取席位 → M7 → headline `已领取同行席位`, seat row, `进入本局` appear after the check. Add haptic `confirm` at the check and the seat row's `第 N 席` with `--cy-ease-spring-soft` scale-in.

---

## 5. Implementation plan

### 5.1 Shared helpers and components (names are final)

| Add | Path | Package | ≈ size (src) | Owner |
|---|---|---|---|---|
| Tokens §2 | `chengyinhub-xcx/style/tokens.wxss` (+ mirror in `style/merchant-light.wxss` for the light shadow values; `scripts/ds-theme-sync-check.sh` locks the two) | main | +1.2 KB | r2-visual |
| `utils/motion-spring.js` | main | 1.5 KB | r2-visual (first consumer: r3-visual ticket swipe) |
| Haptic helpers (M14) | `utils/motion.js` (4 named exports next to `haptic()`) | main | +0.5 KB | r2-visual; every lane replaces the direct `wx.vibrateShort` calls it touches |
| Swipe-card + tilt WXS | `utils/wxs/gesture.wxs` (extend u1's file after it merges; keep its API), `utils/wxs/tilt.wxs` (new, 1 KB) | main (WXS files must sit where every subpackage can `require` them) | +3 KB | r3-visual (swipe-card), r2-visual (tilt) |
| Drag-reorder WXS | `pages/publish/utils/wxs/sort.wxs` (adapted from wxp-ui, MIT notice kept), `pages/publish/utils/sort-core.js` | publish subpackage | 4 KB | r2-create |
| `digit-roll` | `pages/play/components/digit-roll/` (HUD counters, rank in the ending) **and** a copy in `subpackageJourney/components/digit-roll/` (report rank) only if the journey report needs it before the ending moves; otherwise the report links to the play page's ending | play (0 main) | 2 KB | r2-finish |
| `card-stack` | extract u2's inline stack into `subpackageJourney/components/card-stack/` (landing, report) and reuse its WXSS verbatim in `pages/play/components/chapter-card/` | journey + play | 2 KB each | r2-finish |
| `dissolve` canvas | `pages/play/components/dissolve/` | play | 4 KB | r2-finish |
| `celebrate` | existing `components/cy/celebrate` + `count`/`palette` props | main (+0.3 KB) | — | r2-finish |
| `laurel` | `subpackageJourney/components/laurel/` + one mono SVG (≤ 6 KB); growth leaderboard imports it from there (same subpackage? no: `subpackageP3`; duplicate the ≤ 2 KB component there if adopted) | journey | 2 KB | r2-finish |
| `ring-stack` | `subpackageP3/components/ring-stack/` (the album pages live in `subpackageP3`: badge-wall, stamp-album, object-cards) | P3 | 3 KB | r3-visual |
| `ticket-deck` (M9) | `subpackageMember/signup/` (the 票夹 deck is already there: `.deck`, `.wc`, `.wc__flip`) — refactor in place, no new component | member | +3 KB | r3-visual |
| `morph-pill` (M17) | `pages/play/components/morph-pill/` | play | 2 KB | r2-play |
| Result / failure / waiting animated assets | `pages/play/components/playkit-accusation/assets/` (the kits live in the play subpackage) as APNG/GIF via `image`, ≤ 120 KB each, licence in RESULT; `lottie-miniprogram` is **not** in the repo and is not added without the owner | play | ≤ 360 KB | r2-kits |

Main-package additions in this plan total **≈ 6.5 KB source ≈ 4 KiB** by the 0.633 estimate. Staging is at 1998.6 KiB; u1 adds ≈ 9.4 KiB, u3 ≈ 8.2 KiB (→ ≈ 2016 KiB if both merge). That leaves ≈ 28 KiB for the whole wave, so: **only tokens, `utils/` helpers, WXS files and `components/cy/` props go into the main package; every visual component goes into the subpackage of its first consumer (play, journey, P3, member).** If a lane needs more, first move `club-direct-ledger` (10 KB) and the 10 zero-reference main-package components (59 KB) listed in `staging/RESULT.md §4`, in that lane's PR, and say so. `node chengyinhub-xcx/scripts/mainpkg-size-report.js --json` before/after goes in every RESULT.

### 5.2 How pages adopt it

1. **Entrance:** in the page's first successful data handler call `enter(this, 'pm', { once: true })` (`utils/page-motion.js`), add `style="--pm-x:…"` on the container per the kit's convention, and give list items `style="--i:{{index < 8 ? index : 7}}"`. Add every new animated selector to `style/reduced-motion.wxss`.
2. **Success moments:** mark the `cy-btn` with `morph` + `done="{{xDone}}"` and call `settle(this, 'xDone', () => this.setData({…success state…}), { with: { busy: false } })` **after** the server confirmed. Call `haptic.confirm()` inside `then` if the lane owns the page; otherwise leave it to the `settle()` patch.
3. **Numbers:** write the real value to data as today; add a display field driven by `countIn` / `count.wxs` or pass `value` to `cy-digit-roll` with `animate="{{!reducedMotion && firstLoad}}"`.
4. **Gestures:** bind `bindtouchstart="{{g.start}}"` etc. from `utils/wxs/gesture.wxs`; the WXS calls back `callMethod('onSwipeCommit', {dir})` and the page only handles the commit. Rebuild the action ledger (`build-action-ledger.js --write`) in the same PR.
5. **Pages-in-page / game screens:** open with `behaviors/morph-entrance.js` (`anchorSelector` = the tapped card) and `behaviors/exit-motion.js`; lock scroll with `behaviors/page-scroll-lock.js`; register with `utils/sheet-depth.js` so anything below steps back.
6. **Canvas primitives:** mount only while playing (`wx:if`), pass the cover URL and rect, listen for `bind:done`, unmount. Never keep a canvas alive in a list.

### 5.3 Package placement rule

Main package (`components/`, `utils/`, `style/`, `behaviors/`): tokens, `motion-spring`, the haptic helpers in `motion.js`, WXS files, and prop additions to existing `components/cy/*`. Everything visual: the subpackage of its first consumer (`pages/play` for anything the game loop shows, `subpackageJourney` for landing/report/seats, `subpackageP3` for the album family, `subpackageMember` for 票夹/market/invites, `pages/publish` for the editor, `pages/merchant` for merchant screens). A component needed by two subpackages is duplicated by the second consumer only if it is < 2 KB; otherwise it is promoted to `components/cy/` with a note in the PR and a size line in RESULT. Nothing is promoted "just in case".

### 5.4 Test and gate strategy

- **Pure maths gets unit tests** (`node --test chengyinhub-xcx/tests/unit/<x>.test.js`): spring `step()` convergence and `releaseToTransition()` bounds; `sheetRelease`/`swipeRelease` parity (u1 has them); digit-roll column maths; dissolve grid sizing per `benchmarkLevel`; choreography schedulers (`settle` already has `qx-u3-motion.test.js`; follow its pattern: fake timers, assert the order of `setData` calls, assert reduced-motion short-circuits).
- **Motion ratchet:** `tests/unit/motion-property-ratchet.test.js` asserts `KEYFRAMES_CEILING === 146` (non-commented `.wxss` keyframes, exempt names `md-rot md-tw md-shine holo-pan fxCardFloat`), `LAYOUT_ANIMATION_CEILING 9` (transitions on width/height/margin/padding/top/left/font-size) and zero `transition: all`. Adding a keyframe is red; removing one without lowering the ceiling is red. Every primitive here is transition/WXS/canvas, so lanes should never need to touch this number; if a lane deletes an old keyframe it lowers the ceiling in the same PR with a dated comment.
- **Reduced-motion coverage:** `tests/unit/reduced-motion-coverage-contract.test.js` requires an `xx--reduced` / `--static` rule or a `wx:if="{{!reducedMotion}}"` for every translate/scale/rotate animation; opacity-only and spinners are exempt. Add the rule in the component's own WXSS and the selector to `style/reduced-motion.wxss`.
- **`node chengyinhub-xcx/tests/lint/ui-integrity.js` must print `UI-GATE-0:PASS`**, no baseline entries. Known traps from u1/u2/u3: U3 handler-like names (`on|go|tap|handle|retry` + capital) with no WXML binding — name internal animation callbacks `_onAnimEnd`; U4 every top-level `setData` key must be read in WXML (animation state like `pmList`, `_dragStyle`, `morphRun` must appear there); dynamic `setData` keys; line-anchored baselines on `pages/publish/fabu/index.js` (keep the line count unchanged when touching it).
- **Hardcode gate:** `scripts/ds-hardcode-gate.sh` rejects hex/rgba/bare `rpx` on added `.wxss` lines (escape with `/* ds-ok */` only for canvas-derived values); durations/easings are not scanned, but the token rule ("no literal durations in pages/components") is reviewed by hand, so use `var(--cy-motion-*)` everywhere.
- **Action ledger:** rebuild with `node chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js --write`; `tests/unit/action-ledger.test.js` freezes the counts (tap 2927 / event-contract 2948 / external 366 / local 2561 on staging) and each change carries a dated comment. `{{module.fn}}` WXS bindings are accepted once u1 merges; a new binding shape (e.g. `capture-bind`) must be taught to the builder in the same PR, not skipped.
- **Frame evidence:** use u3's `rig/frames.mjs` (CDP screencast, ms-stamped) with u1's `rig/rig-wxs-event-shim.diff`; post `<ID>-after-f1..8.jpg` strips plus one `-reduced-after.jpg`. Inspect for a flash of unstyled state, a snap-back frame, overlapping layers.
- **Performance smoke:** on the rig, a `Performance.getMetrics` sample before/after the hero moment; report the dropped-frame count. On a real phone (owner), the standard is "no visible hitch on a 2021 mid-range Android".
- **Main package:** `node chengyinhub-xcx/scripts/mainpkg-size-report.js --json` before/after in RESULT.

### 5.5 Phased rollout

| Phase | Scope | Lanes |
|---|---|---|
| **0 — foundation (this wave, first)** | §2 tokens, `motion-spring`, `haptic`, `reduced-motion.wxss` entries, u1 + u3 merged into staging; `morph-success.settle` gains the `confirm` haptic. | r2-visual (tokens/haptic/spring), s-integrator (merges) |
| **1 — the game loop** | 4.1 game start, 4.2 arrive, 4.3 hint, 4.4 answers; `morph-pill`; pages-in-page game screen via M5. | r2-play, r2-kits |
| **2 — the payoff** | 4.5 chapter end (dissolve + card stack), 4.6 settlement 4 steps, 4.7 laurel rank, 4.8 badge earned; `digit-roll`, `card-stack`, `dissolve`, `celebrate`, `laurel`. | r2-finish, then r3-share for step 4 |
| **3 — collections and commerce** | 4.9 album open + ring flip + tilt, 4.10 ticket wallet peel, 4.11 pass purchase, 4.12 share-and-earn. | r2-visual, r3-visual, r3-pass, r3-share |
| **4 — everywhere else** | M1/M2 entrances and tab slides on every list page touched; skeletons on the merchant side; drag-reorder in the story editor (`sort.wxs`). | r2-create, r2-merchant, r2-join |

Each phase ends with the owner's screenshot review (owner rule: review before merge). No phase starts on a page whose round-2 verdict is still open.

---

## 6. Don'ts (collected from the owner's notes)

- No black or grey-on-grey sheets: motion does not fix contrast; r2-visual's surface tokens do.
- No pop-up for input errors; red text under the field, rising 16rpx.
- No helper copy explaining an animation ("向左滑动查看下一张"): the peek of the next card is the affordance.
- No motion on unknown values; `—` / `未知` is static.
- No second back button, no animated nav chrome.
- No new `@keyframes` at all (ratchet at 146). The loops that exist and may be reused: `cy-btn-spin`, `cy-sk`, `cy-celebrate-fall`, `md-rot`/`md-shine`, `sfFloatA/B`/`sfTwinkle`, `fxCardFloat`, `holo-pan`. Digit roll, dissolve, curl, flip, tilt and every entrance are transitions, WXS inline transforms or canvas.
- No `wx.createAnimation` (the repo removed it on purpose) and no `movable-view` for drags (it needs `setData` per frame on Android).
- No Questo art, names or copy; structure only.
