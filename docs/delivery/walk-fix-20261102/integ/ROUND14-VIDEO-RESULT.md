# ROUND14 VIDEO — 2026-11-05

**VIDEO implementation and assigned browser UAT complete.** This is not phone/headphone acceptance, four-look signoff, unified CI, deployment or WeChat release. Four-look QA is assigned by root to BRANCH; no duplicate look draft was created.

## Source and ownership

- Public hengyin baseline `4aff0697d715b0ecb278b7fb0a631e65484fd84e`; own branch `liboyang42-cpu/codex-video-20261105`; no push or merge.
- Owned production fix `d8f83ccc5`: `pages/play/chapter-video.js` and isolated tests. Owned audio-contract regression fix `4ac651eb1`. No edit to GAME-owned `pages/play/index.js`, `index.wxml` or `index.wxss`.
- Initial actual author/player run used frontend/backend `1c60516771a6e2d50290ab44134005bdfe96c109`, jar SHA256 `3ca8a7e4ad1db43dcb1f56431211be1bd9357297d0861ef983fc11c383e16dcf`, dedicated test database `cy_e2e_51`, Redis 9. Root owns the runtime and tunnels.
- Final fresh-player UAT independently read `/build/source-identity.json`: frontend `d0cd6eec9b72f0db0c06be3f374f4cc3b76d9a19`, bundle SHA256 `7b79f2926aad86ea99074e3a5bfacf616680d1f52655fd4d1adab8f3500ba21f`; backend remained the unchanged `1c605...` build. This is a composite source receipt, not a claim that the backend embeds d0cd.

## Delivered behavior and root cause

The video helper scopes active/seen clips to account, topic, activity, route session, chapter and media pathname. A previous pending/active clip no longer blocks a later chapter; stale poster/native events/timers/asynchronous completion cannot revive narration in a new session or disposed page. The five-second stall deadline resets only when video time advances, so repeated play/waiting/duplicate-time events cannot hold gameplay indefinitely. Same-session chapter replacement preserves background music without restarting prior narration. No new dependency, audio context, helper caption or redesign was added.

DTO/entity/mapper, professional author save, publication compiler, live/frozen ending projection and player buildChapter all retain audio/video/poster in the inspected source. Completed runs return the ending under `data.story.chapter`; this response shape is exercised by both regression tests and the actual runs below. No backend product-code or schema change was needed.

Actual UAT found a separate visibility defect: after the last task was settled from the backpack, `themeBackpackOpen=true` and the final PASS feedback remained above the ending chapter. The native video played underneath them, and the ending Continue button's DOM hit was `tb-sheet`. This explains a real “video missing” observation without claiming native playback events prove visibility. Evidence: [covered state](proof/round14-video/business-replay/ending-covered-state.json), [covered screenshot](proof/round14-video/business-replay/ending-covered.png).

GAME fixed the shared transition in `bbd23683a`, integrated by root as d0cd: preserve the author's final feedback, queue the ending behind it with owner/session fences, then close backpack/tools/node sheet/advanced play/playKit before opening the ending. GAME also owns narration loading/error/stale-callback feedback and the audio-only ending condition. No duplicate implementation was introduced by VIDEO.

## Actual author → publication → player evidence

All product changes below used normal rendered UI controls and ordinary test-account authentication. Native picker, GPS and browser media-request seams simulated device input; no product fields, roles, database completion, clocks or session progress were injected. OPS independently performed exact normal media/theme moderation.

| Stage | Verified result |
|---|---|
| Professional author | Actual author **9008**, role club, created only `R14-VIDEO-雨停之后1105`, topic **990033**; GAME's fixtures were preserved. Wrote opening, one normal TYPE/GPS station, fallback ending, free ticket and theme BGM through the editor. |
| Media save/readback | Distinct blue opening video and red ending video, separate intro/ending narration, regular chapter narration and BGM survived save and author-saved-detail readback. Approved assets: BGM15; opening audio24/video26/poster25; regular audio32; ending audio27/video29/poster28. |
| Publication | Normal publish-draft HTTP200/code200 returned task34/PENDING/bundled template6044. OPS approved task34, record48; immutable audit snapshot config11/published1 and independent CMS projection preserve the exact asset refs/pathnames. Final chapters128/129/130, node157. [Independent publication readback](proof/round14-video/business-replay/independent-publication-snapshot.json). |
| First independent player | Actual player **990010** (login label9010), free registration88/activity25/activity ticket106/run6. Explicit participant-sharing consent; normal location consent + Start this run + native GPS arrival; real TYPE answer “雨停”, advanced action COMPLETED/session14, task settlement and reward confirmation. No money was charged. |
| Initial ending/reopen | Actual journey/ending HTTP200/code200 success COMPLETED contains `story.chapter130` with video29/poster28/audio27. Native opening and ending video each advanced to 2 seconds and ended; ending narration then really played to 2 seconds. Initial live visibility failed because of the retained overlays. Normal order → Start game reopened the completed run with an unobscured red video and ending narration. |
| Final fresh player | On d0cd, normal new login label9020 returned actual player **990015**, role player; free registration91/run9 on the same topic. The real TYPE/GPS/task/reward flow was repeated independently. Before feedback Continue: author PASS visible, ending queued, red-video playing count **0**. After Continue: backpack, verdict, tools, node sheet and advanced play all **false**; native red video at 0.166 seconds/readyState4 and visible screenshot; video ended at 2 seconds, followed by ending narration playing/ending at 2 seconds; chapter → ending card → finish succeeded. |
| Final-source archived reopen | Normal back to order → Start game reopened run9 on the same d0cd page bundle. Visible red video at 0.158 seconds, no backpack/verdict, ending narration completed again, then finish succeeded. Both full ending HTTP responses retained media. Browser errors: **[]**. |

Final evidence: [feedback waits](proof/round14-video/business-replay/final-source/feedback-before-ending.png), [visible live frame](proof/round14-video/business-replay/final-source/live-ending-visible.png), [visible archived frame](proof/round14-video/business-replay/final-source/archived-final-source.png), [acceptance receipt](proof/round14-video/business-replay/final-source/final-source-acceptance.json), [actual ending responses](proof/round14-video/business-replay/final-source/ending-responses.json).

## Slow/failing media and stale continuation

These actual browser checks ran on the initial 1c605 candidate; the video helper is unchanged in d0cd. Root explicitly accepted these receipts and requested only the fresh live-visibility rerun on the final bundle.

- **Narration delayed 8 seconds:** chapter130 had `audioLoading=true`, `audioPlaying=false`, native time0/readyState0, existing sound button ARIA “旁白加载中”. After 8.27 seconds, native playing occurred and loading became false. [Loading state](proof/round14-video/business-replay/slow-audio-loading.json), [actual playing](proof/round14-video/business-replay/slow-audio-playing.json), [screenshot](proof/round14-video/business-replay/slow-audio-loading.png).
- **Video and audio HTTP503:** native video error at time0 dismissed the video; narration loading cleared with “音频加载失败”; normal chapter/ending-card Continue reached `showFinish=true`. No media failure blocked completion. [Receipt](proof/round14-video/business-replay/failed-media-continuation.json).
- **Video delayed 10 seconds:** exactly 5000 ms without advancing frames dismissed the overlay and left chapter text/continuation available. Normal back to the order page before the delayed bytes arrived left active media empty, with no late playback. [Receipt](proof/round14-video/business-replay/slow-video-and-late-route-callback.json).
- Missing media, stalled time, stale poster/native/finish callbacks, chapter replacement, owner changes and session switches also have focused behavior regressions. These are unit checks, not additional real-player/device passes.

The first GPS checkpoint was not a product defect: location had not been authorized/started. GAME supplied the normal Tools → automatic track → location consent → Start this run path; then native GPS produced actual arrive200. Native viewport/click timing and an initially wrong detail route were corrected without product-state mutation. No stale screenshot was used as final-source acceptance.

## Checks and private harness

- `node --test chengyinhub-xcx/tests/unit/chapter-video.test.js chengyinhub-xcx/tests/unit/r14-video-response.test.js chengyinhub-xcx/tests/unit/k10-immersive-audio.test.js chengyinhub-xcx/tests/unit/play-chapter-story-blocks-contract.test.js chengyinhub-xcx/tests/unit/play-audio-resume.test.js chengyinhub-xcx/tests/unit/play-audio-level-contract.test.js`: **123/123 PASS**, zero failed/skipped; includes actual live/archived response consumers and video transitions.
- `node --test chengyinhub-xcx/tests/unit/play-mechanisms-contract.test.js chengyinhub-xcx/tests/unit/audio-session.test.js`: **14/14 PASS**. The mechanisms tests run the real GPS callback with valid/invalid/missing precision and negative controls, and require all six audible contexts to register while the unique raw preload stays silent and is destroyed. Root's integrated index also passed the seven mechanisms checks.
- Initial owned source: JS syntax and `git diff --check` PASS; `node tests/lint/ui-integrity.js --warn-as-error`: **UI-GATE-0:PASS**. Action ledger `--check` reported drift to root; VIDEO did not weaken it or claim unified gate completion.
- Coordinator VPS receipt for owned `PlayJourneyStoryServiceImplTest`: **27 tests, 0 failures/errors/skips, 0.435 seconds**, exact root 53-file manifest. Adds live media assertions and freeze → archived reopen without mutable live mapper reads. **Root integrated this identical Java test in `64f94e88a`; the preserved VIDEO-worktree copy remains intentionally unstaged.** No local Java/Maven/Docker/MySQL/Redis ran.
- GAME's overlay fix has its own 56 focused passing checks and three before-fix failures; the final-source actual rerun above validates the integration independently.

Root assigned the private rig's runtime.js/nav.js/walk.mjs adaptation: real HTML video/native audio events, token-bound callbacks, native resource teardown, byte-preserving media picker/upload seams and browser-only delayed/failed requests. The isolated synthetic harness self-check was **13/13 PASS**, distinct from the actual business evidence. [Adapter diff](proof/round14-video/media-rig-adapter.diff), [self-check](proof/round14-video/media-selfcheck.json). Root owns server.mjs and runtime/tunnels; VIDEO never restarted them.

Root later requested one narrow filename fix after the static server served MP4 as octet-stream: preserve the selected filename suffix, with MIME fallback. The focused check retained exact 21281 selected bytes and `rig-upload.mp4`; GAME and VIDEO then uploaded actual MP4s successfully. [Supplemental pointfix](proof/round14-video/mp4-filename-pointfix.diff). No further harness feature or broad test rerun was added.

All owned Chrome/Node replay clients and temporary self-check listeners were closed. Shared servers, tunnels, proxy settings and other lanes' resources were preserved.

## Remaining outside this lane

- Four-look visual signoff belongs to BRANCH/root on the combined source; VIDEO directly observed the default/star story, feedback, loading/error and ending views only. Historical four-look screenshots are not new-source acceptance.
- Phone/WeChat native autoplay, real headphones/loudspeaker output and device calibration remain unverified; browser native progress and screenshots do not establish those.
- Root owns unified CI, ledger refresh, unified Java commit and overall launch acceptance. No production access, push, merge, deployment or WeChat DevTools interaction occurred in this lane.
