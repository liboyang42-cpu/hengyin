# Lane W6 — visibility/privacy fixes (#334 F2/F3/F4/F6 + private groups 10/15/24)

Branch: `feature/lane-w6-visibility-20261022` (from master `063a6c3c`). One draft PR.
Evidence: `disposition.md` (#334 F2/F3/F4/F6, branch `feature/lane-g-triage-20261022`) and
`private-groups.md` items 10, 15, 24 (branch `feature/lane-g2-private-groups-20261022`).
Implementations were ported from the #334 carrier (head `81382f28`) **hunk by hunk** onto current master;
none of #334's unrelated hunks (club apply/myApply reverts, conflict markers, IM conversation-list hunk) were taken.

No DDL, no new dependencies, no new migration. No C00-reserved mini files touched.

## Items

### 1. #334 F2 — product detail visibility (`ApiProductController#producInfo`)
- Not found (`errorCode=PRODUCT_UNAVAILABLE`, same message for every reason) when the product is off-shelf
  (`publish_status≠1`), deleted, or its merchant is missing / not approved (`status≠1`) / closed
  (`account_status≠1`) / deleted. The SKU and merchant reads do not run for hidden products.
- Response is a whitelisted map (no `createBy/updateBy/remark/...`); SKUs projected and foreign-product rows dropped;
  `detailHtml` passed through `HTMLFilter`.
- Test: `ApiProductDetailBoundaryTest` (3).
- Note: no mini/admin-UI caller of `/api/product/info` was found in this repo, so the response-shape change has no in-repo consumer.

### 2. #334 F3 + PRIVATE-GROUP-backend-club-post-reference (item 24)
- New `ClubPostReferenceGuard` (system `service/support`): `requireCitable` = target currently publicly visible
  (`selectPublicCmsActivityById` + parent topic / `selectCmsTopicDetailById(id, null)`) **and** the poster has a
  succeeded registration (`CmsRegistrationMapper.countSucceededJoin`, same rule as `/api/registration/my-joined`).
  400/403/404 map to `CLUB_POST_REF_INVALID / CLUB_POST_REF_NOT_JOINED / CLUB_POST_REF_UNAVAILABLE`.
- `/api/club/post/create` calls the guard (the existing 1/2-only and half-reference checks stay in the controller).
- `/api/club/post/list` (`projectPostReferences`) rechecks visibility anonymously; a hidden target sets
  `ClubPost.refUnavailable=true` with no title/cover projected.
- `/post/history` and `/post/comment/list` now apply `canViewClub` (F3 part b).
- Mini `pages/club/detail`: `normalizeClubPost` carries `refUnavailable` and suppresses both card variants; the post
  card shows a neutral `引用不可用` label in the `special` slot; create errors are mapped by `errorCode`
  (the reference is dropped, the text and images are kept).
- Tests: `ClubPostReferenceGuardTest` (7), `ApiClubPostPrivacyProjectionTest` (3), node
  `club-post-reference-unavailable.test.js` (5); the existing `club-post-reference-contract.test.js` still passes.
- Scope: did not touch club lead/edit-ops (W3) or calendar/editions (W10a).

### 3. #334 F4 + PRIVATE-GROUP-route-card-unavailable (item 15)
- `ImServiceImpl` route-card send now requires `selectCmsTopicDetailById(topicId, null)` plus
  `status=1, user_status=1, not deleted, not club-member-only` (the same `isPublicRouteTopic` predicate as the
  existing read projection). Failure message is generic `该路线暂不可用` (404). Any recipient can therefore open it.
- The persisted summary is `[路线]` (no topic name). The existing `projectRouteMessage` still derives the name at read
  time only while the topic is visible, so legacy `[路线] 名称` rows are already redacted on read.
- Mini IM chat: the null-state card says `路线暂不可用` / `活动暂不可用`, and the tap toast says `该路线暂不可用`.
  Neither ever claims the topic was delisted or made private, because a network failure also lands in null.
- Tests: `ImServiceImplTest` (send stores `[路线]`, plus a new deleted/unreviewed/members-only generic-reject case), node
  `im-route-card-unavailable-copy.test.js` (3).

### 4. #334 F6 + PRIVATE-GROUP-activity-collaborators (item 10)
- `publishActivity` writes the selected `collaboratorId`s (deduped, member must exist, failed insert throws), not the
  publisher id.
- `updateActivityByOwner`: when `collaborators` is sent (including an empty list), it runs
  `deleteActivityCollaboratorsByOwner` (the WHERE clause includes `exists cms_activity.member_id = owner`) and then
  re-inserts the list. When the field is absent (null), existing rows are kept. This runs only after the owner-scoped
  main-table update has hit a row.
- Mini `pages/publish/activity`: edit mode pre-fills the list from `collaboratorsList`. `getUserData` now only merges the
  owner row into first position and no longer overwrites the list, whichever response arrives first. The section is
  labelled `合作者仅作活动页署名展示，不获得编辑或管理权限`.
- Tests: `ActivityCollaboratorSaveTest` (4), `ActivityCollaboratorScopeMyBatisTest` (H2, 1), node
  `activity-collaborator-edit-prefill.test.js` (5).

## Verification
- Local targeted: `mvn -pl chengyinhub-admin test -Dtest=ImRouteProjectionTest,ImServiceImplTest,ClubPostReferenceGuardTest,ApiClubPostPrivacyProjectionTest,ApiProductDetailBoundaryTest,ActivityCollaborator*Test` → 80 tests, 0 failures.
- Local full `chengyinhub-admin`+`chengyinhub-system`: 12956 tests. The only error outside the MySQL ITs was
  `ImRouteProjectionTest.sendSuccessProjects…`, which pinned the old behaviour (a members-only topic could be sent and its
  name was stored in the summary). That is exactly what F4 removes, so the test was updated: send now uses the anonymous
  public gate, the stored summary is `[路线]`, and the read-back projection still differs. The remaining 25 errors are 10
  `*MyBatisTest` ITs that need MySQL (`CommunicationsException` in this sandbox). The authoritative result is GitHub CI.
- Local mini `npm run test:unit`: 13487 tests, 13486 pass, 1 fail. The failure is `action-ledger.test.js`
  「release 前重建 inventory」: the action ledger is a source-digest inventory, and this lane changes two control-bearing pages.
  The same test passes on master. The ledger is C00-reserved and must not be committed from a lane, so see the handoff below.
- `ci/xcx-check.sh` and the ds-hardcode self-test were not run (per lane rules).

<!-- CY-HANDOFF
owner: C00 (scripts/uiaudit/action-ledger.json is reserved)
reason: lane W6 edited pages/club/detail/index.{js,wxml,wxss} and pages/publish/activity/index.{js,wxml,wxss}
action: regenerate scripts/uiaudit/action-ledger.json with scripts/uiaudit/build-action-ledger.js after merging W6
expected drift (computed locally, not committed): controls 4947 → 4947; 217 control records re-digested
  (pages/club/detail/index.wxml 155, pages/publish/activity/index.wxml 62); no other top-level keys change.
  The im/chat edits did not change any ledger record.
-->

## Manual WeChat DevTools checks
1. Club detail → a post that cites an activity/topic that was later delisted or made members-only shows the grey
   `引用不可用` tag, with no title or cover. Posting with a reference to a record you did not join toasts the mapped text
   and drops the reference.
2. IM chat → send a route card for a public topic: OK, and the list summary shows `[路线]`. For an unreviewed or
   members-only topic, the toast says `该路线暂不可用`. A card whose topic is later hidden shows `路线暂不可用`, and tapping it toasts the same.
3. Publish activity → edit an existing activity that has co-hosts: the co-hosts appear under the owner row. Removing
   one and saving persists the change, and reopening the editor shows the new list. The hint text renders under the
   「合作者」 title (check light and dark).
4. The product detail endpoint has no mini caller. If one is added later, it must read the projected fields only.
