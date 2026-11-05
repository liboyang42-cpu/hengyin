# Codex play/map result

Final source SHA: `40c3a6770709a6097dd19fb9fa49af260162a3d6`. Branch: `integ/launch-test-20261103`.

## What is actually new

The cloud K8 report (`7c8abe40d` / `52aadba2d`) and cloud map report (`52c5ca6b8` / `89b6106f2`) arrived during this run. I read both and removed the overlapping local work before pushing. The earlier local map/play commit is kept only on a local backup branch; it was not pushed. The final patch is based on `5dbe613bc` and later integration updates, not the old baseline.

| Item | New source/tests | What remains |
|---|---|---|
| N7 repeated failure screens | `pages/play/utils/r5-player-runtime.js` skips the extra standalone verdict for a retryable plain failure. `playkit-view.js` and the terminal template retain the author's reply and images on the existing retry screen. | New unit proof only; no new rig/phone walk. Other result paths keep their existing behavior. |
| N10 daily sign layout | Inline sign has one back/continue route. Outer surface is dark; the thermal receipt remains paper. The inline note sits after the receipt instead of covering its text. | Server still does not save/relay the typed note. No new relay mechanism or misleading local persistence was added. This needs an owner decision. No layout screenshot from this run. |
| N13 held chapter map/title | `pages/play/index.wxml` hides next-station navigation while the chapter is held. The header uses the chapter currently being read. | Led-session walk still needed. The test checks the actual template bindings, not an end-to-end run. |
| F14 instructions | Removed the redundant predict instruction and three duplicate stopwatch instruction lines. Targets/tolerance, local-measurement and reward disclosures remain. | Sensor technical copy is still awaiting the owner decision. |
| F16 redundant submit | Passed classification no longer leaves its submit button above story continuation. Finished inline QA hides its inactive submit button. | Grey wrong-answer treatment and guess-image verdict were not fixed/verified here. |
| F17 advanced text answer | `AdvancedGameRuntimeServiceImpl` exposes TYPE `answerText` only when judging is enabled, author `reveal=true`, and server state is finished. Client shows it in the normal or failed terminal path. Java regression covers before-finish and reveal-off negatives; Node checks the projection and terminal feedback. | Backend compilation passed on the remote hook; Java unit execution belongs to final CI. Legacy led text-question reveal is still unfinished. |

New focused regression: `chengyinhub-xcx/tests/unit/codex-play-map-leftovers.test.js`. No new dependency, endpoint, migration, authentication rule, money rule or settlement file changed. `danger-actions.js`, including `club.enroll.refund`, is left to the coordinator/cloud map patch.

## Requested items already handled elsewhere

These are the cloud lanes' results, not new work or new walks by this worker:

- **Map j4 N1, N2, N3, N4, N5, N6, N8, N9, N10, F14, F15, F16:** retained cloud K7's implementation and rig evidence. See `ROUND12-K7-MAP-RESULT.md`, including its before/after shots. No map style/key/console setting changed. I discarded my duplicate pin/chip, date, text-scale, empty-team, merchant-control, route-delete, avatar and finish-layout patches.
- **Play N1–N6, N8:** earlier K7/K8 fixes retained. N3's stored station progress after quest settlement still needs independent readback.
- **N9, N11, N12, F13, microphone denial and hidden main button:** retained new K8's fixes. Dropped local duplicate stopwatch/album/microphone work. K8's `used` attempt contract and album auto-close remain intact.
- **N14:** new K8 found hidden DOM text, not a visible bug; speculative local change discarded.
- **N15:** coin-flip first continue tap remains unreproduced/unfixed.
- **N16:** retained honesty notices and existing zero-carbon-row fix; did not weaken the C1 tests.
- **N17:** retained the existing P-22 owner rule to display zero without data.
- **N18:** K8 reproduced the opening gate consuming the first tap. It reported no product defect; no speculative patch added. Phone check remains.
- **F15 ending effects/looks, F18 editor look sheet:** not changed or visually walked here. Existing `theme-look.test.js` passes; that does not prove the editor looks right.
- **248/267 shapes:** read K8's editor-config/rig findings. No production template copied. Compass skip bypassing the co-located check needs an owner game-rule decision; combined album/random-card behavior remains K8's recorded finding.
- **K2 follow-up walks:** guest club, stale login, guest 我的参与 and no-game QR have cloud map-lane rig results. Solved-station-on-reopen still lacks the requested walk. I did not repeat or claim these walks.

## Verification

- Focused Node regression: **81/81 pass** on the final source tree, including new leftovers tests, existing K7/K8 play, album, stopwatch and theme-look suites.
- Action-ledger tests: **46/46 pass**, run alone against the stable tree. Inventory check: **PASS, 5,719 controls, 0 evidence results**. Zero evidence results is not runtime acceptance.
- `ui-integrity.js`: **UI-GATE-0:PASS**. No waiver or debt baseline changed in the final patch.
- Existing pre-commit hook: remote backend **compile passed**; JS/JSON syntax and CSS hardcode gate passed. This is not a Java unit-test result.
- Public patch gitleaks scan: **no leaks found**. `git diff --check` passed before committing.
- `preflight-release.js`: main package **2.98 MB**, below 3,145,728 bytes. **FAIL / release blocked** only by missing dynamic action evidence (`pending=5719, nonPassed=0`); no gate weakened.
- CI on source `40c3a6770`: automatic runs queued/running when checked. No green CI claim; final clean run belongs to the coordinator.

Proof logs: `integ/proof/codex-play-map/`. No DevTools opened and no new walkthrough/screenshot produced in this run. The inspected old merchant-map screenshot was before evidence only. Unit/static checks do not prove real GPS, camera, permission dialogs, gestures, native map callouts or sensor readings.

The initial CI failure logs inspected showed club D3 consequences and the fee mapper architecture boundary; those belong to the coordinator. Final clean CI is explicitly reserved for the coordinator after all workers report. No master push, merge, deployment or production access happened. No local Java/Maven, MySQL, Redis or Docker engine started; existing commit-hook compilation used the remote test VPS.

STATUS: PARTIAL - unique source fixes delivered; preflight still blocks on missing dynamic action evidence; legacy F17, remaining F16, F18/ending visual review, coin-flip reproduction, stored-progress/solved-station walks and owner game-rule/copy decisions remain
