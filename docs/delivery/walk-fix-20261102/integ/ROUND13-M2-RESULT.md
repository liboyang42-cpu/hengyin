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

## Second pass: rig walks and checks (this branch's own jar, rebuilt in the rig)

Rig built from this branch (jar included), not the pinned master. All shots viewed, under `fix-shots/m2-*`. Not a phone.

| Item | Walked? | What I saw |
|---|---|---|
| E4 主题详情 | Yes | `m2-after-detail-s2/s3.jpg`, `m2-mode-sheet.jpg`: 值域 row gone, 允许再玩一次 has no caption, 进行方式 cards are name-only. 玩法支持 line still there (pinned by a test) |
| E4 编辑章节 | Yes | `m2-after-chedit2.jpg`: no grey hints. Reason lines (e.g. 请先在主题详情里选进行方式并保存) stay |
| P1 | Yes (editor) | `m2-after-detail-s2.jpg`: with 进行方式 未选择, 同场路线 is on; `m2-after-solo.jpg`: after choosing 个人独立进行 it is off. A solo theme that never picks a mode still defaults on |
| L1 | Yes (state set through the real page, not a played run) | `m2-after-intro-{star,paper,survival,cyber}.jpg`: opening screen keeps pure black in 星尘, warm dark with cream button in 纸本, blue-black in 末世, near-black in 夜城. I forced the page state, since a story theme with a started run needs the full K10 authoring |
| E6 | Yes | earlier pair |
| E12, E13 | No | need saved stations (a saved theme). Unit tests only |
| E2 | No | needs the audio picker and review queue |
| P3, P4, backpack chip | No | need a started story run with a station and a photo/scan kit; setting the chip's state had no effect without a running story. Unit tests and code only |

Checks:
- **P1 Java test** (`TeamTrailServiceImplTest`, run in the cloud rig container with UTF-8 locale): passed, exit 0.
- **action-ledger `--check`**: PASS, 5741 controls (rebuilt after the rebase; it had drifted because of upstream changes).
- **ui-integrity.js**: passed on my tree before the last rebase; after it, two entries in `pages/merchant/apply/index.js` (another lane's, line 359 vs 360) fail. Not mine, left alone.
- **Main package size**: 2.98 MB uncompressed by `preflight-release.js`, under the 3.00 MB (3,145,728 byte) limit. Preflight still ends with one BLOCK, 「Action Ledger 动态证据未完成:pending=5741」, the release-time evidence run that is not done on this branch; it is not something this lane changes.

## Third pass: game library walk (editor screens only)

Rig rebuilt from this branch. I opened every game in the 选择玩法 sheet of the template editor (32 games: 选项问答, 文字问答, 猜图, 拍照打卡, 闭眼盲品, 拍照审核, 预测, 拍物成卡, 排序, 连线, 分类, 抛硬币, 找东西, 相册, 掷骰子, 角色建档, 九宫格, 翻卡, 留言, 猜数字, 今日城市签, D20, 弹球, 倒计时, D20 加值, 安静挑战, 精准停表, 计步挑战, 罗盘指向, 输入挑战, 变色就点, 喊一嗓子), picked it, and read the page with a script (not by eye, except the shots listed): helper captions, controls under the bottom bar at the end of the scroll, ASCII `?` in placeholders, typing into the first inputs, and 查看预览.

Found and fixed:
- Placeholder 「例如 这座桥建于哪一年?」 (ASCII question mark) in 选项问答/文字问答/拍照打卡: now a full-width ？.
- 「旧设置：photoCheck.maxTries：3」 (also ballShake.seconds, typeIn.seconds) shown as a raw-key caption on a **new** 拍照审核, 拍物成卡, 弹球 and 输入挑战. Now the engine's own new-game defaults are not listed, and a real old value shows with a Chinese label (e.g. 旧拍照次数) instead of the field name. Checked: 拍照审核 no longer shows the block. Tests that pin the old-setting rows (typeIn.seconds 19, slowTask) still pass.
- 「再加一段题目」, 「按这份配置试玩」, 「可触发的结果」 and the result row ran edge to edge, outside the card margin: now they use the same page margin as the cards (shot `m2-after-lib-layout2.jpg`, viewed).
- No game had a button hidden under the bottom bar at the end of the scroll (checked by script and in the shots I viewed).

Seen but not a product bug: the 设为正确答案 circle is blank in the rig because the rig does not draw WeChat's native `icon`.

Not done:
- Preview: 查看预览 on a blank template answers with 「请填写玩法看点」 and 「请先保存为我的玩法」 (validation), so I did not preview configured games. I did not fill and save each game's form on screen. I did not view every game's shot; the others were read by script only.
- The typing probe reported the 「这一段的反馈内容」 box as empty after typing in 30 games. I could not reproduce it by hand (the box was not found in the page in the follow-up probe), so I cannot say if it is a real input bug or a probe mistake. **Unverified.**
- E12, E13, E2, P3, P4 and the backpack chip are still not walked (they need a saved theme or a started run).

## Fourth pass: saved theme built on screen, remaining items

Rig notes (what I stood up to make real uploads work) are in `map-shots/m2-rig-notes.md`, section 「Round 13 · m2, authoring a saved theme on screen」. I authored 「午后巷口」 (topic 990014) from the editor screens only: 主题详情 (cover through a real upload and image check), one chapter, two 选项问答 stations (豆浆摊, 油条摊), 保存草稿 to the cloud, dates and a free ticket. Not a phone.

| Item | Walked | What I saw |
|---|---|---|
| E13 | Yes | With both stations saved, 编辑章节 reads 「请先在主题里添加物品」 under 章末配给, no longer 「请先保存本章节点」 (`m2-after-e13.jpg`; the group in that shot was added earlier in the session) |
| E12 | Yes | Adding a 互斥组 with both stations flips 全部完成 off and 至少完成几站 = 1 (`m2-after-e12.jpg`). I also found the 至少完成几站 row drew its label and number stacked on two lines (CSS made every pill row after the first `display:block`): fixed, same shot shows one line |
| E2 | Yes | An audio file sent to review: modal 「文件已提交审核，通过后请重试此文件」, no 「上传失败」 (`m2-after-e2.jpg`) |
| P4 | Yes (editor preview) | 拍照打卡 preview: question larger, 前置说明 and 补充说明 small grey. While walking it I found the question was **invisible** in the editor's player preview: black text on the black preview layer, because the inline preview took the editor's light text colour. Fixed (`theme-dark` on the preview wrapper). After: `m2-after-preview-ink.jpg` (选项问答 preview, question and options readable). Before: I saw it on screen (question missing, only two grey lines) but did not keep that shot |
| P3 | No | The scan kit is only reached in a played run, and the editor preview starts at the question |
| L1 in a run, backpack chip | No | Need a published, started run |
| Game library (story-flow list with 预览/配置) | Partly | See below |

Other things the walks showed, fixed:
- 选项问答 and the other games show a 「反馈分段（本站内）」 card to every identity. Typing in it turns on `branch`, and the server then refuses the save with 「当前身份未解锁「分支 / 条件玩法」」. Clearing the text does not turn it off, so the author is stuck until they find the × on the step. The card is now not offered when the identity is locked (`lockedGames.branch`), as locked games already are.
- Earlier worry resolved: the 「这一段的反馈内容」 box does accept typing. My probe clicked it while it sat under the bottom bar.

Seen, not fixed (not mine or not clear):
- A theme with a 互斥组 cannot be published: the server error 「豆浆摊：开了状态或 d20 检定的主题里，个人任务的这一站参与方式要选「定位到达」或「扫码」…」 reaches the screen only as 「网络中断，暂时确认不了是否已发布」, and the status stays 待确认. This is the E16/E18/E21 family (other lanes). Because of it I could not publish and play the theme, which is why P3 in a run, L1 in a run and the backpack chip are still not walked.
- In the node sheet the 「修改原模板（影响 1 个节点）」 button is clipped by its card.
- The 选择玩法 sheet shows a one-line description under the picked game, and the story-flow game list shows 「这种玩法暂时无法确认是否完成（通用结算）」 under unsupported games. Left as they are (disabled reasons).

Game library: the template editor's 选择玩法 sheet was scanned for all 32 games (third pass). The story-flow list (新建故事玩法, 预览 and 配置 per game, about 20 rows) I opened and saw, but my automated pass over its 预览 buttons broke after the first row (the preview returns to the editor, not the list), so only 文字问答, 选项问答 and 拍照打卡 were previewed by me. No game in it was configured, saved and previewed end to end except 选项问答 (saved and bound twice) and 拍照打卡 (previewed).

Checks on the pushed commit: ledger `--check` 5743 controls PASS before the final rebase; 254 related unit tests pass. I did not re-run `ui-integrity.js` after the last rebase.

## Fifth pass: the masked publish error, then a real run

**The bug.** A publish the server refused showed 「网络中断，暂时确认不了是否已发布」 and the theme stayed 待确认. Cause: the server's content gate threw an `IllegalArgumentException`, the generic handler answered with code 500, and the client treats every 5xx as 「结果不明」. Fix (server, `ApiTopicController.publishDraft`): a content-gate refusal now answers 400 with its plain reason (still passed through `UserSafeMessage`); the client's existing 4xx handling then shows it as a definite failure. A real network failure, a gateway 5xx or a masked 「系统繁忙」 keep the old words (the existing test for those still passes). Client (`fabu/index.js`): when the reason starts with a station name (「豆浆摊」：…), the failure banner's button is 「去修改」 and it opens that station's sheet (`_stationFromReason`, `editRejectedContent`). Test added in `b02-publish-write-unknown.test.js`. Java compiles (`mvn compile` on `chengyinhub-admin`); I did not add a Java test for the controller.

**The wording.** The reasons said 「开了状态或 d20 检定的主题里…」 when the author had only added a 互斥组 or chosen 进行方式. Now: 「「豆浆摊」：这一站的参与方式要选「定位到达」或「扫码」，或绑定承接商家」 and 「…这一站的玩法要带结果规则才能确认完成…」; the same lead removed from the two matching lines in `GameplaySupportMatrix`.

**Walked on the rig with the new jar** (`m2-after-publish-refused-station.jpg`, `m2-after-publish-refused-goto-station.jpg`, viewed): publishing 「午后巷口」 showed 「这次还没有保存 · 「豆浆摊」：这一站的玩法要带结果规则才能确认完成。请在这一站重新打开玩法设置，保存一次后再发布 · 去修改」, no 网络中断. 「去修改」 opened the 豆浆摊 sheet. I then followed the advice for each station (修改原模板 → 保存并绑定), after which the server named the next station (油条摊), and the third publish went through (status 1, then approved through the admin review). Note for the lane that owns this: the advice works, but it must be repeated for every station of a theme that has 进行方式 chosen, because the saved templates carry no result rules until re-saved.

**Real runs** (topic 990014 as player 9009, free ticket through 立即报名 → 参与人信息 → 确认报名; the rig's 立即报名 button did not react to a click, I called the page's `bmClick`):
- L1: the opening screen follows the look in a real run: 星尘, 纸本 and 末世 shots (`m2-real-intro-*.jpg`, viewed). The look was switched by setting `cms_topic.look_json` in the rig database, as k10 did; 夜城 was shot but not viewed, so not claimed.
- P1: 开始游戏 → 开启定位 for the solo theme: no 同场路线 sheet (`m2-real-run-solo-no-team-sheet-chip.jpg`). Before this round the same step showed it (k10-h04-team).
- Backpack chip: in the same shot the bag button at the top left shows no 0/6 count (the theme has no items). Before: 0/6 (k10).
- P3: not walked. I built a second theme (990015, 夜市扫码) with a station whose 参与方式 is 扫码, published and played it. 开始任务 did not open the scan screen; it opened 背包 → 任务 with the station 「尚未到达」 and a greyed 开始任务, even with the rig location set to the station's coordinates (`m2-real-run-scan-station-not-arrived.jpg`). So the scan screen and the 「扫描这一站的二维码」 wording were not seen in a run. I do not know whether that is a rig limit (location events) or a product problem; not investigated further.

**Story-flow game list previews.** From a new draft's node sheet (新建故事玩法) I pressed 预览 on 13 rows in turn (the supported ones, such as 选项问答, 拍照打卡, 分支剧情, 排序, 连线, 分类, 猜数字, and the unsupported 角色建档 and 拍照审核). Each opened the preview and went back to the list. A script checked every visible text in each preview for contrast against its background (below 1.6 would be flagged): none flagged. I did not look at each preview by eye; I did not press 配置 on the rows or walk the rest of the list. This list shows 「这种玩法暂时无法确认是否完成（通用结算）」 under unsupported games; left as is (a disabled reason).

Checks: `b02-publish-write-unknown` 13 pass; ledger and `ui-integrity.js` not re-run after the last two commits.

STATUS: PARTIAL - the masked publish error is fixed (server 400 with the plain reason, station named, 「去修改」 opens it) and walked; P1, L1 and the backpack chip are walked in a real run; P3 is not (the scan station never opened its scan screen in the rig), the story-flow list previews were only checked by script for 13 rows, and the ledger and ui-integrity were not re-run
