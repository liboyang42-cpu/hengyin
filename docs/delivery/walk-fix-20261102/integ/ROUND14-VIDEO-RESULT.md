# ROUND14 VIDEO — 2026-11-05

Source baseline: `4aff0697d715b0ecb278b7fb0a631e65484fd84e`, public hengyin checkout `codex-video-20261105`. Frontend commit: `d8f83ccc5` (`[skip ci]`), ready for coordinator cherry-pick; no push. This lane did not edit `pages/play/index.js`, `index.wxml` or `index.wxss` (GAME owns them).

## Delivered

- `pages/play/chapter-video.js`: isolate active/seen videos by account owner, topic, activity, route session and chapter/media identity. A pending/visible clip from an earlier chapter no longer prevents the next chapter's clip from starting. Its late poster, native events, timeout and asynchronous finish callback cannot start narration in a newer chapter/session or a disposed page.
- A five-second deadline now measures **advancing video time**. Repeated `play`, `waiting`, or identical `timeupdate` events cannot keep a stalled overlay alive indefinitely. The poster remains until time advances; a play event alone is not claimed as decoded playback. Existing error/skip/30-second fallback remains.
- Chapter switches within the same session preserve background music through the new video without reviving the previous chapter's narration. No additional audio context, dependency, caption or visual design was introduced.
- Focused video tests cover missing/failed/slow poster or video, repeated stall events, advancing frames, stale events, same-chapter new route session, chapter replacement, account invalidation, disposal, asynchronous finish races and music carry-over.
- `r14-video-response.test.js` executes the real `_loadLifeEnding` → `_playEndingChapter` → `buildChapter` → `openChapterFull` consumers with platform I/O stubs. It checks both immediate `chapter` and archived `story.chapter`, with the old story already open or closed, then text/continuation recovery on missing/failed/slow media and discarded late responses after session switch. A requested autoplay or narration is not actual media playback.

## Chain inspection and ending finding

The current source retains opening/ending video, poster and chapter audio through all inspected stages:

| Stage | Existing implementation / evidence |
|---|---|
| Professional editor upload/reopen | `pages/publish/fabu/theme-media.js` stores reviewed asset references; `fabu/index.js` restores audio/video/poster to `chapterForm`. Existing CODEX-VIDEO-RESULT records editor-authored save/reload/publish proof; not rerun here. |
| Author save | `ChapterDTO` and `CmsTopicChapter` expose all three media fields; `TopicContentGraphAuthoringServiceImpl` copies DTO properties in both legacy and block-flow paths. `CmsTopicChapterMapper.xml` selects/inserts/updates both video columns. |
| Frozen publication | `TopicRoutePublicationCompiler` freezes opening video/poster and `endingChapters` audio/video/poster. Existing `TopicRouteRuntimeServiceImplTest.a01EndingBodyIsFrozenSeparatelyFromPlayableChapters` asserts all three and continuation snapshot stability. |
| Server ending response | `PlayJourneyStoryServiceImpl.endingChapter` retains media in both live and frozen projections. Completed archived sessions return `result.story.chapter`. This lane adds assertions for real service projection and freeze → archive → reopen. |
| Player projection | `buildChapter` includes `audioUrl`, `videoUrl`, `videoPosterUrl`; `_loadLifeEnding` accepts both response shapes and forwards them. The new behavior tests run these actual consumers. |

No backend product-code or schema change is justified by the inspected source. The historical ROUND13 report's absent ending video is **not** explained or closed merely by finding those fields in current source or by unit tests. Before marking it fixed, capture the same UI-authored theme's saved chapter, publication/session ending snapshot and actual `/api/play/journey/ending` response on the coordinator's verified combined runtime, then observe decoded ending frames live and after reopen. Do not backfill an active/frozen session from mutable author data.

## Coordination

Sent to coordinator for GAME: set chapter narration loading state **before** play, reflect loading/error in the existing story sound button, clear loading on actual play rather than `canplay`, and ignore destroyed/stale guide callbacks. Keep the single shared guide context. This lane does not claim that shared-page fix is delivered until GAME's commit is integrated and verified.

Also sent an exact audio-only ending gap: `_loadLifeEnding` currently accepts a chapter only when `ch.blocks.length || ch.videoUrl`. An ending with configured audio, empty blocks and no video is skipped. GAME should include `|| ch.audioUrl` and cover that response in a behavior test. No shared-page edit was made here.

The video module itself now replaces an earlier chapter's active clip. GAME need not add a second video implementation. Live last-station completion/blank story remains with GAME.

## Checks

On frontend commit `d8f83ccc5`:

```sh
node --check chengyinhub-xcx/pages/play/chapter-video.js
node --test chengyinhub-xcx/tests/unit/chapter-video.test.js chengyinhub-xcx/tests/unit/r14-video-response.test.js chengyinhub-xcx/tests/unit/k10-immersive-audio.test.js chengyinhub-xcx/tests/unit/play-chapter-story-blocks-contract.test.js chengyinhub-xcx/tests/unit/play-audio-resume.test.js chengyinhub-xcx/tests/unit/play-audio-level-contract.test.js
```

Result: **123/123 PASS**, zero failed/skipped; includes 15 focused video/response tests. Pre-commit JS/JSON syntax PASS; `git diff --check` PASS. Staged diff reviewed/scanned for private-key blocks and common credential patterns, with no findings.

From `chengyinhub-xcx`:

- `node tests/lint/ui-integrity.js --warn-as-error`: **UI-GATE-0:PASS**, zero legacy errors/warnings; no gate or baseline changed.
- `node scripts/uiaudit/build-action-ledger.js --check`: **FAIL: 台账缺失或已漂移**. Reported to coordinator; shared inventory must be regenerated and reviewed against the combined GAME/VIDEO source. This lane did not replace or waive the gate.

Backend test source: `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/PlayJourneyStoryServiceImplTest.java`. Adds media assertions to the existing live ending projection test and `endingMediaSurvivesFrozenProjectionAndArchivedReopenWithoutReadingLiveChapter`. Not a funds-gated suite; no expected-count change. **Java compile/test pending coordinator VPS/CI validation**; no Java/Maven/Docker/MySQL/Redis was run locally.

## Coordinator-requested private rig adaptation

Coordinator assigned the temporary rig's `runtime.js`, `nav.js`, `walk.mjs` and isolated self-check to this lane while Java validation runs. `server.mjs`, backend process, database, tunnels and proxy were untouched.

- Actual HTML video now backs the rig's WXML video wrapper. Native play/waiting/timeupdate/ended/error events carry the wrapper's token into existing page handlers. `setData` retains the native element; context stop/seek and detach cleanup operate on it.
- `MPWEB.audioLog`, `videoLog`, `mediaState()` and driver `w.media()` provide native progress/state traces with query strings removed. Audio `onPlay` waits for native `playing`, rather than the browser accepting a pending `play()` call.
- `MPWEB.mediaQueue` / `fileQueue` are explicit native-picker seams. No queued video means cancel. Local selected MP4/MP3 bytes are uploaded unchanged as multipart with the proper extension. The old unsupported-file path no longer silently substitutes a generated JPEG. Same-origin files work; unapproved remote file origins fail.
- `proof/round14-video/media-rig-adapter.diff` records only these temporary harness changes. `media-selfcheck.mjs` and `media-selfcheck.json` are reproducible harness proof, **not business or phone acceptance**.

Self-check: **11/11 PASS** in headless macOS Chrome, with synthetic two-second video/audio served by an isolated ephemeral listener on `127.0.0.1`. It observed decoded 160px frames and increasing time, token-bound play/ended/error, stable media identity across render, HTTP503 failure, no invented progress during delayed video delivery, audio `onPlay` only after delayed bytes actually play, context/resource cleanup, explicit video picker/cancel, and exact multipart MP4/MP3 bytes and filenames. No business backend request or product-data injection occurred; the fixture server and owned Chrome closed in `finally`.

To reproduce, copy the self-check script to the private rig root (which has `mpweb/node_modules/puppeteer-core`), apply the adapter diff to the coordinator's matching pre-adaptation rig, and generate its two fixtures:

```sh
mkdir -p media-selfcheck-assets
ffmpeg -hide_banner -loglevel error -f lavfi -i color=c=blue:s=160x120:r=12 -f lavfi -i sine=frequency=660:sample_rate=44100 -t 2 -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart -y media-selfcheck-assets/clip.mp4
ffmpeg -hide_banner -loglevel error -f lavfi -i sine=frequency=440:sample_rate=44100 -t 2 -c:a libmp3lame -y media-selfcheck-assets/audio.mp3
node media-selfcheck.mjs
```

## Unverified / remaining

- Combined new-source runtime live ending and reopen with decoded ending video; actual saved/frozen/HTTP response comparison to locate the historical missing video.
- GAME narration loading/error feedback and last-station completion fixes after integration.
- Four-look rendering of the changed loading/error states, media poster/native video composition and all phone/headphone behavior. This lane changed no look styles; historical screenshots are not new-source acceptance.
- Unified Java compilation/tests, ledger refresh and final CI/release checks. No unified CI, service restart, tunnel/proxy change, production access, deployment or WeChat DevTools interaction from this lane.

STATUS: PARTIAL — video lifecycle code and JS behavior checks complete; combined runtime ending playback, shared-page narration feedback, Java validation and device acceptance remain unverified.
