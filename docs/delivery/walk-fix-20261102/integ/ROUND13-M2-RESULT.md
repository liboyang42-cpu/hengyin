# Round 13, lane m2-editor-polish: result

Branch `m2-editor-polish`, cut from `origin/integ/launch-test-20261103`. Run 2026-11-05. Not a phone. One rig walk (E6, headless Chrome 390x844, backend = the rig's pinned master jar). Everything else is code change plus unit tests, **not walked**, because the walk needs a saved theme with stations that this rig run did not have.

## Per item

| Item | Status | Files | Proof | Unverified |
|---|---|---|---|---|
| E4 captions | Done, except one pinned notice | `fabu/topic-detail-sheet.wxml`, `fabu/runtime-structure-editor.wxml`, `fabu/index.wxml`, `temp/index.js` | Removed: 值域 row ("开局值在第一章…"), 进行方式 three descriptions, "与票务人数…互不影响", "节点玩法已保存" detail line, the grey hints in 编辑章节 (完成要求/章末配给/章末消耗/互斥选项 descriptions, "补做一个节点…", "先保存草稿…"), 主题详情 "最多 32 项" and "仅个人进行方式生效". Unit tests green. | Not seen on screen |
| E6 | Done, walked | `temp/index.wxss` (`.cg-scroll-pad`) | Before/after `integ/fix-shots/m2-before-temp-bottom2.jpg`, `m2-after-temp-bottom2.jpg`, both viewed: the last card now has clear space above the bar | Only the template editor with 选项问答; 保存并绑定 (from=fabu) not walked |
| E12 | Done in code | `fabu/theme-runtime.js` | New test: with a 互斥组 chosen, 全部完成 flips to 按组计数 (ANY_N with the group count), saved as such. The old test (reset to ALL after removing the group) still passes | Not seen on screen. If the owner wants the switch locked instead, say so |
| E13 | Done in code | `fabu/theme-runtime.js` | New test: with saved stations the 章末配给 reason is no longer 「请先保存本章节点」 (it says 「本章节点还没有绑定玩法」 when stations exist but have no game bound) | Not seen on screen |
| E2 | Done in code | `app.js` | A media item in review shows its own message (「文件已提交审核，通过后请重试此文件」) with no 「上传失败：」 prefix. A rejected one still reads as a failure | Not walked (needs the audio picker) |
| P1 | Done in code, backend too | `fabu/theme-runtime-survival.js`, `TeamTrailServiceImpl.java`, `TeamTrailServiceImplTest.java` (new test), `tests/unit/team-trail.test.js` | A theme that says 个人独立进行 (`runtimeScope: PLAYER`) with no `teamTrail` value is off in the editor and on the server (no sheet for a solo player); the author turning it on writes `teamTrail: true`. Team themes and configs without a scope keep the old default (on). JS tests pass | **Java test written, not run** (no Java on this box by rule; CI will run it). A solo theme that never wrote a scope still defaults on |
| P3 | Done in code, one notice kept | `playkit-scan/index.js`, `pages/play/index.wxml` | Shop wording 「扫一下门口的码」 → 「扫描这一站的二维码」; 「扫描现场那枚二维码，让这一关的答案落到你手里。」 → 「扫描现场的二维码」; trailing dot gone from 「线索藏在声音里 ·」 (now just the length). Photo judged 答对了: already fixed upstream (`HEAD_SHOT` 「拍好了」, k7 F16), no change by me | **Kept on purpose:** 「仅确认这一站的任务码」 (`SCAN_MATCH_NOTE`). `sensor-games-trust-boundary-c1.test.js` pins it as the honest statement that a scan proves the code, not presence. Owner decision needed to drop it |
| P4 | Done in code | `playkit-qa/index.wxml` | In the photo game, when the 前置说明 differs from the question it now uses the small grey lead style, so the question is the larger line. When they are the same sentence nothing changes | Not seen on screen; typography baseline fixture unchanged |
| L1 | Done in code | `pages/play/index.wxml`, `index.wxss` | The opening screen takes the existing `atmosphere--default` tokens of the chosen look (星尘 stays pure black; 纸本, 末世, 夜城 get their own default tones, button and text follow) | Not seen on screen in the four looks |
| Backpack chip 0/6 | Done in code | `pages/play/index.wxml` | The count shows only when the player holds something or the theme has items/clues. Icon stays | Not seen on screen |
| 主题详情 captions | Done (part of E4) | see E4 | | |

## Checks

- `build-action-ledger.js --write` then `--check`: PASS, 5727 controls.
- `tests/lint/ui-integrity.js`: the U5 baseline key for the `my-completed` read in `pages/play/index.js` had drifted (8524 → 8534) on the branch tip before my edits. I moved that one key and recomputed the u5Silent digest. Same code, no new entry. Result in the final line below.
- Unit tests run: team-trail, fabu-theme-runtime-config (29 pass, 3 new), generic-gameplay-ownership-ui-contract, gameplay-support-matrix-ui, sensor-games-trust-boundary-c1, k10-immersive-audio, r4-input-error-under-field-sweep, pro-editor-fabu-flow, doc2-l1-editor-d20, publish-step3-switches, chapter-boundary-config, gameplay-feedback-images-bgm.
- Main package size: not measured (the preflight script timed out on this box). The edits add a few hundred bytes of JS and remove WXML text, so a rise of more than a few KB is not expected.
- No migrations, no money code, no secrets.

## Final check results

- `ui-integrity.js`: UI-GATE-0:PASS (after the one drifted key and digest update above).
- Related unit tests (1051): 1050 pass. The one that failed (`action-ledger.test.js` 「release 前重建 inventory」) failed because the ledger was stale after my last WXML edit; I rebuilt it (`--write`, 5727 controls) and the `--check` re-run was still running when I stopped, so that result is **not confirmed**.
- Owner rule received during the run: `[skip ci]` on every commit, push at once, no waiting on CI. Followed.

STATUS: PARTIAL - code for every item is in with unit tests, but only E6 was walked on the rig (E4, E12, E13, E2, P1, P3, P4, L1, backpack chip not seen on screen); Java test for P1 not run; main-package size not measured; action-ledger check after the rebuild unconfirmed; 「仅确认这一站的任务码」 kept because a test pins it
