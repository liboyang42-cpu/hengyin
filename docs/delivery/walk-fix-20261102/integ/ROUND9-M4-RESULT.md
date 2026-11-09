# Round 9 · lane m4-finish result

Branch `integ/launch-test-20261103`. Started after `ROUND9-M2-RESULT.md` had its `STATUS:` line (m2 still pushed a few
commits after that; I rebased on them each time). Nothing pushed to master, no PR merged, no production touched, no keys.

Commits: `0f793a01` (item 1), `4894eaf4` and `84aacda8` (item 2), `b5924778` (item 3), `dba47580` (gate fix + ledger),
`ffeef5d8` (rig evidence), `31ee554e` (last code commit: an old contract test updated for the restored Moments share).

Rig evidence is under `integ/m4-shots/`. **All shots come from the rig, not a phone.** The map in every map shot is the
rig's canvas stand-in. Rig changes stay in the rig worktree; full diff in `m4-shots/m4-rig.diff` (m2's stand-in patch
plus one tint switch, `MPWEB.rigTint`).

## 1. Guest access — done

**What changed**
- `SecurityConfig`: one new rule, `GET` only, exactly `/api/preset/route/list`, `/api/preset/route/info`,
  `/api/city/nodes/{id:\d+}`. The node pattern only takes a number, so `/nodes/favorites`, `/nodes/merchant/*`,
  `/nodes/{id}/complete`, `/nodes/{id}/favorite` and `/nodes/12a` stay closed. No wildcards.
- Guest projections (only applied when there is no logged-in member; members get exactly what they got before):
  - `PresetRouteGuestProjection`: drops admin audit fields (`createBy`, `updateBy`, times, `remark`, `params`) and the
    `status` / `delFlag` flags from the route and every stop.
  - `CityNodeGuestProjection`: drops shop phone, coupon id, quiz question and options, and personal state (completed,
    favourited, pending redeem). Name, description, photos, coordinates, address, shop name and logo, rating summary stay.
- Only live public rows: offline, deleted, unapproved routes and nodes were already filtered. New: for a guest a node is
  also hidden when its shop is disabled, banned or deleted (`RoamMapper.selectPlayerMerchantNodeDetail`, only when
  `memberId = 0`).
- No side effects for a guest: the node read skips the pending-coupon, rewarded and favourite reads. The preset reads
  never wrote anything.
- Mini program:
  - Station page (`subpackageRoam/poi-detail`): Moments share restored (`onShareTimeline`, same page path, same
    `poiId` / `merchantId` query as the friend share).
  - Official route page (`subpackageRoam/official-route`): friend and Moments share added, title = route name, image =
    route cover or the fixed roam cover (fallback title 「城瘾官方线路」).
  - Guest login wall on a shared place removed: the place now loads for a guest. Check-in and favourite are writes, so a
    guest gets the existing 「登录后继续」 sheet, and after login the place reloads with the member's own state.

**Files** `chengyinhub-framework/.../SecurityConfig.java`, `ApiPresetController.java`, `ApiCityNodeController.java`,
`PresetRouteGuestProjection.java`, `CityNodeGuestProjection.java`, `RoamMapper.xml`,
`subpackageRoam/poi-detail/index.js`, `subpackageRoam/official-route/index.js`,
`subpackageMember/components/cy/scene-roam-poi-detail/index.js`.

**Proof**
- Backend tests (all pass locally):
  - `PublicReadEndpointsSecurityChainTest`: the three paths open for GET only; POST on them, `/api/preset/style/list`,
    `/api/preset/route/info/extra`, `/api/city/nodes`, `/favorites`, `/merchant/5`, `/123/complete`, `/123/favorite`,
    `/12a` all 401. The old test that pinned `GET /api/city/nodes/123` to 401 was changed to the new rule.
  - `UpgradeAuthorizationBoundaryTest`: the same allow and deny lists in the boundary table.
  - `GuestSharedPlaceReadTest`: guest node and route responses carry no phone, coupon, quiz, personal state, admin name
    or note; guest reads touch no personal mapper; members unchanged.
  - `RoamPoiPendingOwnershipMyBatisTest`: guest sees a shop node only while the shop is live (status, account status,
    delete flag each tested), offline node gone; member read unchanged.
- Mini tests: `round9-m4-guest-place.test.js` (guest check-in and favourite open the login sheet and send no write; member
  favourite unchanged), `round7c-timeline.test.js` and `poi-deep-link-params.test.js` (share payloads),
  `get-auth-matrix-contract.test.js` (17 security rules pinned, new negative controls for widening).
- Rig over HTTP, no Authorization header (`m4-shots/m4-guest-http.txt`): list, info and node 200 with public fields
  only; offline route 「路线不存在」, offline node and closed-shop node 410; every sibling and write path 401. Database
  read-back shows the shop phone and the admin name and note exist in the rows but not in any guest response. The same
  reads as member 9004 still return what they did before.
- Rig, opened as a guest (no identity):
  - `m4-guest-place.jpg`: shared place loads (it showed 401 in m1's `share-land-guest.jpg`).
  - `m4-guest-place-favorite.jpg`: tapping 收藏 as a guest opens 「登录后继续 · 登录后即可点赞、收藏和关注」.
  - `m4-guest-official-route.jpg`, `m4-guest-official-routes.jpg`: route detail with three stops, and the list.
  - Zero page errors in all four.

**Unverified (phone only)**
- The share sheet itself, the Moments card and opening it in Moments single-page mode (scene 1154). The rig cannot
  produce a share card or that launch scene.
- Observation, not changed: members (not guests) still get `createBy` / `remark` on the route detail. That was already
  so and is not personal data, but a later pass could apply the projection to everyone.

## 2. Map style per look — done (code); look on a phone unverified

**What changed**
- `utils/map-style.js`: one `subkey` and four numbers `black`, `white`, `paper`, `cyber` (the 2077 style), all empty in
  the repo. `mapStyleFor(kind)`, `kindForLook(look)` (纸本 `PAPER` → paper, 夜城 `CYBER` → 2077, 星尘, 末世 and no look →
  black). With no key every map is WeChat's default base, byte-for-byte as before.
- `scripts/release-map-style.js`: reads `CY_MAP_SUBKEY`, `CY_MAP_STYLE_BLACK`, `_WHITE`, `_PAPER`, `_2077` (the old
  `CY_MAP_LAYER_STYLE` / `CY_MAP_LIGHT_STYLE` still count as black / white). Writes only into the upload copy; fails loudly
  with `--require` when any is missing, and always when a key is given without all four numbers or two numbers repeat.
- Who uses what: `pages/roam`, `subpackageRoam/nearby`, player `pages/searchmap` → black; merchant and editor maps →
  white (unchanged call sites); `pages/play` → by the theme's look.
- `layer-style` can't change at runtime: `pages/play` keeps its three maps unmounted until the look is known
  (`fmStyleReady`, true from the start when no key is set). If the look really changes on the same page, the maps are
  taken down and rebuilt on the next tick with the new style, never patched in place. The page around the map already
  carries the theme's look class, so overlays follow the theme's tokens.
- Readability on white and paper (`free-map` `tone`): my route, the route preview and the node rings switch from white to
  ink with a light edge; team lines keep their team colour and get a darker edge. Black and 2077 keep the white lines.
  With no key `tone` is empty and nothing changes.
- Weather and fog only exist on 漫游, which is always black, so they need no per-style change. The 漫游 map labels are also
  black-only.
- 3D buildings and about 30° tilt: `ROAM_3D = { on: false, minScale: 16, skew: 30 }` in `utils/map-style.js`. When on,
  `pages/roam` turns on `enable-building`, `enable-3D` and `skew` only at zoom 16 and above (checked on region change
  end). Off by default.
- Main package: net about +1 KB. The old long header comment of `utils/map-style.js` moved into `DARK_MAP.md`, which is not
  packaged.

**Files** `utils/map-style.js`, `scripts/release-map-style.js`, `components/cy/free-map/index.js|wxml|DARK_MAP.md`,
`pages/play/index.js|wxml`, `pages/play/utils/play-look.js`, `pages/roam/index.js|wxml`.

**Proof**
- `release-map-style.test.js` (12): empty repo values, four numbers, look mapping, ink colours on white and paper,
  untouched on black and 2077, 3D only when on and at 16+, script failures.
- `round9-m4-map-look.test.js` (7): first look shows the map with the right style in one step; a changed look takes the
  map down and rebuilds it; same style does not; all three play maps wait and carry `tone`; roam passes `tilt3d`.
- Existing map contracts (`c06-state-and-exit-contract`, `play-chapter-story-blocks-contract`) still pass. My first
  version broke them by putting the gate in front of the exact `wx:if` they pin; fixed in `84aacda8`.
- Rig shots over the stand-in, tinted to each style's land colour, 漫游 page with its own route line (from the page's
  `_myRouteLine()`), two team lines in team colours, the player dot:
  - `m4-style-grid.jpg` (black, white, paper, 2077 side by side) and the four `m4-style-*-nofog.jpg`.
  - Route and team lines are readable on all four. The ink route reads clearly on white and paper.
  - With weather: `m4-style-black-rain.jpg`, `m4-style-2077-rain.jpg`, `m4-style-paper-snow.jpg`, `m4-style-white-fog.jpg`.
    White and paper are shown only to check the layers; those combinations don't occur in the product.
- The tone was forced in the rig by setting it on the map component. On a real build it comes from `mapToneFor`, which
  needs a key.

**Seen in the rig, not changed**
- On white and paper the small white 漫游 place dots and 漫游's dark glass stat card read poorly. 漫游 is always black, so
  this can't happen in the product. If 漫游 ever gets a light style, those two need work.
- Snow is white and weak on paper; again not a real combination.

**Unverified (phone only)**
- That the four numbers match the console's style list, and that each real style looks as intended.
- No flash of another base when a theme opens (the map mounts only after the look is set, but only a phone shows the
  native map's first frame).
- Ink lines, team colours and the shop name stickers on the real paper and 2077 styles.
- 3D buildings and tilt: whether `skew` changes smoothly when bound at runtime, whether `enable-3D` works with a custom
  style, and whether the screen fog (projected flat) still lines up when tilted. I would keep it off until a phone shows
  the fog alignment.
- The play page with a real theme in each look was not walked in the rig: it needs the team and session fixtures m2 built
  by hand. The play wiring is covered by the unit tests above.

## 3. Atmosphere video — done

- Not committed to the test branch. The URL stays config: `utils/config.js` `roamAtmosVideoUrl: ''` (empty = no video).
- The no-flash dissolve from m2 (dim to black, then fade) is unchanged; its tests still pass.
- Deploy notes and checklist (`chengyinhub-xcx/docs/发版checklist.md`, section B):
  - source `design/atmos-entry/atmos.mp4` on `origin/claude/map-atmos-20261103` (second pass, approved);
  - upload to OSS `chengyin-oss` (oss-cn-shanghai) at `static/roam/atmos-entry-v2.mp4`, versioned name, never overwrite;
  - that object public read, `Content-Type: video/mp4`, HTTPS; check it plays in a browser with no login;
  - it must sit on a host the mini program plays from: the component only plays https URLs on the hosts in
    `TRUSTED_ASSET_HOSTS` (`www.chengyinhub.com`, `chengyin-oss.oss-cn-shanghai.aliyuncs.com`), and the host should be in
    the WeChat backend's downloadFile domain list;
  - then fill `roamAtmosVideoUrl` and check on a phone.
- Proof: `round9-m4-atmos-config.test.js` (URL empty, no video file in the mini program, checklist names the path, public
  read, the hosts and the config key).
- Unverified: playback from OSS on a phone, and whether WeChat needs the domain listed for `<video>`.

## Verify

- Full mini unit suite (local): 16,003 tests. The one failure on the first full run was `scene-content-contract.test.js`,
  which still forbade Moments share on the station page; updated in `31ee554e` to pin that the restored share only
  carries `shareQuery`. CI ran all 20 shards green on `31ee554e`.
- `ui-integrity.js`: `UI-GATE-0:PASS`. `build-action-ledger.js --check`: `ACTION-LEDGER PASS: 5685 controls` (rebuilt and
  reviewed: line shifts in `free-map` and the reads behind the place page's reload after login).
- Backend tests for changed code (local): `GuestSharedPlaceReadTest`, `PublicReadEndpointsSecurityChainTest`,
  `UpgradeAuthorizationBoundaryTest`, `RoamPoiPendingOwnershipMyBatisTest`, `ApiCityNodeSearchTest`,
  `LegacyAntRequestMatcherTest`, `PresetRoutePublicVisibilityTest`, `CityNodePhotoQuizTest`, `CityNodeCompleteTest`:
  all pass. `CityNodePendingVisibilityMySqlIT` schema updated for the new columns; it runs in CI.
- CI on the last code commit `31ee554e`: all green. GitHub CI run 41793117767, all 31 jobs (Fast gates, Backend tests
  incl. MySQL IT and full app startup, Migration safety, Mini static gates, 20 unit shards, gitleaks, SBOM); Funds MySQL IT,
  Isolated authenticated Redis recovery and Mini followup regression also green. No job hit a time limit this time.
- Colour-literal gate `ds-hardcode-gate.sh --ci` (not the selftest): passes. m2's `283cfb35` marked the shared play/roam
  canvas and polyline literals; my new colours in `utils/map-style.js` raise nothing.
- I pushed item 1 before rebuilding the action ledger, and the roam naming gate failed on my fallback title
  「城瘾官方路线」 (the gate forbids 路线 in roam share names). Both fixed in `dba47580`.
- Main package: about 3.24 MB, net +1 KB from this lane. Still over the ~3,150,000 target because of m2's growth (their
  item 6, needs owner). I did not move other lanes' code to make room.
- No migrations, no new Services, Mappers or Controllers (two static projection classes only).

STATUS: PARTIAL - all three items done and CI green, but the main package is still about 3.24 MB, over the ~3,150,000 target (m2's growth, their item 6 needs owner; this lane adds about 1 KB), and the play page in each look was not walked in the rig
