# C1 channels-structure — backend, admin console and mini-program receipt

Branch `feature/r-channels-structure` (hengyin), based on `origin/roadmap/be-integ` (81b65553d).
App half: `chengyin-app` branch `cloud/r-channels-structure` (see its `docs/roadmap/channels-structure/RESULT.md`).
Scope: channel **structure** only. Messages and real-time delivery are C2 (channels-messaging); events posted in channels are C3 (channels-events).

## Step 1 — inventory (what a Discord-like server needs, and who had it on 2026-11-15)

Legend: ✅ present before this package · 🆕 added here · ➖ not applicable / not needed · ⚠️ partial, see note.

| Structure item | Backend | Admin console | Mini-program | App |
|---|---|---|---|---|
| Create text channel | ✅ `POST /api/guilds/{kind}/{id}/channels` (kind 1) | ➖ owners do it | 🆕 `pages/club/channels` | ✅ `NativeChannelManagementEditorView` |
| Create announcement channel | ✅ kind 2 | ➖ | 🆕 | ✅ |
| Create event channel | ⚠️ no separate kind: clubs get a 活动 text channel by default (club-reach); event cards are C3's | ➖ | 🆕 (text channel) | ✅ (text channel) |
| Categories (create) | ✅ kind 0, one level | ➖ | 🆕 | ✅ |
| Ordering / reorder | 🆕 `POST /api/guilds/{kind}/{id}/channels/reorder` (was missing: `position` was only set on create) | 🆕 read-only position + category columns | 🆕 上移/下移 | 🆕 `NativeChannelReorderView` |
| Move channel between categories | 🆕 same reorder endpoint (`parent_id`) | 🆕 shows category | 🆕 移到 | 🆕 |
| Settings: name, topic, slow mode | ✅ `PATCH /api/channels/{id}`; 🆕 `POST /api/channels/{id}/edit` alias (wx.request has no PATCH) | ➖ | 🆕 频道设置 | ✅ |
| Settings: private/public | ✅ `visibility` + @everyone VIEW overwrite | ➖ | ➖ (app only; not in the mini-program brief) | ✅ |
| Archive / unarchive | ✅ `POST /api/channels/{id}/archive` | ✅ list shows status | 🆕 | ✅ |
| Delete / restore (7 days) | ✅ | ➖ | ➖ (left to the app; destructive) | ✅ |
| Roles = club roles | ✅ owner = `club.member_id`, admin = `club_role_assignment` (`ChannelGuildAccess.standing`), member = `club_member`; team leader/member for teams | ✅ club governance | ✅ `pages/club/roles` | ✅ `NativeChannelRoleEntry` |
| Custom roles + member/role sets | ✅ `/api/im/roles*`, `/api/im/members/{id}/roles` | ➖ | ➖ (club roles page covers the mini-program) | ✅ |
| Per-channel view/post overrides | ✅ `PUT/DELETE /api/channels/{id}/permissions/{type}/{target}` | ➖ | ➖ | ✅ `NativeChannelManagementPermissionsView` |
| Announcement: members cannot post | 🆕 enforced on the server (was app-UI only; open club-reach question) | ➖ | 🆕 shown as "仅主理人和管理员发言" | ✅ UI; now backed by the server |
| Invites and join links | ✅ club share link (`/pages/club/detail/index?id=`), friend channel invites `/api/friend/channel-invite/*`, `/api/invite/revoke`, join requests | ➖ | ✅ club share; 🆕 "邀请朋友" on the channel page (same club link) | ✅ `ClubShareSheet`, channel invite entry |
| Member list | ✅ `/api/channels/{id}/members`, `/api/club/members` | ✅ | ✅ club detail | ✅ |
| Leave | ✅ `/api/club/quit`, `/api/space/leave` | ➖ | ✅ | ✅ |
| Kick / ban / timeout | ✅ `/api/im/moderation/{timeout,kick,ban}`, `/api/club/remove-member` | ✅ 频道治理 (mute, remove, revoke) | ✅ 成员治理 | ✅ |
| Channel op log / audit | ✅ `/api/channels/{id}/op-log` (now also MOVE lines) | ✅ | ➖ | ✅ |
| Where clubs attach channels | ✅ guild kind 1 = club; defaults 闲聊/公告/活动 seeded on create/approval + backfill job | | | |
| Where teams attach channels | ✅ guild kind 3 = play team (`play_team`) | | | |
| Where the game attaches channels | ✅ `InGameChannelService` (`activity_`, `topic_`, `roam_<district>`, `city_team_`, `team_` conversations, `/api/im/ingame/*`); these are IM group conversations, not guild channels, so they have no categories/ordering by design | | | ✅ `InGameChannelEntryViews` |

## Step 2 — what changed (backend repo)

### Rules applied
- Club owners and admins (and team leaders) create, edit, reorder and archive channels: all of these need `MANAGE_CHANNEL`, which only OWNER/ADMIN standing (or a role carrying it) has. Unchanged for create/edit/archive; the same check guards the new reorder.
- **Announcement channels (kind 2): members read but do not post.** Owners, admins, `ADMINISTRATOR` roles and roles with `MANAGE_CHANNEL` post. An owner/admin can still let a chosen custom role post with a per-channel *role* overwrite; an `@everyone` overwrite cannot re-open posting. Members keep reactions and history.
- Invites reuse the existing club invite/share link and friend channel invites; no new invite type.
- Error contract: every new refusal goes through `ChannelRejection` → `ChannelHttpResponse` (HTTP status == body code, `errorCode`, `outcome_known`), the same envelope as the other channel endpoints.

### Files
| File | Change |
|---|---|
| `chengyinhub-system/.../channel/platform/ChannelPermissionCalculator.java` | `ANNOUNCEMENT_MEMBER_DENY` (SEND, ATTACH, EMBED, MENTION_EVERYONE, CREATE_POLL); new kind-aware `effective(..., status, kind)` overload; the old overload delegates with kind −1 (no change for callers that do not know the kind). |
| `.../ChannelGuildAccess.java` | `effective(channel, …)` passes the channel kind, so every send/attach/interaction gate (`ChannelMessageService.authorize`, chain/check-in, webhooks preflight) applies the rule. |
| `.../ChannelPlatformService.java` | Directory passes the kind (so `my_permissions_list` drops `SEND_MESSAGE` for members in 公告). New `reorder(...)`: `Command` shell (request_id, reason == `X-Audit-Log-Reason`, unknown fields refused), receipt family META (replay returns the original), locks rows in id order, optional per-item `expected_version` CAS, categories stay top level, parent must be a live category of the same guild, default channel stays at 0, others 1..100000, ≤200 items, unique ids; `CHANNEL_NAME_TAKEN` on a name clash in the target category; one `MOVE` op-log line per moved row. |
| `chengyinhub-system/.../mapper/channel/platform/ChannelPlatformMapper.java` + `.xml` | `updatePlacement` (parent/position, `meta_version` CAS, live rows only). |
| `chengyinhub-admin/.../api/ApiChannelPlatformController.java` | `POST /api/guilds/{guildKind}/{guildId}/channels/reorder`; `POST /api/channels/{id}/edit` (POST alias of PATCH for the mini-program). Nothing removed or renamed. |
| `chengyinhub-system/.../opsconsole/ChannelModerationAdminMapper.xml` | Channel search also returns `parentId`, `position`, `isDefault`; ordered as a tree when a space id is given. |
| `chengyinhub-ui/src/views/business/channelmoderation/index.vue` | 频道 tab: 类型 (分类/文字/公告/话题, 默认), 所在分类, 顺序 columns. Read-only; no new styling. |
| `chengyinhub-xcx/pages/club/channels/*` (new) | Club channel page. Members: directory grouped by category, 公告 marked "仅主理人和管理员发言", archived "只读", unread counts, tap opens the existing IM chat page. Owners/admins (decided by the server: `/channels/manage` answers 403 to others): new channel or category (text/announcement/category, category, topic, slow mode), 设置 (name/topic/slow mode, version-checked), 归档/取消归档 with confirmation, 调整顺序 (上移/下移, 移到 another category). "邀请朋友" shares the club link. Uses existing `cy-*` components and the `pages/club/roles` row styles. |
| `chengyinhub-xcx/utils/club-channels.js` (new) | Pure helpers: sections, placement planning (mirrors the app planner), request bodies, ASCII audit reason, error texts. |
| `chengyinhub-xcx/app.json`, `pages/club/detail/index.{wxml,js}` | Register the page; "频道" entry on the club overview for owners and joined members (same row style as 俱乐部勋章). |
| Tests | `ChannelStructureTest` (19), `ChannelMessageServiceTest.announcementChannelRefusesMemberPostsButNotOwnerOrAdmin`, `chengyinhub-xcx/tests/unit/club-channels.test.js` (10). |

No migration: no schema change. `MOVE` is already allowed by `ck_channel_op_action`, receipts use the existing `META` family. Nothing added to `ci/migration-dependencies.json`.

### Member data and erasure
This package stores no new member data. The reorder writes channel rows, the existing op log (`operator_id`, as every channel write already does) and the existing receipt table. `MemberPrivacyErasureService` does not cover `im_channel_op_log` / `im_channel_request_receipt` operator ids today; that is pre-existing and unchanged here (see open questions).

## Verification
VERIFICATION_PLACEHOLDER

## Not verified
- No MySQL here: `updatePlacement` and the extra console columns were not run against a real schema (the column names come from `migration_20261105_l8_channel_lifecycle.sql`).
- The mini-program page was not opened in WeChat DevTools or on a phone; only its logic is unit-tested. The chat page is opened with `conversationId` = channel id; how that page renders a channel conversation (type 5) is C2's area.
- The admin console was not built or clicked through (no `npm ci` here); the change is two template columns and one method.

## Owner actions and open questions
1. **Announcement rule.** Enforced as decided in the brief. Confirm that a per-channel role overwrite may still grant posting to a chosen role (e.g. "通知员"). `@everyone` cannot re-open it.
2. **Event channel.** There is no separate "event" channel kind; the club's default 活动 text channel is where C3 posts event cards. Say if you want a dedicated kind (kind 3 is reserved as TOPIC in the shared App spec).
3. **Erasure of operator ids** in `im_channel_op_log` and `im_channel_request_receipt` (pre-existing for every channel write): decide whether deletion should unlink them.
4. Not in this base and not needed: `feature/extgame-money`, `feature/game-types`, `feature/sys-weather`. In-game channels are already in `roadmap/be-integ`.
5. No hard-coded secrets found in the files touched.
