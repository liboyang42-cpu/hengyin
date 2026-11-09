# Round 9 · lane m1-visual · result

Branch `integ/launch-test-20261103`. Started after `ROUND7C-RESULT.md` got its `STATUS:` line (22:25 UTC, 2026-11-03).
Rig shots are under `integ/map-shots/`, 390×844, JPEG q70. **They come from the rig, not a phone.** The rig draws a
canvas stand-in instead of Tencent's map: no tiles, no gestures, no custom map style. What only a phone can prove is
listed under each item.

Rig setup for these shots (rig-only, nothing in product code):
- The walkthrough rig from `claude/cloud-walk-20261101-rig`, with the club lane's harness patch, pinned to this branch.
  MySQL/Redis images pulled by the same digests from `mirror.gcr.io` (Docker Hub rate limit).
- 47 throwaway `roam_poi` rows around People's Square; 32 have a cover photo (the prototype's example covers), 3 are
  merchant nodes. Covers are stored as `/profile/upload/…` keys and the app was started with
  `APP_MEDIA_PUBLIC_BASE_URL=https://www.chengyinhub.com/prod-api`, so they pass the real media review gate.
- The rig never emits `regionchange`, so the walk calls the page's own `onRegionChange` with an `end` event to stand
  in for a pinch or drag. POIs start under fog; for the pin shots they were set to "seen" (the state they reach after
  walking past them).
- Stand-in patch: it now draws marker icons and places `cover-view` callouts by `customCallout`, and uses the page's
  centre and scale when a map has custom callouts.

## 1. Style wiring — done

- `utils/map-style.js` still ships `subkey: ''`. New `playerMapStyle()` / `lightMapStyle()`.
- `scripts/release-map-style.js --dir <upload copy> [--require]` fills `CY_MAP_SUBKEY`, `CY_MAP_LAYER_STYLE` (dark) and
  `CY_MAP_LIGHT_STYLE` into the copy only. It refuses the repository itself, never prints the key, and fails when any
  value is missing under `--require`. A key without a light style index always fails, because merchant maps would turn dark.
- One subkey per mini program (WeChat map docs, and the note in `free-map/DARK_MAP.md`): the style is chosen only by
  `layer-style`. So merchant and editor maps pass the **same** subkey with the light index; with no key they stay on
  the official default.
  - Player: `pages/roam`, `pages/play`, `subpackageRoam/nearby`, and `pages/searchmap` when the viewer is not in merchant view.
  - Light: `pages/publish/fabu`, `pages/topic/index`, `pages/square/components/route-map`, and `pages/searchmap` in merchant view.
- Files: `utils/map-style.js`, `scripts/release-map-style.js`, the pages above, `components/cy/free-map/DARK_MAP.md`.
- Proof: `tests/unit/release-map-style.test.js` (8 tests).
- Phone only: that the bound indexes match the console, that the light index is really light, and that two styles on
  one key don't bleed between pages in one launch. I could not open developers.weixin.qq.com from here (DNS blocked);
  the one-subkey rule is from a search of mirrors of the official docs.

## 2. Photo pins — done

- Circular photo pins through `free-map`'s marker + `cover-view` callout route (`explorePins`). The marker under each
  photo is a dark circle, so a pin is still visible if the callout doesn't render.
- Only real photos count: a city node's `cover_img` (through `app.getImgUrl` and the backend media gate) or a
  registered node's `picUrl`. The old placeholder image is never shown as a photo.
- Participating shops (registered merchants, and nodes with a merchant) get a small white name sticker; everything
  else shows the photo only. The map no longer puts text bubbles on places; street and landmark names are left to the
  base map style. Status bubbles that tell the player something (正在打卡 / 打卡未记录 / 待核销, demo points) are unchanged.
- No photo: dark circle with a small place icon. City nodes without a photo keep their level shape.
- Near off-screen points shrink to 26 px photos at the edge; points further than 0.6 of a screen out are not drawn.
  This runs on `regionchange` end, the only time WeChat reports the view.
- Crowding: photos that would overlap a nearer one drop to a 20 px dark dot. At most 40 photo callouts per screen.
- setData: markers are signed and only re-sent when they change; region updates merge to one every 250 ms.
- Files: `pages/roam/photo-pins.js` (new), `pages/roam/index.{js,wxml,wxss}`, `components/cy/free-map/index.{js,wxml,wxss}`.
- Proof: `tests/unit/roam-photo-pins.test.js` (13 tests); shot `m-dark-pins.jpg`.
- Phone only: `customCallout` placement (anchorY = pin size), cover-view count and scroll smoothness with 40 photos,
  and how the white ring reads on the dark custom style.

## 3. Place card — done

- Tapping a pin moves the map to it (`moveToLocation`), enlarges it, and opens a card that scales up from the pin:
  photo, name, address, rating, distance, 导航 and 分享 (`m-card`).
- 导航 is `wx.openLocation`. The address line also opens the map.
- Rating comes from the existing node detail read (`/api/city/nodes/{id}`: `averageRating` / `ratingCount`). No reviews
  means no rating; a failed read shows "—" in that slot and nothing else.
- 分享 lands on the existing deep-link page `subpackageRoam/poi-detail` (`poiId`; `merchantId` when there is only a
  shop). Cover = the place photo or the fixed picture, never a map screenshot. Plain landmarks (type 1) are not nodes
  and have no landing page (the node read returns 410), so their card has no 分享 button.
- Tapping the card body takes the old path, unchanged: node detail, theme sheet, shop banner or landmark card. During a
  check-in (or a check-in status pin) taps go straight to the old path without the card.
- Files: `subpackageRoam/components/roam-place-card/` (new, async via `componentPlaceholder`), `utils/place-share.js`
  (new), `subpackageRoam/poi-detail/index.js`.
- Proof: unit tests in `roam-photo-pins.test.js` and `roam-atmos-entry.test.js`. Shots `m-card.jpg` (merchant node),
  `m-card-landmark.jpg` (landmark, no share), `share-land-member.jpg` (another member opens the shared link and lands
  on 河滨步道).
- **Needs owner:** a guest who opens a shared node gets a 401 (`share-land-guest.jpg`), because `SecurityConfig` does not let
  guests read `/api/city/nodes/{id}`. This is the same open question as Round 7C. I did not change security.
- Phone only: the scale-from-pin animation, and the share sheet and card cover.

## 4. Card row and map — done

- The existing shop strip under 漫游 (opened from 发现附近店铺) now flies the map to the card's pin with
  `moveToLocation` and enlarges it. With the strip open, tapping a pin scrolls the strip to its card.
- The bottom card and the activity sheet are untouched; the strip still swaps with the bottom card as before.
- Proof: unit test; shot `m-fly.jpg` (the selected pin 半坡展览 is enlarged).
- Phone only: `moveToLocation` easing while swiping fast.

## 5. Density — done

- Scale ≤ 14: real points merged per 18 px screen cell into small dots. This is grid bucketing at `regionchange` end,
  not `initMarkerCluster`: clustering draws count bubbles, the design wants dots. Plus 「附近 N 个地点」 from the real
  point count, hidden under 10. Tapping a dot zooms in.
- The dots are white with a dark ring, not the prototype's blue: the play-surface monochrome contract
  (`play-monochrome-contract.test.js`) does not allow new colours, and white with a dark ring also reads on the light default map.
- Scale > 14: photo pins.
- Proof: unit tests; shot `m-zoom-far.jpg`.

## 6. Drag — done

- A thin two-blob cloud layer above the map, pointer-events none, no `filter`. It brightens slightly on drag start; on
  release it drifts 0.35× the map's move with a 0.9 s ease-out. WeChat only reports start and end, so it lags behind
  rather than following the finger frame by frame. Edge pins settle on release (item 2).
- With `reducedMotion` the layer is not rendered.
- Proof: unit test of the drift maths.
- Phone only: smoothness while dragging. The rig cannot drag.

## 7. Weather — done (built by a worker on this lane, reviewed and pushed: `9c16c4f5`, `26b34bf3`, `28cf05c0`, `4f67cae4`)

- Backend `GET /api/map/weather?lat&lng`:
  - Public, exact path in `SecurityConfig`.
  - `@RateLimiter` 20 per minute per IP.
  - Bad lat/lng gets an error. Everything else is HTTP 200 and fails silent: missing key, timeout or a bad upstream
    reply all give `{kind:'clear'}`.
  - Uses `tencent.map.key` through the existing `TencentMapClient`.
  - Cached in Redis per 0.05° area for 12 minutes; upstream failures are cached as clear for 3 minutes.
  - Only the area centre goes upstream, never the player's exact position.
- Response shape: Tencent WebService `ws/weather/v1/?type=now`, reading `result.realtime[0].infos.weather` (Chinese
  text). lbs.qq.com was unreachable, so the source is Tencent's own `tencentmap-webservice-skill` reference on GitHub.
  **The key cannot be called from here: only the outbound call is stubbed in tests.**
- Architecture owner `R9_MAP_WEATHER` registered and the verifier repinned to `28cf05c0`.
- Client `subpackageRoam/components/map-weather`:
  - Canvas 2D rain, snow or a fog veil, pointer-events none.
  - At most one request per area per 10 minutes.
  - Stopped on hide and detach; nothing at all with `reducedMotion`.
  - No message when the weather can't be read.
- Proof: `MapWeatherServiceImplTest`, `ApiMapControllerSecurityContractTest`, `PublicReadEndpointsSecurityChainTest`,
  `map-weather-overlay.test.js`. Shots `m-rain.jpg` and `m-snow.jpg`: the component was fed rain/snow in the rig,
  because without a key the endpoint returns clear.
- Phone only: canvas over the native map with same-layer rendering, touches passing through, frame rate and battery,
  the live Tencent text values (for example 雨夹雪).

## 8. Atmosphere entry player — done (waiting for m3's video)

- `subpackageRoam/components/roam-atmos-entry`. The URL is `config.roamAtmosVideoUrl`, empty by default; with it empty
  the component is never mounted and the subpackage is not even fetched.
- Mounted 300 ms after the 漫游 entry map is on screen. Transparent while loading, so the map underneath shows.
- Gives up silently on:
  - 2G/3G/no network;
  - `reducedMotion`;
  - an untrusted URL;
  - an error;
  - not playing within 1.5 s.
- Tap anywhere to skip. Fades out over 0.6 s.
- "Seen" is stored per device only once it actually plays or is skipped. `resetSeen()` / `force` let the app replay it.
- Proof: `roam-atmos-entry.test.js`.
- Not verified: anything with a real video. m3 has not delivered it and hosting comes later.

## 9. Every address opens directly — done (worker: `5c903f2a` `081c3a69` `0978d9f4` `290d75a9` `104fe7d1` `bfa4d991` `3f7414f6`; lead: `9375086f`)

- **Helper.** `utils/open-address.js`, one helper used everywhere.
  - With coordinates it calls `wx.openLocation` at scale 17.
  - With text only it asks the new `POST /api/map/geocode`, which sits on the existing `ApiMapController` and
    `MapGeocodeServiceImpl` with the same reliability check. It is cached 5 minutes on the server and keeps the last 60
    addresses on the device, and is rate-limited at 30 per minute per IP. The page JS loads the helper only on tap.
  - With neither, the address has no tap target.
- **Sweep.** `map-shots/address-sweep.md` lists all 86 lines that the grep finds.
  - 33 are covered by this round.
  - 11 already opened the map.
  - 14 are editor fields: a tap opens the picker at the saved point, and read-only states open the map.
  - 28 are not covered, each with its reason. 15 aren't addresses, 3 have no coordinates, 6 sit on rows whose tap does
    something else, 2 are fallback forms that are never shown, and 2 are the roam entry cards, where the whole card
    opens the activity.
- **Roam (lead).** The address on the landmark card and on the shop strip now opens the map.
- **Data the backend now returns.**
  - Coupon list: the issuing shop's name, address and coordinates, public shops only.
  - `/api/project/my`: activity coordinates.
- **导航 buttons.** On the nearby-merchant list and other merchant cards (`cy-merchant-card`), and on coupons.
- **Shop share.**
  - New `POST /api/merchant/share-code` on the existing `ApiMerchantController`. Public merchants only, reuses
    `PlayerWxacodeService`. No migration, and no new class, so no architecture registration was needed.
  - The shop home has a share button. ··· → 分享主页 opens the same share card instead of a toast.
- **Profile.** `pages/gerenziliao` uses `button open-type="chooseAvatar"`, reusing the existing crop, upload and review
  path, and `input type="nickname"`.
- **Rule kept.** On `pages/publish/activity`, once tickets are sold the location row still shows "can't change" rather
  than the map. `publish-activity-edit.test.js` pins that behaviour.
- **Proof.**
  - `open-address.test.js` and `round9-m1-address-share-profile.test.js`.
  - Java: `ApiMapControllerSecurityContractTest`, `MapGeocode*`, `ApiCouponControllerMerchantAccessTest`,
    `ApiMerchantPublicHomeTest`, `ApiProjectControllerTest`.
  - Rig shots: `a9-profile.jpg`, `a9-shop-home.jpg` (share button next to ···), `a9-shop-share.jpg`. In the rig the share
    card opens without the shop code, because the rig cannot reach WeChat to generate one: the endpoint returns
    「个人码生成服务繁忙」. The card still renders cleanly without the code.
  - `pages/coop/nearby` shows 「附近暂无商家」 in the rig because the fixture shops have no coordinates.
- **Phone only.**
  - `wx.openLocation` opening.
  - Geocode quality with the real key.
  - The `chooseAvatar` sheet and nickname suggestion.
  - Real shop code generation and the scan landing (scenes 1047/1048).
  - `chooseLocation` opening at the saved point.

## Verification

- **Full mini unit suite** (`npm run test:unit`, local, on `9375086f`): 15,950 of 15,951 pass. The one failure was the
  action-ledger event-contract count: 2743 → 2744, a real rebuild count hidden behind the tap count in the same test.
  It is fixed in the last commit, and `action-ledger.test.js` now passes on its own.
- **UI gates:**
  - `node tests/lint/ui-integrity.js`: UI-GATE-0:PASS.
  - `scripts/ui-validity-gates.js` (U1–U6): pass.
  - `setdata-dead-field-lint.js`: pass.
  - `build-action-ledger.js --check`: PASS, 5678 controls.
  - `wechat-release-contract --source-only`: pass.
- **Baselines:**
  - `ui-integrity` baseline: only line-number moves (difflib against the parent commit, same call line) with
    recomputed `keyDigest`. No entries added.
  - `u4-dead-setdata-field.json`: two existing `merchant/apply` entries relabelled A1 → A2. They are the same dead
    fields; the item 9 address tap now reads them in JS.
- **Backend:**
  - Weather: 7 + 5 + 11 passing.
  - Item 9: `ApiMapControllerSecurityContractTest`, `MapGeocode*`, `ApiCouponControllerMerchantAccessTest`,
    `ApiMerchantPublicHomeTest`, `ApiProjectControllerTest`, all passing (worker's runs).
  - Architecture: `test_compiled_controls.py` 10/10 after the repin.
- **Migrations:** none. No migration rehearsal was needed, and PR #723's ③ section needs no change.
- **CI on `9375086f`:** Backend tests, Fast gates, Migration safety and the other workflows are green. Two jobs failed:
  "Mini program unit shard 12/20" (the ledger count above) and "Mini program static checks", which only summarises
  the shards. The count fix is in the last push.
- **CI on `8b78f5ca`** (which contains the count fix; the `caa3ece4` run was cancelled by the newer push): GitHub CI
  run 41763058228, **31 of 31 jobs succeeded**, including Backend tests, Fast gates, Migration safety and all 20 mini unit shards.
- **Why CI was cut off.** On `3f7414f6`, "Backend tests" showed as *cancelled* at 「Describe the tested Boot Jar」. That
  job has `timeout-minutes: 15`. It started 01:05:36 and was stopped 01:20:41, exactly 15 minutes. Build+test took
  9m41s, isolated startup 1m55s and the migration MySQL IT 1m54s, which leaves seconds for the jar description. On
  `9375086f` it finished in time, so it is on the edge, not broken. I did not change the gate. Suggestion for the
  owner: raise `timeout-minutes` to 20 for that job, or move the jar description and compiled-reference checks to their
  own job.
- Main package source bytes (`mainpkg-size-report`):
  - 3,181,969 before this lane, 3,211,141 after items 1–8 (+29 KB).
  - Most of the growth is roam page logic, which has to sit in the main package with `pages/roam`.
  - New components and the weather renderer are in `subpackageRoam` behind `componentPlaceholder`.
  - **The main package was already above the ~3,150,000 target before this round started.** I did not move unrelated
    components to make room.

## Needs owner

- A guest who opens a shared place gets a 401, because `SecurityConfig` does not let guests read
  `/api/city/nodes/{id}`. This is the same open question as Round 7C. Recommended default: allow guests to read
  that one endpoint (the controller already treats a guest as memberId 0).
- "Backend tests" sits close to its 15-minute limit. Recommended: raise `timeout-minutes` to 20, or split the job.
- Main package source is about 3.21 MB, above the ~3.15 MB target. It was already 3.18 MB before this round.

STATUS: COMPLETE
