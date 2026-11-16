# W7 x-channel-safety — backend and mini-program receipt (hengyin)

Branch `feature/r-x-channel-safety` from `origin/roadmap/be-integ` (`63bc29f9d`). App side: chengyin-app `cloud/r-x-channel-safety`.
Review: `review/opus-w7:docs/review/opus/social/ISSUES.md` (PKG-C "Safety").

## Issues

| ID | Status | Notes |
|---|---|---|
| SOC-4 | Fixed (backend, MP; app in chengyin-app) | One send gate for send, react and typing; group and channel pages flag messages from senders the viewer blocked; MP collapses them. |
| SOC-10 | Fixed | MP opens channels as `type=5`; group/channel menus pick a specific message or author; composer no longer locked by an unrelated block; 公告 / archived channels show the real reason. Backend computes `blocked`/`blockedByMe` only for one-to-one conversations. |
| SOC-14 | Fixed behind switches (step 1 waits for owner and counsel) | Chat text scrub on account deletion (`chengyin.erasure.im-messages`, default `SCRUB`), operator ids (`chengyin.erasure.channel-operator-ids`, default `SCRUB`), purge job for deleted channels (registered paused). |

All cited `path:line` were re-checked on `be-integ`; none was already fixed. SOC-13's per-member UPDATE was already gone (x-channel-reach), so the `send` extraction below works on that newer code.

## Changes, file by file

**SOC-4 / SOC-10 (server)**
- `chengyinhub-system/.../service/IImService.java`: new `requireSendable(memberId, conversationId)`.
- `.../service/impl/ImServiceImpl.java`:
  - the speech-restriction check and the club/group send-access block of `send` are extracted into `requireNotSpeechRestricted` and `requireGroupSendAccess` (same messages; the restriction now carries code 403);
  - `requireSendable` = login, restriction, system conversation refused, group send access, membership, and the one-to-one block (single and merchant chats);
  - `listMessages`: `blocked`/`blockedByMe` only for one-to-one conversations (not group 4 or channel 5); for group and channel pages one query (`selectBlockedAmong`) marks messages whose sender the viewer blocked (`senderBlocked: true`); rows are kept, cursors unchanged.
- `.../channel/messaging/MessageInteractionService.java`: `react` (when adding) and `typing` call `requireSendable`. Removing one's own reaction is not blocked. Channel `ADD_REACTION` / `mentionScope` checks stay.
- `.../mapper/ImBlockMapper.java` + `mapper/business/ImBlockMapper.xml`: `selectBlockedAmong(viewer, senderIds)` (viewer → sender direction only).
- `.../domain/ImMessage.java`: transient `senderBlocked` (omitted when null).
- `.../channel/platform/ChannelMessageService.java`: `decorateHistory` carries `senderBlocked` onto channel snapshots.

**SOC-14 (server)**
- New `ChatErasureService` + `ChatErasureMapper` (`mapper/business/channel/platform/ChatErasureMapper.xml`):
  - `eraseMember` (SCRUB): inbox previews cleared where the member's message is the latest; a `RECALL` mutation per visible channel message; revision text nulled; pins removed; messages get `content=''`, `extra_json=NULL`, `version+1`, and status 0 → 1 (held rows keep their status). Rows stay. Idempotent.
  - operator ids (SCRUB): `im_channel_op_log.operator_id` → NULL, the member's `im_channel_request_receipt` rows deleted (setting 0 would collide on the primary key).
  - `KEEP` on either switch skips that part; any other value means `SCRUB`.
  - `purgeDeletedChannels(limit)`: channels with `status=3` and `purge_at` passed: reactions, mentions, refs, revisions and messages deleted in batches of 500 (up to 40 batches per channel per run, the rest next run), then pins, mutations, conversation closed, `status=4`. Children before parents, so an interrupted run just continues.
- `AccountDeregistrationServiceImpl.java`: calls `chatErasureService.eraseMember` after the R13 erasure (inside `eraseOptional`, so an old schema logs and continues).
- `ChannelMessageMapper.xml` `selectMessages` also reads `u.del_flag`; `ChannelMessageService.snapshotView` adds `account_erased: true` to recalled messages of deregistered senders (app shows 该用户已注销).
- New `task/ChannelPurgeTask.java` (`channelPurgeTask.purge(50)`, scheduling leader only).
- `sql/migration_20261128_channel_purge_job.sql`: registers the job paused (status 1). No schema change.
- `sql/migration_20261128_stagger_channel_purge_job.sql`: `-- plan: channelPurgeTask.purge(50) = 46 40 3 * * ?`.
- `ci/migration-dependencies.json`, `ci/migration_ledger.py` (`POST_DEPLOY_MIGRATIONS`: both new files).

**Mini-program (SOC-10, SOC-4 step 5)**
- `pages/club/channels/index.js`: chat URL adds `&type=5`.
- `subpackageB/pages/im/chat/index.js`:
  - group (4) and channel (5): 更多 = 举报这条消息 / 拉黑该用户 / 隐藏会话; both open a `cy-option-sheet` of the last six messages by others (or their distinct authors); report sends that message id; block confirms with the existing `im.block` dialog and only reloads (no composer lock);
  - `blocked` from `/api/im/messages` no longer disables a group or channel composer;
  - `loadChannelGate`: `GET /api/channels/{id}`; no `SEND_MESSAGE` → 这个频道只有主理人和管理员可以发言; status 1 → 频道已归档，只能查看;
  - `senderBlocked` messages collapse; 显示 reveals one message on this page.
  - The 1:1 menu literal and behaviour are unchanged (BL-36 and CU-C-120 tests still match).
- `subpackageB/pages/im/chat/index.wxml`: the collapsed row (reuses `time-div`, `cy-pressed`) and the picker sheet.
- Deviation from the review: no long-press menu. The bubble rows have many variants, and wrapping them would change layout (no restyling). The picker in 更多 gives the same "this message / this author" target.

**Tests**
- `ImServiceImplTest` (+4): channel page never reports a pair block and flags only blocked senders; single chat skips the flag query; `requireSendable` refuses restriction (403), blocked pair (403), non-member (403), system conversation; ignores unrelated blocks in a channel.
- `MessageInteractionServiceTest` (+3): restricted/blocked cannot react, can remove own reaction; blocked pair and read-only in-game member get no typing hint or reaction; react and typing call the gate.
- New `ChatErasureServiceTest` (H2, production mapper XML): scrub keeps rows and other members' text, adds one mutation, clears revisions/pins/preview/operators, idempotent; KEEP; switch parsing; purge removes only channels past the window, in batches, and leaves restorable and live channels.
- New MP `tests/unit/soc10-channel-chat-as-group.test.js` (10 tests).

## Verification

| Command | Result |
|---|---|
| `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile` | exit 0 |
| `mvn -B -q -pl chengyinhub-admin -am test -Dtest='ChatErasureServiceTest,MessageInteractionServiceTest,ImServiceImplTest,SysJobStaggerPlanTest,TwoNodeSchedulingTest,MemberPrivacyErasureServiceTest,AccountDeregistration*Test,ChannelMessageService*Test,PlayerMessagePunctuationTest,E2eTestProvidersProfileTest'` | exit 0; ChatErasure 4/4, MessageInteraction 11/11, ImServiceImpl 59/59, SysJobStagger 3/3, TwoNode 4/4, MemberPrivacyErasure 4/4, AccountDeregistration* 44/44, Punctuation 2/2 |
| `bash ci/migration-safety-check.sh` | 迁移安全门禁:通过 |
| `bash ci/migration-layout-check.sh` | 迁移布局门禁:通过 |
| `node ../ci/prepare-mini-artifacts.js`; `node --test "tests/unit/**/*.test.js"` | branch 17897 pass / 32 fail; base 17887 / 32; same 32 failures (none new) |
| `node tests/lint/ui-integrity.js --warn-as-error` | fails on base and branch; only difference: one more listed dynamic path `/api/channels/:dynamic` (existing route, already used by `pages/club/channels`) and +3 checked bindings |
| `node ci/silent-error-gate.js` | fails on base (8 unrelated files); no entry for the changed chat page |

## Not verified (needs a server, device or owner)

- MySQL run of the new SQL (H2 MySQL mode only). The correlated preview update and `INSERT … SELECT` from `im_message` are standard MySQL; on a large `im_message`, `scrubMessages` scans by `sender_id`, which has no index (deregistration is a nightly batch of single accounts; consider an index if it is slow).
- The purge job on real data; it is registered paused.
- Mini-program on a device (picker sheet, collapsed row, 公告 read-only text).

## Owner actions and open questions

1. **SOC-14 step 1 (owner and counsel):** keep chat text of deleted accounts (`KEEP`) or remove it (`SCRUB`, current default)? Set `chengyin.erasure.im-messages`.
2. Keep channel operator ids for audit? `chengyin.erasure.channel-operator-ids=KEEP` keeps them (default `SCRUB`).
3. Enable `已删除频道清理` in 系统监控 → 定时任务 after the jar is live.
4. IM file/video/voice uploads (`im_media_upload`) of a deleted account are no longer linked from the message (`extra_json` cleared) but the objects are not deleted; there is no orphan pipeline for IM media yet. Open.

## Overlap with other agents

- `fix/registration-list-r3` (UI agent) changes other hunks of two files this branch also edits:
  `ImServiceImpl.java` (B6: club `memberCount` clamp) and `AccountDeregistrationServiceImpl.java` (B7: `status()` after cancel), in the broad commit `fdf15c0ff` (B1–B8). Those hunks are not the code SOC-4/10/14 change, and none of the listed shared areas (stop-answer/lease, club chat screen, merchant dashboard) is touched, so nothing was cherry-picked. `git merge-tree HEAD origin/fix/registration-list-r3` shows the same 10 conflicts as `be-integ` vs that branch; this branch adds none.
- Shared files: `ImServiceImpl.java`, `AccountDeregistrationServiceImpl.java`, `ImServiceImplTest.java` (appended tests only).
- `ImServiceImpl.java` and `ChannelMessageService.java` were also changed by x-channel-reach (already in `be-integ`); this branch builds on them.
