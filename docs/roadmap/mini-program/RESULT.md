# T6 mini-program: receipt (hengyin `feature/r-mini-program`)

Base: `origin/roadmap/be-integ` (branch started at `f0dc762c1`; the newer be-integ with the game loop and UX pages was merged in at `8c21c53a2`). Mini-program only (`chengyinhub-xcx`): **no Java, SQL or admin UI change, and no chengyin-app change**. This is a resumed run: the first five commits come from the earlier run, which stopped on a usage limit. Its work was kept as pushed and continued here, with no rebase and no force-push.

Every change keeps today's behaviour for existing users unless the server already sends the new fields or switch. No production setting was touched.

## Findings

| ID | State | What the mini-program does now |
|---|---|---|
| SEC-14 (mini-program side) | closed | After a **fresh** sign-in it reads `POST /api/compliance/age/status`. If the account has not confirmed, it asks with the app's copy (确认年龄 / 我已年满 18 周岁 / 我未满 18 周岁) and posts `POST /api/compliance/age/confirm` (`source=sign_in`). Accounts that already confirmed are not asked again, and a cached session restored on cold start is not asked. Answering "under 18", or closing the dialog, shows the plain under-18 message and signs out. The login sheet waits for the answer before it resumes the action the user started. |
| S3a merchant plans | closed | Workbench → 更多 → 套餐 opens a new page `pages/merchant/plans`. It shows the shop's current plan and what each plan includes (copy as in the app). The 开通 button appears only when `POST /api/merchant/plan` reports `salesState === 'OPEN'` (money switch `merchantMoney` on) **and** the viewer is the owner. It calls the existing `POST /api/merchant/plan/checkout`, which only records a pending order. Otherwise the page says 即将开放. |
| P7 daily board | closed | 成长中心 shows 今日任务 from `/api/growth/center` → `daily`, and the streak, freezes and next badge from `/api/play/growth`, with the app's copy. When an older server sends no `daily` key, the section does not appear. When `daily` is `null`, it says 暂时还用不了 instead of showing 0. |
| CLB-13 roster sorts | closed | 俱乐部贡献榜 first loads the board with today's sort. It also reads `/api/club/leaderboard/sorts` and offers only the sorts the server allows. It re-reads with the default sort only if the current sort is unavailable, and it uses only the latest response. If the sorts read fails, the three sorts from today stay. |
| MER-08 #1 four statuses / two start dates | closed | The calendar day is now taken in Asia/Shanghai, which fixes the start date that was off by one. The workbench card, 「我的」 preview and project page all say 待审核 for the same state. |
| MER-08 #2 expired opportunities | closed | The coop-center list and the advisor pool already dropped expired items (`recruit-routes.visibleRecruitItems`; server `MerchantInsightServiceImpl.recruitingTopicPool`). The project page still showed 申请承接 after the deadline and only said 已结束 on tap. Its main button now reads **报名已结束 / 报名未开始** and is disabled. When the merchant already has applications, the button still reads 承接进度. |
| MER-08 §3.8 phone in full | closed | The 入驻审核 status page now shows `138****1234`. The form and the just-submitted receipt keep the merchant's own full number (an existing contract requires it on the receipt). |
| MER-08 #3 test data, #4 copyright images | open: data | These are production rows, not code. See owner action 1. |
| MER-08 #9 advisor recommends other cities | open: backend | Expiry is already filtered on the server. The city filter belongs in `MerchantInsightServiceImpl.recruitingTopicPool`, which is server code, not mini-program code, so this package did not change it. |
| MER-08 other items (#5–#8, #10, §3.2–§3.7) | re-verified | Already fixed by the mini-program team since 2026-09-27: 消息 goes to the message centre, the duplicate profit empty state is gone, and the coop list filter uses `topicName` when the page that opens it passes one (the bare `#id` is only a fallback). |

## City orienteering themes and story (owner request)

I compared the mini-program with chengyin-app `cloud/r-city-orienteering` `docs/roadmap/city-orienteering/RESULT.md`:
- **The four looks** STARDUST / PAPER / SURVIVAL / CYBER and their colours come *from* the mini-program (`pages/play/utils/theme-look.js`, `look-tokens.wxss`, pinned to `ThemeLook.java` by `theme-look.test.js`). The mini-program also has per-look transitions, particles and story fonts that the app does not have. **No gap.**
- **Story flow.** The mini-program already has the theme intro, chapter gates with 「第 n 章 / 共 N 章」, chapter-end flips, story resume per account and run (`story-resume.js`), the story HUD, backpack, casebook, 本局线索, ending fragments and settlement review. It matches or exceeds the app here.
- **Gap closed:** the app explains before the ending choice that the choice is final and doesn't preview the outcomes. The branch component (`playkit-branch`) now shows 「选定后不能更改，故事会按你的选择走下去」 before the options. It is hidden at an ending, when read-only, or once a choice is made. The jump table is still not sent to the client. No other branch touches this component, so it adds no collision.
- **Gap left open:** in-mini-program walking directions. The app uses Apple MapKit; the mini-program would need a Tencent Maps WebService key behind a server proxy (owner action 3). Today it hands off to `wx.openLocation`.
- I did not edit `pages/play/index.*`. The play team's unmerged branches (`fix/play-round3-1113g`, `feature/player-acceptance-fixes-1115`) are reworking it.

## Files

- `app.js`, `utils/auth/age-gate.js` (new), `utils/auth/require-login.js`: age gate.
- `pages/merchant/plans/{index.js,json,wxml,wxss,view-model.js}` (new), `app.json`, `pages/merchant/index/index.{js,wxml}`: plans page and the 更多 → 套餐 entry.
- `subpackageP3/utils/daily-board.js` (new), `subpackageP3/pages/growthcenter/index/index.{js,wxml,wxss}`: daily board.
- `pages/club/detail/index.js`: roster sorts.
- `utils/merchant-workbench.js`, `subpackageMember/components/cy/profile/index.js`, `pages/topic/merchantinfo/merchantinfo.{js,wxml}`, `utils/merchant-signup-window.js` (new), `pages/merchant/apply/index.{js,wxml}`: MER-08.
- `pages/play/components/playkit-branch/index.{wxml,wxss}`: the ending-choice note.
- `scripts/uiaudit/build-action-ledger.js`: the age routes are classed as sign-in routes, and 套餐 is in the merchant 更多 menu contract.
- `tests/lint/ui-integrity-baseline.js`: five existing anchors moved because lines shifted (app.js 435→455, merchant/apply 360→367, merchantinfo 667→671 / 2451→2455 / 2483→2487). The call lines and reasons are unchanged and the count is the same. The `u1Variable` / `u4Dynamic` keyDigests were recomputed with the gate's own algorithm.
- Tests: `tests/unit/r-mini-program-{age-gate,merchant-plans,daily-board,club-sorts,mer08,story-branch}.test.js` (new, 36 tests). The adjusted existing tests are `guest-browse-login-on-write`, `nearby-layer-read-access`, `merchant-appointment-semantic-contract`, `merchant-topic-fulfillment` and `merchant-topic-cover-states` (the last only adds the new pure-helper require to its sandbox).

## Verification

```
cd chengyinhub-xcx
for f in tests/unit/r-mini-program-*.test.js; do timeout 60 node --test $f; done
#   age-gate 11, club-sorts 6, daily-board 5, mer08 4, merchant-plans 8, story-branch 2: all pass

# Every unit test that references a changed file or app.js (365 files), one by one with a 90 s timeout,
# on origin/roadmap/be-integ (worktree) and on this branch:
#   be-integ: 351 pass, 6 fail, 2 timeout (+6 new files absent)
#   branch:   357 pass, 6 fail, 2 timeout
#   The failing and timed-out files are the same on both sides (get-auth-matrix, nav-page-title-spacing,
#   nav-title-unconditional, profile-devfix-l08, sc-ui-date-count-probe, title-system-owner; timeouts:
#   action-ledger, action-ledger-handler-sources). No new failure.

node tests/lint/ui-integrity.js --warn-as-error
#   fails on be-integ and on this branch with the same violations (stale roam U5 anchors etc.);
#   the diff of the two outputs is only the scanned counts (+1 static path, +1 binding, +1 statically handled)
node --expose-internals scripts/js-scope-lint.js     # JS-SCOPE PASS: 985 files
node scripts/uiaudit/build-action-ledger.js --check  # fails on be-integ already (shop-games not in route-matrix); see below
```

Not verified: nothing ran in WeChat DevTools or on a phone. That covers the age dialog on a real sign-in, the plans page layout, the daily board with a live server, and the branch note in each look.

## Integration notes (for the coordinator)

- **Route matrix and action ledger are stale on be-integ** (`pages/merchant/shop-games`, `pages/merchant/plans` and three other new pages are missing). `route-matrix-freshness.test.js` also hard-codes 173 routes. I left both files alone because regenerating them adds a 30k-line diff that collides with the play team's own regeneration. After integrating, run `node scripts/uiaudit/build-route-matrix.js --json && npm run audit:actions:update`, then update the route count.
- Merging with `feature/r-integ-master` (master #830–#852): the only mini-program conflicts are two test files. In `merchant-appointment-semantic-contract.test.js`, take `['messages','aftercare','verifyRecords','bundleReservations','payoutAccount','plans','coupon','brand']`. In `nearby-layer-read-access.test.js`, keep both tests. `feature/player-acceptance-fixes-1115` additionally conflicts in `scripts/uiaudit/action-ledger.json` and `route-matrix.json` (regenerate those).

## Owner actions and questions

1. Production data: remove the test rows the walkthrough found (opportunity titles 好多好多不是吧 / 这和 / 新时尚 / 横说竖说 / 还是少男少女, the invite note 还挺好听, the customer note Computer Use验收备注, the tag 走查), and replace the Mickey and Picasso images. This is console work, not code.
2. Advisor city filter (MER-08 #9): should recommendations be limited to the merchant's city? This is a backend change in `MerchantInsightServiceImpl`.
3. Walking directions inside the mini-program need a Tencent Maps WebService key (server-side) and a proxy route. Do you want them?
4. Age gate: if saving the "18+" answer fails three times in a row, the user is let in and asked again at the next sign-in, so a server outage cannot lock everyone out. Keep this, or block instead?
5. The plans checkout appears only while the server reports sales open. The money switch stays off in production, so production shows 即将开放.

No hard-coded secrets were found in the files touched.
