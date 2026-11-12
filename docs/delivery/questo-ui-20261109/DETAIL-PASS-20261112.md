# Detail pass on EXISTING screens (2026-11-12, lead spec)

Owner: "optimize based on our existing work, not completely alter it" + "the main video lacks details". RULE: keep every screen's layout, structure, components, copy, colours and looks exactly as on master 920141d5. Add craft detail ON the existing elements only. Never add new sections, never redraw. Each item cites the reference detail it borrows.

## Global (player dark pages; merchant light gets the light equivalents)
G1 Card depth: existing cards/sheets get a 1px top inner highlight (dark: inset 0 1px 0 rgba(255,255,255,.06); light: inset 0 1px 0 rgba(255,255,255,.9)) and a soft two-layer shadow on elevated surfaces (sheets, floating cards): 0 1px 2px rgba(0,0,0,.18), 0 12px 32px rgba(0,0,0,.28) dark / .04 + .08 light. (desdivj, dominikmartn)
G2 Press feedback: every tappable card, row and button gets a pressed state scale(.97) + opacity .92, 120ms in / 220ms out, using the existing hover-class mechanism (tune cy-pressed globally rather than per page). (amananimates)
G3 Sheet depth: when an existing half sheet opens, the page content behind dims and scales to .96 with border-radius 24px at the top, reversing on close, 420ms cubic-bezier(.32,.72,0,1). Apply in the shared sheet component(s), not per page. (desdivj)
G4 Numbers: all stat numbers use tabular-nums; existing stat rows (station distance/time, chapter stats, finale stats, settlement, earnings) count up from 0 over 600–800ms ease-out when first shown. (BThree, dominikmartn)
G5 Accent only on the key figure: where a line contains the key figure (checkout total, progress x/y, 剩 N 天, ¥ amounts), colour only that figure with the look accent / brand accent; the rest stays secondary grey. (desdivj)
G6 Kickers: existing small labels above titles (e.g. 「第 1 章 / 共 2 章」, 「已通关」, 「城市定向」) become 11–12px, letter-spacing .12em, 60% opacity, consistent everywhere. (BThree)
G7 Entry rhythm: when a page/sheet appears, its existing blocks enter with 12px rise + fade, 420ms cubic-bezier(.32,.72,0,1), staggered 40ms in document order; primary action last. Reduced motion → no entry motion. (Kubai087)
G8 List rows: existing list rows with thumbnails: thumbnail radius 12px, title weight 600, subtitle grey, hairline divider inset to start after the thumbnail (no full-width lines). (Kubai087)

## Screen-specific (existing screens, existing elements)
S1 Play station card (星尘/纸本/末世/夜城): look-tinted soft glow behind the card's top edge (radial, 18% accent, blur), G1 highlight, G4 count-up on distance/minutes, G2 on 导航/开始任务, card entry G7.
S2 Chapter start (the existing dot 「01」 + seal + 读下去 screen): keep it exactly; add a slow halo pulse behind the dot number (scale 1→1.04, opacity .5→.8, 3s loop via animate API), faint look-colour sparkles drifting (6–10 dots at ≤10% opacity), stats G4, 读下去 enters last (G7).
S3 Finale/通关 card (as fixed by x-design1 D2): keep layout; add rays + glow behind the card in look accent at low opacity, a few drifting sparkles, stats G4, buttons last.
S4 Free-explore pack: existing foil pack gets a continuous diagonal sheen sweep (3s, animate API loop), idle float ±6px, tap squash (scale .96 then 1.04) before opening; existing card fan gets a small spring overshoot.
S5 Theme detail: hero image parallax on scroll (moves at 0.5× scroll, existing nav collapse unchanged), stat strip G4, 去票夹/报名 button G2.
S6 Wallet 票夹 ticket card: existing card gets subtle perforation notches on both sides at the stub line and G1; 开始游戏 G2.
S7 Checkout/结算: G5 on 总计 amount; pay button G2; agreement checkbox press feedback.
S8 Home: carousel cards G1 + G2; list sections G8; city-location card (now inline) G1.
S9 ⋯ tools menu: existing menu scales in from the top-right corner (transform-origin at the button, .42s cubic-bezier(.32,.72,0,1)), items stagger 30ms; existing rows unchanged.
S10 Merchant workbench/store/earnings (light): G1/G2/G4/G8 light variants only.

## Constraints
WeChat animate API (this.animate/clearAnimation) or transitions only; NO new WXSS @keyframes (ratchet); no comments inside CSS values; respect reduced motion and the in-app 动态效果 switch; main package < 2048 KiB; no layout/copy changes; UI-integrity must stay PASS with no new baseline entries.

## Addendum (owner, 2026-11-12): the references are a DESIGN + MOTION LANGUAGE for the whole app
The X videos are not fixes for specific pages; they define how 城瘾 should look and move everywhere. S1–S10 are examples. Build the language into shared components/tokens and roll it across EVERY page (player, merchant, club, author, profile, messages, settings, wallet, maps, editors, sheets, empty/error states), without changing any layout, copy or colour scheme.

### The language (from all references)
L1 Depth: surfaces are layered — page < card < floating card < sheet; each layer has its own highlight + shadow (G1), sheets push the page back (G3).
L2 Continuity: a result appears where the user acted — buttons morph into their result state (loading → done) in place, rows update in place, cards change state instead of navigating away whenever the existing flow allows.
L3 One hero per moment: during a reward/completion/success, everything except the hero dims; the hero gets glow/rays; text arrives after the hero; the button arrives last.
L4 Choreography: elements enter in document order with 30–40ms stagger, 12px rise, 420ms cubic-bezier(.32,.72,0,1); exits are faster (220ms); nothing pops in without motion; nothing moves without purpose.
L5 Physicality: press = scale .97; release springs back (cubic-bezier(.34,1.56,.64,1)); toggles/checks pop with a small overshoot; drags follow the finger.
L6 Living details: small things are subtly alive — foil/glass surfaces have a slow sheen, hero icons breathe (scale 1→1.03, 3s), progress fills animate, numbers roll.
L7 Type & figures: big confident titles, secondary text grey, key figure in accent, tabular numbers, consistent kickers (G6).
L8 Restraint: one accent per screen, consistent radius family (12 / 20 / 28), consistent 16/24 spacing rhythm; reduced motion and the in-app switch turn motion off cleanly.

### Rollout
Phase 1 (lane detail-pass): shared foundation + S1–S10.
Phase 2 (lane detail-pass-2): apply L1–L8 via the shared foundation to every remaining page in app.json and all subpackages; per page group before/after compares; same "no layout/copy/colour change" rule.
