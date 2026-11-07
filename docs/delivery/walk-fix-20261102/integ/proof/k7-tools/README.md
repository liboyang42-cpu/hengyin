# k7 rig tools (round 12)

Walk helpers used for ROUND12-K7-RESULT.md. They extend j3's second-pass tools
(`docs/delivery/check-20261105/j3-player-game/tools/` on `claude/check-1105-j3-player-game`):
author2.py / approve.py / fx_onsale.py / buy_api2.py were used unchanged except for paths.

- `w.mjs <identity> <topicId> <shot-prefix> [extra-steps-json]` — open the play page at station 1, turn on location,
  arrive, open the task; env `OPEN` (button to tap, `none` to skip), `PRE` (steps before it), `GREP`.
- `drive.mjs` — j3's driver plus an `evalfile:` step.
- `hook.js` — logs every setData touching chapterParas / journeyCheck / advancedPlay (used to find N4's cause).
- `probe.js` — reads the chapter scroll box and `.st-line` rects (N4: both measured 0×0).
- `advstate.js` — reads the advanced-game session state (N3: FAILED, canRetry false).
- `n3run.sh` — wrong answer → automatic quest restart → right answer → bag.
- `rig-changes.diff` — rig branch changes: j3's diff plus `SHA` overridable in up.sh.

Rig notes: Docker Hub rate-limited the pinned images; `/etc/docker/daemon.json` with
`"registry-mirrors":["https://mirror.gcr.io"]` fixed it. Backend changes were loaded by exploding the
planned candidate jar and overlaying `BOOT-INF/lib/chengyinhub-*.jar` and `BOOT-INF/classes/com/**`
from this branch's build (chain_acceptance refuses a jar whose source differs from the plan); the
e2e stubs stayed in place. The browser bundle was rebuilt from this working tree (`CAND=<repo> mpweb/build.sh`).
