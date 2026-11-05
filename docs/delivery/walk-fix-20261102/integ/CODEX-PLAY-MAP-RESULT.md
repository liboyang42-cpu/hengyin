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


## Follow-up in progress, 2026-11-05

Delivered source commits: `a03357ea4` (daily-sign-only, wrong-answer surface and price verdict, legacy reveal, meaningful contract updates), `de904286c` (album before card, compass check guard, persisted quest progress), `0d82371f1` (coin result waits for deliberate continuation), `fa48e21f0` (inline hidden-object image height). This extends the original report; the old unresolved list above is historical.

Verified browser-rig walks, screenshots opened and inspected:

- **Legacy F17:** synthetic text-answer station 82 has no coordinates. Answer `1873` returned the real answer receipt; the next screen shows both the author's completion feedback and server answer reveal. SQL status is 1. It no longer loses the reveal when author feedback exists. This is a personal legacy walk, not a led-session claim.
- **N15:** fresh topic 990027 / node112: real FLIP_COIN, wait for visible result, scroll to the visible Continue Story button, tap once. One arrive request succeeds and the run reaches its finish screen. No automatic continuation timer hides the result first. The early driver clicked below the viewport; that attempt is not counted as a product failure or pass.
- **F16 price/guess-image verdict:** topic990028 shows `不是` on the selected wrong image, then `就是它` on the correct image and Continue Story. Both screenshots inspected. QA's wrong-answer surface and removal of the inert finished submit remain in source and focused tests; the inspected generic wrong-answer shot is `before-state-wrong.jpg` (new frontend / old backend).
- **Daily sign:** same topic's second station opens the actual sign. No visible input/textarea remains (`NOTE_INPUT_COUNT []`); receipt and tear/reveal controls remain. Claim/poster continuation is still being walked. Saving a note or relaying one is deliberately absent by owner decision.
- **Solved station on reopen:** read cloud `ROUND12-K8-MAP-PLAYER-RESULT.md` and opened its answer/reopen-80m/tap screenshots. Its SQL-backed PASS is retained as cloud evidence; no duplicate local fix.

Rig identity: frontend127.0.0.1:18097, backend127.0.0.1:18099 -> owned VPS loopback28099. Shared scratch `cy_e2e_51`, UUID `76fd666f-d8ef-11f1-b090-d26bfff2310e`, task `codex-play-map-followup-20261105`. Running backend remains source `a2285b3dd558d40357462a87cd43a70b8d538548`; no candidate upgrade is claimed yet. Baseline c09 JAR is preserved. K10/root/pages borrow the same app and DB, with separate frontends and fixture names. My author9008 / player9004 / club7002 / title prefix PLAY-MAP-1105. No production content or credential used.

Test-only external standins are now wired to this app only, with synthetic OSS settings, fixed loopback hosts and a dedicated trusted certificate. Actual upload returned HTTP200/code200 and APPROVED image ownership. Root's edit-detail990014 parses fully after repairing only its scratch placeholder media asset. This is harness configuration evidence, not a product workaround or production configuration.

Focused checks on integration through `a8b142029`: Node85/85, hidden frame4/4; action-ledger5729 controls PASS, ui-integrity UI-GATE-0 PASS. Contracts retain target/tolerance/local-measurement/accessibility and poster palette checks; removed stopwatch captions and removed daily input are no longer falsely required. No new waiver. No branch CI read under the new owner rule.

Budget212 actual backend result on `35b3dc6df`: 285 tests, 282 pass, 2 failures, 1 error. AdvancedActionJudgement7/7; AdvancedGameRuntimeServiceImpl215/216 (the failing newly added ordinary-photo assertion belongs to K10); matrix30/30 and9/9; date-wire5/5; publication scope4/5 (chapter egg rejected); author save12/13 (ObjectMapper null). Exact failures handed to K10; its correction `a8b142029` is now integrated. No new JAR was produced by that failed run. A retry was refused `another_build_holds_lock`; no bypass or competing build. Original ThemeRuntimeMySqlIT Date ambiguity was fixed upstream during my rebase. New progress/check MySQL cases compile but have not run yet.

The two exact-shape synthetic legacy fixtures are topic990021 (17 stations) and990022 (11), each two chapters, one template per station, NULL frozen graph/story/runtime/look fields, status1/version1 and synthetic theme audio. There is no copied production content. Because the owner has not supplied the four no-coordinate validation methods, the first two stations are 0/1 and0/3 respectively; this is an explicit assumption, not exact production equivalence. Preparation and start work. The first two no-coordinate stations of990021 are completed in SQL. Theme audio has an actual rig HTML audio readback: playing, readyState4, duration3 seconds, advancing currentTime. This does not prove a phone speaker. Remaining stations/end without ending still in progress. The walk exposed a zero-height inline hidden-object image; the one-line height fix is pushed and real server hit actions now succeed.

Published-theme impact: these runtime changes also affect already-published themes, including album+random and compass+D20 combinations like shapes248/267. They do not rewrite their stored configurations. Daily sign no longer offers a note input on existing themes. Coin and legacy answer reveal apply on reopen too. No money/settlement rule changed.

Remaining now: corrected budget package and sole shared-app update; after-build N3 SQL proof; album/card order/content and compass-skip+D20 walks; led N13; remaining legacy17/11 stations and ending; daily claim/poster. New NO_BRIDGE/branches/eggs, editor polish and four-look/media-failure lanes remain with m1/m2/m3. I am continuing this list and have not declared it finished.

STATUS: PARTIAL - source fixes and several rig proofs pushed; remaining walks and corrected shared candidate package are in progress


## Follow-up proof update, 2026-11-05

- **Legacy shapes reached the end.** 990021 completed17/17 stations and990022 completed11/11 in `player_node_progress`, member9004/activity0/default instance. The two NULL coordinate stations in each completed. Both render the actual All Complete screen with matching17/11 counts and no ending configured. Screenshots were opened and inspected. The signed synthetic3-second theme audio played during walking (paused=false, readyState4, advancing currentTime); finishing stopped it. SQL shape/UUID/count proof is `proof/codex-play-map-followup/legacy-final-readback.json`. This uses the earlier explicit validation-method assumption, not copied production data or an exact station order supplied by the owner.
- **One ending polish fixed:** an absent optional ending produced a misleading configuration toast while completion still worked. Source `e8a4023a8` makes only this optional read quiet; its fallback and stale-run guard remain. New behavioral regression proves the finish callback runs once for absent ending and never for a reply belonging to an old run. Later frontend reopen proof has allDone=true/showFinish=true. Legacy has no theme runtime row; `topicCompleted` remains false in that generic-only UI field, so I am not claiming a generic runtime FINISH receipt for these plain legacy runs.
- **N13 led held chapter walked.** Own topic990030/activity19/author9008/player9004. Real joined membership, real registration and trusted-payment callback seam, then lead start. After first station, player sees Waiting for Release, gate.held=true, actual reading chapter81/title待放行章节2 while lead's released chapter remains80. Direct second-station arrive is rejected: 本章还没开启,等队长放行. Independently logged-in leader9008 calls existing unlock endpoint with expected80/target81; SQL then reads81. Screenshot and `held-readback.json` include that boundary. No led settlement was run or changed.
- **Daily sign claim walked.** Own valid fixture990031/session15: tear, direct reveal, accept. Real CLAIM_DAILY_SIGN returns200 with payload{}; signed receipt displays the poem, Save to Album and Continue Story. There is no input, photo or note relay. A new one-line minimum height is on the inner poem box: before claim, server deliberately sends no lines, and a height on the outer component alone had clipped the direct-reveal button. This fixed a real click blocker; source `3b60e789f`. Native phone album write is not proven by the rig; its canvas/save success and failure contracts remain30/30 passing.

Additional proof files are in `proof/codex-play-map-followup/`: opened completion/held/sign screenshots, focused test/static logs, sanitized API traces, SQL readbacks and rig-only adapter diff. The adapter proxies only the dedicated loopback backend and test OSS, retaining certificate verification. Rig camera/GPS/WeChat/payment remain explicit standins; none is a real-device claim. Synthetic media URL signatures and action nonce strings were removed from public logs; gitleaks scan has no findings. No credential or certificate is included.

Latest verification encountered integration drift outside play: action inventory's35 static-source locations moved after club edits, and merchant apply's existing U4 dynamic setData anchor moved359→360. Refreshed only the generated locations and that exact anchor, retaining all reasons/debt counts. No merchant implementation, control or waiver changed. Previous inventory had5729; current inventory has5741 controls after other lanes' controls. Fresh gate result is recorded with the commit that follows.

The corrected combined backend package has not acquired budget212 yet. Subsequent attempts returned exit75/another_build_holds_lock while pages K9, root money/a8-log, then pages K9 held it. Requested the next free slot for the shared candidate. No competing build, force restart, local Java or budget bypass. **Still pending:** new-JAR N3 settlement SQL retest, album→card content/order, compass skip→mandatory D20. These are the remaining three runtime walks on my list, not new scope. The rest above is delivered and walked with the stated limits.

STATUS: PARTIAL - front-end and legacy/held/sign proofs delivered; three backend-dependent walks await the corrected shared candidate package
