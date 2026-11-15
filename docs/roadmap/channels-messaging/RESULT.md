# W5 C2 channels-messaging: backend result

Branch `feature/r-channels-messaging`, based on `origin/roadmap/be-integ` (81b65553d). Brief: `roadmap/briefs:docs/roadmap/briefs/w5/channels-messaging.md`.
App side: `chengyin-app` branch `cloud/r-channels-messaging` (its own RESULT.md).

## Step 1: inventory (before this package)

B = backend (hengyin), MP = mini-program (`chengyinhub-xcx/subpackageB/pages/im/chat`), App = iOS (`roadmap/integ`).
"Channel" means conversation type 5; "chat" means single (1) and group (4) conversations.

| Feature | B | MP | App |
|---|---|---|---|
| Text, images | yes (`/api/im/send`, image review) | yes | yes |
| Files, video, voice | yes (`/api/im/{file,video,voice}/upload`, channels need ATTACH_FILE) | no | yes (`IMFileViews`, `IMVideoViews`, `IMVoiceViews`) |
| Replies with a quote | **no** | no | quote reuses the send journal (text only, no link to the quoted message) |
| Reactions | **no** (ADD_REACTION bit existed, no storage or route) | no | no |
| Mentions: user / role / everyone | rows written only by channel **edits**; `/api/im/send` wrote none (links-push open question 7) | no | mention picker in channels |
| Mention notification | push read mention rows, but USER/EVERYONE only; ROLE and replies ignored | — | — |
| Edit + edited mark, delete (recall) | channels: CAS edit with revisions, recall, mutation feed | no | channels |
| Pins | channels: pin/unpin/sort/list | no | channels |
| Threads | **no** | no | no |
| Unread markers and read state | yes (`/read`, `/read-state`, `/unread-summary`, read-through) | yes | yes |
| Typing indicator | **no** | no | no |
| Search in a channel | channels (`/api/im/search`) | no | channels |
| Jump to a message (link / notification) | channels: `/api/im/message/context`; push page had no message id | — | in-channel locate; a chat push opened the **inbox** |
| Real-time delivery | **no** (PERF-11): no socket, no stream | 8-second poll | 8-second poll (`NativeChannelMessageEntry.swift:74`) |

## Findings closed

| ID | Status | What closes it |
|---|---|---|
| PERF-11 (backend part) | Closed | `GET /api/im/stream` (SSE) per open conversation, fanned out over Redis pub/sub, falls back to local delivery and to client polling. |
| links-push Q7 (mentions on send) | Closed | `/api/im/send` writes `im_message_mention` rows in the send transaction (USER, ROLE, EVERYONE, and REPLY for the quoted author). Push treats REPLY as a mention and expands ROLE to role members. |
| links-push "a chat tap opens the inbox" (backend part) | Closed | Chat push `page` now ends `&messageId=<id>` and the payload carries `message_id`. |
| Replies, reactions, threads, typing (new) | Built | See routes below. |

Left open (reasons in "Not done"): mini-program parity for the new features; read receipts per message (only per-member read state exists); search and edit/delete outside channels.

## Routes added (all need the member token, same envelope as `/api/im`; refusals are HTTP 200 with `code` 400/403/404/409/429 and `errorCode`)

- `GET /api/im/stream?conversation_id=` → `text/event-stream`. Events (each one `event:` line and one single-line JSON `data:`):
  - `ready` `{type, conversation_id, heartbeat_sec:25, reconnect_after_sec:300}`
  - `message.created` `{message_id, sender_id}`, `message.updated` `{message_id, kind: EDIT|RECALL}`, `pin.updated` `{message_id, kind: PIN|UNPIN}`, `reaction.updated` `{message_id}`, `typing` `{member_id, expires_in_ms:6000}`
  - `error` `{code, errorCode, msg, fallback:"POLL"}` then the stream closes (no sign-in 401, no access 403/404, switched off 503 `REALTIME_OFF`, cap 429 `TOO_MANY_STREAMS`).
  - Events carry ids only, never content. The client re-reads through `/api/im/messages?after_id`, `/api/im/message/mutations` and `/api/im/message/extras`, so blocking, image review and channel permissions stay where they were.
  - Access is checked with the same gate as history (`IImService.requireReadable`, extracted from `listMessages`). Streams time out after 5 minutes and the client reconnects (re-checking access). Heartbeat comment every 25 s. Headers `Cache-Control: no-cache`, `X-Accel-Buffering: no` (nginx must not buffer).
- `POST /api/im/reaction` JSON `{conversation_id, message_id, emoji, on}` → `{changed, reactions:[{emoji,count,mine}]}`. `emoji` is one of 8 fixed keys (`thumbs_up, heart, laugh, party, wow, sad, fire, clap`), so no free text and no content review. Channels need ADD_REACTION (and not timed out/archived).
- `GET /api/im/reaction/emoji` → the key/glyph list.
- `GET /api/im/message/extras?conversation_id=&ids=1,2,…` (≤ 50) → `items[id] = {reactions, reply_to:{message_id,state OK|RECALLED|UNAVAILABLE, sender_id, sender_name, msg_type, excerpt≤80}, thread_root_id, thread:{reply_count,last_reply_id}}`. Quotes are computed at read time from the current message, so a recalled or unreviewed message never shows its text.
- `GET /api/im/thread?conversation_id=&root_id=&after=&limit=` → visible replies in a thread, oldest first, keyset paging.
- `POST /api/im/typing` `{conversation_id}` → `{sent}`; at most one hint per member per conversation every 3 s (per node).
- `/api/im/send` (unchanged signature): text messages may carry in `extra_json` `reply_to_id`, `thread_root_id`, `mention_member_ids`, `mention_role_ids`, `mention_everyone`. Other keys are kept as before; non-JSON `extra_json` is stored unchanged.
  - Replies/threads must point at a non-recalled message in the same conversation (404 otherwise). Threads do not nest (a reply in a thread joins the thread's root).
  - `@role` and `@everyone` only in channels: everyone needs MENTION_EVERYONE; a role must be active, in the channel's space and `mentionable` (MENTION_EVERYONE allows any role). In single/group chats, mentioned members who left the conversation are dropped; role/everyone return 400.

## Two-node behaviour

- Publish happens after commit. `ImRealtimeBus` publishes `{"c","t","d"}` to Redis channel `cy:im:realtime:v1`. A node starts its Redis subscription only when the first local stream opens (no subscriber, no Redis listener), then delivers to its own streams. If the publisher is not subscribed, or Redis publish fails, it also delivers locally. Redis failure logs one WARN and never affects the write.
- The heartbeat is a per-node daemon thread for the node's own connections, not a business `@Scheduled` job; no new `@Scheduled` methods were added.
- Config (all optional): `chengyin.im.realtime.enabled` (default true), `.redis-fanout` (true), `.per-member` (6 streams), `.max-streams` (5000 per node).

## Files

- `chengyinhub-admin/sql/migration_20261116_channels_messaging.sql` (new, idempotent, rollback note on top): `im_message_reaction`, `im_message_ref`. Ledger: `"20261116_channels_messaging": ["im", "20261112_im_channels_platform"]`.
- `chengyinhub-system/.../service/IImRealtimePublisher.java` (new) – event names and publish contract.
- `chengyinhub-system/.../service/channel/messaging/MessageComposeSupport.java` (new) – parse/validate reply, thread and mentions on send; writes rows in the send transaction.
- `chengyinhub-system/.../service/channel/messaging/MessageInteractionService.java` (new) – reactions, extras, threads, typing.
- `chengyinhub-system/.../mapper/channel/messaging/ImMessagingMapper.java` + `resources/mapper/business/channel/messaging/ImMessagingMapper.xml` (new).
- `IImService` / `ImServiceImpl`: new `requireReadable` (the history read gate, extracted unchanged from `listMessages`); `send` calls the compose support for text and publishes `message.created` after commit (only when visible, `check_status=1`).
- `ChannelMessageService`: `mentionScope`, `requireReact`; edit/recall and pin/unpin publish hints.
- `push/PushMessage` (optional `messageId`, old 6-arg constructor kept), `PushPayloadBuilder` (`message_id` for chat), `PushFanoutService` (REPLY and ROLE mentions, `&messageId=` in the page), `PushMapper` (`selectRoleMemberIds`).
- `MemberPrivacyErasureService` + mapper: deletes the member's reactions and the USER/REPLY mention rows that name the member.
- `chengyinhub-admin/.../web/realtime/ImRealtimeHub.java`, `ImRealtimeBus.java` (new); `controller/api/ApiImMessagingController.java` (new).
- Tests (new): `MessageComposeSupportTest` (6), `MessageInteractionServiceTest` (8), `ImRealtimeHubTest` (6); extended `PushFanoutServiceTest` (+1), `PushPayloadBuilderTest` (+1), `MemberPrivacyErasureServiceTest` (new tables and assertions).

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests install          # exit 0
bash ci/migration-safety-check.sh                                    # 迁移安全门禁:通过
bash ci/migration-layout-check.sh                                    # 迁移布局门禁:通过
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -pl chengyinhub-admin test \
  -Dtest='MessageComposeSupportTest,MessageInteractionServiceTest,ImRealtimeHubTest' -Dsurefire.failIfNoSpecifiedTests=false
# 20 tests, 0 failures
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -pl chengyinhub-admin test \
  -Dtest='PushFanoutServiceTest,PushPayloadBuilderTest,MemberPrivacyErasureServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
# 18 tests, 0 failures
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -pl chengyinhub-admin test \
  -Dtest='*Im*Test,*Channel*Test,*Push*Test,*Erasure*Test,TwoNodeSchedulingTest' -Dmaven.test.failure.ignore=true
# 3320 run, 1 failure, 0 errors, 18 skipped. The failure is TwoNodeSchedulingTest.everyScheduledMethodIsLeaderGated
# naming MerchantVisitBillingJob, ExternalGameTrafficSettlementJob, CreatorEarningsJob: pre-existing on the base
# (listed in docs/roadmap/integ-channels/RESULT.md), not touched here.
```

## Not verified

- The migration against MySQL (no database here); the new mapper XML is exercised only through mocks, except the erasure statements, which run against H2 in `MemberPrivacyErasureServiceTest`.
- SSE through the real Spring Security/RuoYi filter chain, Tomcat async and nginx. Only the hub and bus are unit-tested; no end-to-end HTTP stream was run.
- Redis pub/sub against a real Redis and across two nodes (the bus logic is unit-tested with mocks).

## Not done (open)

1. Mini-program: none of replies, reactions, threads, typing or the stream is in `chengyinhub-xcx` (it polls). The new routes are additive; the mini-program keeps working. WeChat mini-programs cannot use SSE; a `wx.connectSocket` path would need a WebSocket endpoint.
2. Edit, delete (recall) and search for single and group chats still exist only for channels.
3. Per-message read receipts ("seen by") are not built; per-member read state is unchanged.
4. A removed member keeps an open stream until it times out (≤ 5 minutes); it only ever carries ids, and every read re-checks access.

## Owner / ops actions

- nginx in front of `/prod-api`: for `/prod-api/api/im/stream` set `proxy_buffering off; proxy_read_timeout 330s;` (the `X-Accel-Buffering: no` header already asks for no buffering).
- Run `migration_20261116_channels_messaging.sql` (test, then production) through the ledger.
- Two nodes: both must point at the same Redis (already required by TWO-NODE.md).

## Open questions

1. Should replies notify the quoted author even when that author's level is "mentions only"? Implemented: yes (Discord behaviour).
2. Should threads hide their replies from the main timeline? Implemented: no; thread replies stay in the timeline (older clients and the mini-program see them) and the thread view lists them.
3. Is the 8-emoji set right for launch, or should clubs get custom emoji later?
