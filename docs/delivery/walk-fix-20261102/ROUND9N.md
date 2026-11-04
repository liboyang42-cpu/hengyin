# Round 9 last build lane: m4-finish

Work on `integ/launch-test-20261103`, after `docs/delivery/walk-fix-20261102/integ/ROUND9-M2-RESULT.md` has a `STATUS:` line. Rules from `ROUND9.md` apply. Never push to master, merge a PR or touch production. No keys in the repo. Commit each item and push after each.

## 1. Guest access (owner approved 2026-11-04)

Allow guests to read exactly these three GET paths in `SecurityConfig`, no wildcards:
- `GET /api/preset/route/list`
- `GET /api/preset/route/info`
- `GET /api/city/nodes/{id}`

Check every field they return for a guest: no phone, member id, order, sales, private progress or unpublished content. Only live, public rows: unpublished or deleted rows must not be returned. Use a guest projection where needed, as `ActivityGuestProjection` does, and no browse or write side effects for guests. Tests: guest gets public fields only; the three paths and only they open up (a sibling path stays 401); write and member-only endpoints stay 401; add to the existing security chain tests. Rig proof over HTTP.

Then: restore station Moments share (noted in `subpackageRoam/poi-detail/index.js`), and add Moments and friend share to the official route page. Remove the guest login wall on a shared place. Rig: open each shared link as a guest and screenshot.

## 2. Map style per look

Owner's final set: four styles.

| Style | Used by |
|---|---|
| black | `pages/roam`, `subpackageRoam/nearby`, `pages/searchmap` as player, theme looks 星尘 (`STARDUST`) and 末世 (`SURVIVAL`), and any theme with no look |
| white | merchant and editor maps |
| paper | theme look 纸本 (`PAPER`) |
| 2077 | theme look 夜城 (`CYBER`) |

`utils/map-style.js` holds one subkey plus the four numbers, all empty in the repo and filled by `scripts/release-map-style.js` from environment variables. `pages/play` picks by the theme's `look.style`. WeChat's docs say `layer-style` can't change at runtime, so render the map only after the look is known, or re-create it cleanly on change, without flashing. Pages and overlays around the map follow the theme's existing tokens. Pins, routes and team colours must stay readable on all four, especially white and paper: check the route glow, weather layers, fog and label stickers on each and adjust by style if needed. Rig shots for each style's overlays over the stand-in map, tinted to the style's land colour.

Also try: 3D buildings and about 30 degrees of tilt only at zoom 16 and above in `pages/roam`, behind one config flag, off by default. Report what the phone must prove.

## 3. Atmosphere video

The owner approved the realistic Earth ending on China (`design/atmos-entry/atmos.mp4` on `origin/claude/map-atmos-20261103`, second pass). Don't commit the video to the test branch. Keep the URL as config, default empty. Add to the deploy notes: OSS path, public read, and that the video must sit on a host the mini-program can play from. Put the upload in the deploy checklist. Keep the no-flash dissolve.

## Verify and report

Full mini unit tests, `ui-integrity.js`, `build-action-ledger.js --check`, backend tests for changed code, all CI on the final commit. Don't push docs while the last code commit's CI is running, because that cancels it. Main package under about 3,150,000 source bytes. Write `integ/ROUND9-M4-RESULT.md`. Plain words. Per item: status, files, proof, unverified. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
