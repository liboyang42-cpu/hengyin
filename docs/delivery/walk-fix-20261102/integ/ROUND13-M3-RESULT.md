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

STATUS: PARTIAL - rig fixture chain broken on this head, so no throttled-media or four-look walk; code fixes for voice-error toast, music retry and next-station preload are in with unit tests only
