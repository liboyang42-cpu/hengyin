# Round 12, lane k3-org-pages: result

Branch `integ/launch-test-20261103`, worked on a private local branch, `git pull --rebase` before every push, no force-push.
Lanes k1, k2 and k6 pushed to the same branch at the same time.

Code commits from this lane:
- `39fc94a0` (template preview), `7228c97d` (sale-window test), `b9df1ecd` (names);
- `4661687d` (club/merchant/publish fixes, captions, red text under fields, ledger);
- `da7770d0` (join-request names, empty-template caption, two tests).

**Screenshots come from the cloud rig, not a phone.** Real jar (MySQL 8, Redis 7) and the real mini-program in headless Chrome at 390×844, both built from `da7770d0` (except `k3-09`, from `4661687d`). The rig is `claude/cloud-walk-20261101-rig` with the club lane's `tools/harness-patches.diff`. I looked at every shot I name. Rig fixtures and read-backs are in `fix-shots/k3-walk-readbacks.txt`. All names are fictional.

No migrations. No new endpoints. No gated `@Test` added or removed (`ci/funds_it_gate.py` unchanged). Main package: the changes are small edits to existing main-package files (`utils/danger-actions.js`, `utils/group-code-session.js`, `components/cy/modal-host`, `pages/club/*`, `pages/publish/*`, `pages/topic/*`, `pages/coop/*`, `pages/merchant/*`). Net bytes are about −1 KB (captions removed). I did not run preflight's size count.

## Summary

| # | Item | Status |
|---|---|---|
| 1 | Names (p3 N4, j2 F-08 leftovers, p2 N-6, j1 N12), club owner = nickname | done, with one limit (see below) |
| 2 | p3 N3 template preview reads real data | done |
| 2 | Remaining p3, p2 (N-7, N-8, LOW), j1 LOW | partly done; list below |
| 3 | Sale period of club session tickets | confirmed in code, test added, no change needed |
| 4 | f2 leftover captions; j1 F11 pink error blocks | done |
| 5 | p3/p2 "not covered" reachable by a fixture | not done |

## 1. Names

**Rule now, in one helper** (`chengyinhub-system/.../util/OrganiserPersonName.java`):
- Sign-up name (only where the viewer may see it, as before: people who govern the club; a shop only for customers it is responsible for contacting).
- Otherwise the nickname.
- If the nickname is the default 「城瘾用户」, it gets the moment the person joined or signed up, e.g. 「城瘾用户 · 11月5日 13:30 加入」. Never 「成员#id」.
- The club owner is shown by nickname everywhere (`IClubService.ownerDisplayName`). `club.leader_name` (the name typed when applying) stays in the database for platform review and is no longer sent to the app.

**Where it now applies:**
- Club: member list, role picker, governance picker and transfer confirm, contribution board, join requests (new), 报名名册 (new; was nickname only), ban records (new; p3 saw 「林一帆」 banned as 「玩家小城」), customers and check-in detail, session roster, director page. Mapper fallbacks `concat('成员#', id)` removed in `ClubCrmMapper`, `ClubEventOpsMapper`, `ClubLeadMapper`, `CoopGroupVerifyMapper`. Front fallbacks 「成员 #id」 removed in governance, roles and director pages.
- Owner name: club list/detail (`attachClubDisplay`), project home rows, merchant aggregate rows, co-op partner card.
- Shop: 客户 shows the sign-up name for customers the shop is responsible for contacting and has the sensitive-info permission for (same rule as the phone). Others: nickname, with last-visit time for default nicknames. 经营团队 staff with default nicknames get their join time; the remove confirm adds the role after the name (for example 「… · 店员」). The duplicated first line of that confirm was removed.
- Join requests: the card already has 「申请于 …」, so the name is not repeated there. The reject confirm adds the time: 「拒绝「城瘾用户 · 2026-11-05 13:18 申请」的入会申请?」.

**Limit.** Two people with the default nickname, no sign-up name and the same join minute still read the same. This needs a decision: **needs owner**. My default: keep it; it is rare, and anything more (a code, a phone tail) is either an id in disguise or personal data.

**Proof.** Tests: `OrganiserPersonNameTest` (incl. owner nickname, join-time fallback), `ClubCrmPrivacyTest`, `ClubEventAttendanceServiceImplTest`, `GameSessionOrganiserNamesTest`, `ApiClubLifecycleTest` (owner name = nickname, DB row not written), `ApiCoopControllerPartnerNodeTest`, `ProjectHomeVisibilityTest`, `MerchantCrmProfileNamespaceMyBatisTest` (H2 schema gets the `real_name` column). Rig over HTTP: see `k3-walk-readbacks.txt`. Shots `k3-01` (join requests), `k3-02` (reject confirm), `k3-04` (role picker), `k3-03-governance-picker`, `k3-03b-governance-record`.

**Unverified:** the merchant 客户 list and 经营团队 with real staff and buyers were not walked in the rig (needs a co-op sale fixture); covered by unit and MyBatis tests only. The rig's MySQL is UTC, so the times on these shots read 8 hours early (HARNESS).

## 2. p3 N3 and remaining findings

**p3 N3, done.** `pages/templatedetail/topic-preview/index.js`. Card backs no longer use fixed per-type steps (「读档案 · 做任务，拿证据」, 「三选一结局 · 领取勋章」, 「随时开始 · 玩满 3 次通关」); they show the game's title and time only. The ✓ list now comes from the template: chapter count · node count, time, named medal. 「N 关剧情，每关一个玩法」, 「最后一关三选一结局」, 「一个人就能玩」 and 「随时开始，可以跨天」 are gone. Test: `template-shelf-redesign-20261028.test.js` (2 章 3 节点 template → 「2 章节 · 3 节点」, no 结局 claim). Shot `k3-09`: a template with no games now shows no claims at all (it used to say 「1 个玩法，一个人就能玩」). The cover is blank because the rig has no OSS (HARNESS).

| ID | Status | What changed | File | Proof |
|---|---|---|---|---|
| p3 N5 | fixed | 团码 skips the theme's self-host shell (no phantom 「10:18」, no auto-open of an unusable one); 报名名册 drops an empty theme-level ticket row once sessions exist | `utils/group-code-session.js`, `ApiClubController.topicRegistrations` | `r12-k3-club-publish-fixes.test.js`; roster not walked |
| p3 N6 | fixed (part) | Editor keeps 「看上去选中」 and the submitted club id the same after a template draft loads; checked again before publish | `pages/publish/fabu/index.js` (helper at file end so UI baseline anchors don't move) | `r12-k3-club-publish-fixes.test.js`. **Not done:** copy drops category and tickets; repeated 应用 makes more drafts |
| p3 N7 | fixed (part) | Confirm now one line with the amount: 「原路退还 ¥39.00，对方从本团名册移出。此操作不可撤销。」; removed 「已核销订单不可退款」 under the button | `utils/danger-actions.js`, `pages/club/checkin-detail` | danger/checkin tests. **Not changed:** the chip 「已退款」 next to the backend message 「…尚未确认到账」: F07 tests require showing the backend's read-back text; the chip reflects the order, the message the channel |
| p3 N8 | fixed | 开一场 defaults to the next hour + 1 (Shanghai clock); after 21:00, tomorrow 09:00 | `pages/club/event-ops/index.js` | test in `r12-k3-club-publish-fixes.test.js`; shot `k3-06` |
| p3 N11 | fixed | Removed on 开一场: 「仅可修改无报名的未来场次」, 「按重复方式批量生成」, 「最多排未来 90 天（64 期）」, 「满员后按报名顺序候补」, the capacity explanation, 「当前为 X。」, 「空位为候补首位保留…」, 「先发布主题或完成商家合作」. Refund rule became the 集合时刻 row's value 「集合前 24 小时停止退款」; claim duration shows as the row's value 「1 天」. 容量 is hidden for multi-ticket themes (new read-only `sessionTicketCount` from `ClubSessionServiceImpl.applySessionTicketFacts`). 确认终价: 「终价不可低于此值」 and the empty 「样本不足」 box removed. 团码: 「团码仅限本场次；组队邀请码不能核销」 removed. 发布活动 / 商家报名: 「用于地图与到场导航，拒绝不影响填写」 removed. club/detail 开一场 hint removed. 角色: 「全部权限，不可撤销」 removed. 入会拒绝: two helper lines removed. 合作链接已失效: the repeated sub removed | event-ops, pricing, group-code, publish/activity, merchantapply, club/detail, roles, danger-actions, pricing/partner | shots `k3-06`, `k3-02`, `k3-04`; tests updated (`merged-copy-groups-contract`, `club-event-ops-series-title-contract`) |
| p3 N14 | fixed | Success message stays 2.5 s before going back | `pages/topic/pricing/index.js` | — |
| p3 N17 | fixed | 「请填写玩法看点」 | `pages/publish/temp/index.js` | — |
| p3 F11 | fixed | 历史起草记录 with nothing to show says 「还没有起草记录」 | `pages/publish/simple/index.js` | not walked |
| p2 N-7 | fixed | After review ended with no merchant opinion: 「商家未提交意见」 instead of 「等待商家意见」 | `pages/merchant/aftercare/detail/view-model.js` | test in `r12-k3-club-publish-fixes.test.js` |
| p2 N-8 | fixed | Missing reason: red text 「请写拒绝理由」 under the field, page scrolls to it and focuses it; same for 撤回 | `pages/coop/invite-detail` | `r4-input-error-under-field-sweep` updated; not walked |
| p2 N-10 | fixed | Red text under field; 「请选择承接地点」 | `pages/topic/merchantapply` | — |
| p2 N-14 | part | Removed 「需要对齐事项时，请点击上方…」 (wrong direction, helper line) | invite-detail | test updated |
| p2 N-17 | part | Remove confirm: duplicated first bullet removed, full-width punctuation | danger-actions | — |
| j1 N5, N9 | fixed | see captions / red text | publish/activity | shot `k3-08` |
| j1 N6 | fixed | After 发布 the merchant lands on 我的项目's 活动 tab | publish/activity | `publish-activity-merchant-host.test.js` |
| j1 N10 | part | 「已加入经营团队」 no longer in error red; caption 「岗位调整或离职由店主操作，立即生效。」 removed | merchant/team | — |
| j1 N12 | fixed | see names | merchant/team | — |
| j1 N14 | part | Cancel confirm no longer restates the rule line; punctuation fixed | danger-actions | — |
| j1 「先创建节点玩法，再回来选择。」 | fixed | removed | publish/activity | — |

**Not done (with reason):**
- p3 N9 (transfer feedback; one picker drives ban and transfer), N10 (「¥ x」 text built by the settlement backend; k1's area), N13 (reject in three taps), N16 (群发 inactive counts), N18 (may be the rig), N19 (fixture-only lead).
- p3 N12 and N15: **needs owner** (which phone, if any, organisers see; light pages in the lead's dark flow). Default: leave as is.
- p2 N-9 (wrong role / guest leftovers on merchant pages), N-11 (early redeem; money rule, k1), N-12 (server should refuse an invite without `shareMode`: the controller comment pins it as optional for back-compat; I did not change a write contract without checking every caller), N-13 (coupon form), N-14 rest (评价 timing, reply thread, sender name), N-15 (AI 形象 status), N-16 (tags), N-17 rest.
- j1 N7 (limit heading), N8 (verifier id: data model), N11 (apply intro layout), N13 (财务 rows; k1's money screens), 「长按卡片管理」 (needs a ··· on the card), 「授权状态待确认，可点击申请」.

## 3. Sale period of club session tickets

Confirmed, no change. Path:
1. 开一场 copies each theme ticket to a session ticket: `OmsTicketServiceImpl.copyTopicTicketsToActivity` → `newActivityTicket`, with `projectedStartTime/EndTime` = the session's own start and end for a club session (`activity.clubId != null`). `selfGuided` is not copied, so club session tickets are never self-guided.
2. Buying: `CmsRegistrationServiceImpl` refuses an owner_type 2 ticket once `ticket.startTime` has passed: 「该场次已开始，不能再报名」. Earlier in the same path, `SaleAvailabilityPolicy` also ends sales at the theme's `saleEndAt`, or earlier at the ticket's own `signupDeadline`.
3. The app uses the same rule (`utils/ticket-window.js`): a session shows 「场次已开始」 from its start.

So each session stops selling when it begins, from its own ticket. One note: `signupDeadline` is copied as an absolute time from the theme ticket. If an author sets one, a later session would stop at that time. The editor no longer sets it (成团 removed 2026-07-15), so today it is empty.
Test: `ClubSessionTicketSaleWindowTest` (new) plus the existing `RegistrationTicketWindowGateTest`.

## 4. Pink error blocks (j1 F11, j1 N9, p2 N-10, j2 F-20)

Form validation now uses the kit's red-text-under-field style (`cy-inline-error kind="field"`, no fill, no icon) on 发布活动 (10 fields), 商家报名 (5), 玩法模板 basics (title, 看点, category, interaction), and the shared input dialog (`components/cy/modal-host`, which covers 交接原因 and other required-reason dialogs). The 「清除」 buttons inside those errors are gone (7 controls). Operation failures, such as 「终价确认失败」, keep the filled block. 确认终价's team size already used red text. Shot `k3-08`.

## Gates

At `da7770d0` (after rebasing on k1/k2/k6):
- `node tests/lint/ui-integrity.js`: `UI-GATE-0:PASS`. No baseline entries added; fabu's line anchors were kept by putting the new helper at the file end.
- `build-action-ledger.js --check`: PASS, 5716 controls (5723 → 5716 from the 7 removed 「清除」 buttons; event contracts 2774 → 2767). `action-ledger.test.js` 46/46.
- Mini unit tests touching changed files: 346 files run together, all pass except `sc-ui-date-count-probe.js`, which fails the same way without my changes (it is a probe, not a test).
- Backend tests listed in section 1 plus `ClubSession*`, `ClubEventOps*`, `ApiClub*`, `MerchantCrm*`: green locally (`mvn -o -pl chengyinhub-admin test -Dtest=…`). `ClubCrmCheckinDetailMyBatisTest` needs a MySQL the local run doesn't have; not run.
- CI: the PR #723 workflows on `4661687d` (my main commit): Mini followup regression success, Isolated Redis recovery success, Funds MySQL IT success; GitHub CI cancelled by a newer push. CI on `da7770d0` and later is still running at the time of writing. The CI result for the final commit is not confirmed here.

**For the root:** k2's new `docs/delivery/walk-fix-20261102/integ/k2-club-post-contact-scan.sql` makes `ci/migration_ledger.py` refuse the SQL inventory (`UNMANAGED_SQL_LOCATION`), so `build_schema.py` fails. That stops the rig and probably the schema-based CI jobs. I removed it only in my rig's candidate worktree. It needs a reviewed classification or a move.

STATUS: PARTIAL - items 1, 3 and 4 done; item 2 done for N3 and the higher findings except N6's lost category/tickets, with several LOW findings listed as not done; item 5 not done; CI on the final commit not confirmed
