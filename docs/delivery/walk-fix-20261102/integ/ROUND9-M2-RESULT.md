# Round 9 · lane m2-routes · result

Branch `integ/launch-test-20261103`. Started after `ROUND9-M1-RESULT.md` had its `STATUS:` line.

Rig shots are under `integ/map-shots/m2-*.jpg`, 390×844, JPEG q70. **They come from the rig, not a phone.** The rig
draws a canvas stand-in instead of Tencent's map. It was patched (rig only, `map-shots/m2-rig-standin.diff`) to draw
marker icons, polylines at their real width and colour, and `cover-view` callouts. Setup, fixture rows and every seam
are in `map-shots/m2-rig-notes.md`. I looked at every shot.

The rig walk found three product bugs in this lane's own code. All three are fixed (`a726b420`, see items 2 and 3).

## 1. My route — done

- One helper, `utils/my-route-line.js`, gives two polylines: a wide translucent glow (14 px, white at 22%) under a
  narrow bright line (4 px, with a thin dark border so it also reads on the light default map).
- Point count stays small. The settled part of the track is simplified once and cached; only the newest tail (up to
  48 points) is added as is. Each `setData` carries at most about 200 points, however long the walk.
- **Roam.** Before, the whole track went to `setData` every 1.5 s and grew without limit. Now it goes through the helper.
  Nothing is pushed while the page is hidden; on return the line is sent once.
- **Theme play.** Before, play did not record the player's own GPS track at all. Now it records from the existing location
  callback only, so the foreground and background rules are unchanged. It skips jitter under 8 m and points with
  accuracy worse than 60 m. It draws only while a map is visible (compass map, story map, chapter map, nav) and the page
  is in front, at most every 3 s. A thinned copy is kept on the device per session, so a reload continues the line.
- `free-map` has a new `trail-polyline` that is drawn above the route. Callers that don't pass it are unchanged.
- Files: `utils/my-route-line.js`, `pages/play/utils/play-trail.js`, `pages/roam/index.js`, `pages/play/index.{js,wxml}`,
  `components/cy/free-map/index.{js,wxml}`.
- Proof: `tests/unit/my-route-line.test.js` (3,000-point walk stays under the cap, a new session does not reuse the old
  cache, hidden page does not push). Shots `m2-route-roam.jpg`, `m2-route-play.jpg`.
- Phone only: how the translucent glow looks on the custom dark style; battery over a 3-hour walk.

## 2. Same-session team routes — done

Owner-approved limits, as built:
- **Only teams in the same session see each other.** "Session" is the activity (`cms_activity`). A team is the
  player's `play_team` for that activity; someone without a team is a team of one. Self-play (no activity) never
  shows this.
- **Each person decides before the start.** Before the first start of a session, a sheet 「同场路线」 asks with one
  switch row 「让同场队伍看到我的路线」 and a full-width 「开始」 (the prototype's thin button is fixed). The default is
  **on**, from one config value `chengyin.team-trail.default-share`.
- **The theme's author can turn it off.** New 「同场路线」 switch in the theme editor's detail sheet, stored as
  `teamTrail:false` in the theme runtime config. No migration.
- People who turned it off, left (refunded or cancelled), or finished show nowhere, and their past points are not served.

How it works:
- **Backend.** `GET /api/team/trail/state`, `POST /api/team/trail/consent`, `POST /api/team/trail/sync` on the existing
  `ApiPlayTeamController`, with logic in the new `TeamTrailServiceImpl`.
  - Every call needs a logged-in member who has a paid, valid registration in that activity.
  - The theme must not have it turned off, and the session must not be over.
  - Otherwise the answer is only `{available:false}`; guests get 401.
  - Rate limits: consent 20/min, sync 12/min per member.
  - Turning sharing off deletes the person's points at once. `finished:true` (sent when the theme ends) deletes them too.
    On every read, people who are no longer paid in the session, or whose last report is older than 10 minutes,
    are left out.
  - The response carries only a team name or nickname, an avatar, the progress count and coarse points. Team ids
    go out only as a 12-character one-way key; no member ids and no phone numbers.
- **Storage and retention.** Redis only, at four decimals (about 11 m), at most 240 points per person.
  - The keys expire 1 hour after the session ends, and never later than 6 hours after the last write.
  - Nothing goes to MySQL. Nothing is on any public projection.
- **Client.** It polls about every 12 s and backs off to 60 s on failure, with no sockets. It pauses when hidden and
  sends only the points that are new since the last success.
  - Each team gets one colour, a glow line, an avatar pin with 「第 N 站」 (moved with `translateMarker`), and a rail on the
    right of the story map. Tapping a team follows it; tapping again stops.
  - Colours: three from the prototype plus three already approved, added to the single-colour contract's allow list for
    this one palette only.
- **Architecture.** Owner `R9_TEAM_TRAIL` is registered, and the verifier is repinned to `9041c5a6` in `ci.yml` and both
  `compiled_controls` files.

Bugs found by the rig walk, now fixed (`a726b420`):
- Consent and sync went out form-encoded, and the backend (which takes JSON) answered 500. The feature did not work at all.
  Both calls now send `application/json`.
- A failed consent request used to fall back to the server default "on", and could start uploading for someone who had
  switched it off. Now, if the server has not confirmed, the client follows what the person chose; off stays off and
  nothing is uploaded.

Proof:
- `TeamTrailServiceImplTest` (10 tests). It covers same session, own team hidden, coarse storage, retention to session
  end, another session, guest, unpaid, opt-out deletes and hides, left or finished hides, author off, and the default
  as one value.
- `tests/unit/team-trail.test.js`: polling interval, only new points sent, not running when unavailable, finish deletes,
  JSON headers, failed consent with off never starts.
- Rig, over real HTTP with two real accounts in the same session (`m2-team-negative.txt`):
  - another session: `available:false` and Redis untouched;
  - guest: 401;
  - unpaid: `available:false`;
  - opted out: the field is gone from `team_trail:pts:14`;
  - `finished`: the field is gone.
- Shots `m2-consent.jpg`, `m2-team-a.jpg` and `m2-team-b.jpg` (each account sees the other team's line, pin and rail),
  and `m2-team-follow.jpg`.

Retake on the fix (`a726b420`, then `12d84be8`), without any rig shim:
- A normal consent + sync from the page returns 200 and is stored (`m2-team-negative.txt` §13–§15). A two-player run made
  2 consents and 5 syncs, all 200, with no rate-limit replies.
- Switch off + consent request forced to fail: the client stays off and sends no sync; Redis has no opt row and no
  points for that person (§12).
- `m2-team-a.jpg`, `m2-team-b.jpg` and `m2-team-follow.jpg` were retaken and are unchanged from the first walk.

Not verified:
- **Phone only:**
  - `translateMarker` smoothness and avatar callout placement on Tencent's map;
  - polling on a cellular network, and pausing in the background;
  - whether 12 polls a minute is too tight for someone who reopens the page.

## 3. Route share card — done

- The existing end-of-theme review moved in rather than a second one being added.
  - The finish sheet now opens with the card: the route draws itself on a dark street-pattern background, then a big
    kilometre number, the theme name, time, stops, city, and the theme's mini-program code (`/api/topic/share-code`).
  - The old 「保存足迹卡」 / medal-screen save now saves this same card. 分享 uses `wx.showShareImageMenu`.
  - Nothing rewards or asks for sharing.
- **Data.** The real walked track is used first; without one, the completed stops in order. Solo, team and free
  exploration are the same.
- **Route and number.** The route is fitted into the upper box only, so it never crosses the number or the code (unit
  tested on long, flat and steep routes).
- **Privacy.** The first and last 150 m of the route are cut off (a quarter each for very short walks), so the card
  never shows a doorstep.
- City comes from the existing reverse geocode. With `reducedMotion` the card appears finished.
- Rig fix: the card now starts below the capsule, and the title row no longer slides up under the buttons
  (retaken `m2-card2.jpg`; the fixed 「继续」 button still floats over scrolled content, as before).
- Files: `pages/play/utils/route-card.js`, `pages/play/index.{js,wxml,wxss}`.
- Proof: `tests/unit/route-card.test.js` (6 tests). Shot `m2-card2.jpg`.
- Not verified: in the rig the theme code is missing (WeChat cannot be reached, 「个人码生成服务繁忙」) and the city is missing
  (no Tencent key). Phone only: album save, the share sheet, real fonts.

## Extra items (ROUND9M2B.md)

### 1. Swipe cards — done
- The roam strip is dark now and shows only the name, the distance and 「去这儿」. The 「合作商家 · 探店 · 距你很近」 line is gone.
- The distance line opens the map (it keeps m1's address tap). The card is 200 rpx tall, so there is no empty lower half.
- Shot `m2-fly-after.jpg` (before: `m-fly.jpg`).

### 2. Pin crowding — done
- All non-status pins avoid each other: photos, shop figures, node icons and dark circles. They are placed in order of
  relevance: the selected one, then participating shops, then those with a photo, then those nearest the centre. A
  pin that would touch one already placed becomes a small dot until you zoom in.
- At most 40 photo callouts. On the first frame, before any region is reported, the view is estimated from the
  centre and scale.
- Unit-tested at 46 and 200 markers for scales 15–18: no overlaps, and a participating shop only loses to another
  participating shop. Shots `m2-pins-46.jpg`, `m2-pins-200.jpg`.
- The rig showed a shop tag sitting on the player's own dot. The player dot now takes part in the avoidance too (unit tested).

### 3. Density dots — done
- Positions are still the real points. Dots are white cores with a soft glow, and three sizes (18/26/34 px) get
  brighter as more points stack. No labels added. Shot `m2-zoom-far-after.jpg`.

### 4. Share sheet — done
- The shop card uses the theme card layout: the shop's own cover photo full-bleed and darkened, the name, the
  address, and a larger shop code (168 rpx).
- Cover order: decoration cover, then first gallery photo, then shop logo or avatar. The saved image uses the same layout.
- Shot `m2-shop-share-after.jpg`. No code in the rig (WeChat unreachable). The address line was made brighter after the walk.

### 5. Atmosphere video default — done
- The video now ends in two steps. First it dims to black over 0.36 s, then the whole layer fades out over 0.6 s.
  Before, the near-white last frame faded straight onto the dark map.
- Frame sequence from the real `design/atmos-entry/atmos.mp4` (`origin/claude/map-atmos-20261103`):
  `map-shots/atmos-transition.jpg`.
  - It shows before and after, from the video's last frame over the dark roam map.
  - Mean brightness, after: 242 → 214 → 134 → 0 → 19 → 35 → 46 → 53 → 55 (every 120 ms).
  - Mean brightness, before: 242 → 174 → 121 → 84 → 62 → 55. The white stays over the map through the whole fade.
- This sequence is a composite with the same timing curves, not a recording from a device.

### 6. Main package — not done
- Main package source: **3,222,030 bytes** before this lane, **3,233,569** now (+11.5 KB). The growth is from
  `free-map`, roam's glow line, pin layout and heat dots, and `utils/my-route-line.js`, which roam needs synchronously.
- Getting under 3.15 MB with room means moving about 100 KB out of the main package. `pages/roam` is 528 KB, but almost
  all of it is page logic that runs synchronously: the largest single method is `_syncMarkers` at 12 KB, and the rest
  is spread across about 300 methods.
- The only way to load page JS from `subpackageRoam` later is `require.async`.
  - Nothing in the repo uses it yet, and the rig cannot run it.
  - Moving roam's logic behind it now would be an unverifiable change to the busiest page.
  - Moving overlay sheets into async components saves only about 30 KB of WXML and WXSS.
- So I did not move anything. I'd rather say that than ship a change I couldn't check.
- **Needs owner:**
  - Recommended: first prove `require.async` on one phone with a small roam module, for example the share-image drawing.
    Then move the end-of-session, share and passport logic behind it, which is about 60–80 KB.
  - Alternatively: relocate the four utils used by only one subpackage (42 KB: `deregister-flow`, `exploration-goals`,
    `user-invoice`, `capsule-client`).

### 7. Small fixes — done
- **Growth center.** `pages/growthcenter/index` no longer has `onShareAppMessage`, so there is no 「转发」.
- **Reminder landing pages.** Coop, recruit and broadcast reminders now land on:
  - invitee (coop invite, club interest): `pages/coop/list/index`, the 「收到的」 tab;
  - inviter (invite status): `pages/coop/list/index?tab=sent`;
  - publisher (recruit status): `subpackageA/pages/myproject/index`;
  - official broadcast with an event: `pages/activity/official-detail/index?id=<event>`;
  - pure announcement: the merchant or club workbench, and still home for players.
  - Paths only (the event id is public); ownership checks are unchanged.
- **Proof.**
  - `WxSubscribeMsgLandingPageTest`: every path is registered in `app.json`.
  - `round9-m2b.test.js`.
  - Every page opened in the rig with a fitting identity: `m2-land-*.jpg`. All opened without a page error. Background
    403s on the club and official pages come from the fixture data and are listed in the rig notes.
- **Phone only:** the tap from a real WeChat service notification.

### 8. CI — done
- `Backend tests` `timeout-minutes` 15 → 20. Nothing else about that job changed, and no other CI change was made.

## Verification

- **Full mini unit suite** (local, on `40b4dcdb`): 15,976 tests, 0 failures. The final full run is below. After the rig fixes, the touched files were
  rerun: team-trail, route-card, play-ui, monochrome, GET matrix, action ledger, roam pins and the shop strip all pass.
- **UI gates:**
  - `ui-integrity.js`: UI-GATE-0:PASS. The baseline has only line-number moves, re-anchored line by line against the
    parent commit with recomputed `keyDigest`. No entries were added.
  - `build-action-ledger.js --check`: PASS, 5,685 controls. Test counts were updated with reasons: tap 2494 → 2497,
    event contracts 2744 → 2746, controls 5678 → 5685.
  - `setdata-dead-field-lint.js`: pass.
- **Backend:**
  - `TeamTrailServiceImplTest`, `WxSubscribeMsg*Test`, `OfficialBroadcastDeliveryTest`, `OfficialEventContractTest`,
    `NotifyFanoutContractTest`, `ThemeRuntime*Test` and related: 187 run, 0 failures.
  - `OfficialBroadcastDeliveryMySqlIT` was updated for the new four-argument call but needs MySQL, so CI runs it.
- **Architecture:**
  - `test_compiled_controls` passes.
  - `test_gate` has the same 6 errors with and without this change. They come from the shallow clone missing an old
    commit (`54c524ca`), not from this lane.
- **Migrations:** none. No rehearsal was needed, and PR #723's ③ section needs no change.
- **Full mini unit suite, final** (local, on `bd6d9e83`): 15,978 tests, 0 failures.
- **DS hard-code gate** (`ds-hardcode-gate.sh --ci`, not its selftest): CI caught new literal font sizes in the roam strip
  and literal colours in native polyline / canvas code. Fixed in `283cfb35`: the font sizes now use `--cy-type-*`, and
  the colours that native map data and canvas can only take as literals carry `/* ds-ok */` with the reason. The gate
  passes locally.
- **CI.**
  - GitHub CI run 41793117767 on `31ee554e`, the branch tip containing all of this lane's commits: **31 of 31 jobs
    succeeded**. That includes Fast gates (4m00s), Mini program static gates, all 20 mini unit shards, Backend tests and
    Migration safety (4m13s).
  - Backend tests took **14m10s**. That is under the new 20-minute limit and only 50 s under the old 15, so the raise
    was needed.
  - Earlier runs on this branch show as *cancelled* only because a newer push (this lane's or lane m4's) replaced them.
    No job was cut off at its time limit in this lane's runs.

## Needs owner

- Main package size (item 6). Recommended default: verify `require.async` on a phone first, then move roam's
  end-of-session, share and passport logic.
- Team-route polling budget is 12 per minute per member. Recommended: keep it. A player who reopens the page several
  times a minute gets a short 「访问过于频繁」, and the client backs off silently.

STATUS: PARTIAL - extra item 6 not done: main package source is 3,233,569 bytes, above the ~3,150,000 target; moving roam logic out needs require.async, which could not be verified here (needs owner)
