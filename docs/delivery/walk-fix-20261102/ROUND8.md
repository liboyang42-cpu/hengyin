# Round 8: pre-launch check

The owner's final check before go-live: "you need to review every part of it. After this final check, it will go live." and "verify everything, run through the entire process, and then send me screenshots."

Judge everything as the real user would. A passing API with a confusing screen is a finding. You find; you don't fix. The root triages findings and routes the fixes, because several lanes would otherwise edit the same files.

## Owner's standing rule for UI

"Cut taglines, helper text and secondary labels. If a screen needs them to make sense, fix the layout. Make the best decision that will elevate all three: the user experience (UX), developer experience (DX), and agent experience (AX) always, without breaking anything."

Every tagline, helper line and secondary label you see is a finding, with the layout change that would make it unnecessary. A second line carrying real data (a time, a count, a status, an amount) isn't a secondary label. Legal text and consent aren't either; mark those as such.

## Leads from earlier rounds to run down

Not yet explained; each owner lane says what's true:
- 13 `scene-*` components now load asynchronously from subpackages, reached through `componentPlaceholder` (see `integ/ROUND7-RESULT.md`). First open of settings, merchant home scenes, coop finance, deep links and earnings: anything blank, flashing or stuck (p1, p2).
- `pages/club/edit` showed 「当前账号无权进入该俱乐部」 for club lead 9003 with `?id=7001` while still rendering its form (j2, p3).
- `pages/privacy/index` as a launch path jumps to home with a page error, same before the moves (p1).
- `pages/topic/merchantinfo` rendered blank in the Round 7B walk when opened as a merchant (j2, p2).
- 确认终价: 「请填写大于 0 的成团人数」 shows as a pink block with an icon. The owner's rule is red text under the field (p2).
- Rig's anonymous `GET /api/city/nodes` returned 401 (j4).
- Club settlement builds 「¥ -2.00」 in the backend, while the app now shows 「−¥」 (j2).
- 「确认到点」 on the new platform places was not proven over HTTP (j4).

## Rules for every lane

- Test only the exact commit named in your prompt, on `integ/launch-test-20261103`. Create your own branch `claude/check-1103-<lane>` from it, and commit only under `docs/delivery/check-20261103/<lane>/`. Never edit product code, never touch `integ/launch-test-20261103`, never push to master, never merge a PR, never touch production. Don't run `ci/xcx-check.sh` or `ds-hardcode-gate-selftest.sh`.
- Rig: `claude/cloud-walk-20261101-rig` plus `tools/harness-patches.diff` from the club lane, with `LANG=C.UTF-8 LC_ALL=C.UTF-8` for Maven and `mirror.gcr.io` as the Docker mirror. See `docs/delivery/walk-fix-20261102/integ/RESULT.md` for how Round 6 ran it. Real jar, real MySQL and Redis, real mini-program code in the browser runtime. Say plainly that it is not a phone. Rig gaps (WeChat responses, payment seam, missing images) are labelled `HARNESS`, not product bugs.
- Go through the real screens and tap what a user taps. Use the API only to read back that the data is right. Use fictional data only.
- Screenshot every step at 390x844, 2x, JPEG under 200 KB: `shots/<nn>-<step>.jpg`. Look at each shot yourself, including layout, clipped text, wrong colours, raw codes, English, fake zeros, stray captions and dead buttons. Player side is dark, merchant side is light.
- A step that can't run in the rig is listed as NOT VERIFIED with the reason. Never mark something passed because the code looks right.
- Don't report as a bug anything the owner decided. See `docs/delivery/walk-fix-20261102/BRIEF.md`, `ROUND3C.md`, `ROUND4.md`, `ROUND5C.md`, `ROUND5D.md` and each lane's `RESULT.md`. Fake seed content, report buttons, Memoji and bundled images are unchanged by owner decision. Editor-page input pop-ups come after launch. The guest 发现 feed opens after launch.
- Push after each journey section, so a usage limit loses nothing.

## Report

`docs/delivery/check-20261103/<lane>/REPORT.md`:
1. A table of every step: PASS, FAIL or NOT VERIFIED, with shot and read-back.
2. Findings, each with severity (`BLOCKER` money wrong, data leak, dead end, crash or explicit content; `HIGH` a user can't finish or is misled; `LOW` polish), exact repro, file:line if known, and shot.
3. What you did not cover.

Plain words. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.

## Lanes: journeys cross roles, so one lane plays every role in its journey

**j1-merchant-sale.** One paid sale from start to finish. Merchant applies and is approved → sets up the shop and a paid activity with tickets → player finds, quotes and pays → ticket shows in 票夹 → merchant scans it at the counter → numbers on the merchant home, ledger and earnings agree → settlement and payout. Then the refund branches at each tier, with amounts on screen and in the database matching, and merchant numbers updating. Also clerk permissions.

**j2-club-session.** One club session from start to finish. Player applies as 主理人 → creates the club → joins, roles → creates a theme from a template → 招商: invites a merchant, merchant takes a station → publishes and opens a session with paid tickets → players join and form teams → leader runs the session from the director page, gather, broadcast to chosen members → players play and finish → settlement pays the current 主理人 at the agreed rates. Then transfer of ownership, and self-play on a club theme at 3%.

**j3-player-play.** The player's game. Solo and free-exploration themes, 城市定向 with stations, story chapters and every game type on the live 990068 and 990069 style configs, hints, the wait-for-release screen and 返回地图, endings, medals, points and 积分明细, order detail, invoices. Every font and text effect in 专业主题 画面风格, as the author sets them and as the player sees them.

**j4-roam-team.** 漫游: map, markers, bubble text at normal and largest phone font, place cards, the city list, location refused, guest. Team formation with two or more players at once: create, join, leave, kick, full team, wrong code, ready, start.

**j5-posts-moderation.** Posts and comments: writing, images, likes, follow, feed, my posts, messages inbox and outbox, 城瘾小助手. Automatic moderation through the real screens on every text and image entry a user has, including club and merchant profiles and theme content: library word caught, split-up word caught, allow-listed phrase passes, known-bad image and a near copy refused, WeChat busy holds then releases, published content rescan. List every user-content entry you find and say which protection it reaches. An entry that reaches none is a BLOCKER.

**j6-break-it.** Things going wrong, across journeys. Closing the app mid-payment and mid-game, weak network and timeouts, double taps on pay, submit and refund, back button mid-flow, session expiry, resuming a game in progress, stale pages. Two accounts on one device: log out, log in, and confirm nothing from the first remains. One user trying to read or change another's order, ticket, team, club or merchant data by changing ids, on backend and client. Every page as a guest: nothing raw, nothing blank, and login returns to the right place.

**p1-pages-player, p2-pages-merchant, p3-pages-club-publish.** Every page, one lane per group. List all 157 pages registered in `chengyinhub-xcx/app.json`, assign each to exactly one group, and record each in a table with how you reached it, states seen (loading, empty, error, full), and shot. Tap every control. Compare to the page's neighbours. Pages moved out of the main package must open from every entry that links to them. Together the three tables must total 157. p1 owns the merged list file `docs/delivery/check-20261103/pages-list.md`, pushed on `claude/check-1103-p1-pages-player` first; p2 and p3 read that file and take their groups. Player pages: `p1`. Merchant pages: `p2`. Club, publishing and editor pages: `p3`. If unsure, the page goes to `p1`.

**r1-release.** Rehearse the release. In a throwaway MySQL with production's structure as captured by the repo's schema tools: apply the 7 migrations in the order in `integ/RESULT.md` plus `migration_20261101_author_write_receipt.sql`, prove each assertion, and prove the old jar `014fc0d83` still boots against the migrated schema in case of rollback. Then run the last mini-program bundle from master `c6eac3b8c` against the new jar: login, home, a sign-up, a game resume and an order detail, since users stay on the old version for a while. Report main package size by the method in `ROUND7.md` and both preflight BLOCKs. Confirm the two scheduled tasks register paused and `content.sensitive.rescan.remove` defaults to false.
