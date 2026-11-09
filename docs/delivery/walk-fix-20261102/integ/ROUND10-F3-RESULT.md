# Round 10, lane f3-pages: result

This lane works on branch `integ/launch-test-20261103`. It started at `7ceb2cf3` after the owner released f2, f3 and f4 to run in parallel (F2's `STATUS: RELEASED` placeholder). The last f3 code commit is `bc40b85e0`.

**How the work was split.** Six workers handled it:
- A: item 1 dead ends, and item 2.
- B: item 3 wrong words, plus the LOW items from j5 and j6.
- C: merchant polish and captions (p2, j1).
- D: club and publish polish and captions (p3, j2).
- E: j5 H4.
- F: leftovers.

I ran the rig walks and the cross-lane fixes. The per-item notes are condensed below.

**Rig.** All rig results come from the cloud rig: the real jar at `ba3c22071` on MySQL 8 and Redis 7, with the real mini-program in headless Chrome at 390×844. **They are not a phone.** Screenshots are `fix-shots/f3-*.jpg`, and I looked at every one.

Rig harness changes are in `proof/round10-f3-rig-changes.diff`, and none of them touch product code:
- the club lane's patches;
- dummy OSS env vars;
- `up.sh` pinned to the tested commit;
- one new patch to `nav.js`. On navigate-back it fires `pageLifetimes.show` on every component of the page that is shown again, which is what WeChat does. The rig only fired it on the page itself, so the j6 F4 fix looked broken until this was patched.

Fixture writes went to the throwaway database only:
- `ums_member.role='club'` for 9003 and 9008. Both own clubs but have the `player` role in the fixtures (p3 F13 / j6 F8).
- `chains.py player` data.
- Three `walk-player-*` accounts.

## Incident: shared branch ref

The first four workers each ran `checkout -B integ/launch-test-20261103` in their own worktree, but a local branch ref is shared by all worktrees. So one worker's commit could take another's stale index and silently revert their work. This happened twice on origin:
- `467ede59` reverted club/create p3 F1/F2. They were restored in `00b7dde7`.
- `991ccfe2` reverted p2 F-5/F-7. They were restored in `3346026e2`.

After that, every worker moved to a private local branch and checked `git show --stat` before each push. No f1, f2 or f4 commit was touched; between `7ceb2cf3` and the incident, origin only had f3 commits. Nothing was force-pushed.

## Summary

| # | Item | Status |
|---|---|---|
| 1 | Dead ends | Fixed. j5 H2 is partly done: the publish receipt still says 发布成功 for a held theme, which needs a backend flag (owner). |
| 2 | Guest reads of club posts and themes | Report below. Nothing was opened. |
| 3 | Wrong words | Fixed. p3 F9 (which name for the owner) needs the owner. |
| 4 | Polish | Most items are fixed. The rest are listed as needs-owner, needs-backend or not done. |
| 5 | Captions | Most are cut. The kept ones are listed for the owner. |
| — | Main package | f3's net change is **−45 bytes**, after `f66451790` trimmed the comments the gate component had added. |

## 1. Dead ends

| Finding | Status | Commits | Proof |
|---|---|---|---|
| p3 F1: the first 下一步 tap on club/create step 3 was swallowed | Fixed | `991ccfe2`, restored as `00b7dde7` | `club-create-state-contract`: a queued 下一步 now waits for the same name check (fails on old code). **Phone only:** keyboard blur order. |
| p3 F2: guest saw a blank club/create | Fixed | same | Rig `f3-1-guest-club-create`: 「登录后创建俱乐部 · 登录」. |
| j6 F3: about 30 guest blank pages | Fixed | `a0939b806` | `cy-access-gate` now checks for a guest before waiting for clubId, and has a new `kind="login"`. It is used on 20 pages; club customers, customer-detail and settlement are covered through the component. After login it `redirectTo`s the same page with the same params. Test `r10-f3-guest-login-gate`. Rig: guest roles, address and coupon show 「登录后查看 · 登录」. On address, tapping 登录 reloads it as a logged-in page (`f3-1-guest-address-after-login`). On roles, a new non-manager account is correctly refused after login and sent back. `subpackageRoam/citystamp` was not changed: it opens the camera, which the rig can't draw. **Phone only:** the reload after a real WeChat login. |
| p3 F3: business refusal vs network failure | Fixed. They can be separated. | `37a209aa9` | A refusal is HTTP 200 with a non-200 code that is not a login code, plus a message other than 系统繁忙; it shows the reason and one 返回. Network failures, non-2xx responses and 系统繁忙 stay blank as before. topic-preview now offers 返回 instead of a 重试 that can never succeed. Test `r10-f3-business-refusal`. Rig: templatedetail and topic-preview (`f3-1-*-notfound`); club topic-detail for another club's theme shows 「链接已失效 · 返回」. **Caveat:** the backend uses the same envelope for business errors and for masked runtime errors, so a runtime message that survives masking would be shown as a reason. |
| j5 H1: held or removed theme opened by link | Fixed | `dd4f8a8b6` | 「主题活动不存在 · 返回」. Test `r10-f3-topic-held`. Not walked in the rig, which needs a WeChat-busy hold. |
| j5 H2: author doesn't see the held state | Partly fixed | `dd4f8a8b6` | On their own theme page the author now sees a 审核中 / 未通过 / 已下架 chip. **Needs owner/backend:** `/api/topic/v2/create` still answers 「主题发布成功」 for a held theme, so the publish receipt reads as live. |
| j6 F3: 票夹 error state | Fixed | `a492a36e3`, `c3b37d701` | Rig, offline: 「票夹没能打开 · 网络异常，请稍后重试 · 重试」. Back online, 重试 loads the list (`f3-1-wallet-offline`, `-retry`). |
| j6 F4: activity join gate on return | Fixed | `3c1029705` | Test `r10-f3-join-gate-refresh`. Rig, with the nav.js harness patch: a new player opens activity 12 → 去加入俱乐部 → 加入 → back, and the page shows the session and tickets (`f3-1-join-gate-before`/`-after`). **Phone:** confirm that the component's `pageLifetimes.show` fires on back. WeChat documents that it does. |
| j6 F7: guest club page | Fixed | `b71b0e2eb` | Members and leaderboard are replaced by a single 「登录后查看 · 登录」 row, and those two endpoints aren't called (`f3-1-guest-club-detail`). |

## 2. Guest reads on the club page: for owner review (nothing opened)

Today a guest gets 401 on `/api/club/members`, `/topics`, `/post/list` and `/leaderboard`. Members and leaderboard stay closed (owner decision, done above).

- **`/api/club/topics`** (ApiClubController :1404). It already uses `selectPublicCmsTopicList` behind `canViewClub`. It returns topic summaries: id, name, cover, price/ticket summary, status and club; `/api/topic/list` already serves these fields to guests. It serializes the whole `CmsTopic` entity, including per-viewer flags (isSignUp, isOwner), the author-only rejectReason, and sale-audit lookups.
  - **Low risk, if opened through a guest projection:** only `status=1 AND user_status=1`, with no per-viewer flags and no rejectReason.
- **`/api/club/post/list`** (:1764). It serializes `ClubPost`:
  - id, content, images, authorMemberId, nickname, avatar;
  - counts, isPinned, version;
  - isLiked / viewerReposted / isFollowTheUser;
  - clubName/logo and repost items.

  Posts are written for members and may show people at sessions. Posts pass the text check, but phone and WeChat ids are not blocked.
  - **Needs your decision.** If opened: a projection without memberId or per-viewer flags, no comments, and only clubs with `joinPolicy=0`. Before opening, run a read-only check of production posts for contact details.

## 3. Wrong words

| Finding | Status | Commits | Proof |
|---|---|---|---|
| j5 H3: rescan takedown notice gave the image reason for a text hit | Fixed | `467ede59` | 「原因：文字未通过内容安全检测」. Tests `ContentSafetyImageTakedownNoticeTest`, `SensitiveLibraryIntegrationTest`. |
| j5 H4: club posts missing from 我的 → 帖文 | Fixed | `fbfb910b1`, `349f43c92`, `a561e020a` | The club feed takes `author:"me"`, which returns only the caller's own posts under the usual visibility, deletion and block rules. Any other value is ignored, so there is no listing posts by id. The own profile then loads the newest 20 club posts after the community posts; 「还没有帖文」 shows only if both are empty. Backend: 2 new tests. Mini: 2 new tests (fail on old code). **Limits:** only the newest 20 club posts; not merged by time; no like button on these cards. Not walked in the rig. |
| j5 H5: profile 昵称 showed 「未填写」 and 「城瘾玩家」 | Fixed | `f28995488` | `r10-f3-profile-nickname-fallback`. |
| j6 F9: unpaid order showed 「实付款」 | Fixed | `98bfee807` | Now 「待付款：¥…」. |
| j6 F12: guest saw raw 「登录状态已失效」 | Fixed | `c0dba82f9` | Rig `f3-3-guest-coop-nearby`: 「登录后查看 · 登录」. |
| p3 F4: event-ops with no theme | Fixed | `08468142e`, `42a999708` | With no theme and no series, only 「暂无可开场的主题 · 发布主题」 shows. In the rig, club 7002 *has* a co-op theme, so the builder showing there is correct (`f3-3-event-ops-empty`). |
| p3 F6: 确认终价 error style and timing; the unlabelled ¥ | Fixed | `fef56634d`, then `bc40b85e0` | No error on open (rig `f3-3-pricing-open`). 成团人数 and ticket-price errors appear after input or a confirm tap. The slider end reads 「最高 ¥x」. In `bc40b85e0` the 成团人数 error moved to a new `field` kind of `cy-inline-error`: red text under the field, with no background and no icon. That keeps the plain-red-text rule and passes CI's U6 raw-error gate, which the bare `<text>` had failed. Price rules are untouched. Captions 「终价不可低于此值」 and 「样本不足，暂不展示参考价」 were left for f2, which owns the page's logic. |
| p3 F9 / j2 F-21: 群主 vs 主理人 | Term fixed; **name needs owner** | `44d8f4d40` | Now 主理人 everywhere (rig `f3-4-owner-club-detail`). The 概览 cell shows the name from the 主理人 application and member rows show the nickname. Using the application name everywhere could publish a real name. Which one should show? |
| r1 leads | Merchant quote fixed by f1. The two old-bundle leads do not reproduce on current pages. | — | — |
| Found in the rig: 「模版不存在」 | Fixed | `8d88dc550` | p3 F3 now shows the server reason, which was spelled 模版 where every other screen says 模板. `ApiTemplateMerchantOperatorScopeTest` 21/21 pass. |

## 4. Polish

**Fixed:**
- **p2 F-5** (`3346026e2`): the 项目 heading is hidden when the project list fails and there are no cards.
- **p2 F-7** (`3346026e2`): 核销详情 for no-cash rows drops the empty 「我的收入」.
- **p2 F-9** (`2239674c4`): customer detail and invite detail with a missing id show the reason and 返回. Without permission, the customer list hides its controls.
- **p2 F-11** (`5c10e5528`): 编辑 is hidden once the theme is locked.
- **p3 F5 / j2 F-21** (`8ff0f9eee`): an owner with no projects sees only the 现在要做 card.
- **p3 F8** (`9a84857a7`): create-flow punctuation is full-width.
- **p3 F10** (`27d58b5e7`): the 选择玩法 highlight corrects for the sheet's entry animation. Test fails on old code. **Phone only.**
- **j2 F-17** (`3a0e45c38`): no raw `NODE_` code; no false 「主办方尚未配置本站任务」; a ready station reads 「等待活动开场」.
- **j2 F-19** (`8873acffd`): the team join page shows the real join mode.
- **j2 F-21 / F-23** (`8fb636201`):
  - the 主理人 no longer sees 举报俱乐部 on their own club;
  - the roles form resets after 分配;
  - after a transfer the old owner leaves governance and gets no 「欢迎加入」.
- **j1 F6, F9** (`d4c63aa60`): ticket price is no longer prefilled with 0 and is required. The summary drops the seconds and no longer covers 添加票单. The location field reads 「选择活动地点」.
- **j1 F10** (`da26161be`, `b4918a567`): 返回首页 is full width.
- **j1 F21, three of four parts** (`ff9101a03`): refund rows read −¥ with no doubled 退款; the owner's own-invite message is in Chinese. 其他收入 was already fixed by f1.
- **j1 F23** (`07608d68a`): 「还剩N天 / N小时」.
- **j1 F18, part** (`af03cb846`): order amounts show 2 decimals, and times use the 24-hour clock.
- **j1 F11, part** (`47dca2ac7`, `bc40b85e0`): no 「0分钟」. The duration unit now has a `|| 0` fallback for CI's unit gate.
- **j5 L1** (`abfc6d55d`): club type no longer falls back to the city.
- **j5 L8** (`98b745f76`): 帖文 instead of 帖子.
- **j5 L4, profile part** (`6ab06d679`): a refused save gives its reason once.
- **j5 L5, part** (`f1694b467`): theme review notices name the theme and don't repeat the outcome as the reason.
- **j6 F8** (`9bbd45fca`): only one refusal sheet on club/edit.

**Needs the owner:**
- **p2 F-6 / j1 F15:** the tab bar shows content through it (seen again in rig `f3-4-merchant-home`). This is your 2026-09-19 transparent-bar decision.
- **p2 F-12:** your two title rules conflict.
- **j1 F17:** 「N 天内过期」 is your V-02 decision.
- **j1 F19:** quote vs create fee gate is a money rule, so I didn't change it.
- **j1 F24:** the cut-off chips are your 2026-10-22 horizontal scroll.
- **j5 L3:** busy WeChat refuses posts instead of holding them; it's built that way.
- **j5 L5:** button names 「查看生成时记录 / 查看当前原单」, which three tests pin.
- **j5 L6:** a 「系统通知」 thread title may only be fixture data.
- **j2 F-18:** 「x/5 人」 counts the lead on purpose.

**Needs backend or f2:**
- **j2 F-18, the trend block:** it asks for sign-ups by theme, but club tickets are filed by activity. The fix is a permission scope on f2's 招商 page.
- **j2 F-22:** 票夹 can't tell that a session ended early; this needs a new field.
- **j1 F5:** category data.
- **j1 F8:** approval notification.
- **j1 F16:** scan result fields.
- **j1 F21:** the sign-up name on refund rows.
- **j5 H2:** the receipt, as in item 1.

**Not done:**
- **p2:** F-8 (not reproduced by code reading) and F-13 (rig or phone).
- **j1:**
  - F11: the editor's pink error blocks, the template name defaulting to the game type, and whether 完成奖励 is still required for merchants;
  - F18: the glyph under the status bar, the repeated 未开始/不可退款, and ASCII punctuation in backend `RefundPolicy` strings that many tests pin;
  - F20.
- **j2 F-18:** 到场 4 vs 3.
- **j5:** L2, L7, L11; L10 (phone).
- **j6:** F13 (rig-only).
- **j6 F10** stays as is on purpose: the key lets a re-login reuse the original consent request id.

## 5. Captions

**Cut** (`9a84857a7`, `c96acf2ab`, `f45deb1c0`, `b4918a567`, `d4c63aa60`, `ca8ba2a1a`, `8a1d1a7c3`, `8873acffd`, `e5fd8a0dc`):
- 成为主理人: intro (rig `f3-5-apply-intro`), steps and done screen.
- 建俱乐部 intro and steps.
- Welcome-sheet and 设置-sheet lines.
- Notify: three event-only audience rows (rig `f3-5-owner-notify`).
- join-requests and group-code lines.
- Team-page and play-intro lines.
- 品牌中心, 合作经营 and 营销 row hints.
- 商家入驻 intro and step lines.
- 发布活动 lines.
- Activity 积分 line.
- Guest 设置 line.
- 3D badge 「拖动可旋转查看」.
- 经营团队 「请在本页分享」.
- Order 「票夹已生成…」 and 「退款按本单…」.
- Profile empty-state lines.
- Duplicate 评价 disclaimer.
- Chat-footer second half.
- 行业类型 hint, now 「已选 n/5」 with the first chip marked 主.
- 快速配置 「你喜欢什么」; the bubbles now carry their words.

**Restored.** 快速配置's pre-draft 「城市定向 · 集合式路线 / 自由探索 · 章节承接路线」 line was cut, then restored in `bc40b85e0`. It is the only place that page says which product it will draft, so it is data, not a caption. CI's dead-field gate caught that `mode` was no longer shown anywhere.

**Kept, for the owner** (privacy, consent, legal, or a consequence on a danger action):
- 「联系方式不对外展示」 (privacy).
- The 解散俱乐部 consequence line.
- 「转让后你将失去主理人权限」. It stays on the governance row because the confirm dialog's text area is taken by the reason input.
- 「商家身份不能报名」 and the real member perks on the welcome sheet.
- 「用于地图与到场导航，拒绝不影响填写」 (location purpose).
- 「标 * 的是必填」 on 商家入驻, which an accessibility test requires.
- The coupon-management empty line, your M-07, pinned by three tests; it was removed on 营销 only.
- The 实名 consent line.
- The 发布活动 edit-mode line about public scope.
- 「合作者可编辑内容，不参与结算」, now shown once inside the collaborator picker.
- The topic 评价 积分-rule line.
- 「系统通知不支持回复」, which replaces the input box.
- The cancel-order and logout consequence lines (`utils/danger-actions.js`).
- Sign-up consent and refund-tier terms.

**Left for f2**, whose screens are changing now:
- 开一场 / event-ops captions (「仅可修改无报名的未来场次」, 「最多排未来 90 天」, 「退款窗口按…」), seen in rig `f3-3-event-ops-empty`.
- Coop invite and merchant station-apply captions.
- 确认终价's two lines.

**Seen in the rig, not changed:** 「还没有可统计的项目数据」 under the zero tiles on a club that has a project but no plays (`f3-4-empty-club-manage`). p3 F5 hid the tiles only when there is no project.

## Old tests updated because the owner's rule changed them

Each commit says why. Safety assertions and negative controls are kept.

| Group | Tests |
|---|---|
| Captions | `club-apply-state-contract`, `screenshot-fixture-completeness` C40, `merged-copy-groups-contract`, `club-lifecycle-contract`, `club-join-request-confirm-name-contract`, `club-event-ops-pages-contract`, `cu-c-113`, `profile-posts-empty-neutral-contract`, `wt2-player-scene-fixes-contract` (`764cb4cd0`) |
| Blank-to-visible states | `club-e-fixes-gate-contract`, `uiux-player-state-adoption`, `v4-club-share`, `dialog-contract-r3`, `club-detail-ia-contract` |
| Pricing | the two pricing tests; `p34-r4-pricing-tickets-and-team-size` now pins the `field` kind and keeps the no-pink-block check |
| Other | `club-workbench-contract` (帖子), `index-format.test` |

## Checks

**Mini unit tests (full suite).** At `ba3c22071`: 16,135 tests, 16,134 pass. The one failure is `r11-g3-order-truth` 「自玩通行证只说一次不可退款」. It already fails at round 11 g3's own commit `f0be9f938`, so it is g3's, not f3's. After that run, the related suites were re-run for each later f3 commit, and they pass.

**`ui-integrity.js`.** UI-GATE-0 PASS at `bc40b85e0`. Earlier it was red on g1's `publish/temp` anchors (`3c056cf32`); that lane has since fixed them.

**`build-action-ledger.js --check`.** PASS after the rebuild in `bc40b85e0` (5712 controls). One control fewer, because the 成团人数 error is now a component attribute.

**Backend.** Each worker's touched suites pass: content-safety, AuditTask, ClubPost feed and ApiTemplate (21/21).

**CI.** CI on `bc40b85e0` contains every f3 commit. Of its 31 jobs, 28 pass and 3 fail. None of the failures comes from f3:
- **Mini program static gates and static checks.** These fail only on:
  - growthcenter `me.rank` unit (round 11 g3, `4751ec464`);
  - session-picker `ticketsError` U6 (round 11 g3, `5b8009bdc`);
  - merchantinfo `hostMerchants` dead prop (f2, `dd062b828`).

  The three f3 items that were red on `559d41330` (pricing U6, the duration unit and the `mode` dead field) are gone.
- **Backend tests.** One error: `ClubSettlementTopicNameH2Test`, `BadSqlGrammar` in the club settlement summary (f2). On `559d41330`, Funds MySQL IT failed with the same error in `ClubSettlementSummaryMybatisIntegrationTest`.

So CI is not fully green on a commit that holds all f3 work. Everything still red belongs to f2 or round 11 g3. The commit I relied on is `bc40b85e0`.

**Migrations.** None in this lane.

## What only a phone proves

- Keyboard blur order on club/create.
- The login gate's reload after a real WeChat login.
- `pageLifetimes.show` on back for the join gate.
- The 选择玩法 highlight while the sheet animates.
- Real network loss for 票夹.
- The composer edge (j5 L10).
- Real fonts and wrapping of the changed rows.

STATUS: PARTIAL - items 1, 2 and 3 are done except j5 H2's receipt and p3 F9's name (owner). Items 4 and 5 are mostly done, with needs-owner, needs-backend and not-done items listed. CI on `bc40b85e0` (all f3 work) has 3 red jobs, all from f2 (club settlement SQL, hostMerchants) or round 11 g3, none from f3.
