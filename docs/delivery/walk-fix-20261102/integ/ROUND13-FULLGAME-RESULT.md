# Round 13: the owner's full game, walk result

Run 2026-11-05 by the m3 worker, on branch head `1a9b55ab5`. **Not done.** I did not author, publish or play the full game, and I did not run the throttled, failing-media or four-look passes. **Nothing was heard**, and no audio or video call was logged this round.

## What I did
- Pulled the head, read `CODEX-IMMERSIVE-RESULT.md`, `CODEX-VIDEO-RESULT.md` and `proof/k10-walk/`.
- Brought the rig up on `1a9b55ab5` with `up.sh` (jar built with `-Dmaven.test.skip=true`, real MySQL 8.0.45 and Redis, browser bundle): it reported `done` in 100 s and the app answers. No game was authored on it.
- The rig also needs the k10 stand-ins before an author can save images or audio: TLS certificates and `/etc/hosts` entries for `api.weixin.qq.com` and the production bucket host, a JVM truststore, `wxfake.py` and `fakeoss.py` on port 443, and `approve.py` for audio review. I did not set these up.

## Why I stopped
Building the whole game on screen means hundreds of editor steps (opening chapter with video and music, theme music, narration, photo and QR stations, branch, egg, ending chapter with video and music), then two branch runs, an egg miss and hit, a reopen, then three more passes. K10 spent a full session and reached a partial result on an older editor; the editor has since changed (video and theme-music fields). That is more than my remaining credit. I stopped before the stand-ins rather than leave a half-built rig that looks like progress.

## Requirement table
"Existing" means another worker's report on the shared branch; I did not re-verify it.

| Requirement | Works? | What was seen |
|---|---|---|
| Opening chapter with opening video | Existing, partly | CODEX-VIDEO-RESULT: authored on screen, saved, reloaded, published; opening playback, poster, skip and slow/error fallback walked in the rig on theme 990032. Not re-walked by me. |
| Opening music / theme music | Existing | Same report: one theme-wide music row, editor to player start/pause/resume, rig only. Not re-walked. |
| Chapter narration | Existing | K10: plays once when the chapter opens, rig audio log. Not re-walked. |
| Photo station | Not walked | Codex: the ordinary qaShot with branch/egg save and the full run are NOT proven on the current backend. |
| QR station | Not walked | Printed-code scan only on a phone. |
| Branch (互斥选项) | Not walked | The 进行方式 / chapter-completion block (E16/E18) was addressed by Codex (a photo-and-egg save regression test), not proven on screen. |
| Easter egg | Not walked | TIME text egg saved in an earlier draft only. |
| Ending chapter with ending video and music | Not walked | Ending music was walked by K10. Ending video is unverified, as CODEX-VIDEO-RESULT says. |
| Publish, free solo ticket, both branch runs, egg miss and hit, reopen | Not walked | Needs the authored theme. |
| Real audio and video call log | Not done | No play happened. |
| Throttled media, failing media | Not walked | My unit tests cover music retry (3 s / 8 s), the voice-error toast and the next-station preload; no rig run. Stand-in media server code is in this session only (`/tmp/media_srv.py`, 500 / trickle / hang). |
| Four looks on station sheets, photo, scan, finish card, error states | Not walked | |
| Branch completion setting that blocks 进行方式 | Not re-checked | Codex reports it handled; E18's explicit `ALL` marker is kept on purpose. |

## To continue
Someone with budget should: set up the k10 stand-ins (certs, hosts, truststore, `wxfake`, `fakeoss`, `approve.py`), author the game in the editor with `k10-walk/helpers.mjs`, then use `looks.sh` for the four looks and the `127.0.0.1:8765` media server pattern for slow and failing media. Fix bugs one at a time, and push each with `[skip ci]`.

STATUS: PARTIAL - rig is up on the head, but the full game was not authored, played, throttled or looked at; no bug was found or fixed in this job because nothing was walked
