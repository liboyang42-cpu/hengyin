# Round 12, lane k9-org-leftovers: result

**Two k9 workers ran on the same branch.** The other one is `k9-pages`, which writes `ROUND12-K9-RESULT.md`. Where we overlapped (p2 N-9, N-13, N-15, j1 N7, N11 and the 授权状态 line), its commits landed first (`969265a59`…`9cdb9af4a`) and I kept them. I dropped my own versions of those items. I also dropped my ··· button on 我的项目 cards: `k9-pages` points out that the owner's design 260:369 says no buttons on cards. This file covers this lane only.

Branch `integ/launch-test-20261103`. I worked on a private local branch and ran `git pull --rebase` before every push; nothing was force-pushed. Lanes k4–k8 pushed to the same branch at the same time, and I skipped everything they had already fixed.

**What this lane covered:** what `ROUND12-K3-RESULT.md` lists as not done, except the items it marks needs owner:
- p3 N6's lost category and tickets;
- k3's leftover LOW findings;
- item 5: the "not covered" pages a fixture can reach.

I also walked k3's name fixes on the real screens.

**Screenshots are from the cloud rig, not a phone.**
- Real jar with MySQL 8 and Redis 7, and the real mini-program in headless Chrome at 390×844.
- The rig is `claude/cloud-walk-20261101-rig` with j2's `tools/rig-changes.diff`: the club lane's harness patches, plus the OSS and `api.weixin.qq.com` stand-ins.
- Docker uses the `mirror.gcr.io` mirror.
- **Which build each shot comes from:**
  - The j2 journey replay (`k9-01`…`k9-07`, `k9-16`, `k9-17`) ran on a jar and bundle built from `943b7f67`.
  - My own walks of the changed screens ran on `f1bd03ccc` and `e495f79d1`, on the same database.
- I looked at every shot I name.
- All people, phones and names are fictional.
- Read-backs are in `fix-shots/k9-walk-readbacks.txt`.

**Other facts about this round's changes:**
- No migrations.
- No new endpoints.
- No `@Test` was added to or removed from a gated suite, so `ci/funds_it_gate.py` is unchanged. One assertion line changed in `MerchantCrmFreeExploreRosterMySqlIT`; it has the same tests.
- Main package: small edits to existing pages. I did not run preflight's size count.

## Summary

| # | Item | Status |
|---|---|---|
| 1 | p3 N6: category and tickets lost when copying from a template | done for both. Category was never lost; tickets now copy. One gap needs owner (below) |
| 2 | k3's LOW leftovers | p3 N9, N13, N16, N19 and p2 N-16 done; p2 N-14 and N-17 part done; p2 N-12 not done; the rest done by `k9-pages` |
| 3 | Item 5: fixture coverage of what p2/p3 left uncovered | done for club session pages, roles, governance, customers, settlement with money, template full states and game-node. Not reachable: coop settlement-detail, batch-detail and predict with data (reasons below) |
| 4 | k3's name fixes walked on the real screens, including the transfer confirm and the ban record | done |
| new | After 另存为模板, 我的项目 showed the template copy as a second 「进行中」 theme | fixed |

## 1. p3 N6: template copy

**Category.**
- The real flow keeps the category. Theme 990014 has category 21. Save-as made template 990016, and 应用 made draft 990018.
- The editor's data has `selectedCategoryIds ["21"]` and `clubId 990004`.
- p3 (and j2) built their themes with `categoryIds "1"`, which is not a real `sys_category` row. That is fixture data, not a copy loss.
- The remaining 「还有 1 项未填」 in 主题详情 is the subtitle, which is empty on the source theme as well. Shot `k9-14`.

**Tickets: fixed.**
- `TopicContentCopyServiceImpl` had a comment saying copyType 3 (「应用」) copied ticket types, but no code did. It now copies the template's own topic-level ticket types into the draft:
  - it copies the settings: name, price, stock, mode, team size, times, meeting point and refund rule;
  - stock is reset to full;
  - nothing is copied from sessions, sales, sign-ups or `source_ticket_id`.
- The draft is not on sale, so nothing can be bought from it.
- copyType 2 (transfer to a club, and save-as since k1) still copies no tickets.
- **Proof:**
  - New test `TopicTemplateUseTicketCopyTest` (2 tests). `TopicCopyNpcRemapTest` still passes (6/6).
  - Rig: a template carrying one ticket type gives a draft whose editor shows `tickets [["领队场",39,20]]` (shot `k9-14`).
- **Files:** `chengyinhub-system/.../TopicContentCopyServiceImpl.java`, `chengyinhub-admin/src/test/.../TopicTemplateUseTicketCopyTest.java`.

**Needs owner.** Templates made with 另存为模板 since k1 carry no ticket types at all. k1 deliberately copies with copyType 2, and the template row is `user_status 1` / `status 1`.
- So a draft made from such a template still starts with an empty 票务 card.
- Putting ticket rows on a template row is not safe today. The registration path skips `SaleAvailabilityPolicy` for free topics (`CmsRegistrationServiceImpl:882`), so a free ticket type on a template could be signed up for.
- **My default:** keep it as is. The author sets tickets in the editor, and the 票务 card already says what's missing.
- **The alternative:** store ticket types on the template in a non-ticket form. That needs a schema decision.
- Templates that already carry ticket rows now copy them: production's older in-place templates and platform templates.

`k9-pages` fixed repeated 应用 making extra drafts. It also reached the same conclusion on the category independently. It asked for a check that copied tickets can't be sold: the draft has `status 0` / `user_status 0` / `lifecycle 0` and isn't listed, so they can't be bought. The free-topic path is the reason I didn't put tickets on templates.

## 2. LOW leftovers

| ID | Status | What changed | Files | Proof |
|---|---|---|---|---|
| p3 N9 | fixed | **The fault:** the success toast lived in the page's own `cy-toast`, and the page left immediately, taking the toast with it. **Now:** the page shows 「主理人已转让给 X」, waits 1.5 s with its buttons disabled, then leaves. The transfer row has its own 选择成员 sheet, which leads straight into the existing confirm, so the ban selection is no longer shared. The row's action reads 「选择成员」 | `pages/club/governance/index.js`, `.wxml` | `club-governance-ui-contract.test.js` (16/16; the new test fails on old code). Rig: shots `k9-08` (own picker; the ban row still reads 未选择), `k9-09`, `k9-10` (toast); `owner/transfer` 200, then the route goes to club detail |
| p3 N13 | fixed | 拒绝 opens one sheet: the title 「拒绝「城瘾用户 · 2026-11-05 15:32 申请」的入会申请?」, an optional reason, and 确认拒绝. The extra danger-confirm layer is gone (`confirmKey` dropped from the registry entry; unused `club.joinRequest.reject` removed) | `pages/club/join-requests/index.js`, `scripts/danger-action-registry.json`, `utils/danger-actions.js` | `club-join-request-confirm-name-contract.test.js`, `pr10-review-ui-fixes.test.js`. Rig: shot `k9-12`; `join-request/reject` 200 |
| p3 N16 | fixed | **The fault:** 「近 90 天未活跃」 only counted club-session sign-ups by `payment_time`. **Now:** a member is not inactive if, within 90 days, they joined, created the club, or have a paid registration in the club's scope (club topic, club session, or the publisher's self-host session). One SQL fragment is shared by the count and the send filter | `ClubEventOpsMapper.xml` | New H2 `ClubInactiveRecipientsMyBatisTest` (3 tests); `ClubEventOpsMapperContractTest`, `ClubEventNotificationServiceImplTest` and `ClubCancellationSnapshotEligibilityMyBatisTest` green. Rig: 4 members who joined and paid today → 「近 90 天未活跃 0 人」 (shot `k9-13`) |
| p3 N19 | fixed | A session that never started and has ended (or was cancelled) shows 已结束 / 已取消, with 查看结算报告 as its main button, instead of a dead 开始准备. It uses the stats' server-clock `sessionStatus` | `pages/club/topic-detail/index.js` | `club-topic-detail-status-truth.test.js` (9/9). Rig: session 9 shows 已结束 and 查看结算报告 (shot `k9-11`) |
| p2 N-12 | not done | One caller still posts `/api/coop/invite` without `shareMode`: the club branch (`inviteType 1`) of `subpackageMember/components/cy/coop-invite-sheet`. It isn't reachable today, because the only mount passes `invite-type 0`. The server can't require `shareMode` until that branch sends it | — | — |
| p2 N-14 | fixed (part) | An ended invite's footer says 「这条邀约已拒绝」 (or 已取消 / 已顶替 / 已过期) instead of 「这条邀约已失效，没有可执行的操作」. When an accepted invite has no contact, the 联系方式 row is hidden, so the hint repeating the 联系合作方 button is gone. **Needs owner:** 评价 timing. The backend allows a review as soon as the invite is accepted, and there is no later state to wait for. **Not reproduced in code:** my reply missing on a 已拒绝 invite; the reason is saved and rendered | `pages/coop/invite-detail/index.wxml` | `r12-k9-org-leftovers.test.js` (3 tests, all fail on old code). Not walked |
| p2 N-16 | fixed | 「发生退款」 shows only at the shop that was paid for the order (same rule as `HostPaidRegistration`). An unredeemed sign-up stops being 待核销 once its event has ended. Applies to list tags, counts, segment filters and the detail tags | `MerchantCrmAppMapper.xml` | New H2 test `refundTagOnlyAtPayeeShopAndEndedUnredeemedSignupIsNotPending`; `MerchantCrmProfileNamespaceMyBatisTest` 7/7, `MerchantCrmAppMapperContractTest` 6/6 |
| p2 N-17 | fixed (part) | **Duplicate 扫码:** a shop that only accepted an invite no longer gets a 扫码 quick action next to the bottom 扫码核销; the empty quick-action row isn't drawn. **Repeated hint:** free-explore 客户 rows no longer repeat 「联系方式在主题名单按客户授权查看」; the reveal endpoint still gives that reason. 营销 counts left as they are | `pages/topic/merchantinfo/merchantinfo.js`, `pages/topic/components/project-join/index.wxml`, `MerchantCrmAppServiceImpl` | `coop-invited-partner-project-view.test.js`, `r12-k9-org-leftovers.test.js`, `MerchantCrmAppServiceImplTest.freeExploreShopRowsCarryNoRepeatedContactHint` (33/33). `MerchantCrmFreeExploreRosterMySqlIT` (one assertion line changed, no @Test added) **not run locally** (needs MySQL) |
| p2 N-9, N-13, N-15; j1 N7, N11, 「授权状态待确认」 | done by `k9-pages` | see `ROUND12-K9-RESULT.md` | — | — |
| j1 「长按卡片管理」 | needs owner (as `k9-pages` says) | I had added a ··· button and dropped it: the owner's design 260:369 says no buttons on cards | — | — |
| new | fixed | After 另存为模板, the copied template row (k1) also showed in 我的项目 → 主题 as a second 「人民广场 · 喷泉报时 · 进行中」 card with 下架 / 取消主题并退款. `/api/project/my` now asks for non-template rows (`CmsTopic.excludeTemplates` → `is_template = 0`); the template stays in the 模板 tab | `ApiProjectController.java`, `CmsTopic.java`, `CmsTopicMapper.xml` | `ApiProjectAcceptStatusTest.my_topic_list_leaves_template_copies_to_the_template_tab`; `ApiProjectControllerTest`, `ProjectPlayerContactVisibilityTest` green. Rig on the rebuilt jar: `/api/project/my` for 990005 returned rows [990016, 990014] before and [990014] after (read-backs). The after-shot was taken on a build that still had the dropped ··· button, so I didn't keep it |

Still not done from k3's list, with reasons:
- p3 N10 (「¥ x」 built by the settlement backend): k5's area. It is still there; see shot `k9-06`, 「¥ 98.49」 in monospace next to 「¥98.49」.
- p3 N18 (editor preview sheet, may be the rig) and p3 N19's real-use case beyond what's above.
- p2 N-11 (early redeem: a money rule).
- j1 N8 (verifier id: a data-model change; `k9-pages` lists it for the owner).
- j1 N13 (财务 rows: k6).

## 3. Item 5: coverage by fixture

**How I got there:** I replayed j2's journey from its own scripts on the current jar, with two changes: category 21, and **按核销付费 ¥5/人** terms instead of 引流.
- It ran to the end with no fixture past any gate.
- The time shifts and env fixtures (3% `settlement_rule` rows, `cms_setting` 1, the H8 migration) are listed in `k9-walk-readbacks.txt`.
- The settlement shift is now 16 days, because the hold is 14 days.

| Page / state | Result | Shot / read-back |
|---|---|---|
| Club session pages: director, gather, start, broadcast, chapter release, end, recap; topic-detail of an ended session | reached; all PASS. N-01 is fixed (管理 now shows 发邀约), and N-02 and N-04 are better than in j2's pass | `k9-11`; j2 replay shots |
| Roles (角色与权限) | 周小雨 assigned 副主理人 (`club_role_assignment` 1). The picker shows sign-up names; the owner row shows the nickname | `k9-03` |
| Governance: ban and record, transfer | ban 陈小满 30 days; transfer 990005 → 990006, then 990006 → 990007 with the new picker | `k9-02`, `k9-01`, `k9-08`…`k9-10` |
| Club customers | 4 real buyers by sign-up name. The refunded customer shows ¥0 and 已退款 1, which is right after the refund | `k9-05` |
| Merchant 客户 with a co-op sale | **empty, by design.** The merchant CRM only takes club buyers for `product_type 2` themes; this was a city-orienteering session | read-back |
| Settlement with money | session 9: 4 × ¥39. Each ticket = 1.17 platform (3%) + 5.00 merchant fee + 32.83 主理人, and totals 156 = 4.68 + 20 + 131.32, to the fen. After the ¥39 refund: 117.00 / 3.51 / 15.00 / 98.49, with clawbacks −1.17 / −5.00 / −32.83. Merchant 9002 balance 15.00 | `k9-06`, `k9-07`, `settlement_detail` rows in read-backs |
| Template full states | preview of 990016: 「2 章节 · 3 节点 · 约 45 分钟」 and three game cards; detail; 应用 → editor | `k9-17`, `k9-14` |
| game-node with data | station 1 (merchant 9002, CLOSED) with real recap numbers | `k9-16` |
| coop settlement-detail | **not reachable.** Per-player merchant fees are paid through `settlement_detail` rows (k5), and `coop_settlement` stays empty for club sessions. Only a theme-level co-op settlement would fill this page | read-back: `coop_settlement` empty |
| ledger batch-detail | **not reachable.** The rig has no 对公结算 batch path (same as p2) | — |
| predict with data | **not reached.** None of the journey's stations is a 预测 game, and building one is a new theme. Not done | — |

**Findings from these walks (reported, not fixed: other lanes' areas):**
- **Money (k5/k6):** 俱乐部结算's 「商家计酬 0 人次 · ¥0.00 · 还没有计酬核销记录」 card sits above a session row that says 「商家核销费 ¥15.00」. The two contradict each other (`k9-06`).
- **Money (k5/k6):** the merchant's 账户收益 lists 「合作商家核销费 +20.00」 but not the −5.00 clawback in the month list, while 可提现 is ¥15.00 (`k9-07`).
- **Money (k5):** 王一舟 never checked in at the gather point but played every station, and was counted for the merchant fee. That fits "took part" if playing counts; k5 should confirm.
- **Station recap (game runtime):** game-node station 1 recap says 到站玩家 2 and 正常完成 0/0, although all four players answered station 1 (`k9-16`).
- **Still there from earlier checks:** F-22 (票夹 「生成中… · 今天过期」 after the end), F-23 (old owner gets 「欢迎加入」 after transfer), N-03 (报名名册 refund group shows 「城瘾用户」 for every payer), N-07 (chapter application list doesn't refresh after approval).

## 4. Names walked on the real screens (k3's fixes)

| Screen | Seen | Shot |
|---|---|---|
| Transfer confirm | 「转让主理人给 林一帆」, then 「转让主理人给 周小雨」: sign-up names, the same as on the member rows | `k9-01`, `k9-09` |
| Ban record | 「成员「陈小满」」 for the person banned as 陈小满: the record names the right person (p3 g04 is gone) | `k9-02` |
| Roles picker | 王一舟 / 周小雨 / 林一帆; the current delegation row is the owner's nickname | `k9-03` |
| Transfer picker | the former owner (default nickname) reads 「城瘾用户 · 11月5日 14:46 加入」; no 「成员#id」 | `k9-08` |
| Club page after transfer | owner card 「城瘾用户 · 主理人」 = the new owner's nickname (the owner rule: nickname everywhere). Member row 「城瘾用户 · …」 for the former owner | `k9-04` |
| Join request reject | 「拒绝「城瘾用户 · 2026-11-05 15:32 申请」…」 | `k9-12` |

The rig's MySQL runs in UTC. Times written with SQL `NOW()` (the ban record, join and apply times) read 8 hours early (HARNESS).

## Gates

At the head of my last push (see CI):
- `node chengyinhub-xcx/tests/lint/ui-integrity.js`: `UI-GATE-0:PASS`, no baseline entries added.
- `build-action-ledger.js --check`: PASS, 5728 controls. That is the same count as `k9-pages`' last rebuild; my last change only moved line numbers. `action-ledger.test.js`: 46/46.
- `chengyinhub-xcx/scripts/danger-confirm-gate.js`: `DANGER-GATE PASS`.
- **Mini unit tests touching my changed files:** all pass, including `club-governance-ui-contract`, `club-join-request-confirm-name-contract`, `club-topic-detail-status-truth`, `coop-invited-partner-project-view` and `r12-k9-org-leftovers`.
- **Backend:** every test named above is green locally (`mvn -pl chengyinhub-admin -am test -Dtest=…`). Not run: `MerchantCrmFreeExploreRosterMySqlIT`, which needs MySQL.

## Unverified

- Everything here is the rig, not a phone. Real fonts, keyboard, native pickers, toasts on a real device, and image hosts are not covered.
- p2 N-14 and the duplicate-scan part of N-17 were checked by unit tests only, not walked.
- The merchant 客户 list with a free-explore co-op sale was not walked, so tag changes are proven by the H2 test only.
- predict, coop settlement-detail and batch-detail with data: not reached (reasons above).

## CI

(filled in after the push)
