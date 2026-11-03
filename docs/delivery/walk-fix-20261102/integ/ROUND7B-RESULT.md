# Round 7B result: approved page polish on `integ/launch-test-20261103`

I started after `integ/ROUND7-RESULT.md` showed `STATUS: COMPLETE` (Round 7 head `f66f1b8c`). Each item has its own commit and was pushed on its own. Draft PR #723 runs CI.

- Nothing was pushed to master, no PR was merged, and production was not touched.
- `ci/xcx-check.sh` and `ds-hardcode-gate-selftest.sh` were not run.
- No secrets were added. I grepped every diff before pushing.
- One other session pushed to this branch during the round: `34ac7763`, a Java test time fix. I rebased onto it and did not touch it.

## Main package

The method is the one in `scripts/preflight-release.js`: every file outside `packOptions.ignore` and outside subpackage roots, measured on a clean worktree.

| Tree | Source bytes |
|---|---|
| Round 7 end (`f66f1b8c`), measured the same way | 3,090,886 |
| **Round 7B end** | **3,093,281** |

That is under the ~3,150,000 target. The growth (+2,395 bytes) comes mostly from the new `utils/money-format.js` and comments.

## Items

| # | Item | Status | Commit | Files | Proof |
|---|---|---|---|---|---|
| 1 | Merge 积分明细 (`dca92673`) | done | `8f4d602a` (real merge) | points-history page | No conflicts. The action ledger auto-merged, and `--check` passes. |
| 1a | Same month never twice | fixed | `724e3dd3` | `subpackageMember/points-history/rows.js`, `index.js` | Rows are sorted by their own time, newest first: equal times keep the API order, and unreadable times go last. Grouping runs after the sort, so each month appears once. Test `points-history-order-and-zero.test.js` uses a row with a lower id and a later time; it fails on the old code (5/5 red) and passes now. |
| 1b | Hide 0-point rows | fixed | `724e3dd3` | same | `visibleRows()` drops `delta === 0` before anything else, so filters, empty state and paging all count without them. If every row is 0, the page shows the empty state. The same test drives the real page: all rows 0 gives `state: 'empty'`, and 20 rows + 1 zero row gives no paging. The empty state keeps its current size, and gains and uses keep their colours. |
| 1c | Re-shoot | done | `73b6e3aa` | `points/final/` | `top`, `bottom`, `long-reason`, `paged-more`, `paged-end`. See "Shots". |
| 2a | Merchant press values | done | `d4bdb059` | `pages/merchant/ledger/index.*`, `pages/merchant/style/merchant-finance.wxss`, `pages/merchant/marketing/index.*`, `pages/topic/pricing/index.*`, `pages/topic/merchantapply/index.*` | Every static `hover-class` on these 4 pages is now `cy-pressed`. Replaced: ledger .72, marketing .6/.7/.88/.9/.92, pricing .6, merchantapply scale .99/.97. The local classes are deleted. Test `merchant-press-unified-r3.test.js` is red on the old code (4/4). |
| 2b | `publish/temp` grid | done | `cdfa3928` | `pages/publish/temp/index.wxss` | `.cg-card`, the inline config blocks and `.cg-addmod` now use `--cy-page-x` (16.6) and `--cy-radius-lg` (16.6), where they were 26rpx (13.5) and 28rpx (14.6). In the rig, cards measure left 16.6 / right 16.6 / radius 16.6, the same as the hero. Test `polish-r3-grid.test.js`. |
| 2c | Merchant customer two left edges | done | `ab0fca86` | `pages/merchant/customer/index.wxss` | Title, filter chips, search and list rows all moved to `--cy-page-x`; before, the title was at 20.8 and the search at 12.5. The row divider moves with them. In the rig, search now measures left 16.6 and right 16.6. The customer drawer (§4-15, keep) is untouched. |
| §4-2 | Scrim exemption, then N-03 and `.rolemask` | done | `b34bbd24` | `tests/unit/press-feedback-coverage-contract.test.js`, `subpackageRoam/nearby/index.wxml`, `pages/play/index.wxml` | The press test now exempts a tap target only when it says `hover-class="none"` and its static class is `pdim`, `rolemask`, `*-mask` or `*-scrim`. Without that, it is still counted. Negative controls: a non-scrim with `none`, a scrim with no `hover-class`, and a scrim class that appears only inside `{{ }}` all still count. Then the 6 N-03 scrims (`.pdim` ×5, `.mdd-mask`) and `.rolemask` got `hover-class="none"`. The ratchet stays at 51. |
| §4-3 | Club title gap | done | `fad06a3c` | `pages/club/roles|notify|governance/index.wxss` | I added the same one-line rule `cy-page-title + .section { margin-top: 0; }` as 结算/运营/勋章. The gap goes from about 21 px to about 9 px (shots). Test `club-title-gap-r3.test.js`. |
| §4-6 | `.cy-refresh-float--pill` | done | `3e2fa510` | `pages/index/index.wxml/.wxss` | The three home 「正在更新…」 texts now use the pill. `.v3-sec-sync` no longer sets padding, so the pill's own padding applies. This gives the class its first users. Test `home-refresh-pill-r3.test.js`. |
| §4-7 | `cy-card` media variant at `2xl` | done | `c1e2de40` | `style/components.wxss`, `components/cy/card/index.*`, `pages/activity/list/index.wxml` | Added `.cy-card--media { border-radius: var(--cy-radius-2xl) }` globally and a `media` property on the component. It is used only on the activity-list cover cards. A test checks that no other WXML uses it. |
| §4-9 | 「−¥15.44」 through one helper | done | `b85c67a2` | new `utils/money-format.js`; ledger, batch-detail, order-detail (+view-model), aftercare view-model, coop settlement-detail view-model, customer detail view-model, `scene-merchant-profit` (js+wxml); 6 test stubs updated | `yuan()` always gives 2 decimals, a U+2212 minus before ¥, and `+` only when asked for. Callers checked: ledger refund rows, redemption rows, income rows, batches, summary, `moneyDisplay`; batch detail (batch total and its rows); order detail (amount, unit fee); aftercare refund amounts and group totals; coop settlement detail; customer paid amount; profit stat items and topic income. The test drives the real ledger `entryRow`/`batchRow`/`redemptionRow` with negative amounts. In the rig, the ledger row reads 「¥-15.44」 before and 「−¥15.44」 after. |
| §4-10 | Buy-sheet underline easing | done | `40307dc5` | `pages/topic/index/index.wxss` | `.bseg__u` now uses `.4s var(--cy-ease-out)` instead of the overshoot curve; the duration is kept. |
| §4-11 | `scrollToFirst` 350 ms | done | `22d5f1b4` | `pages/topic/index/index.js` | The duration is now 350 ms; with reduced motion it is still 0. |
| §4-12 | Two .88 press changes | done | `54d68b95` | `components/cy/avatar/index.wxss`, `components/cy/error/index.wxss` | `.av--press` went from .82 to .88, and the error retry from .85 to .88 (scale .98 kept). The rest of §4-12 is left as recommended. |
| §4-13 | M-14, M-15, C60 | done | `cca2adb6` | `subpackageA/components/scene-merchant-profit/index.wxml/.wxss`, `scripts/shot-matrix.js`, `scripts/selectors.json`, `scripts/selectors-overrides.json`, 2 tests | M-14: the status line under the topic name shows only when the topic is settled, because the badge already says 「待结算」. M-15: `.fc-explainer` and its CSS are removed. Shot-matrix C60 selector is now `.stat-grid, .fc-card` with min 2, and its fixture gets `incomeDisplay`. The selector files were edited by hand, because `_gen_selectors.js` already differs from the committed `selectors.json` on the base tree. |
| §4-14 | Light ghost button surface | done, **not seen on a ghost button in the rig** | `c4d744af` | `style/tokens.wxss`, `style/merchant-light.wxss`, `components/cy/btn/index.wxss` | New component token `--cy-comp-btn-ghost-bg`. It is declared only in `.theme-merchant` and the merchant-light `page{}` mirror, as `var(--cy-bg-subtle)`. `.theme-dark` resets it with `initial`. `.btn--ghost` reads `var(--cy-comp-btn-ghost-bg, var(--cy-bg-glass))`. It is deliberately not declared on `page{}`: a value there would resolve early and override pages that set `--cy-bg-glass` on a button themselves (the template-detail share key, caught by test V-24). |
| §4-20 | 「—」 on counters, money with 2 decimals | done | `2d55046e` | `subpackageMember/components/cy/profile/index.wxml`, `pages/topic/pricing/index.wxml/.js`, 3 tests | 我的资产: before load it now shows 「¥0.00」, not 「¥0」; the owner's P-49 "no balance shows 0" is kept. 确认终价: the final price, slider max and floor go through `money.amount2`, and the fixed-fee text through the page's 2-decimal helper; 「¥0」 became 「¥0.00」 and 「¥100」 became 「¥100.00」. 我的队伍: the counters already start at 「—」 on this branch (MT-01, `null` initial stats). The rig shot shows 「—」 in both builds, so no code change was needed; a test pins it. |

Left unchanged, as recommended: §4-1, 4, 8, 15, 16, 17, 19. §4-18: nothing added. §4-5: later.

## Conflicts with lane work

None of these items conflicted with a lane's approved result on this branch. One related point: the 我的 header counters (好友/关注/粉丝) still show 0 before load. That is the owner's ruling recorded in round 2 (§5 there), and §4-20 here is about 我的队伍 counters, so I did not touch them.

## Verification

| Check | Result |
|---|---|
| Full mini unit tests, `node --test "tests/unit/**/*.test.js"` at `73b6e3aa` | **15865 / 15865 pass** (9 min) |
| `node tests/lint/ui-integrity.js` | `UI-GATE-0:PASS` after every item |
| `node scripts/uiaudit/build-action-ledger.js --check` | `ACTION-LEDGER PASS: 5635 controls`. The ledger was rebuilt twice; both times only line numbers moved, in `scene-merchant-profit`. |
| CI on PR #723 | See the CI line below. |

One test note. `action-ledger.test.js` "inventory 与可持久化动态证据分离" compares a source digest at HEAD with the working tree. It fails while shared style files are uncommitted and passes on the committed tree (46/46). This is not a product issue.

## Shots

All shots come from the cloud walkthrough rig, not a phone. Setup:
- Backend: the rig branch's `up.sh` with the club lane's harness patches, a jar from this branch, and real MySQL and Redis. `chains.py finance` passed all five chains.
- BEFORE bundle: `724e3dd3` (after item 1, before items 2+), served on :8097. AFTER bundle: the final tree, served on :8098.
- Size: 390 × 844, JPEG q70, 4–27 KB each.

Rig-only data:
- Fictional points rows, `create_by='rig-walk-fixture'`.
- `ums_member.role='club'` for 9003 and 9008.
- Topic 990013 hosted by 9002.

Where the seed cannot reach a state, I set page data in the browser: ledger rows built by the page's own `entryRow`, profit topics, activity-list events, and home rail items with the refresh flag on. The steps are kept in the shot list (`shoot.mjs` / `items.json`, not committed).

`points/final/`, all with real API data:
- `top` (9004): 11月, 10月 and 9月 each appear once.
- `long-reason` (walk-player-2):
  - The 11月3日 row has a lower id than the 11月2日 row but a later time. It sits inside the single 11月 group, on top.
  - The real 「注册赠送 0」 row written at login is gone.
  - The 62-character reason wraps over 3 lines with no overlap.
  - The 「余额 30」 on that row is a fixture artifact, because it was seeded separately.
- `paged-more` / `paged-end` (walk-player-1, 27 non-zero rows plus one 0 row): 加载更多 shows after 20 rows, and 已经到底了 shows at the end. 9月 continues as one group.
- `bottom`: 9004 scrolled to the end; it is a short list, so there is no footer.

`integ/polish-shots/<name>-before.jpg` / `-after.jpg`:

| Shot | What I saw |
|---|---|
| `r3-publish-temp-grid` | Cards move from 13.5 to 16.6 and now line up with the box above them. Section titles (模板信息) sit about 1 px inside the card edge (17.7 against 16.6). That is under 2 px and I left it. |
| `r3-merchant-customer-edges` | Title, chips and search now share one left edge. The rig has no customers, so **list rows are not rendered**. |
| `r3-merchant-ledger-press`, `r3-merchant-marketing-press` | Identical at rest (AE 0), as expected. A press change cannot be seen in a still. |
| `s4-03-club-roles-title-gap`, `s4-03-club-notify-title-gap` | Title → first section goes from about 21 px to about 9 px. Nothing else moves. |
| `s4-06-home-refresh-pill` | 「正在更新附近活动…」 now sits on a dark pill instead of bare text over the covers. The rail covers are blank because the injected rail items use a local image path the rig did not load. |
| `s4-07-activity-list-media-card` | Cover cards have rounder corners (2xl). The cards show 「· —参与」 in both builds; that comes from my injected events missing a count field, not from the product. |
| `s4-09-ledger-negative-money` | 「¥-15.44」 → 「−¥15.44」. 「+¥1000.00」 is unchanged. |
| `s4-13-coop-finance-status` | The unsettled card no longer repeats 「待结算」 under the name. The explainer line is gone. 「还没有已入账分润」 next to ¥128.00 comes from my injected topics not updating the withdraw state; it is not product behaviour. |
| `s4-14-merchant-home`, `s4-14-merchant-reviews` | Shared-token check: identical except the relative time 「7分钟前 / 9分钟前」. |
| `s4-20-profile-balance-before-load` | 「¥0」 → 「¥0.00」. The header 0/0/0 is unchanged (owner ruling). |
| `s4-20-pricing-money` | 「¥0」 → 「¥0.00」 and 「¥100」 → 「¥100.00」. |
| `s4-20-myteams-counters-loading` | 「—」 in both builds (already fixed before this round). |

Odd things I noticed. All of them are the same in the BEFORE build, so this round did not cause them; I did not change any of them:
- Merchant 工作台: the 「项目」 heading has nothing under it in the rig (no projects).
- 确认终价: 「请填写大于 0 的成团人数」 shows as a pink block with an icon beside the field, not as red text under it. That may go against the standing rule that input errors stay red text under the field. **Worth an owner look.**
- Club settlement (`ApiClubSettlementController.money`): the backend builds 「¥ -2.00」 itself. That is a backend string, outside this mini-program helper, and changing it is a cross-module backend edit, so I left it. Recommended default: give the backend the same 「−¥」 rule in a later backend item.

## Not verified

- Nothing was checked on a real phone: real fonts, safe areas, native press timing.
- Press and motion items cannot be seen in stills: 2a, §4-2 (scrims), §4-10, §4-11, §4-12.
- §4-14: no reachable merchant page in the rig rendered a ghost `cy-btn`. `merchantinfo` (held page) renders blank, and the ai-npc and reviews ghosts need data the seed lacks. The token is checked by tests, and merchant pages were checked for unintended change.
- Not rendered in the rig: customer list rows (2c), batch detail and refund lines (§4-9; covered by tests), the play `.rolemask`.

CI: see the line added below once PR #723's run on the final commit finishes.

STATUS: COMPLETE
