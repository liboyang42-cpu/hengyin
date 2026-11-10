# qx-u3 motion (ref 3): RESULT (2026-11-10)

Branch `claude/qx-u3-20261109`, cut from `origin/staging/questo-review-20261110` at `a7b5b5d5`. The staging RESULT.md had a STATUS line when this lane started (`STATUS: PARTIAL`, the qx-s0 run), so the lane did not have to wait. The draft PR goes into `staging/questo-review-20261110`. Nothing was merged or marked ready, and nothing was pushed to master, integrate or staging. No production, VPS or private-repo access was used.

## What the owner will see

- **Success morph (ref 3 「Morph」).** On a success moment the button's pill shrinks into an 88 rpx circle with a spinner. When the server confirms, a check pops in with one pulse ring. After a short hold the page's own success state appears. Where that state keeps the button, the pill grows back wearing the new label. Failure and "result unknown" never show the check.
- **Stacked sheets with depth (ref 3 「Stacked cards」).** When a sheet opens on top of a sheet that is still open, the lower one eases to scale .94, moves up 10 px and dims. Before, it jumped there instantly (.96 / −22 rpx). The sheet host decides this from opening order, so pages no longer pass `stacked` / `under`.
- **Reduced motion.** The circle and check states switch instantly with no hold and no headline rise. Stacking depth snaps straight into place.

## Items

| ID | Status | What changed | Files | Verified | Unverified on a real phone |
|---|---|---|---|---|---|
| U3-KIT morph kit | fixed | `cy-btn` gets opt-in `morph` / `done` props. The pill → circle change is a `clip-path: inset()` transition, so the layout does not move. The spinner reuses the existing `cy-btn-spin`. The check and pulse ring are transitions only, with no new keyframes. `utils/morph-success.js` holds the check for 720 ms. It writes the page's existing success state **before** removing `done`, and writes `done` in the same `setData` as `busy:false`, so the pill never flashes back for a frame. Reduced motion skips the hold. Buttons without `morph` behave exactly as before. | `components/cy/btn/*`, `style/morph-mark.wxss`, `utils/morph-success.js`, `style/reduced-motion.wxss` (2 selectors) | `qx-u3-motion.test.js` (节拍, 减动效, cancel, cy-btn guards); rig strips below | `clip-path` interpolation on older Android X5/XWeb |
| P5 seat claim | fixed | 领取席位 morphs. The headline 「已领取同行席位」, the seat row and 「进入本局」 appear after the check. Network-unknown and failure branches are unchanged and never reach the check. | `subpackageJourney/pages/landing/index.{js,wxml,wxss}` | Rig, real landing page, user 9009: `shots/P5-seat-{before,after}{,-strip}.jpg`, `P5-seat-after-f1..8.jpg`, and reduced motion in `P5-seat-reduced-after-strip.jpg`. **HARNESS:** the `/api/play/seat-invitations/claim` response is stubbed (`{seatNo:2}`, 500 ms). On this base, the real `/invite` on the fixture order answers 409 「该订单不含可邀请的多人内含席位」, so no real token exists. | A real claim on a multi-seat ticket |
| P1 gift claim | fixed, not shot | The same landing button and `settle` call as the seat claim. The headline 「礼物已领取」 appears after the check. | same as P5 | Unit/contract test only. The rig DB has 0 `pms_gift` rows, and the gift **read** drives the whole screen, so stubbing it would amount to redrawing the page. | Everything visual |
| /use theme adoption | fixed | Both 「应用」 (direct adoption) and the adopt sheet's 确定 morph. After the check, the sheet closes and the editor opens. | `pages/templatedetail/topic-detail/index.{js,wxml}` | Rig, **fully real**: user 9003 saved fixture topic 990014 as template 990015 through the real `/api/template/topic-template/save-as`, then tapped 应用 and the real `/use` created a draft. The rig adds 500 ms latency to `/use` so the in-flight state is visible. Shots: `use-adopt-{before,after}{,-strip}.jpg`, `use-adopt-after-f1..8.jpg`. Before: no feedback, then a hard jump to the editor. | — |
| UG-05 hint unlocked | fixed, not shot | On the unlocked row, the action text fades and a spinner and then a check appear in its place. The hint content rises in after the hold (`fresh` → `cy-rise-in`). The data is written immediately, so the existing b1 tests still read it synchronously. Re-viewing an unlocked hint (重看) gets no check. Reduced motion shows the content straight away. | `pages/play/utils/play-help-panel.js`, `pages/play/play-help.{wxml,wxss}` | `qx-u3-motion.test.js` (busy → done → clear; reduced). The b1 suite `qx-b1-play-help-panel.test.js` still passes. | Not reachable in the rig: the server refuses self-play runs (`play_instance_id='default'`), as recorded in staging Cross-lane 3 |
| U03-02 publish success | fixed | The editor's bottom CTA morphs during 发布. On success, the result is written immediately and the result sheet opens after the check hold. The existing tests read `resultSheet.title` synchronously, and that contract is kept. On failure the pill comes back and the existing fail sheet opens, with no check. | `pages/publish/fabu/index.{js,wxml}` (JS line count unchanged, so the line-anchored UI baseline does not move) | Rig, real: adopted draft 990017, 下一步 → 预览 → 完成. The real server refuses with 「媒体来源未经审核，请重新上传」, because the copied cover is not an approved asset (the same blocker class as b1). The rig adds 900 ms to `/api/topic/v2/update`. The shots show the busy circle, then the real failure with no check: `U03-02-publish-{before,after}{,-strip}.jpg`, `-after-f1..8`. The success branch is covered by the fabu suites (`fabu-edit-existing-topic`, `fabu-server-draft`, 94/94). I tried a stub of the save response, but the editor's readback rejects it (「保存结果待确认」), so it is not used as evidence. | The success check followed by the result sheet |
| STACK sheet depth | fixed | `utils/sheet-depth.js` is a per-page registry in opening order. `behaviors/sheet-depth.js` is attached to `cy-sheet`, `cy-scene-sheet`, `project-drawer` and `project-layer`. All four use the same .94 / −10 px / brightness .7, with a transition for the change (`--cy-motion-sheet-in`). The `page` variant of `cy-scene-sheet` only dims, because it cannot carry a transform (its fixed nav bar would follow it). | `components/cy/sheet/*`, `components/cy/scene-sheet/*`, `pages/topic/components/project-{drawer,layer}/*` | Rig, real: editor 主题详情 (full sheet) → 画面风格 「选择」 opens the look sheet on top. `STACK-look-{before,after}{,-strip}.jpg`, `-after-f1..6`. Before: an instant jump. After: an eased step back over about 250 ms. | — |
| W2-05 招商 / 邀约 stacks | fixed, not shot | `project-host` no longer passes any `under=`. The drawers and layers step back through the registry. | `pages/topic/components/project-host/index.wxml` | Contract test. In the rig, the fixture theme is free, so 「开放商家承接申请」 answers 「免费主题不能转为商业主题,如需收费请重新发起主题」. The stations stay 「未开放」 and render no 档位 / 招商 layer buttons. A real sheet-on-sheet stack in the editor is shot instead (row above). | The workbench stack on a paid theme |
| Card → detail | needs owner | No change. The order detail is a `page`-variant scene: the owner ruled on 2026-10-27 (G-1) that order detail 「不能是弹窗」. Theme detail is a real page. LANES says "only where the destination is already a sheet. Pages keep normal navigation", so neither qualifies. **Default implemented: normal navigation.** If the owner wants it on a sheet destination such as 参与详情 (`member-participation-detail`, full sheet), the FLIP maths in `behaviors/morph-entrance.js` can drive `cy-scene-sheet` from a tapped card rect. | — | — | — |

## Gates

- **Unit tests.**
  - `node --test chengyinhub-xcx/tests/unit/qx-u3-motion.test.js`: 10/10.
  - Touched suites (`action-ledger`, `qx-b1-play-help-panel`, `sheet-*`, `scene-sheet*`, the fabu publish suites): all pass.
  - Full suite (`node --test chengyinhub-xcx/tests/unit/*.test.js`): **16976 tests, 16939 pass, 37 fail**. The staging base gave 16966 tests and 39 fail.
  - **No new failures.** Two staging failures now pass: 「release 前重建 inventory…」 and 「共享组件名称、角色、状态与文本替代完整」.
  - The 37 remaining failures are all pre-existing on staging, for example `get-auth-matrix` and `nav-page-title-spacing` (staging Cross-lane 1–2).
  - During development, four fabu publish tests went red because the result sheet was delayed. They are green again because the result is now written synchronously.
- **`ui-integrity.js`:** `UI-GATE-0:PASS`. The A11Y gate needed `.btn.btn--morph` and `.cy-mm__spin` added to the system static fallback list in `style/reduced-motion.wxss`. No baseline entries were added.
- **Action ledger:** `build-action-ledger.js --write` → 5874 controls. Only line moves changed.
- **Main package:** 3,233,159 → 3,246,467 source bytes. The estimate goes from 1998.6 to **2006.8 KiB**, under 2048.

## Rig

The rig is `claude/cloud-walk-20261101-rig`, with staging's `rig-up-sha-param.diff` and `rig-app-oss-seam.diff`, qx-c1's `rig-app-media-seam.diff`, and the club `harness-patches.diff`. The candidate is this branch, rebuilt with `mpweb/build.sh`; the "before" shots use the staging head bundle. Placeholder `CHENGYIN_OSS_*` values are exported for `app.py` as staging did; they are not credentials.

- **Docker Hub** answered 429. I pulled the pinned digests from `mirror.gcr.io` and tagged them locally; the daemon config was not changed.
- **Fixture:** staging's `free_orient_theme.py` (topic 990014). Template 990015 and draft 990017 were created through the real endpoints described above.
- **Driver:** `qx-u3/rig/frames.mjs`. It records frames with CDP `Page.startScreencast` at real speed, so no animation is slowed. Each frame is stamped with ms after the tap. The driver can add latency to one endpoint, and, only where stated above, return a stubbed response from one write endpoint. Scenarios are in `qx-u3/rig/scenarios/`.
- **One rig artefact, fixed defensively:** the rig's hover emulation left `btn--press` on the button while it was busy, which showed a grey circle. The morph state now overrides the press tint, which also covers a finger still resting on the button on a real phone.

## Cross-lane

- **qx-u1 (gestures)** also edits `components/cy/sheet/*`. This lane touches only the `behaviors` list, the root class expression (`(stacked || _under)`) and the old `.sh--stacked` rule, which is replaced by the depth rule plus a panel transition. If u1 moves the drag to WXS, keep `sheetDepth` in the behaviors and the `.sh.sh--stacked` rule. Under sheets get `pointer-events:none`, so they never start a drag.
- **qx-u2 (onboarding)** restyles the P1 landing. This lane changes only the CTA's `morph` / `done` attributes, the `jn-title` rise class, and the two claim success branches in `landing/index.js`. u2's white pill CTA should keep `morph loading done`.
- **Shared file:** `style/reduced-motion.wxss` gains two selectors in the existing static list.

## Needs owner

- **Card → detail.** Both named destinations are pages, so normal navigation is the default (see the row above).
- **Hold length.** 720 ms, long enough to see the check and pulse. The default is implemented. Shorten `HOLD_MS` in `utils/morph-success.js` if it feels slow on a phone.

STATUS: PARTIAL - morph and stacked-depth implemented and tested; rig frame sequences for seat claim (claim response stubbed), /use (real), publish in-flight/failure (real; success unreachable: unapproved media) and sheet-on-sheet depth (real); gift claim, hint unlock (UG-05) and the W2-05 workbench stack not shot (no gift fixture, server refuses self-play hint runs, free theme cannot open recruit); card→detail needs owner (both destinations are pages)
