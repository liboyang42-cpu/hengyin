# Video and theme music, 2026-11-05

Work is on a private local branch based on `integ/launch-test-20261103`. E20 `6f63e08d3`, E16 `ac2ac8571`, E21 `87cb26eb8` and the cloud K10 result are preserved. Nothing was merged to master, deployed or changed in production. New commits use `[skip ci]`; the lead owns the one final CI run.

## Opening and ending video

Status: code connected; complete-game walk still unverified.

Files: `chengyinhub-xcx/pages/publish/fabu/theme-media.js`, the chapter rows in `fabu/index.wxml`, `pages/play/chapter-video.js`, small play-page hooks; chapter DTO/entity/mappers, `AppUploadService`, `ChapterVideoPolicy`, media review guards and admin preview; publication snapshots, ending reads and chapter copy/reopen paths.

One optional video is available on the opening chapter and each chosen ending chapter. Other chapters cannot receive it through the professional editor. The new fields are `videoUrl` and `videoPosterUrl`. The media stays in the existing owner-bound review queue. Publication requires reviewed video and poster types. Existing theme owner and operation-scope checks stay in force; the generic chapter CRUD cannot bypass the professional editor's video validation.

The picker refuses non-mp4, unknown duration/size, more than 30 seconds or more than 10 MiB. The server reads actual MP4 movie and track duration and rejects oversized, long, audio-only and truncated containers before storage. It does not trust picker metadata. The player caches the poster before showing the video surface, keeps the poster until time advances, allows tap/skip, and returns to text on failure or five seconds of waiting. It pauses currently playing page audio and restores it afterward. A fresh page entry replays; a same-page story reflow does not loop the clip.

Proof: `proof/codex-video/editor-boundaries.txt`, `video-duration-refused.jpg`, `video-size-refused.jpg`. I created the chapter and opening through the professional editor's buttons, then clicked 添加视频. Native picker stand-ins returned 31 seconds and 10 MiB + 1 byte. Both red messages appeared below the field; both screenshots were viewed; neither case sent an upload. There were no page errors. The unrelated favicon returned 404. The first harness attempt had `MP is not defined`; that rig-only picker scope error was corrected before this recorded run.

A valid synthetic 3-second MP4 was also selected through 添加视频. On the shared old backend the poster upload returned 200; the MP4 upload returned HTTP 400, with the red field message `不支持的文件类型或文件过大`. This is recorded in `proof/codex-video/valid-upload-old-backend.txt` and the viewed `valid-video-shared-backend.jpg`. The old running backend does not contain the new MP4 admission code. No theme save/publish was sent in this run.

Unverified: upload and review of both valid videos on the final backend, save/reload/publish readback, actual playback/skip/slow-network behaviour on the rig, and replay of the published ending. Player state tests are not a walk. The existing shared backend was reported as source `c09b51b`; a combined backend with the new columns and all lane changes is still needed. Do not start a second backend.

Migration: `chengyinhub-admin/sql/migration_20261105_chapter_video.sql`; listed in `integ/RESULT.md`, before the new backend. It adds two nullable columns, is repeatable and rewrites no existing rows. Production execution is not authorized by this work.

## Theme-wide music

Status: editor row, load/copy/save payload connected; complete save/publish/play walk still unverified.

Files: `fabu/topic-detail-sheet.wxml`, `fabu/theme-media.js`, `fabu/index.js`, and the updated September-removal regression test. Uses existing `audioUrl` / `audioDuration`, the existing reviewed document upload and the existing `topicAudioUrl` player. No new track engine or database column.

Proof: the real editor displayed one 主题配乐 row. The first snapshot was viewed and its unsupported icon was replaced with the existing play icon; `proof/codex-video/theme-music-row-final.jpg` was then captured and viewed. Focused tests exercise upload/clear isolation from chapter narration, both reload/copy mappings and the save payload. Existing K10 tests cover the theme/station music and narration relationship.

Unverified: author selecting a valid track, review, save, reload, publish, theme music during walking, station override and video pause/resume in the final combined game.

## Checks and environment

- Mac: JavaScript only, browser preview and Docker client. No local Java, Maven, MySQL, Redis, Docker engine or DevTools was started.
- VPS: `cy-video-build-20261105`, task label `codex-video-20261105`, now exited with code 0. First source snapshot was `6f63e08d3` plus the initial media patch. Package compiled and 145 tests passed: ChapterVideoPolicy 2, TopicPublicationContentSnapshot 6, TopicRouteRuntimeService 96, AppMediaReferenceGuard 20, AppMediaPublicationGuard 13, AppMediaAssetService 8. Those reports do not prove the later E16/E21 integration or the final ChapterVideoMedia test. Preserve this stopped container for the lead; do not reuse its JAR as the final candidate.
- The staged backend snapshots `a783ff4daa` and `7396213b3f` also compiled successfully through the existing pre-commit hook on the VPS (704 MiB, one CPU). The first commit attempt was then held by the design-token gate; the new styles were corrected to existing tokens without a waiver and the hook passed. The new `ChapterVideoMediaTest` still needs focused verification through the VPS budget entry. The exact budget command was requested; no new package was started after the coordinator asked for one combined build.
- Java gated suites: no `@Test` was added or removed in an existing gated suite; `ci/funds_it_gate.py` counts remain unchanged.
- Frontend focused run after rebase: 107 pass; later poster readiness change: 9 media tests pass, and a separate chapter/video/K10/editor run: 62 pass. UI integrity passed with the existing debt counts and unchanged reasons; only old line anchors moved. Ledger regenerated to 5,741 controls. Latest rebase on `0d82371f1`: 109 focused frontend tests passed. A slow-first-frame regression was reproduced with a test: a play event without advancing time must still fall back within five seconds. The watchdog was corrected and the test passed. Final UI integrity: `UI-GATE-0:PASS` (55 existing zero-binding debts, 99 existing zero-consumption debts, all 63 dynamic setData and 44 silent calls accounted for). Final ledger regeneration: `ACTION-LEDGER WRITE PASS: 5741 controls`. The focused inventory test passed, 2 tests.
- Main package at final `a8b142029` integration: 3,121,892 bytes, below 3,145,728, using the preflight script's pack-ignore/subpackage calculation. New media code is in existing publish/play subpackages.
- Migration SQL statement gate and dependency/ledger order check against `0d82371f1` passed for the one new migration. Diff whitespace check passed. Gitleaks scanned the patch and complete changed text files (42.43 MB), no leaks. No secrets, environment files or backend addresses are committed.

Preflight reports the main-package size as safe. Its remaining release blocker is the existing global dynamic-evidence backlog: last measured at the earlier 5,739-control snapshot as `Action Ledger 动态证据未完成:pending=5739,nonPassed=0`; the current inventory has 5,741 controls. This worker does not waive that gate or call preflight fully green.

## Latest integration rebase

Rebased on `a8b142029`, preserving the newer background/silent-switch audio changes and personal-runtime egg fix. The only play lifecycle conflict retains both upstream `audioSession.pauseAll(false)` and video cleanup on unload. Generated ledger/baseline conflicts were rebuilt from the upstream versions, retaining their existing reasons and debt limits.

Focused run on this rebase: **110 passed, 1 failed** (111 tests). The one failure is upstream's `pro-editor-fabu-flow.test.js:1044`, test 「保存失败提示和原因都在章节滚动容器内，不盖住章节动作」. It still searches for `<cy-inline-error class="slopes-submit-error"`, but upstream changed that to a wrapper `<view class="slopes-submit-error">`. The same assertion fails on upstream WXML alone (`errorAt = -1`), before the media rows. No test was removed or weakened. E17/publish-flow owner must reconcile this before the clean run. The media-only tests pass (9/9).

## Combined walk still required

The acceptance target is an ordinary author building the whole game on the professional editor: opening and ending music/video, theme music, narration, photo and QR, branches and eggs together, then playing from entry to ending. Draft 990016 `K10-CODEX-1105夜航`, author 9003, was opened from the real edit-detail endpoint. The video worker did not save database/API-authored fields into it. The editor boundary run used a fresh browser-local draft and did not save or publish it.

The browser access host returned `The browser access host is unavailable`; the explicitly authorized Puppeteer harness worked. The shared rig at 18098 and backend 18099 were preserved; the video frontend preview used loopback 18108 and the same backend. Port 18108 was stopped with the approved helper and verified closed; no video-worker build container remains running. The private copied harness has native video and media-picker stand-ins. These do not replace backend validation or authoring writes.

**I heard nothing.** No valid video/music playback walk is claimed yet. Only a phone proves audible balance, real headphones, native full-screen composition, silent switch, lock-screen behaviour, interruptions and cellular buffering. K10 owns those broader audio/gameplay items. Unit tests and screenshots are not phone or full-game acceptance.

Code commit `9307c39df` was pushed to the integration branch with `[skip ci]`. The worker is quiet; the implementation list is connected, but the full acceptance list below is not finished. The lead needs one combined budget build and a repeat of the on-screen authoring walk.

STATUS: PARTIAL - media code is connected, but the final combined backend and the full on-screen author/save/publish/play walk are still required
