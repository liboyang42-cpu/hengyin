# R10 admin-consoles — result

Branch: `feature/r-admin-consoles` (hengyin), based on `origin/roadmap/be-integ` (5eb0ae1d7).
Goal: operations can run what the backend already supports without SQL.

## Findings

| ID | Status | What now exists |
|---|---|---|
| BE-21 (P1) reward drops had no rule editor | **Closed** | 掉落规则 console: list, create, edit, enable/disable, validation that matches the runtime, audit trail |
| BE-22 (P1) pledges and non-cash rewards had no policy rows or review queue | **Closed** | 奖励承诺审核 console: policy editor + platform review queue (approve, reject, note) |
| BE-23 (P1) channels had no platform moderation | **Closed** | 频道治理 console: search channels and messages, reports, hide/restore message, mute, remove from space, revoke, audit trail |
| BE-24 (P2) journey rules and state keys had no editor | **Closed** | 旅程规则 console: structured editor, server preview with normalized JSON, state-key create/edit/delete |
| BE-25 (P2) no view of roam sessions, fog, teams or play runs | **Closed** | 漫游与游玩运营 console: read-only lists and totals with filters, no tracks or positions |

## Shared design

- **Audit.** Every console write appends one `business_audit_event` row (`domain_type=PLATFORM`, `actor_type=SYS_USER`, before/after JSON, reason). The row is written in the same transaction as the change: if the audit write fails, the change rolls back. Rows show up in the existing platform audit page (`/business/audit-log`). RuoYi `@Log` (`sys_oper_log`) is also on every write endpoint.
- **Permissions.** Five pages and seven buttons are registered in `sys_menu` by the migration. The migration grants no role. The `@PreAuthorize` strings match the migration exactly.
- **Money.** Nothing here pays. Reward pledges are coupon-backed and have no cash value. Cash kinds are still refused at draft time by the existing `CashPayoutSwitch` (off by default). The pledge console shows the switch state read-only and never changes it.

## Changes, file by file

### Backend: `chengyinhub-system`
- `business/opsconsole/OpsConsoleAudit.java` (new): appends the PLATFORM / SYS_USER audit row.
- **BE-21**
  - `mapper/RewardDropRuleMapper.java` and `.xml`: add `selectAdminList`, `selectById`, `insertRule`, `updateRule`, `updateStatus`. The runtime `selectApplicableRules` is unchanged.
  - `opsconsole/RewardDropRuleAdminService.java` (new). Validation:
    - scope is 0, 1 or 2; a topic rule needs a topic id; a scene rule needs a known scene (`topic_node`, `roam_merchant_poi`);
    - type is 1, 2 or 3; weight is 1–10000;
    - the parameter JSON keeps only the field for its type: `mult` 2–10, `badgeCode` matching `[A-Za-z0-9_-]{1,32}`, or `couponTemplateId` > 0. Unknown fields are refused.
  - New rules are saved disabled. A broken stored rule cannot be enabled until it is edited.
- **BE-22**
  - `mapper/rewardpledge/RewardPledgeAdminMapper.java` and `.xml` (new): policy list and upsert, review queue, `freezeByPlatform`, `rejectVersion`, review notes.
  - `service/rewardpledge/RewardPledgeAdminService.java` (new):
    - The policy editor accepts only codes that `RewardPledgeService#open` reads, plus the new `PLATFORM_REVIEW`. `RULE`, `CLAIM` and `MINOR` need a rule version. The app reviewer (`REVIEWER`) needs a member id. Every change needs a note.
    - **Platform review is closed by default.** Approve and reject work only when the `PLATFORM_REVIEW` policy row is OPEN. Approve also needs the chosen `RULE` version to be OPEN; the app review path uses the same check.
    - Approve freezes the version, creates its stock and sets the pledge to FROZEN. Reject sets the version and the pledge to REJECTED. Both lock the version and refuse with 409 when it is not the latest confirmed version.
  - `service/rewardpledge/RewardPledgeService.java`: **one-line change**. `revise` now also accepts a REJECTED latest version, so a merchant can fix and resubmit. This is the only shared file changed.
- **BE-23**
  - `mapper/opsconsole/ChannelModerationAdminMapper.java` and `.xml` (new).
  - `service/channel/platform/ChannelModerationAdminService.java` (new):
    - **Hide** sets the message to status 0→2 (the existing 违规屏蔽 status), adds a version, writes a `RECALL` mutation so clients sync, and resolves the message's pending reports. **Restore** reverses a hide (2→0) and writes a `RESTORE` mutation.
    - **Dismiss** closes a report.
    - **Mute** is a channel `TIMEOUT`, from 1 minute to 28 days.
    - **Remove from space** is a space `BAN`, from 1 minute to 90 days; for a team it also ends team membership. **Club membership is kept**, because it involves fees and benefits; ending a club membership stays in the club-governance console. The club owner and the team leader cannot be removed.
    - **Revoke** cancels an active action.
    - All actions use the existing `im_moderation_action` table and the existing send gate, so a platform mute behaves exactly like a space admin's. `operator_id=0` shows as SYSTEM in the space audit chain. The real admin account is recorded in `business_audit_event`.
- **BE-24**
  - `mapper/opsconsole/JourneyRulesAdminMapper.java` and `.xml` (new): topic search, and a compare-and-set write of `journey_rules_json`.
  - `opsconsole/JourneyRulesAdminService.java` (new). It reuses the author-side code:
    - `JourneyRulesValidator` for rules;
    - `ThemeStateKeyService` for checking and writing state keys;
    - `TopicStateKeyRefService.referencesOf` before a delete, and `collect` for the preview's list of unregistered keys;
    - `TopicEditWindow` for the edit window.
  - Guards:
    - rules save only when the topic's edit window is FULL (draft or not yet approved);
    - state keys cannot be changed once the topic is selling (same rule as the author side);
    - `attributeBinding` and `attributeDefinition` are frozen derived blocks: the console always keeps the stored values and ignores submitted ones.
  - Preview never writes.
- **BE-25**
  - `mapper/opsconsole/RoamPlayOpsMapper.java` and `.xml` (new): roam sessions with totals, fog cell counts per member with totals, teams with member counts, player run progress, node game runs.
  - The queries never select `track_json`, coordinates, `cell_key`, `geohash7` or `state_json`; a unit test guards this.
  - `opsconsole/RoamPlayOpsService.java` (new): ISO date filters and a 200-row cap.

### Backend: `chengyinhub-admin`
- Controllers (new, under `web/controller/business/`):

  | Controller | Route | Permissions |
  |---|---|---|
  | `RewardDropRuleAdminController` | `/business/rewardDropRule` | `business:rewardDrop:list/add/edit` |
  | `RewardPledgeAdminController` | `/business/rewardPledge` | `business:rewardPledge:list/review/policy` |
  | `ChannelModerationAdminController` | `/business/channelModeration` | `business:channelModeration:list/message/member` |
  | `JourneyRulesAdminController` | `/business/journeyRules` | `business:journeyRules:list/edit` |
  | `RoamPlayOpsController` | `/business/roamOps` | `business:roamOps:list` (read-only) |

- `sql/migration_20261115_admin_consoles.sql` (new):
  - `r10_reward_pledge_review_note` table (`IF NOT EXISTS`);
  - 5 C-menus and 7 F-buttons, each guarded by `NOT EXISTS(perms)` and placed under a resolved top-level directory (fail-closed when the directory is missing or not unique);
  - a rollback note at the top and a read-back assertion at the end.
- Tests (new):
  - `opsconsole/RewardDropRuleAdminServiceTest`
  - `opsconsole/JourneyRulesAdminServiceTest`
  - `opsconsole/RoamPlayOpsPrivacyContractTest`
  - `opsconsole/OpsConsoleMapperXmlTest`: every new statement parses, every mapper method has SQL, and every statement passes the production Druid wall
  - `service/rewardpledge/RewardPledgeAdminServiceTest`
  - `service/channel/platform/ChannelModerationAdminServiceTest`

### Admin console: `chengyinhub-ui`
- API modules: `src/api/business/{rewardDrop,rewardPledge,channelModeration,journeyRules,roamOps}.js`.
- Pages, which follow the existing RuoYi and `Cy*` patterns (`CyPageHeader`, `CyEmptyState`, `CyResourceState`, `CyStatusTag`, `CyMetricCard`, Element UI) and add no new visual styling:

  | Page | Menu path |
  |---|---|
  | `views/business/rewarddrop/index.vue` | 数据与配置 › 掉落规则 |
  | `views/business/rewardpledge/index.vue` | 审核与风控 › 奖励承诺审核 |
  | `views/business/channelmoderation/index.vue` | 审核与风控 › 频道治理 |
  | `views/business/journeyrules/index.vue` | 供给与发布 › 旅程规则 |
  | `views/business/roamops/index.vue` | 俱乐部与漫游 › 漫游与游玩运营 |

- Routes come from the menus (`sys_menu` path + component); `router/index.js` is unchanged.
- Enums are shown as plain business words. The journey editor's number inputs use the validator's ranges.

## Verification

Run with `LANG=C.UTF-8 LC_ALL=C.UTF-8`.

- `mvn -B -q -DskipTests compile`: passes.
- `mvn -B -q -o -pl chengyinhub-admin -am test -Dtest='Channel*Test,RewardDrop*Test,RewardPledge*Test,ThemeStateKey*Test,JourneyRules*Test,OpsConsole*Test,RoamPlayOps*Test,RoamRewardDeliveryTest,Druid*Test' -Dsurefire.failIfNoSpecifiedTests=false`
  - **395 tests, 0 failures, 0 errors.** This includes the existing `RewardPledgeFlowTest` (16), the channel suites, `RewardDropRuleMapperSqlTest` and the Druid wall contracts.
- `DeadlockRetryEntryPointContractTest` and `BusinessAuditDomainTypeLengthTest`: pass.
- Admin console:
  - Dependencies: `npm ci --ignore-scripts --replace-registry-host=registry.npmmirror.com --registry=https://registry.npmjs.org/`. The lockfile points at npmmirror, which this sandbox's proxy blocks; the lockfile itself is not changed.
  - `node --test "tests/unit/**/*.test.js"`: **623 tests, 598 pass, 25 fail**. The same 25 tests fail on the untouched base `origin/roadmap/be-integ` (checked in a separate worktree, identical list), so none is new. They cover topic, ticket and node pages. The SFC template-compile contract, which compiles every `.vue` file including the five new pages, passes.
  - `node --test "tests/e2e/contracts/*.test.js"`: **36 / 36 pass**.
  - `npm run build:prod`: **Build complete**, exit 0.
  - The console has no lint script.

## Not verified

- **No MySQL here.** The new statements were checked for parsing, Druid wall acceptance and privacy columns, but they have not run against a real schema. The likeliest gaps are column names on older production tables (`club_member.del_flag`, `play_team_member.status`, `im_channel_report.status`); all of them were read from the migrations.
- **No browser.** The pages were not clicked through; they compile, and the production build covers imports.
- **The app side** of a platform-hidden message (status 2 plus a RECALL mutation) is assumed to behave like a recall, based on `ChannelMessageMapper` filtering `status = 0`. iOS was not checked.

## Owner actions and open questions

1. Run `chengyinhub-admin/sql/migration_20261115_admin_consoles.sql`, with the usual read-only pre-check, then grant the new menus to operations roles in 角色管理. Super-admin sees them right away.
2. **Platform pledge review stays off** until someone sets 平台审核 (`PLATFORM_REVIEW`) to OPEN in the console, and a `RULE` version must be opened before approval. Decide who owns those switches.
3. **A REJECTED pledge state is new.** The merchant can revise it, but the iOS and mini-program merchant screens may show it as an unknown state. Copy is needed in both languages, outside this package.
4. **Removing a club member from channels** is a channel-wide ban; club membership is kept. Confirm this split, or extend the club-governance flow to end membership.
5. **Journey rules:** the console refuses saves once a topic is approved or selling. Confirm whether operations should ever edit live rules; that would need an edition or versioning path.
6. No hard-coded secrets were found in the files touched.
