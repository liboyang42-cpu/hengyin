# W7 x-codes-links: receipt (hengyin)

Branch `feature/r-x-codes-links` from `origin/roadmap/be-integ` (`87aaf3dd2`). App work is on chengyin-app `cloud/r-x-codes-links` (its own receipt).

## Issues

| ID | State | Notes |
|---|---|---|
| ONB-5 | Fixed (code); owner WeChat step open | Backend `linkQr`, mini-program `options.q`, console common code. |
| ONB-6 | Fixed (code); owner nginx step open | Public landing page and anonymous URL Link endpoint. Until nginx serves the page, shared links still reach the console login. |
| ONB-12 | App-only, see app receipt | No backend change needed. |

## Changes, file by file

### ONB-5: one standard QR beside every mini-program code
- `chengyinhub-system/.../service/IPlayerWxacodeService.java`: new static `linkFor(scene)` returns `https://www.chengyinhub.com/door?scene=<urlencoded scene>`. It calls nothing and uses no WeChat quota.
- `linkQr` is added next to the existing `qrcodeUrl`. The change is additive and no field was removed.
  - `ApiMerchantController.shareCode` (`sh.<id>`).
  - `ApiChapterMerchantNodeController.posterCode` (the node check-in code).
  - `ApiOfficialEventController.shareCode` (`ac.<id>`).
  - `ApiTopicController` host code (`tp.<id>`) and `share-code`.
  - `PlayTeamServiceImpl.inviteQrcode` (`tm.` / `th.`). Here `linkQr` is returned even when the WeChat code degrades (`qrState=UNAVAILABLE`).
- Not covered: the player personal code (`/api/user/player-code`, `p1.` scene). Neither the mini-program nor the app opens a `p1.` scene, so a standard code for it would lead nowhere. This needs an owner decision on where a scanned player code should land.
- `chengyinhub-xcx/utils/game-door-entry.js`:
  - `sceneFromDoorLink(q)` accepts only the `https://www.chengyinhub.com/door?` prefix and a single `scene` of at most 32 characters.
  - `withDoorLinkScene(options)`: an existing `options.scene` wins.
  - `captureDoorScene` also accepts link-QR entries 1011, 1012 and 1013.
- `chengyinhub-xcx/pages/index/index.js`: `consumeDoorScene` normalises `options` through `withDoorLinkScene`, so a standard QR goes down the existing topic, event, shop, team and door paths.
- `chengyinhub-ui`:
  - Added `qrcode@1.5.4`, pinned exactly in `package.json` and `package-lock.json`. The lockfile also flips some transitive packages from dev to prod.
  - New `src/components/CyLinkQrCode` draws the standard QR labelled 「App 与微信通用码」 with a download link.
  - It is shown in the topic node dialog under the read-only check-in code (`src/views/business/topic/detail.vue`). That is the only place the console shows a code; it shows the code as text, not a wxacode.

### ONB-6: shared links no longer meant for the console login
- `chengyinhub-system/.../growthloops/ShareUrlLinkService.java` (new). It reuses the existing `MiniProgramUrlLinkService`, which has the release link, a 7-day cache and honest `available=false`.
  - It accepts only the content pages the app shares and AASA claims: activity, club, topic, template, post and place. For the place page, the mini-program reads `poiId`.
  - The id must be a canonical positive integer.
  - A path+id the service has not seen before counts against an hourly global budget (`share.urllink.hourly-budget`, default 600). Once the budget is spent it returns `BUSY` without calling WeChat. Ids already seen do not count.
- `chengyinhub-admin/.../api/ApiShareLinkController.java` (new): `GET /api/share/url-link?path=&id=`, `@Anonymous`, `@RateLimiter` 30/min per IP. A bad path or id returns 400 「链接不对」.
- `chengyinhub-ui/public/link/index.html` (new, same pattern as `public/door`):
  - Fixed copy: 「内容需要在 App 或小程序中查看」 / "Open this in the app or the mini program".
  - 「用城瘾 App 打开」 appears when `app-handoff` returns an App Store URL. Otherwise it shows the honest "coming soon" state.
  - 「在微信小程序中打开」 appears when the URL Link is available.
  - The page does not show content titles: that would need public content reads per type, which are out of scope.
- Commit note: the ONB-6 backend files (`ShareUrlLinkService`, `ApiShareLinkController`, `ShareUrlLinkServiceTest`) landed in commit `a41f4fa08`, whose message names ONB-5.

## Tests added
- `PlayerWxacodeLinkTest`: same scene, encoded once; empty scene gives no link.
- `ApiMerchantPublicHomeTest`, `ApiChapterMerchantNodePosterCodeControllerTest`, `PlayTeamInviteQrcodeTest`: `linkQr` carries the same scene as `qrcodeUrl`; for the team invite it survives a WeChat failure.
- `ShareUrlLinkServiceTest`: allowlist; canonical id; poi maps to `poiId`; WeChat refusal gives `available=false`; budget exhausted means no WeChat call; seen combinations are free.
- `chengyinhub-xcx/tests/unit/onb5-door-link-scene.test.js`, `chengyinhub-ui/tests/unit/onb5-link-qr-code.test.js`.

## Commands and results
- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test -Dtest=…`: all pass.
  - `ShareUrlLinkServiceTest` 5, `MiniProgramUrlLinkServiceTest` 4, `PlayerWxacodeLinkTest` 3, `PlayTeamInviteQrcodeTest` 7, `PlayerWxacodeServiceImplTest` 8.
  - `ApiChapterMerchantNodePosterCodeControllerTest` 4, `ApiMerchantPublicHomeTest` 15, `ApiUmsMemberPlayerCodeTest` 2, `PlayerMessagePunctuationTest` 2.
  - `GuestBrowseAnonymousReadTest` 19, `PublicReadEndpointsSecurityChainTest` 15, `PermitAllUrlPropertiesPathPatternTest` 4, `UpgradeAuthorizationBoundaryTest` 122.
  - `WellKnownControllerTest` 2, `TwoNodeSchedulingTest` 4, `MemberPrivacyErasureServiceTest` 4.
- `bash ci/migration-safety-check.sh`: 迁移安全门禁:通过. `bash ci/migration-layout-check.sh`: 迁移布局门禁:通过. No SQL, no sys_job, no new personal-data table.
- Mini-program: `node ../ci/prepare-mini-artifacts.js`, then `node --test "tests/unit/**/*.test.js"`.
  - Base: 17923 pass / 32 fail. Branch: 17927 pass / 32 fail. The 32 failures are pre-existing.
- `node tests/lint/ui-integrity.js --warn-as-error`: exit 1 on both base and branch, with the same findings. The only difference is the backend mapping count, 1626 → 1627 for the new route.
- Console: `node --test "tests/unit/**/*.test.js"`.
  - Base: 551 pass / 31 fail. Branch: 553 pass / 31 fail. The 31 failures are pre-existing.

## Owner actions
1. **WeChat console (ONB-5):**
   - Under 「扫普通链接二维码打开小程序」, add the rule prefix `https://www.chengyinhub.com/door` → `pages/index/index` (release and trial).
   - Host the verification file WeChat issues on `www.chengyinhub.com`.
   - Decide whether posters carry both codes or only the standard one.
2. **nginx (ONB-6):**
   - On `www`, serve `/link/index.html` for the shared content paths. Leave `/.well-known` and `/prod-api` alone. For example:
     `location ~ ^/(pages|subpackageRoam)/ { try_files /link/index.html =404; }`
   - Better: move the admin console to its own host (for example `admin.chengyinhub.com`).
   - Verify with `curl -sI "https://www.chengyinhub.com/pages/club/detail/index?id=1"`, which should return 200 text/html of the link page, not `/login`.
3. URL Link quota and the AppSecret come from the existing server configuration (`IWxContentSecurityService`). No secret is in code. Tune `share.urllink.hourly-budget` if needed.
4. Device checks:
   - Scan a printed `linkQr` in WeChat, in the iOS Camera and in the app's 「扫一扫」.
   - Open a shared club link in WeChat's in-app browser and in Safari without the app.

## Overlap with other branches
None with `fix/registration-list-r3`. The merchant dashboard was not touched; `ApiMerchantController` got a one-line additive field in `shareCode`.
