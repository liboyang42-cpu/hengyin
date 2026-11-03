# Round 7C: WeChat sharing and traffic fixes on the test branch

Owner's goal: "maximizing the integration of maps and other features with the WeChat ecosystem to ensure the smoothest possible experience for both players and merchants, while simultaneously driving traffic to the platform."

Work on `integ/launch-test-20261103`. Never push to master. Never merge a PR. Never change production. No secrets. Don't run `ci/xcx-check.sh` or `ds-hardcode-gate-selftest.sh`. Follow root `AGENTS.md` and `chengyinhub-xcx/AGENTS.md`, including "For UI agents". Commit each item separately, push after each one, and pull first because others may push.

No visible new buttons, captions or layout in this round. Those wait for the owner's design review. WeChat's menu share is not a visible change. The main package must stay under about 3,150,000 source bytes; report the final number.

Never add anything that rewards, requires or nudges sharing: no share-to-unlock, no rewards, no red dots, no animated share buttons (WeChat rule 2.5.1).

## Items

Paths are under `chengyinhub-xcx`. Evidence is from a read-only audit, so verify each one before changing it. If an item is already fine, say so and skip it.

1. **朋友圈 share.** Add `onShareTimeline` to theme detail, official event, club detail, official route and shop profile. Moments opens a no-login single-page mode: the page must render useful public content there without login or `wx.login`-dependent calls, and its action buttons must not dead-end. Check how WeChat signals this mode (scene 1154) and handle it. Remove `onShareTimeline` from the live game (`pages/play/index.js:8664`), since that page can't work in that mode. Check `pages/growthcenter/index/index.js:535` and `subpackageRoam/poi-detail/index.js:32` work in it too.
2. **Game share.** `pages/play/index.js:8659` has no cover, so WeChat screenshots the screen, which can expose position and puzzles (`utils/roam-menu-share.js:4` already blocks this for roam). Self-play sends an empty id. Point it at theme detail with a fixed cover. `subpackageMember/mycanyu/mycanyu.js:236` shares a private page; point it somewhere public or remove it.
3. **Share cards.** Real name and cover on club (`pages/club/detail/index.js:3439`), gameplay (`pages/templatedetail/templatedetail.js:522`), station (`subpackageRoam/poi-detail/index.js:27`), team (`pages/team/detail/index.js:436`), shop (`pages/merchant/profile/index.js:133`), home (`pages/index/index.js:937`). A team link must not expose private team data. The receiver lands on the right page as a guest and login returns there.
4. **Missing share.** Public pages with no handler: official routes, activity list, shop inside a theme, badge wall. Only where the content is public.
5. **Reminder links.** Subscribe messages open home today (`WxSubscribeMsgServiceImpl.java:198-296`, `application.yml` `page:`). Open the right order or ledger page, with an id-safe route, ownership checks unchanged. Test, and prove with the rig that the page path is valid.
6. **Consent list.** `utils/subscribe.js:18` asks consent for "refund rejected", which the backend never sends. Remove it from the request.
7. **Search index.** `sitemap.json` allows everything. Disallow merchant, club-admin, withdrawal and invoice pages and other private pages. Keep themes, activities, clubs, shops and official routes allowed. List both groups.
8. **Unused location permission.** `choosePoi` is declared in `app.json` `requiredPrivateInfos` but never called. Confirm and remove it, including any test pin.

## Don't touch

Inviter points, merchant referral, paid merchant extras, and the 随机盲盒 and 竞猜 wording wait for the owner. Don't touch the map, pins or fog. A separate design round owns them.

## Verify

Full mini unit tests, `ui-integrity.js`, `build-action-ledger.js --check`, backend tests for item 5, all CI. In the rig: for each new share, call the handler and open the returned path as a guest, screenshot the landing page, and look at it. Also open each Moments page in the single-page mode as far as the rig can simulate; say plainly what only a phone can prove.

## Result

Write `docs/delivery/walk-fix-20261102/integ/ROUND7C-RESULT.md`, with shots in `integ/share-shots/` (JPEG under 200 KB). Per item: status, files, proof, unverified. Plain words. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
