# Video and theme music — 2026-11-05

Code delivered: initial media commit `9307c39df2bc09233176ee199d36822684081e4f`, reopen fix `29856bc3852563a776d78ee0a1af9c6aa3d09f8f`. The latter is rebased on play's `f559c3aec`. Both were pushed to integ with `[skip ci]`. No master push, merge, production change or deployment was made. The lead owns unified CI.

## 1. Opening and ending video

Status: implementation connected; author/upload/review/save/reload/publish proved for both fields. Opening playback, poster, skip, re-entry and slow/error fallback were walked. Playback at the actual ending remains unverified.

Files: `pages/publish/fabu/theme-media.js`, chapter rows in `fabu/index.wxml`, `pages/play/chapter-video.js` and small hooks in `pages/play/index.js` (paths relative to `chengyinhub-xcx`). Backend: chapter DTO/entity/mappers and copy/reopen paths, `ChapterVideoPolicy`, `AppUploadService`, media type/reference/publication guards, publication compiler, ending projection and the admin audit video preview.

The optional fields are `videoUrl` and `videoPosterUrl`. Only opening/ending chapters can receive them. Upload uses the existing authenticated media review. Publish checks the owner-bound reviewed video/poster types; generic chapter CRUD cannot bypass this path. The picker refuses non-MP4, unknown limits, over 30 seconds or over 10 MiB, with red text below the row. The server parses actual MP4 movie/track duration and rejects oversized, long, audio-only or truncated files before storage.

The player caches the poster, keeps it until time advances, and returns to text on error or five seconds without progress. It pauses playing page audio and resumes it afterward. Rebuilding the same open story does not loop the clip. Explicitly closing/reopening a chapter now permits replay; a fresh page also replays.

Proof: author 9003 built fresh theme **990032, CODEX媒体夜航1105**, through the professional editor. No theme/chapter/node/template fields were inserted by SQL or authoring API calls. A synthetic 3-second MP4 and its poster were selected through the native-picker stand-in twice: opening asset17/poster16, ending asset20/poster19. Both videos returned upload200/HOLD; the real admin audit UI approved them. `07-admin-video-play.jpg` shows the decoded three-second review preview. Previous `video-duration-refused.jpg` and `video-size-refused.jpg` show the 31-second and 10 MiB+1 red errors without uploads.

The editor saved three chapters and showed 已存云端. Reload before play restored both video fields and all three chapter audio fields. After the game started, a further edit-detail request correctly refused editing with code500 `主题已开始，不能再编辑；要改内容请先下架后重发`; it is not a lost-media readback or a reason to bypass the lock. The preview showed 3章/2节点. Clicking 完成 submitted `/api/topic/publish-draft`: code200, topic990032, auditTask38, PENDING. Admin approved task38 through its screen. Player9004 then enrolled through the free-ticket checkout and entered the published game. The actual journey opening response carried video/poster/audio fields.

Player9004's native HTML video advanced from 0.166 to 3 seconds and emitted ended. The trace records theme music paused at 3.125895 seconds, video played, theme music resumed at 3.125918 seconds, then opening narration played. See `media-playback-compact.json`; this is an actual rig trace, not a unit test.

On the later verified ce84 backend, player9009 enrolled through the UI. An 1.8-second MP4 transport delay left the poster visible (`29-video-poster-first.jpg`, playing=false); then decoded frames appeared (`30-video-decoded.jpg`, playing=true). Clicking 跳过 returned to text (`31-video-skipped-text.jpg`). Leaving through Back and clicking 开始游戏 again replayed the opening. Holding MP4 delivery for 8.5 seconds returned to text in **5009 ms**, with both theme/narration contexts unpaused (`32-reopen-slow-poster.jpg`, `33-reopen-slow-text.jpg`, `slow-fallback.json`). Actively aborting the MP4 request emitted the native error event at time0 and returned to the usable text page with theme music unpaused (`34-network-error-text.jpg`, `network-error-fallback.json`). The explicit same-page reopen flag is covered by the focused regression test; that particular UI route was not separately walked.

Unverified: reaching the chosen ending in the combined game and seeing/hearing its video; native WeChat video composition and audio on a phone. Do not turn the successful opening into a claimed ending walk.

Migration: new `chengyinhub-admin/sql/migration_20261105_chapter_video.sql`, registered in `ci/migration-dependencies.json` and the deploy order in `integ/RESULT.md`, before the new backend. Adds two nullable columns, repeatable, no existing migration edited. Applied only to the owned test schema. Production execution is outside this task.

## 2. One theme-wide music row

Status: editor upload/review/save/reload/publish and player start/pause/resume proved in the rig.

Files: `fabu/topic-detail-sheet.wxml`, `fabu/theme-media.js`, `fabu/index.js` and the restored-row regression test. Reuses `audioUrl`/`audioDuration`, existing reviewed document upload and player `topicAudioUrl`. One 主题配乐 row; no new helper caption, secondary label, track engine or database column.

Proof: UI selected synthetic `tone.mp3`; asset15 entered HOLD. The existing admin review screen approved it; selecting the same file again used the existing pending-asset retry and attached it. `08-theme-music-attached.jpg` shows 移除/更换. Save/readback and published player both retained it. Actual HTML audio advanced, paused during video, resumed at its prior position and was ducked for narration. Opening, regular chapter and ending each received their own chapter audio through the editor/review flow (assets21,22,18 respectively).

Unverified: audible balance and station-music override in this particular walk. **I heard nothing.** A phone is required for actual sound, headphones, mute switch, lock screen/background, calls, native full-screen layering and cellular buffering. Unit tests, browser media events and screenshots do not prove those.

## 3. Full-game acceptance

Status: PARTIAL. My media implementation list is finished; the complete acceptance list is not finished.

The same UI-authored theme contains opening text/audio/video, theme music, a regular chapter with narration, a photo template, a scan-participation template, a one-minute TIME text egg, and an ending chapter with text/audio/video. Templates6049/6050 were created with 保存并绑定. The photo was uploaded and verified: advanced action reported passed=true/readyForBase=true; `/api/play/arrive` returned node124, done1/total2. No second photo proof was requested. The screen text also showed 一分钟夜航彩蛋. No egg reward was configured or claimed verified.

The player reached node125's 扫描这一站的二维码 screen. This run did **not** achieve an on-screen issuance of that station's usable check-in code. The author's default project route returned code403 `商家身份无效或权限不足`; opening the existing CLUB route displayed the host project and its theme QR entry. That entry is a theme landing code, not evidence of a station check-in. I did not fetch/forge a database code, call a completion API, remove the QR station or mark the route complete to reach the ending.

The template named 夜航扫码分支 has scan participation, but its saved branch.enabled is false. Its title is not a completed branch. Branch authoring, QR completion, full reward/egg interaction and end-to-end ending remain with the combined gameplay/editor acceptance, owned by K10/play and immersive. The report does not claim a FULL game pass.

## 4. Focused checks and artifacts

- Backend tests ran through `/home/ubuntu/.local/bin/chengyin-build-budget-212`, on the VPS and on the two owned classes only. `ChapterVideoPolicyTest` 2/2 and `ChapterVideoMediaTest` 2/2 passed. `backend-owned-tests.txt` records 4 tests, zero failures/errors/skips, BUILD SUCCESS. Test source was da9; media source did not change in ce84. A temporary profile in the private test checkout limited test compilation to these two classes, following the repository's existing testIncludes pattern; it is not a product/POM change.
- Actual preceding failures: two refusals `another_build_holds_lock`; then missing offline `maven-surefire-plugin:3.5.6`; after copying the worker's existing cache, compiling all 2327 admin test sources exhausted Java heap. Scoped test compilation fixed the test invocation, without weakening tests or running Java on the Mac. The earlier 145-test result remains historical, not final-source or walk evidence.
- After the reopen patch and rebase: **106/106 focused frontend tests pass** (media, chapter story contracts, K10 audio). Before that, the editor/media/ledger set passed96. E17's former failure was upstream `2c235b568`; its owner corrected it in `cbc857d9d`, and the current E17 assertion passes. No test was deleted or weakened.
- `UI-GATE-0:PASS`; ledger check PASS, **5743 controls**. Existing debt counts/reasons unchanged. The temporary club/apply baseline failures were fixed upstream before the final check.
- Preflight main package is **2.98 MiB**, below 3,145,728 bytes. Its global release result remains BLOCK: dynamic ledger evidence pending5743. This worker did not waive or call that release gate green. New player/editor media code stays in the existing subpackages.
- No existing gated Java suite gained/lost `@Test`; `ci/funds_it_gate.py` needed no count change.
- JAR run42006455345: source `da9b30f1dd770ce0c4cc55b278e9d08e1ad99a7f`, SHA256 `f4c1fe89441cf2cba7feb03addf3774c17aed23fe6bff17e6d5d1a02c681f429`. This powered authoring, publish and the first opening/photo walk.
- JAR run42014102646: source `ce84d219dc7c49f03acdff7aef967c836b82142d`, SHA256 `284f4bb6369804ee1ce5fa2efdbd65afacdebb6dcd7fc48d95c7fdc8de09da95`. The downloaded checksum, SOURCE_SHA, embedded git.commit.id and copied runtime file hash matched. Only the owned application was restarted; health200. This powered the second player's poster/skip/re-entry/slow walk. The container's creation label still says da9; the verified running JAR file is ce84. These are one-day GitHub artifacts, with no deploy or secrets.
- All retained screenshots were viewed. Proof is under `integ/proof/codex-video/`. Public proof omits tokens, config/env files, signing queries and database dumps. Patch scans and whitespace checks passed; final evidence scan is recorded at handoff.

## 5. Rig, incident and cleanup

Mac ran lightweight Node/browser previews and remote Docker CLI only. No local Java/Maven, Docker engine, MySQL, Redis or DevTools. Browser access host was unavailable; the owner's explicitly authorized Puppeteer rig was used. The harness was copied privately, with native media/file/GPS/scan stand-ins and the existing external wx/OSS providers. TLS verification stayed enabled. Browser playback is real HTML audio/video, not native WeChat.

The original harness kept wx storage only in process memory. For the editor update/reload, its native-storage adapter was corrected to persist the exact existing UI-written draft envelopes; no form fields were injected. Authored content remained in the professional editor. Signed-URL save confirmation initially reproduced the old error; upstream `08c9a00df` then confirmed the same saved draft and preserved later local edits.

Incident: my attempted clone/import in a separate schema on the shared 512MiB MySQL daemon caused that shared container to exit137/OOMKilled. I informed the owner and stopped touching shared resources. The owner assigned restoration to play and explicitly authorized my own MySQL. A complete pre-incident dump survived privately at `/private/tmp/codex-video-rig-20261105/fixture.private.sql` and was successfully restored into my independent schema; it is available to the recovery owner. Shared recovery is not claimed here.

Owned runtime: `cy-video-rig-20261105-app` (896MiB, one CPU, port18119), `cy-video-rig-20261105-mysql` (768MiB, one CPU, schema `cy_video_walk_20261105`, port18116), `cy-video-rig-20261105-redis` (96MiB, port18112). Task label `codex-video-20261105`. These use retained named volumes. The external stand-in and all other workers' resources are preserved. The older stopped `cy-video-build-20261105` is preserved as requested. Walk finished. The owned app/MySQL/Redis containers were stopped and independently inspected: app exited143 after stop, MySQL/Redis exited0. Named volumes remain. Both owned browsers closed; Mac Node listeners18108/18118 were stopped with the approved helper and checked closed. The owned SSH master/forwards were closed;18119/18183/18197 also checked closed. The UI-authored test database has a private 3,162,386-byte backup at `/private/tmp/codex-video-rig-20261105/ui-authored-walk.private.sql`. No shared container was stopped in cleanup. The worker is quiet. No regular branch CI was started or read.

STATUS: PARTIAL - media implementation and opening/music rig checks are finished; station QR/branches and the combined ending/phone acceptance are not proved
