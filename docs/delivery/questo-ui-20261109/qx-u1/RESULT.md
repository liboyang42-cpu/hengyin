# qx-u1 gestures: RESULT (2026-11-10)

Branch `claude/qx-u1-20261109`, based on `origin/staging/questo-review-20261110` at `a7b5b5d5`. When this lane started, staging `RESULT.md` already had its STATUS line (`STATUS: PARTIAL …`), so no waiting was needed. Nothing was merged or marked ready, and nothing was pushed to master, integrate or staging. No production, VPS or private-repo access was used.

## Items

| ID | Status | What changed | Files | Verified | Unverified on a real phone |
|---|---|---|---|---|---|
| U1-1 shared sheet | fixed | Drag tracking moved from logic-layer `setData` to a WXS touch handler: 1:1 follow, written as inline `transform` on the panel. On release the sheet **dismisses** if velocity > 0.5 px/ms **or** distance > 30 % of the panel. Otherwise it **snaps** to the nearest detent. Dragging up past the top detent is **rubber-banded** (offset × 0.25) and **springs back** with `--cy-ease-spring`. On dismiss the panel falls from the finger's position, and the `sh-down` keyframe no longer restarts from 0. Only the **handle and the header** start a drag; the body `scroll-view` scrolls as before. A tap (< 4 px) is not a drag, so ✕ still works. **Reduced motion:** no rubber band, and snaps are instant. `maskClosable=false` / `dirty` use the same gate as tapping the mask: past the threshold the sheet only snaps back, and `dirty` still emits `requestclose`. If the host ignores the close, the panel is pulled back after 400 ms. A same-colour bleed below the panel's bottom edge stops the page showing through during the rubber band. It sits off-screen when the panel is at rest; inset and morph sheets don't get it. | `components/cy/sheet/index.{js,wxml,wxss}`, `utils/wxs/gesture.wxs`, `utils/gesture-physics.js` | Rig: 编辑资料 → 个人介绍 sheet, real pointer drags on the header (`shots/U1-1*`). Unit: `gesture-physics.test.js` (maths), `gesture-physics-parity.test.js` (WXS mirror = JS, with negative controls), `sheet-handle-drag-contract.test.js` (WXS driven on a fake view layer plus component side, each gate with a negative control) | 60 fps feel. Native WXS `getComputedStyle` / `getBoundingClientRect` availability by base library (falls back to velocity-only if neither exists). `change:` observer semantics |
| U1-1 scene-registry sheets | fixed | `cy-scene-sheet` half/full: the header starts a drag with the same physics and the same exit as ✕. `closable=false` or `dirty` ⇒ snap back only. Page variant unchanged (no header, no drag). Same bleed. | `components/cy/scene-sheet/index.{js,wxml,wxss}` | Unit (component side, wiring). Not shot: no scene sheet is reachable without play/roam fixtures in this run | Same as above |
| U1-1 detents | needs owner | The physics supports peek/half/full detent lists (`detentOffsets`, multi-detent release rules, tested). Every sheet currently has one detent: its own resting height. Pages are unchanged. Default implemented: single detent. Question: which sheets, if any, should rest at a lower detent (e.g. `fixed="full"` editors stopping at half)? | `utils/gesture-physics.js`, `utils/wxs/gesture.wxs` (`data-detents`) | Unit | — |
| U1-2 floating corner player | needs owner / not done | I searched for floating corner widgets. The play page "music corner" is `playkit-musiccorner`, an **inline story card or a cy-sheet**, not a floating widget. The radar/compass sits in the top bar's flow. Nothing else is a draggable-size floating widget (FABs are fixed actions). Default implemented: **nothing invented**. A PiP corner snap needs a floating mini player to exist first. Question: should playing music collapse into a floating mini player when its card scrolls away? If yes, the corner snap (four safe-area corners, avoiding nav and tab bars) can be built on `gesture.wxs`. | — | grep of `position: fixed` widgets in play and components | — |
| U1-3 swipe-back | fixed | Full-screen overlays opened as pages-in-page close on a horizontal swipe from the **left edge (≤ 32 px)**. They use the same thresholds (0.5 px/ms or 30 % of the width), slide out, and then call the host's existing close: the album uses `close` (the same exit as 收起相册), and story preview (剧情预览, fabu `.sp`) uses `closePublishCheck` (the same exit as ✕). Otherwise they spring back. A vertical move or a leftward move aborts. Album paging (`album-glass`) leaves the left edge to the swipe. Reduced motion closes without the slide. | `pages/play/components/playkit-album/index.{js,wxml}`, `pages/play/components/album-glass/index.js`, `pages/publish/fabu/index.{js,wxml}` | Unit: `swipe-back-overlay-contract.test.js` (WXS physics on a fake layer, wiring, negative controls). Not shot: the album hangs the headless rig (documented in staging RESULT, `staging/rig/album-hang.txt`); story preview needs a complete authored draft that this run did not author | Edge swipe on device versus WeChat's own page swipe-back on non-custom-nav pages (both are pages-in-page here, so the page swipe only fires when the overlay is closed) |

## Screenshots (`shots/`, 390 px, rig, JPEG q70)

The sheet is 编辑资料 → 个人介绍 (`pages/gerenziliao`, player dark). Before = staging base `a7b5b5d5`; after = this branch. The drags are real pointer events on the sheet header.

| Frame | Before | After |
|---|---|---|
| Open (baseline must be identical) | `U1-1-open-before.jpg` | `U1-1-open-after.jpg` |
| Finger 140 px down, holding | `U1-1a-follow-before.jpg` (panel doesn't move; the highlighted title text is the browser's mouse selection, a harness artefact) | `U1-1a-follow-after.jpg` (panel follows 1:1) |
| Finger 140 px above start, holding | `U1-1b-rubber-before.jpg` | `U1-1b-rubber-after.jpg` (≈ 35 px, ×0.25, bleed fills below) |
| Released above | — | `U1-1b-springback-after.jpg` (back at rest) |
| Slow drag to 45 % | — | `U1-1c-past30-after.jpg` |
| Released | `U1-1c-dismiss-before.jpg` (still open; the text in the field comes from the harness mouse-selection drag) | `U1-1c-dismiss-after.jpg` (falling from the release point) → `U1-1c-closed-after.jpg` |

I inspected every shot myself. The after frames have no overlap or clipping. In `U1-1b-rubber-after` a faint seam shows where the blurred glass panel meets the flat bleed; on a phone the panel's backdrop blur covers the same area.

## Rig

- The rig is `claude/cloud-walk-20261101-rig` with staging's `rig-up-sha-param.diff` and `rig-app-oss-seam.diff`. `up.sh` ran with `SHA=8e21f9ba`.
- **New rig-only shim:** `rig/rig-wxs-event-shim.diff`. The rig runtime called WXS event functions as `h.call(owner, evt)`. The shim passes `(event, ownerInstance)`, adds `event.instance`, and provides `setStyle`, `addClass`, `getState`, `callMethod`, `selectComponent` and `getBoundingClientRect`, matching the device API. Without it, no WXS gesture can run in the rig. It is not product code.
- The driver is `rig/gesture-shots.mjs <before|after> <dir>`.
- `change:prop` observers are not emulated by the rig. That leaves the "host ignored the close" pull-back unexercised in the browser; it is unit-tested only.

## Gates

- `node chengyinhub-xcx/tests/lint/ui-integrity.js` ⇒ `UI-GATE-0:PASS`. No baseline entries were added. Two hits found on the way were fixed in code: a dynamic `setData` in cy-sheet, and a one-line shift in fabu that moved two existing anchors.
- New tests: `gesture-physics` (11), `gesture-physics-parity` (5), `sheet-handle-drag-contract` (25, rewritten for the WXS path), `swipe-back-overlay-contract` (11). All pass.
- The sheet / scene / wxss / motion / ledger suites (683 tests) have 7 failures. All 7 also fail on the untouched base `a7b5b5d5`: the scene twin single-source group and the wildcard-selector lint. None are from this lane.
- **Action ledger (shared file, minimal edit):** the builder failed closed on `{{module.fn}}` event bindings, because the repo had none until now. `build-action-ledger.js` now treats that form, which WeChat only allows for WXS functions, as a view-layer handler with no logic-layer action. The close methods it reaches are already registered through ✕ / 收起. After the rebuild there are 5869 controls: −5 event contracts (the cy-sheet grip's 4 touch handlers and fabu `.sp`'s `catchtouchmove="noop"`). The count assertions in `action-ledger.test.js` were updated to the measured values, with a note.
- Main package: +14.8 KB of new source (`utils/gesture-physics.js`, `utils/wxs/gesture.wxs`), ≈ +9.4 KiB by the 0.633 estimate.

## Cross-lane

- qx-u3 (motion, stacked sheets): `.sh--stacked` sheets have `pointer-events:none`, so they never start a drag. If u3 moves the depth styling into the sheet host, the bleed shadow on `.sh--bottom .sh__panel` must stay off-screen under the 0.94 scale. Check it with an open stack.
- qx-u4 (iOS): the same parameters (0.5 px/ms, 30 %, ×0.25) are in `utils/gesture-physics.js` if the native sheets should match.

STATUS: PARTIAL - floating corner player not built (no floating widget exists; needs owner), multi-detent resting points left to owner (single detent per sheet), album and story-preview swipe-back and scene-sheet drag unit-tested but not shot (album hangs the headless rig; preview needs an authored draft)
