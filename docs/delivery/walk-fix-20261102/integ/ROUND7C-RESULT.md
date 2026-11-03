# Round 7C result: WeChat sharing and traffic fixes on `integ/launch-test-20261103`

Started from `7b4326c1`. Each item has its own commit, pushed on its own. Nothing went to master, no PR was merged, production was not touched, and no secrets were added; I grepped each diff before pushing. `ci/xcx-check.sh` and `ds-hardcode-gate-selftest.sh` were not run. Nothing visible was added: no new buttons, captions or layout. The only new user-facing text is a toast that replaces a dead tap in Moments mode (item 1).

## Main package

I used the `scripts/preflight-release.js` method on a clean worktree.

| Tree | Source bytes |
|---|---|
| Start (`7b4326c1`) | 3,093,281 |
| **End (this round's code)** | **3,100,731** |

That is +7,450 bytes, under the ~3,150,000 limit. The growth is the new `utils/single-page-mode.js`, `utils/play-menu-share.js` and comments.

## Items

| # | Status | Commit | Files |
|---|---|---|---|
| 1 Moments share | done, with 2 pages left for the owner (below) | `85c18f1f`, `7b3bd74d` | `utils/single-page-mode.js` (new), `app.js`, `utils/auth/require-login.js`, `pages/topic/index/index.js`, `pages/activity/official-detail/index.js`, `pages/club/detail/index.js`, `pages/userinfo/userinfo.js/.wxml`, `subpackageMember/components/cy/profile/index.js`, `pages/play/index.js`, `subpackageP3/pages/growthcenter/index/index.js`, `subpackageRoam/poi-detail/index.js` |
| 2 Game share | done | `a49d5a91` | `utils/play-menu-share.js` (new), `pages/play/index.js`, `subpackageMember/mycanyu/mycanyu.js` |
| 3 Share cards | done (team and official event not walked: no fixture) | `f613287f` | club detail, `templatedetail`, `poi-detail`, team detail, `merchant/profile` (old link), `cy/profile`, `pages/index/index.js` |
| 4 Missing share | done for the activity list; the other three are deliberately not added | `0471190a` | `pages/activity/list/index.js` |
| 5 Reminder links | done for order and settlement reminders | `108af3ff` | `WxSubscribeMsgServiceImpl.java`, 2 Java tests |
| 6 Consent list | done | `a70ffa71` | 3 refund-confirm components, `utils/subscribe.js` comment, 5 tests |
| 7 Search index | done | `2fc3a979` | `sitemap.json` |
| 8 Location permission | done | `504efd4b` | `app.json`, `tests/unit/app-config.test.js` |

### 1. Moments (朋友圈) share

**What WeChat does.** A Moments card opens the page in "single-page mode", scene 1154:
- there is no login (`wx.login` fails);
- the page can't navigate to other pages or pay;
- WeChat itself puts a 「前往小程序」 bar at the bottom.

The card path is always the page it was shared from; only the query can be set.

**Fix, in one place.** `utils/single-page-mode.js` detects scene 1154 through `getEnterOptionsSync`, falling back to `getLaunchOptionsSync`. When the app launches in that mode:
- **Gated actions.** The login gate (`require-login.js`) no longer opens 「登录后继续」, whose 「微信登录」 would always fail there. It shows 「请前往小程序使用完整服务」 instead. This covers every 报名 / 购票 / 入会 / 收藏 / 发帖 tap.
- **Navigation, login and payment calls.** `navigateTo`, `redirectTo`, `reLaunch`, `switchTab`, `navigateBack`, `login`, `requestPayment` and two related APIs are wrapped. They show the same toast once per 1.5 s, then call `fail`/`complete`.
- **Normal mode.** In a normal launch nothing is installed. The scene is checked on every call, so tapping 「前往小程序」 into the full app is never blocked.
- **Privacy page.** In this mode the app no longer tries to open the privacy page, which can't be opened there.

**Added `onShareTimeline`** (title, card image and query). Each page's read API already allows guests:
- **Theme detail:** `id`, theme cover. Reads `/api/topic/info-to-user`.
- **Official event:** `id`, event cover. Reads `GET /api/official/events/*`.
- **Club detail:** `id` only, without post or ticket parameters. Club cover, else the logo. Reads `/api/club/detail`.
- **Merchant home** (`pages/userinfo/userinfo`): `userId` + `tab=about`, shop cover. The Moments menu starts hidden on that page; the profile component turns it on only after `/api/merchant/public-home` confirms a public merchant. Ordinary personal profiles never go to Moments. The old `merchant/profile` link page still has no Moments share, as its existing contract requires.

**Removed `onShareTimeline`** from pages that can't work in this mode:
- **Live game** (`pages/play/index.js`): a session page with no login, so a Moments card could only open an empty session.
- **Growth centre:** it shows only the logged-in viewer's own achievements, so a stranger sees 「登录后查看」 and nothing of the sharer's.
- **Station** (`poi-detail`): `GET /api/city/nodes/{id}` needs login. The controller handles a guest (`memberId=0`), but `SecurityConfig` does not allow guests on that path. In the rig a guest gets an empty sheet (shot `s08`).

**Official route:** not added. `/api/preset/route/list` and `/route/info` also need login.

**Proof**
- `tests/unit/round7c-timeline.test.js` (10 tests) covers:
  - scene detection;
  - the guard: wraps calls, throttles the toast, passes calls through in normal mode, installs once, and works on a proxy `wx`;
  - the login gate in this mode: no modal and no `firstLogin`;
  - each page's Moments payload;
  - the three pages with Moments share removed.
- Rig, launched with `scene: 1154` as a guest (`t01`–`t05`):
  - Theme detail, club and merchant home render their public content with real API data.
  - Calling the theme page's own checkout and favourite handlers shows the toast, with no modal and no page error (`t02`).
  - Tapping 「加入」 on the club shows the toast (`t04`). `wx.navigateTo` shows the toast and the route does not change.
  - A normal launch leaves `wx.navigateTo` unwrapped.
- The rig found a real bug in my first version. The "already installed" marker was a property on `wx`, and the rig's `wx` proxy returns a function for unknown keys, so the guard never installed. Fixed in `7b3bd74d` with a module-level `WeakSet`, plus a test with a proxy `wx`.

**Unverified (phone only)**
- That real WeChat reports scene 1154 and lets `wx.*` methods be replaced. If it doesn't, the login-gate toast still works, but a navigation tap would fail silently as before.
- Whether WeChat shows its own notice as well as ours.
- How cards look in Moments, and that 「前往小程序」 opens the full app.

### 2. Game share

- **Game page share.** `pages/play/index.js` used to share `/pages/play/index?activityId=` with no image. Self-play sent an empty id, and WeChat screenshotted the live game (position and puzzles). The menu share now goes through `utils/play-menu-share.js`:
  - the path is public theme detail, `/pages/topic/index/index?id=<topicId>`;
  - the title is the theme name;
  - the image is the theme cover, else the fixed location-free picture from `roam-menu-share.js`;
  - a mock or invalid theme id goes to home with the fixed picture.

  The in-page activity share button is unchanged.
- **我的参与.** `subpackageMember/mycanyu` is a private list, so its share handler is removed.
- **Proof.** `tests/unit/round7c-game-share.test.js` (5 tests). In the rig, as 9004 on `play?topicId=990013`, the handler returned `/pages/topic/index/index?id=990013` with the fixed picture, and a guest landed on theme detail (`s06`). The title was the fallback because the rig session had no theme name loaded.

### 3. Share cards

| Page | Before | After |
|---|---|---|
| Club (menu and post) | Club name, screenshot | Club cover, else logo |
| Gameplay (`templatedetail`) | Title, screenshot, `&scope=my` for the author | Detail cover; the link never carries `scope=my`, because the receiver isn't the author |
| Station | Generic title, screenshot | Shop name and shop cover, read from the already-loaded scene component (the page still fetches nothing, per its contract). Fixed picture if not loaded; another station's data is never used. |
| Team | Team title, screenshot of member list and avatars | Fixed picture. The link still carries only the invite code. `/api/team/info` with a code returns the team row and member nicknames and avatars, which is the existing invite contract and wasn't changed. |
| Old merchant link (`merchant/profile`) | 「城瘾 · 商家主页」 | Real shop name and cover from the `public-home` reply it already reads |
| Merchant home (`cy/profile`) | 「<nickname> 的城市主页」, path without `tab=about` | Shop name, shop cover, canonical `merchantHomeUrl` |
| Home (menu) | 「城瘾Hub」, screenshot of a location-sorted feed | Same title, fixed picture. In-card buttons unchanged. |

- **Unit tests.** `tests/unit/round7c-share-cards.test.js` covers each row. `team-p1-contract.test.js` was updated for the new image.
- **Rig.** Each share was called as the sender, then its path opened as a guest. Theme detail, club and merchant home all landed on the right page with real data (`s01`–`s03`, `s05`).
- **Login returns there.** The gate is an in-place modal, so the guest stays on the page; club join in normal mode works this way. I did not log in from those guest pages in the rig.
- **Not walked.** The seed has no team, gameplay template or official event, so those three were checked by unit test only. A guest opening a bad team code lands on the join page (`s09`).

### 4. Missing share

- **Activity list:** added. Title 「城瘾 · 官方活动」, the default public tab (`/api/official/events`, which allows guests), and the fixed picture, so a viewer on 「我的活动」 never puts that tab in the card. Rig: the guest landed on the list (`s04`).
- **Not added**, because the content is not public:
  - **Official routes:** the API needs login (see item 1).
  - **Badge wall:** the viewer's own badges.
  - **Shop inside a theme** (`pages/play/merchant`, the in-game shop page): it carries the player's check-in and redemption state.

  A test pins that these pages have no share.

### 5. Reminder links

`WxSubscribeMsgServiceImpl` now sends:
- refund, signup success and before-start reminders → `subpackageMember/order/order` (我的订单);
- settlement → `pages/merchant/ledger/index` (核销与退款).

Coop, recruit, club-interest and official broadcast reminders keep the configured default (home).

- **Id-safe.** No id goes into the link. Both pages list only the logged-in account's own rows through their existing APIs. Ownership checks are untouched, and there is no id for a forwarded message to change.
- **Before-start reminders** are prepared and frozen. New ones freeze the order page, and already-frozen messages still go out with whatever page they froze.

**Proof**
- **New test.** `WxSubscribeMsgLandingPageTest` (5 tests) checks each template's `page` in the real rendered body. It also checks that both paths carry no `?` and appear in `chengyinhub-xcx/app.json` (main pages plus subpackages).
- **Updated test.** `BeforeStartPreparedWxTest` now expects the order page.
- **Maven run.** `mvn -pl chengyinhub-admin -am -Dtest=WxSubscribeMsgLandingPageTest,BeforeStartPreparedWxTest,BeforeStartDate5FormatContractTest,WxSubscribeMsgTemplateFallbackTest,WxSubscribeMsgSendErrcodeTest,RestoreDispatchConsumersTest test`: 56/56 pass.
- **Rig.** Opened both paths as the right person: player 9004 sees 我的订单 (`r01`) and merchant 9002 sees 核销与退款 (`r02`).

**Unverified.** Tapping a real subscribe message on a phone.

### 6. Consent list

- **Confirmed.** The backend has no `tplRefundRejected` and no code path sends a "refund rejected" message, so the user granted consent for nothing.
- **Change.** The three refund-confirm taps now call `subscribe.request(['refund'])`. The template constant stays, with a comment, because other tests use it as a configured-key fixture.
- **Tests.**
  - `l21-b9-refund-rejected-subscribe.test.js` now asserts `['refund']` only.
  - A repo scan finds no `request(... refundRejected` in shipped source.
  - It fails if the backend ever gains a "refund rejected" template.
  - Four related tests were updated.

### 7. Search index

`sitemap.json` lists allowed pages, then ends with `disallow *`.

**Allowed:**
- home;
- theme detail;
- gameplay detail, preview and topic detail;
- activity list, activity detail and official event;
- club home;
- merchant home (`pages/userinfo/userinfo` only with `tab=about`, so ordinary personal profiles stay out);
- official route list and route;
- station;
- privacy and agreement.

**Not allowed (everything else):**
- all `pages/merchant/*` back-office pages (the old `merchant/profile` link page redirects, so it isn't listed);
- club admin (`pages/club/*` except `detail`);
- coop, withdrawal (`coop/withdraw`, `tixian*`) and invoices;
- orders and the whole `subpackageMember`;
- asset centre, IM, publishing, settings, `member`;
- the live game and roam session pages;
- growth and badges;
- search.

**Test.** `round7c-sitemap.test.js`:
- every allowed page is a registered route;
- the last rule is `disallow *`;
- a personal profile without `tab=about` is not allowed;
- 60+ private routes all resolve to `disallow`.

### 8. Unused location permission

- **Confirmed.** Nothing in shipped JS or WXML calls `choosePoi`; `location-manager` switched to `chooseLocation` on 2026-08-20.
- **Change.** `choosePoi` is removed from `requiredPrivateInfos`. There was no product-level test pin on it; the `wechat-release-contract` tests use synthetic fixtures.
- **New test** in `app-config.test.js`: the declared location APIs must equal the APIs the shipped source calls. Today that is `chooseLocation`, `getLocation`, `onLocationChange`, `startLocationUpdate` and `startLocationUpdateBackground`.

## Needs owner

1. **Allow guests to read three public endpoints:** `GET /api/preset/route/list`, `GET /api/preset/route/info` and `GET /api/city/nodes/{id}`. The controllers already handle `memberId=0`, but changing `SecurityConfig` is an auth change that AGENTS.md says to ask about first.
   - Today a guest, and any Moments viewer, sees an empty station sheet (`s08`) and an error on official routes.
   - **Recommended:** allow exactly these three GET paths (not wildcards), then restore station Moments share (one line, noted in `poi-detail/index.js`) and add Moments plus friend share to the official route page.
2. **Growth centre friend share** still says 「来城瘾看看我的城市探索成就」, but the receiver sees their own page. **Recommended:** remove it or point it at home in a later round. It was out of scope here.
3. **Coop, recruit and broadcast reminders** still open home. **Recommended:** coop → `pages/coop/list/index` after checking that page for each role.

## Verification

| Check | Result |
|---|---|
| Full mini unit tests, `node --test "tests/unit/**/*.test.js"` at `7b3bd74d` | **15892 / 15892 pass**. An earlier run at `108af3ff` caught the hard-coded ledger count in `action-ledger.test.js`, fixed in `7b3bd74d`. |
| `node tests/lint/ui-integrity.js` | `UI-GATE-0:PASS` |
| `node scripts/uiaudit/build-action-ledger.js --check` | `ACTION-LEDGER PASS: 5636 controls`. +1 is the new `bind:subjectmerchant` on `cy-profile`; the rest is line moves. `action-ledger.test.js` count updated 5635 → 5636. |
| Backend tests (item 5) | 56/56 pass (Maven, list above) |
| ui-integrity baseline | Only line moves of existing entries. `app.js` 428→435, `play` 1795→1796 and 8276→8277, `cy/profile` 1293→1295 and 2140→2142, with the three key digests recomputed and a header note. No entries were added. Item 2's commit (`a49d5a91`) shifted the two `play` lines and left the gate red until item 1's commit (`85c18f1f`) re-anchored them. |

The rig is the walkthrough rig from `claude/cloud-walk-20261101-rig` with the club lane's harness patch, pinned to this branch, with real MySQL and Redis. Docker Hub rate-limited the pinned images, so I pulled the same digests from `mirror.gcr.io`.
- **Rig-only data:** the theme, club and merchant fixtures got covers that point at bundled images (`/images/home-banner-*.jpg`), so the cards have an image.
- **Shots:** `share-shots/`, 390×844, JPEG q70, all under 31 KB. They come from the rig, not a phone.

## CI

PENDING
