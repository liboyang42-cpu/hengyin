# Round 13, lane m3-media-network: result

Run 2026-11-05, private branch from `integ/launch-test-20261103`. **Not a phone and not a real weak network walk.** Docker did start in this container and the rig came up (real jar, MySQL 8.0.45, Redis, mini-program in headless Chrome). I heard nothing; no audio was played in the rig this round.

## What the rig could not do (items NOT walked)
- The rig's fixture chain (`chains.py player`) fails on this branch head at `merchant_club`: 「终态回读不符: lifecycle 应为 2=PRICING」. Without it there is no activity or ticket for topic 990014, so the play page only shows the 自由探索 gate (`fix-shots` not kept: it is a black screen with the title and 「查看详情与报名」, no cover, which is the fixture's missing cover, not a media failure).
- Mock play mode needs the dev-env flag and is off in the rig.
- Also, `mvn -DskipTests package` fails on this head: `ThemeRuntimeMySqlIT.java:[101,319] reference to Date is ambiguous` (java.util.Date vs java.sql.Date) in test compile. I built with `-Dmaven.test.skip=true` in the rig only. That test file needs an owner (backend lane); I did not touch it.
- Rig finding, backend env only: without `CHENGYIN_OSS_*` the order screen showed the raw text 「Could not write JSON: 对象存储配置缺失: CHENGYIN_OSS_BUCKET_NAME」 (from `/api/topic/info-to-user`). Production has those set, but the screen printing a backend error verbatim is worth a look by the order lane.
- So NOT walked: throttled/failing narration, station voice, station/theme music, images, video; the four looks (星尘 纸本 末世 夜城) on station sheets, photo and scan screens, finish card, error states.

## Items
1. **Voice error showed raw errMsg as a toast (initAudio onError)** — FIXED in code. `pages/play/index.js`: the toast is now 「这段音频放不出来，点一下再试」; the card keeps its existing 「播放失败，点此重试」. Proof: unit test `k10-immersive-audio.test.js` (source contract). Unverified: on a phone.
2. **Music errors stopped silently** — FIXED in code. New `_retryBgm`: theme and station music retry on the same player after 3 s and 8 s, then give up quietly; the game is never blocked and no new text is shown. Proof: unit test drives the retries and the give-up (plays 1→2→3, then destroyed). Unverified: real network drops.
3. **Media only loading at 开始任务** — PARTLY. New `_preloadNextStationAudio`: when a station's voice starts, the next undone station's voice is warmed once, only on wifi/4g/5g, volume 0, never played, not in the audio session (no focus, no screen-awake). Theme music and chapter narration are not preloaded. Proof: unit test (wifi preloads one file once; 3g does not). Unverified: whether WeChat's InnerAudioContext really starts buffering on `src` alone; if it does not, this does nothing harmful.
4. **Oversized author media** — a limit already exists: 10 MB per file, enforced before upload in `utils/transport/upload-client.js` (and `node-npc-form`); `chooseDocument` shows it in a dialog, not as red text under the field. Not changed (would need editor layout work; no new captions). Image limits not re-audited. Unverified.
5. **Blank or silent screen while waiting; a failed media load blocking the game** — not walked. Reading the code: chapter, station, theme and shop-NPC audio errors all end in a stop and never gate a completion; images were not reviewed.
6. **Opening/ending video** — not present yet (video worker).
7. **Four looks** — NOT walked; no palette changes made.

## Files
`chengyinhub-xcx/pages/play/index.js` (+44/-4), `tests/unit/k10-immersive-audio.test.js` (+3 tests), `tests/lint/ui-integrity-baseline.js` (U5 line 8524→8573 and digest, line shift only), `scripts/uiaudit/action-ledger.json` (177 `staticSource` line numbers; it was already drifted before my change and `--check` failed on the base).

## Checks run
- `node --test tests/unit/k10-immersive-audio.test.js`: 16 pass, 0 fail.
- `tests/lint/ui-integrity.js`: UI-GATE-0 PASS on my base. After the final rebase on the shared branch it FAILS with 4 violations, all in `pages/club/apply/index.js:244/255` (U4-DYNAMIC baseline entries gone stale from another lane's field-error commit `c5227c532`); none are in my files. Whoever owns that lane must re-anchor them.
- `build-action-ledger.js --check`: PASS after `--write`.
- `scripts/preflight-release.js`: main package 2.98 MB uncompressed (under 3,145,728); its one BLOCK was the stale ledger, fixed afterwards; not re-run to the end.
- Full `npm run test:unit` not run (credit).

## Second pass (10:10 instruction)

- **merchant_club 「lifecycle 应为 2=PRICING」: the rig was stale, not a product bug.** The deadline job logs `平台费率规则缺失(settlement_rule merchant_id=0, biz_type=topic),拒绝按默认费率计价` (PricingServiceImpl, the intended k1 item 7 behaviour: no silent 10%), skips the topic, and lifecycle stays 1. My rig was built from a commit before `ci/chain_acceptance.py` got `FIXTURE_TOPIC_RATE_SQL`, which seeds the 3% rule on an empty database. I inserted that row by hand and re-ran `chains.py player`: all four chains PASS (topic 990015, activities 9-13). Production has the rule, per that code comment (not re-checked by me).
- **Order screen raw error: FIXED.** The 「Could not write JSON: 对象存储配置缺失: CHENGYIN_OSS_BUCKET_NAME」 text was the topic page's load-error line (`pages/topic/index/index.js`, two places) printing `res.msg` as is. It now goes through `safeUserMessage`, and `utils/transport/safe-user-message.js` now turns Spring serialization errors and `CHENGYIN_*` / 配置缺失 text into 「服务开小差了，请稍后重试」. Unit test added; `safe-user-message.test.js` 10 pass. Not re-walked on screen.
- **Walks: still NOT done.** With the fixture in place I tried the play page. Player 9009 holds the only registration (activity 13) and opens straight to 「本轮探索已完成」, because the chain already scanned its ticket (8 scan rows); deleting its progress rows did not change that. Player 9004 has no registration, and buying the pass through the order screen did not reach a playable run in the few tries I allowed. The k10 on-screen authoring scripts (a long editor session) were not run. Result: no throttled or failing media walk, no four-looks walk, no rig audio log this round. I set up the stand-in media server (ok / slow trickle / 500 / hang) and set theme and chapter audio on topic 990015, but nothing was played through it.
- Also seen: opening the topic's 自由探索 gate before buying shows a black screen with only the title and one button; the fixture has no cover, so I can't say whether a real topic looks the same.

To finish the walks someone needs a rig player with an unstarted run: a fresh registration on activity 13 for 9004 (through the order screen with the pay stub, or SQL), then the media stand-in at `127.0.0.1:8765` and `look_json` switched per look.

## Third pass (10:21 instruction): what exactly blocks the walks

Done in my own rig database only, no product code changed:
- Reset player 9009 on activity 13: registration 74 `verification_status` 1→0, deleted its `cms_registration_scan`, `redemption`, `store_verify_growth_delivery`, `player_run_session` and `player_node_progress` rows. The play page still opens on 「本轮探索已完成 · 看看这一天的收获」. Reason: activity 13 is the chain's 探店日 (自由探索 shop-day) session, a different game from the theme journey. `index.wxml:987` shows that text for `mode==2`, and `/api/play/journey/casebook` answers 「这一局还没开始」. It has no chapters, narration or station audio to play. It cannot carry the media walk.
- Player 9004 on topic 990015 (no activity id): the play page shows 「请先购买自玩通行证 · 获取通行证」. That button opens the topic page, whose 立即报名 sheet lists only the four ¥69 club sessions (11.5, 11.15 to 11.17). There is no self-play pass ticket seeded on this topic, so no free or paid solo ticket can be bought on screen. The order text I saw earlier belonged to a hidden sheet layer.
- A solo journey topic with chapters, narration, station voice and music (k10's 990017, codex's 990027) was made by authoring on screen (k10 `s_*.js`, a long editor session) or by SQL that was never saved to the repo. I did not repeat the authoring in the 30 minutes.

Needed to finish: either replay k10's `proof/k10-walk/s_reg.js` through `s_ticket.js` (author, publish, free ticket) on this rig, or seed a solo 自玩通行证 ticket on topic 990015 and set `cms_topic_chapter.audio_url` / the template audio. The stand-in media server (`127.0.0.1:8765`: fast, slow trickle, 500, hang) is ready and the `look_json` switch is a single UPDATE.

Still not walked: throttled and failing narration, station voice, station and theme music, images, video; the four looks on station sheets, photo, scan, finish card and error states. Nothing was heard.

STATUS: PARTIAL - code fixes and the merchant_club answer done; media and four-look walks blocked because the rig has no solo journey topic and no solo ticket to start a fresh run
