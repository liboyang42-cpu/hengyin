# Round 3 (2026-11-10): motion and page references + implicit state machines

BRIEF.md and ROUND2.md (global rules) stay binding. Each design lane here **stacks on a round-2 lane**: it waits for that lane's `RESULT.md` to have a `STATUS: ` line, branches from its branch, and opens a draft PR into it. RESULT.md in Simplified Chinese, shots under `r3-<lane>/shots/`, last line STATUS.

**Motion system (binding for every design lane from now on): `MOTION.md`** (tokens, primitives M1–M18, per-moment timelines, package/gate rules — no new `@keyframes`: the ratchet is fixed at 146). **`IMPACT.md`** ranks the 30 highest-impact page changes and names the owning lane; do the rows for your lane. Re-fetch the brief branch before you start coding.

All references are in `refs4/`. Look at every frame. Copy motion and structure, keep 城瘾 styling (dark player, `--cy-*` tokens); never copy names, art or copy. Respect reduced motion; transforms/opacity first; `canvas type="2d"` only where needed; keep the main package < 2048 KiB.

Interaction-pattern sites the owner named (React web libraries; patterns only, nothing to install): **uiarc.dev** (morph select that grows into a list, elastic slider that stretches past its ends, toast stack that fans, kanban cards that lift/tilt/spring) and **detentui.com** (region wheel, upload, rewind inputs).

## r3-visual (stacks on r2-visual)

1. **票夹 / ticket holder** — `refs4/ticket-holder-frames.jpg`: full-bleed blurred backdrop taken from the current ticket's cover; one big card at a time; horizontal swipe where the leaving card curls/peels like a page turn while the next slides in; header 「即将开始 ▾」-style filter + add + avatar. Apply to the player's ticket wallet (票夹) using existing ticket data.
2. **收集册 album entry** — `refs4/album-ring-stack-frames.jpg`: a ring-bound stack of coloured cards; each page flips over the ring to reveal the next; big serif headline per page; dots + round arrow button. Use it as the album opening / section browser (combine with round-2's album transition; the owner: 「Access the collection album page」).
3. **分享赚积分 / share and earn** — `refs4/share-earn-credits-frames.jpg`: scan/QR card → amount morphs in → a card slides up into place → green check; the points pill in the top bar counts up (1,650 → 1,760). Apply to the invite / referral reward page (invite records) — only real server facts (reward amount, credited points).
4. **Loading and tab transitions** — `refs4/load-tab-transition-frames.jpg`: skeleton → content with staggered fade-up rows; tab switch slides content. Apply to the pages you touch.

## r3-share (stacks on r2-finish)

- **Share page** — `refs4/share-page-cards.jpg`: one bold full-colour card per share target, illustration/cover on top, our logo badge in the middle, big two-line headline, one primary button. Use for the theme / result / badge share pages and the share card generator (keep r2-finish's flow).

## r3-pass (stacks on r2-store)

- **主题通票 / theme pass purchase motion** — `refs4/pass-purchase-motion-frames.jpg`: dark → warm orange gradient; a 3D gold object (key/vault) assembling from pieces, coins with +N popping, a dial that turns to unlock, streak chain, badge carousel with rarity line, paged dots + white pill 「继续」, 「跳过」 top-right. Apply to the pass / membership purchase page and its success moment (structure from refs3/STRUCTURE.md). Use our own objects (city pass / ticket / seal), not a vault or Questo/third-party art.

## qx-sm — implicit state machines (independent; base staging)

Owner: "Find all of the implicit state machines in my app. Make the best decision that will elevate all three: UX, DX and AX always, without breaking anything."

1. **Inventory.** Find implicit state machines across backend (status/phase ints and strings, nullable timestamps used as state, boolean pairs, scattered `if status ==` transitions, Quartz-driven transitions) and mini-program (page flags like `loading/empty/error/submitting`, sheet open/close stacks, multi-step flows, play/run/session state, payment/registration/ticket lifecycles, editor modes). For each: where the states and transitions live, who writes them, illegal or unreachable transitions, missing states (unknown/expired/refunding…), races, and user-visible symptoms. Write `docs/architecture/state-machines/INVENTORY.md` (English) ranked by risk × user impact.
2. **Decide and implement the top items** that are safe: make the machine explicit (single enum + transition table/guard per aggregate on the backend; a small shared `createMachine`-style helper or per-flow reducer in the mini-program), route existing writes through it, and add characterization tests first so behaviour is proven unchanged; add tests for illegal transitions. Prefer one well-done machine per area over touching everything. **Never change payment, refund or settlement outcomes**; for money aggregates only add guards/assertions that reject transitions the code already treats as impossible, with tests, and list anything else under needs owner. No DB schema changes unless a migration is truly required (new file, registered).
3. **AX:** add a short `docs/architecture/state-machines/README.md` that tells agents where each machine lives and how to add a state/transition safely, and a lint/test that fails if code writes a status column outside its machine where feasible.
4. Avoid files the round-2/3 UI lanes are editing (pages/play, publish editor, merchant pages, finish/share pages); if a machine lives there, document it and leave the refactor for after merge.
Branch `claude/qx-sm-20261110` from `origin/staging/questo-review-20261110`; one draft PR into staging. RESULT.md in Simplified Chinese at `docs/delivery/questo-ui-20261109/qx-sm/RESULT.md`.
